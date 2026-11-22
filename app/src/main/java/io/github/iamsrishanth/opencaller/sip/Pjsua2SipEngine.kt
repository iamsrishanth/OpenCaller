package io.github.iamsrishanth.opencaller.sip

import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AccountMediaConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.AuthCredInfoVector
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaPlayer
import org.pjsip.pjsua2.AudioMediaPort
import org.pjsip.pjsua2.AudioMediaRecorder
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.LogConfig
import org.pjsip.pjsua2.MediaFormatAudio
import org.pjsip.pjsua2.MediaFrame
import org.pjsip.pjsua2.OnAudioMediaOpCompletedParam
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.TlsConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjmedia_conf_op_type
import org.pjsip.pjsua2.pjmedia_format_id
import org.pjsip.pjsua2.pjmedia_srtp_keying_method
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pj_ssl_sock_proto
import org.pjsip.pjsua2.pjsip_cred_data_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_media_status

enum class SipRegistrationState {
    DISCONNECTED,
    REGISTERING,
    REGISTERED,
    FAILED,
}

enum class SipCallDirection {
    INCOMING,
    OUTGOING,
}

enum class SipCallPhase {
    INCOMING,
    CALLING,
    RINGING,
    ANSWERING,
    ACTIVE,
    DISCONNECTING,
}

enum class SipGreetingPhase {
    PLAYING,
    COMPLETED,
    STOPPED,
    FAILED,
}

data class SipGreetingPlaybackSnapshot(
    val callToken: Long,
    val phase: SipGreetingPhase,
)

enum class SipEngineFailure {
    INVALID_LINE_CONFIG,
    NATIVE_INITIALIZATION,
    REGISTRATION,
    CALL_START,
    CALL_ACTION,
    MEDIA,
}

data class SipCallSnapshot(
    /** Monotonic in-process identity. This is not PJSIP's reusable call slot. */
    val token: Long,
    val direction: SipCallDirection,
    val remoteUri: String,
    val phase: SipCallPhase,
    val mediaReady: Boolean,
    val recipientAccepted: Boolean,
    val statusCode: Int,
)

data class SipEngineSnapshot(
    val registration: SipRegistrationState = SipRegistrationState.DISCONNECTED,
    val call: SipCallSnapshot? = null,
    val failure: SipEngineFailure? = null,
)

/**
 * Owns a single PJSUA2 endpoint and at most one SIP call.
 *
 * PJSIP callbacks never connect microphone media by default. For an incoming call, call
 * [answer] only after the user has chosen an answer action. For voicemail screening call
 * [answer] with `connectMicrophone = false`, then route only the caller audio into the greeting
 * player and recorder returned by [createLiveVoicemailMedia].
 */
class Pjsua2SipEngine : Closeable {
    private val lock = Any()
    private val tokenSource = AtomicLong(1L)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenCaller-PJSIP").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(SipEngineSnapshot())
    val state: StateFlow<SipEngineSnapshot> = mutableState.asStateFlow()
    private val mutableGreetingPlayback = MutableStateFlow<SipGreetingPlaybackSnapshot?>(null)
    val greetingPlayback: StateFlow<SipGreetingPlaybackSnapshot?> = mutableGreetingPlayback.asStateFlow()

    private var endpoint: EngineEndpoint? = null
    private var account: EngineAccount? = null
    @Volatile
    private var activeCall: EngineCall? = null
    private var tlsTransportId: Int = -1
    @Volatile
    private var sipThread: Thread? = null
    @Volatile
    private var micConnected = false
    @Volatile
    private var speakerConnected = false
    private var callerRecorder: AudioMediaRecorder? = null
    private var greetingPlayer: AudioMediaPlayer? = null
    private var greetingCallToken: Long? = null
    private var greetingSink: AudioMedia? = null
    private var callerAudio: AudioMedia? = null
    private var callerTranscriptPort: TranscriptAudioMediaPort? = null
    private var callerTranscriptCallToken: Long? = null
    /** Detached ports remain alive until PJSIP has finished the associated call's media callbacks. */
    private val retiredCallerTranscriptPorts = mutableMapOf<Long, MutableList<TranscriptAudioMediaPort>>()
    private var captureMedia: AudioMedia? = null
    private var playbackMedia: AudioMedia? = null
    @Volatile
    private var closed = false

    private val pendingMediaOperations = ConcurrentHashMap<MediaOperationKey, CompletableFuture<Boolean>>()
    private val recordingSessions = ConcurrentHashMap<Long, CallerRecordingSession>()

    /** Initialize PJSUA2, create a TLS transport, and register the supplied line. */
    fun register(line: SipLineConfig): CompletableFuture<Unit> = submit(SipEngineFailure.NATIVE_INITIALIZATION) {
        validate(line)
        synchronized(lock) {
            check(endpoint == null) { "A SIP line is already active" }
            mutableState.value = SipEngineSnapshot(registration = SipRegistrationState.REGISTERING)
        }

        try {
            System.loadLibrary("pjsua2")
            val ep = EngineEndpoint(this)
            endpoint = ep
            ep.libCreate()

            val epConfig = EpConfig()
            try {
                epConfig.uaConfig.setMaxCalls(1L)
                val logConfig = LogConfig()
                try {
                    logConfig.setLevel(0L)
                    logConfig.setConsoleLevel(0L)
                    logConfig.setMsgLogging(0L)
                    epConfig.setLogConfig(logConfig)
                } finally {
                    logConfig.delete()
                }

                val mediaConfig = epConfig.getMedConfig()
                mediaConfig.setSndAutoCloseTime(-1)
                epConfig.setMedConfig(mediaConfig)
                ep.libInit(epConfig)
            } finally {
                epConfig.delete()
            }
            registerSipThread(ep)

            val tlsConfig = TlsConfig()
            val transportConfig = TransportConfig()
            try {
                tlsConfig.setVerifyServer(true)
                tlsConfig.setProto(pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_2.toLong())
                tlsConfig.setCaListFile(line.caPath)

                // Local ephemeral port. The account's registrar URI selects the remote TLS port.
                transportConfig.setPort(0L)
                transportConfig.setTlsConfig(tlsConfig)
                tlsTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig)
            } finally {
                transportConfig.delete()
                tlsConfig.delete()
            }

            ep.libStart()
            ep.audDevManager().setNullDev()

            val accountConfig = createAccountConfig(line)
            val sipAccount = EngineAccount(this)
            try {
                sipAccount.create(accountConfig)
            } finally {
                accountConfig.delete()
            }
            account = sipAccount

            mutableState.value = SipEngineSnapshot(registration = SipRegistrationState.REGISTERING)
            Unit
        } catch (_: Exception) {
            mutableState.value = SipEngineSnapshot(
                registration = SipRegistrationState.FAILED,
                failure = SipEngineFailure.NATIVE_INITIALIZATION,
            )
            destroyEndpoint()
            throw SipEngineException(SipEngineFailure.NATIVE_INITIALIZATION)
        }
    }

    /** Place an outgoing call to a user/number at the registered SIP host. */
    fun placeCall(destination: String): CompletableFuture<Long> = submit(SipEngineFailure.CALL_START) {
        val user = destination.trim()
        require(user.isNotEmpty() && SIP_USER_PATTERN.matches(user))
            endpoint ?: throw SipEngineException(SipEngineFailure.CALL_START)
        val acc = account ?: throw SipEngineException(SipEngineFailure.CALL_START)
        synchronized(lock) {
            check(activeCall == null)
                check(mutableState.value.registration == SipRegistrationState.REGISTERED)
            val call = EngineCall(this, acc, tokenSource.getAndIncrement(), SipCallDirection.OUTGOING)
            activeCall = call
            micConnected = false
            speakerConnected = false
            callerAudio = null
            mutableState.value = mutableState.value.copy(
                call = SipCallSnapshot(
                    token = call.token,
                    direction = SipCallDirection.OUTGOING,
                    remoteUri = user,
                    phase = SipCallPhase.CALLING,
                    mediaReady = false,
                    recipientAccepted = true,
                    statusCode = 0,
                ),
                failure = null,
            )
            val params = CallOpParam(true)
            try {
                call.makeCall("sip:$user@${uriHost(currentLineHost)}:${currentLinePort};transport=tls", params)
            } catch (_: Exception) {
                activeCall = null
                mutableState.value = mutableState.value.copy(call = null, failure = SipEngineFailure.CALL_START)
                call.delete()
                throw SipEngineException(SipEngineFailure.CALL_START)
            } finally {
                params.delete()
            }
            call.token
        }
    }

    /** Answer the current incoming call. With `connectMicrophone=false` no recipient audio is routed. */
    fun answer(callToken: Long, connectMicrophone: Boolean = true): CompletableFuture<Boolean> =
        submit(SipEngineFailure.CALL_ACTION) {
            val call = activeCall ?: return@submit false
            if (call.token != callToken || call.direction != SipCallDirection.INCOMING) return@submit false
            val info = call.getInfo()
            try {
                if (info.state != pjsip_inv_state.PJSIP_INV_STATE_INCOMING &&
                    info.state != pjsip_inv_state.PJSIP_INV_STATE_EARLY
                ) {
                    return@submit false
                }

                call.recipientAccepted = connectMicrophone
                updateCallSnapshot(call, info, SipCallPhase.ANSWERING)
                val params = CallOpParam(true)
                params.setStatusCode(pjsip_status_code.PJSIP_SC_OK)
                try {
                    call.answer(params)
                } catch (_: Exception) {
                    call.recipientAccepted = false
                    mutableState.value = mutableState.value.copy(failure = SipEngineFailure.CALL_ACTION)
                    throw SipEngineException(SipEngineFailure.CALL_ACTION)
                } finally {
                    params.delete()
                }
            } finally {
                info.delete()
            }
            true
        }

    /** Hang up the exact current call represented by [callToken]. */
    fun hangup(callToken: Long): CompletableFuture<Boolean> = submit(SipEngineFailure.CALL_ACTION) {
        val call = activeCall ?: return@submit false
        if (call.token != callToken) return@submit false
        val params = CallOpParam(true)
        params.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE)
        try {
            call.hangup(params)
            updateCurrentPhase(call, SipCallPhase.DISCONNECTING)
            true
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(failure = SipEngineFailure.CALL_ACTION)
            false
        } finally {
            params.delete()
        }
    }

    /** Returns a same-call media adapter only while [callToken] is the active call. */
    fun createLiveVoicemailMedia(callToken: Long): Pjsua2LiveVoicemailMedia? = synchronized(lock) {
        activeCall?.takeIf { it.token == callToken }?.let { Pjsua2LiveVoicemailMedia(this, callToken) }
    }

    /** Tear down the account and native endpoint. */
    fun disconnect(): CompletableFuture<Unit> = submit(SipEngineFailure.NATIVE_INITIALIZATION) {
        destroyEndpoint()
        mutableState.value = SipEngineSnapshot(registration = SipRegistrationState.DISCONNECTED)
        Unit
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        try {
            executor.submit {
                runCatching { destroyEndpoint() }
                mutableState.value = SipEngineSnapshot(registration = SipRegistrationState.DISCONNECTED)
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The endpoint may already have been destroyed by a failed initialization.
        } finally {
            executor.shutdown()
        }
    }

    internal fun executeMedia(callToken: Long, action: () -> Boolean): Boolean =
        runOnSipThread(SipEngineFailure.MEDIA) {
            val call = activeCall
            if (call == null || call.token != callToken || call.infoState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                return@runOnSipThread false
            }
            try {
                action()
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(failure = SipEngineFailure.MEDIA)
                false
            }
        } ?: false

    internal fun disconnectRecipientMicrophone(callToken: Long): Boolean = executeMedia(callToken) {
        if (!micConnected) return@executeMedia true
        val source = captureMedia ?: endpoint?.audDevManager()?.getCaptureDevMedia() ?: return@executeMedia false
        val sink = callerAudio ?: return@executeMedia false
        val disconnected = performMediaOperation(source, sink, connect = false)
        if (disconnected) micConnected = false
        disconnected
    }

    internal fun startCallerRecording(callToken: Long, output: File): Boolean = executeMedia(callToken) {
        if (micConnected || callerRecorder != null || recordingSessions.containsKey(callToken)) {
            return@executeMedia false
        }
        val parent = output.absoluteFile.parentFile ?: return@executeMedia false
        if (!parent.exists() && !parent.mkdirs()) return@executeMedia false
        val call = activeCall ?: return@executeMedia false
        val info = call.getInfo()
        val audio = try {
            audioMediaFor(call, info) ?: return@executeMedia false
        } finally {
            info.delete()
        }
        val recorder = AudioMediaRecorder()
        var sessionRegistered = false
        try {
            recorder.createRecorder(output.absolutePath)
            callerAudio = audio
            callerRecorder = recorder
            recordingSessions[callToken] = CallerRecordingSession(
                token = callToken,
                callAudio = audio,
                recorder = recorder,
            )
            sessionRegistered = true
            val started = performMediaOperation(audio, recorder, connect = true)
            if (!started) {
                // Keep the recorder session for the coordinator's one finalization attempt. A
                // hangup may race the async bridge operation and leave a short valid partial WAV.
                return@executeMedia false
            }
            true
        } catch (_: Exception) {
            if (!sessionRegistered) runCatching { recorder.delete() }
            false
        }
    }

    /** Taps decoded caller audio only. The PJSIP media callback copies frames and never blocks. */
    internal fun startCallerTranscription(callToken: Long, onPcmFrame: (ByteArray) -> Unit): Boolean =
        executeMedia(callToken) {
            if (micConnected || callerTranscriptPort != null || retiredCallerTranscriptPorts.containsKey(callToken)) {
                return@executeMedia false
            }
            val source = callerAudio ?: return@executeMedia false
            val format = MediaFormatAudio()
            val port = TranscriptAudioMediaPort(onPcmFrame)
            var connectionAttempted = false
            try {
                format.init(
                    pjmedia_format_id.PJMEDIA_FORMAT_L16.toLong(),
                    TRANSCRIPT_SAMPLE_RATE.toLong(),
                    1L,
                    TRANSCRIPT_FRAME_TIME_USEC,
                    16,
                )
                port.createPort("opencaller-voicemail-transcript", format)
                connectionAttempted = true
                if (!performMediaOperation(source, port, connect = true)) {
                    retireCallerTranscriptPort(callToken, port)
                    return@executeMedia false
                }
                callerTranscriptPort = port
                callerTranscriptCallToken = callToken
                true
            } catch (_: Exception) {
                if (connectionAttempted) retireCallerTranscriptPort(callToken, port)
                else runCatching { port.delete() }
                false
            } finally {
                format.delete()
            }
        }

    internal fun stopCallerTranscription(callToken: Long): Boolean =
        runOnSipThread(SipEngineFailure.MEDIA) { stopCallerTranscriptionOnSipThread(callToken) } ?: false

    internal fun playGreeting(callToken: Long, wavFile: File): Boolean = executeMedia(callToken) {
        if (greetingPlayer != null || !wavFile.isFile || !wavFile.canRead()) return@executeMedia false
        val call = activeCall ?: return@executeMedia false
        val audio = callerAudio ?: run {
            val info = call.getInfo()
            try {
                audioMediaFor(call, info) ?: return@executeMedia false
            } finally {
                info.delete()
            }
        }
        val player = EngineAudioPlayer(this, callToken)
        try {
            player.createPlayer(wavFile.absolutePath)
            if (!performMediaOperation(player, audio, connect = true)) {
                player.delete()
                mutableGreetingPlayback.value = SipGreetingPlaybackSnapshot(callToken, SipGreetingPhase.FAILED)
                return@executeMedia false
            }
            greetingPlayer = player
            greetingCallToken = callToken
            greetingSink = audio
            mutableGreetingPlayback.value = SipGreetingPlaybackSnapshot(callToken, SipGreetingPhase.PLAYING)
            true
        } catch (_: Exception) {
            runCatching { player.delete() }
            mutableGreetingPlayback.value = SipGreetingPlaybackSnapshot(callToken, SipGreetingPhase.FAILED)
            false
        }
    }

    internal fun stopGreeting(callToken: Long): Boolean =
        runOnSipThread(SipEngineFailure.MEDIA) {
            val session = recordingSessions[callToken]
            if (session?.callerDisconnected == true) {
                // The remote endpoint already stopped consuming media. Deleting a leftover
                // player closes its local WAV reader without touching the disconnected call.
                if (greetingCallToken == callToken) {
                    greetingPlayer?.let { runCatching { it.delete() } }
                    greetingPlayer = null
                    greetingCallToken = null
                    greetingSink = null
                }
                return@runOnSipThread true
            }
            if (greetingCallToken != callToken) return@runOnSipThread true
            val player = greetingPlayer ?: run {
                greetingCallToken = null
                return@runOnSipThread true
            }
            val sink = greetingSink ?: callerAudio ?: session?.callAudio ?: return@runOnSipThread false
            if (!performMediaOperation(player, sink, connect = false)) return@runOnSipThread false
            greetingPlayer = null
            greetingCallToken = null
            greetingSink = null
            player.delete()
            if (mutableGreetingPlayback.value?.callToken == callToken &&
                mutableGreetingPlayback.value?.phase == SipGreetingPhase.PLAYING
            ) {
                mutableGreetingPlayback.value = SipGreetingPlaybackSnapshot(callToken, SipGreetingPhase.STOPPED)
            }
            true
        } ?: false

    /** Stops call-audio capture and destroys the recorder, which closes/finalizes its WAV file. */
    internal fun stopAndFinalizeRecording(callToken: Long): Boolean =
        runOnSipThread(SipEngineFailure.MEDIA) {
            val session = recordingSessions[callToken] ?: return@runOnSipThread false
            if (session.finalizationAttempted) return@runOnSipThread false
            session.finalizationAttempted = true
            stopCallerTranscriptionOnSipThread(callToken)

            val currentCall = activeCall?.takeIf { it.token == callToken }
            val callStillConnected = !session.callerDisconnected &&
                currentCall?.infoState() != pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED &&
                currentCall != null
            if (!callStillConnected) session.callerDisconnected = true

            // A remote hangup removes the call's conference port. In that case, destroy the
            // recorder directly to close and finalize its WAV; never issue a late route operation
            // against media owned by the disconnected call.
            val routeClosed = if (callStillConnected) {
                performMediaOperation(session.callAudio, session.recorder, connect = false)
            } else {
                true
            }
            val recorderClosed = runCatching { session.recorder.delete() }.isSuccess
            session.finalized = recorderClosed
            if (callerRecorder === session.recorder) callerRecorder = null
            if (session.callerDisconnected && callerAudio === session.callAudio) callerAudio = null
            if (recorderClosed && session.callerDisconnected) recordingSessions.remove(callToken)
            val callerHungUpWhileClosing = session.callerDisconnected ||
                (activeCall?.takeIf { it.token == callToken }?.infoState() ==
                    pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED)
            recorderClosed && (routeClosed || callerHungUpWhileClosing)
        } ?: false

    /** Connects the mic only after the voicemail recorder has been stopped and destroyed. */
    internal fun handoffSameCallToRecipientIfConnected(callToken: Long): Boolean = executeMedia(callToken) {
        val session = recordingSessions[callToken] ?: return@executeMedia false
        if (!session.finalized || session.callerDisconnected) return@executeMedia false
        if (micConnected) return@executeMedia true
        if (callerRecorder != null || callerAudio == null) return@executeMedia false
        val call = activeCall ?: return@executeMedia false
        val info = call.getInfo()
        val audio = try {
            if (info.state != pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) return@executeMedia false
            audioMediaFor(call, info) ?: return@executeMedia false
        } finally {
            info.delete()
        }
        val ep = endpoint ?: return@executeMedia false
        val audioDevices = ep.audDevManager()
        try {
            // Opening the hardware happens only after the user picked up this same call.
            audioDevices.setCaptureDev(DEFAULT_CAPTURE_DEVICE)
            audioDevices.setPlaybackDev(DEFAULT_PLAYBACK_DEVICE)
            audioDevices.setSndDevMode(0L)
            val capture = audioDevices.getCaptureDevMedia()
            val playback = audioDevices.getPlaybackDevMedia()
            captureMedia = capture
            playbackMedia = playback
            val micStarted = performMediaOperation(capture, audio, connect = true)
            if (!micStarted) {
                runCatching { hangupCall(call) }
                return@executeMedia false
            }
            micConnected = true
            if (!speakerConnected && !performMediaOperation(audio, playback, connect = true)) {
                performMediaOperation(capture, audio, connect = false)
                micConnected = false
                runCatching { hangupCall(call) }
                return@executeMedia false
            }
            speakerConnected = true
            recordingSessions.remove(callToken)
            true
        } catch (_: Exception) {
            runCatching { hangupCall(call) }
            false
        }
    }

    internal fun terminateSameCall(callToken: Long) {
        runOnSipThread(SipEngineFailure.CALL_ACTION) {
            val call = activeCall ?: return@runOnSipThread false
            if (call.token != callToken) return@runOnSipThread false
            runCatching { hangupCall(call) }
            true
        }
    }

    private var currentLineHost: String = ""
    private var currentLinePort: Int = 0

    private fun createAccountConfig(line: SipLineConfig): AccountConfig {
        currentLineHost = line.host
        currentLinePort = line.tlsPort
        val cfg = AccountConfig()
        try {
            cfg.setIdUri("sip:${line.username}@${uriHost(line.host)};transport=tls")
            cfg.getRegConfig().setRegistrarUri("sip:${uriHost(line.host)}:${line.tlsPort};transport=tls")
            cfg.getRegConfig().setRegisterOnAdd(true)
            cfg.getSipConfig().setTransportId(tlsTransportId)

            val credentials = AuthCredInfoVector()
            val credential = AuthCredInfo(
                "digest",
                "*",
                line.username,
                pjsip_cred_data_type.PJSIP_CRED_DATA_PLAIN_PASSWD,
                line.password,
            )
            try {
                credentials.add(credential)
                cfg.getSipConfig().setAuthCreds(credentials)
            } finally {
                try {
                    credential.delete()
                } finally {
                    credentials.delete()
                }
            }

            val mediaConfig: AccountMediaConfig = cfg.getMediaConfig()
            mediaConfig.setSrtpUse(pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY)
            // Value 1 means SRTP requires a TLS signaling transport.
            mediaConfig.setSrtpSecureSignaling(1)
            val srtpOptions = mediaConfig.getSrtpOpt()
            val keyings = srtpOptions.getKeyings()
            keyings.clear()
            // Mbed TLS PJSIP builds support SDES-SRTP; DTLS-SRTP needs a TLS-exporter API.
            keyings.add(pjmedia_srtp_keying_method.PJMEDIA_SRTP_KEYING_SDES)
            srtpOptions.setKeyings(keyings)
            mediaConfig.setSrtpOpt(srtpOptions)
            cfg.setMediaConfig(mediaConfig)
            return cfg
        } catch (exception: Exception) {
            cfg.delete()
            throw exception
        }
    }

    private fun audioMediaFor(call: EngineCall, info: CallInfo): AudioMedia? {
        val medias = info.media
        try {
            for (index in 0 until medias.size) {
                val media = medias.get(index)
                try {
                    if (media.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        media.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                    ) {
                        return call.getAudioMedia(media.getIndex().toInt())
                    }
                } finally {
                    media.delete()
                }
            }
        } finally {
            medias.delete()
        }
        return null
    }

    private fun performMediaOperation(source: AudioMedia, target: AudioMedia, connect: Boolean): Boolean {
        val operation = if (connect) {
            pjmedia_conf_op_type.PJMEDIA_CONF_OP_CONNECT_PORTS
        } else {
            pjmedia_conf_op_type.PJMEDIA_CONF_OP_DISCONNECT_PORTS
        }
        val key = MediaOperationKey(operation, source.getPortId().toLong(), target.getPortId().toLong())
        val completion = CompletableFuture<Boolean>()
        if (pendingMediaOperations.putIfAbsent(key, completion) != null) return false
        try {
            if (connect) source.startTransmit(target) else source.stopTransmit(target)
            return completion.get(MEDIA_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return false
        } finally {
            pendingMediaOperations.remove(key, completion)
        }
    }

    private fun stopCallerTranscriptionOnSipThread(callToken: Long): Boolean {
        if (callerTranscriptCallToken != callToken) return true
        val port = callerTranscriptPort ?: run {
            callerTranscriptCallToken = null
            return true
        }
        val session = recordingSessions[callToken]
        val source = session?.callAudio ?: callerAudio
        val disconnected = session?.callerDisconnected == true || activeCall?.token != callToken
        val routeClosed = if (source != null && !disconnected) {
            runCatching { performMediaOperation(source, port, connect = false) }.getOrDefault(false)
        } else {
            true
        }
        // PJSIP can still be in AudioMediaPort::onFrameReceived after the disconnect operation
        // completes. Keep the SWIG callback object alive until the call's media is torn down.
        port.stopAcceptingFrames()
        retireCallerTranscriptPort(callToken, port)
        callerTranscriptPort = null
        callerTranscriptCallToken = null
        return routeClosed
    }

    private fun retireCallerTranscriptPort(callToken: Long, port: TranscriptAudioMediaPort) {
        port.stopAcceptingFrames()
        val ports = retiredCallerTranscriptPorts.getOrPut(callToken) { mutableListOf() }
        if (ports.none { it === port }) ports += port
    }

    /** Called only once PJSIP reports DISCONNECTED for this call. */
    private fun releaseRetiredCallerTranscriptPorts(callToken: Long) {
        retiredCallerTranscriptPorts.remove(callToken).orEmpty().forEach { port ->
            runCatching { port.delete() }
        }
    }

    private fun completeMediaOperation(param: OnAudioMediaOpCompletedParam) {
        try {
            val op = param.getOpParam()
            val key = when (param.getOpType()) {
                pjmedia_conf_op_type.PJMEDIA_CONF_OP_CONNECT_PORTS -> {
                    val info = op.getConnectInfo()
                    MediaOperationKey(param.getOpType(), info.getMediaId(), info.getTargetMediaId())
                }
                pjmedia_conf_op_type.PJMEDIA_CONF_OP_DISCONNECT_PORTS -> {
                    val info = op.getDisconnectInfo()
                    MediaOperationKey(param.getOpType(), info.getMediaId(), info.getTargetMediaId())
                }
                else -> return
            }
            pendingMediaOperations.remove(key)?.complete(param.getStatus() == PJ_SUCCESS)
        } catch (_: Exception) {
            // The waiter times out and keeps the media path closed.
        }
    }

    private fun handleIncoming(account: EngineAccount, pjsuaCallId: Int) {
        synchronized(lock) {
            if (closed || activeCall != null) return
            val call = EngineCall(this, account, tokenSource.getAndIncrement(), SipCallDirection.INCOMING, pjsuaCallId)
            activeCall = call
            micConnected = false
            speakerConnected = false
            callerAudio = null
            mutableState.value = mutableState.value.copy(
                call = SipCallSnapshot(
                    token = call.token,
                    direction = SipCallDirection.INCOMING,
                    remoteUri = runCatching {
                        val info = call.getInfo()
                        try {
                            info.remoteUri
                        } finally {
                            info.delete()
                        }
                    }.getOrDefault(""),
                    phase = SipCallPhase.INCOMING,
                    mediaReady = false,
                    recipientAccepted = false,
                    statusCode = 0,
                ),
                failure = null,
            )
        }
    }

    private fun handleCallInfo(call: EngineCall, info: CallInfo) {
        synchronized(lock) {
            if (activeCall !== call) return
            val phase = when (info.state) {
                pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> SipCallPhase.INCOMING
                pjsip_inv_state.PJSIP_INV_STATE_CALLING -> SipCallPhase.CALLING
                pjsip_inv_state.PJSIP_INV_STATE_EARLY -> SipCallPhase.RINGING
                pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> SipCallPhase.ANSWERING
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> SipCallPhase.ACTIVE
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> SipCallPhase.DISCONNECTING
                else -> SipCallPhase.CALLING
            }
            updateCallSnapshot(call, info, phase)
        }
    }

    private fun handleCallMediaState(call: EngineCall, info: CallInfo) {
        synchronized(lock) {
            if (activeCall !== call) return
            val snapshot = mutableState.value.call ?: return
            val active = hasActiveAudio(info)
            mutableState.value = mutableState.value.copy(call = snapshot.copy(mediaReady = active))
        }

        // Only explicitly accepted, confirmed calls may open the Android microphone.
        if (call.recipientAccepted && info.state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
            dispatchToSipThread {
                if (activeCall === call && call.recipientAccepted) connectRecipientAudio(call)
            }
        }
    }

    private fun connectRecipientAudio(call: EngineCall) {
        if (activeCall !== call || micConnected) return
        val ep = endpoint ?: return
        try {
            val devices = ep.audDevManager()
            devices.setCaptureDev(DEFAULT_CAPTURE_DEVICE)
            devices.setPlaybackDev(DEFAULT_PLAYBACK_DEVICE)
            devices.setSndDevMode(0L)
            val info = call.getInfo()
            val audio = try {
                audioMediaFor(call, info) ?: return
            } finally {
                info.delete()
            }
            val capture = devices.getCaptureDevMedia()
            val playback = devices.getPlaybackDevMedia()
            if (!performMediaOperation(capture, audio, connect = true)) return
            micConnected = true
            captureMedia = capture
            if (!performMediaOperation(audio, playback, connect = true)) {
                performMediaOperation(capture, audio, connect = false)
                micConnected = false
                return
            }
            callerAudio = audio
            playbackMedia = playback
            speakerConnected = true
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(failure = SipEngineFailure.MEDIA)
        }
    }

    private fun handleCallDisconnected(call: EngineCall) {
        synchronized(lock) {
            if (activeCall !== call) return
            activeCall = null
            recordingSessions[call.token]?.callerDisconnected = true
            micConnected = false
            speakerConnected = false
            captureMedia = null
            playbackMedia = null
            mutableState.value = mutableState.value.copy(call = null)
        }
        dispatchToSipThread {
            stopCallerTranscriptionOnSipThread(call.token)
            releaseRetiredCallerTranscriptPorts(call.token)
            if (greetingCallToken == call.token) {
                greetingPlayer?.let { runCatching { it.delete() } }
                greetingPlayer = null
                greetingCallToken = null
                greetingSink = null
                mutableGreetingPlayback.value = SipGreetingPlaybackSnapshot(call.token, SipGreetingPhase.STOPPED)
            }
            val session = recordingSessions[call.token]
            if (session != null && callerAudio === session.callAudio) callerAudio = null
            if (session?.finalized == true) recordingSessions.remove(call.token)
        }
    }

    private fun registrationChanged(account: EngineAccount) {
        val info = runCatching { account.getInfo() }.getOrNull()
        val next = if (info == null) {
            SipRegistrationState.FAILED
        } else {
            try {
                if (info.regIsActive && info.regStatus in 200..299) SipRegistrationState.REGISTERED
                else if (info.regIsActive) SipRegistrationState.REGISTERING
                else SipRegistrationState.FAILED
            } finally {
                info.delete()
            }
        }
        mutableState.value = mutableState.value.copy(
            registration = next,
            failure = if (next == SipRegistrationState.FAILED) SipEngineFailure.REGISTRATION else null,
        )
    }

    private fun updateCurrentPhase(call: EngineCall, phase: SipCallPhase) {
        synchronized(lock) {
            mutableState.value.call?.takeIf { it.token == call.token }?.let { current ->
                mutableState.value = mutableState.value.copy(call = current.copy(phase = phase))
            }
        }
    }

    private fun updateCallSnapshot(call: EngineCall, info: CallInfo, phase: SipCallPhase) {
        val current = mutableState.value.call
        val mediaReady = hasActiveAudio(info)
        mutableState.value = mutableState.value.copy(
            call = SipCallSnapshot(
                token = call.token,
                direction = call.direction,
                remoteUri = info.remoteUri.ifBlank { current?.remoteUri.orEmpty() },
                phase = phase,
                mediaReady = mediaReady,
                recipientAccepted = call.recipientAccepted,
                statusCode = info.lastStatusCode,
            ),
        )
    }

    private fun hasActiveAudio(info: CallInfo): Boolean {
        val medias = info.media
        return try {
            (0 until medias.size).any { index ->
                val media = medias.get(index)
                try {
                    media.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        media.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                } finally {
                    media.delete()
                }
            }
        } finally {
            medias.delete()
        }
    }

    private fun registerSipThread(ep: Endpoint) {
        if (sipThread == Thread.currentThread()) return
        if (!ep.libIsThreadRegistered()) ep.libRegisterThread("OpenCaller-PJSIP")
        sipThread = Thread.currentThread()
    }

    private fun hangupCall(call: EngineCall) {
        val param = CallOpParam(true)
        try {
            call.hangup(param)
        } finally {
            param.delete()
        }
    }

    private fun awaitCallDisconnected(call: EngineCall): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MEDIA_OPERATION_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (call.infoState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) return true
            try {
                Thread.sleep(CALL_DISCONNECT_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return call.infoState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED
    }

    private fun dispatchToSipThread(action: () -> Unit) {
        if (closed) return
        runCatching {
            executor.execute {
                endpoint?.let(::registerSipThread)
                runCatching(action)
            }
        }
    }

    private fun onGreetingReachedEof(callToken: Long, player: EngineAudioPlayer) {
        dispatchToSipThread {
            if (greetingCallToken != callToken || greetingPlayer !== player) return@dispatchToSipThread
            val session = recordingSessions[callToken]
            val call = activeCall?.takeIf { it.token == callToken }
            val callStillConnected = session?.callerDisconnected != true &&
                call != null && call.infoState() != pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED
            val sink = greetingSink ?: callerAudio ?: session?.callAudio
            val routeClosed = if (callStillConnected && sink != null) {
                performMediaOperation(player, sink, connect = false)
            } else {
                !callStillConnected
            }
            val playerClosed = runCatching { player.delete() }.isSuccess
            greetingPlayer = null
            greetingCallToken = null
            greetingSink = null
            val phase = when {
                !callStillConnected -> SipGreetingPhase.STOPPED
                routeClosed && playerClosed -> SipGreetingPhase.COMPLETED
                else -> SipGreetingPhase.FAILED
            }
            mutableGreetingPlayback.value = SipGreetingPlaybackSnapshot(callToken, phase)
        }
    }

    private fun <T> runOnSipThread(failure: SipEngineFailure, action: () -> T): T? {
        if (Thread.currentThread() === sipThread) {
            return runCatching(action).getOrNull()
        }
        return try {
            executor.submit<T> {
                endpoint?.let(::registerSipThread)
                action()
            }.get(MEDIA_OPERATION_TIMEOUT_SECONDS + 2L, TimeUnit.SECONDS)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(failure = failure)
            null
        }
    }

    private fun <T> submit(failure: SipEngineFailure, action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (closed) {
            future.completeExceptionally(SipEngineException(failure))
            return future
        }
        executor.execute {
            try {
                endpoint?.let(::registerSipThread)
                future.complete(action())
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(failure = failure)
                future.completeExceptionally(
                    if (exception is SipEngineException) exception else SipEngineException(failure),
                )
            }
        }
        return future
    }

    private fun destroyEndpoint() {
        val snapshot = synchronized(lock) {
            val ep = endpoint ?: return
            val call = activeCall
            val acc = account
            val recorder = callerRecorder
            val player = greetingPlayer
            val transcriptPorts = buildList {
                callerTranscriptPort?.let(::add)
                retiredCallerTranscriptPorts.values.forEach(::addAll)
            }.distinct()
            transcriptPorts.forEach(TranscriptAudioMediaPort::stopAcceptingFrames)
            recordingSessions.values.forEach { it.callerDisconnected = true }
            endpoint = null
            activeCall = null
            account = null
            callerRecorder = null
            greetingPlayer = null
            greetingCallToken = null
            greetingSink = null
            callerAudio = null
            callerTranscriptPort = null
            callerTranscriptCallToken = null
            retiredCallerTranscriptPorts.clear()
            captureMedia = null
            playbackMedia = null
            micConnected = false
            speakerConnected = false
            EndpointCleanup(ep, acc, call, recorder, player, transcriptPorts)
        }

        // Native teardown may wait for callback threads. Keep this section outside lock so
        // callbacks can observe the cleared endpoint/call state and return without deadlocking.
        val callDisconnected = snapshot.call?.let { call ->
            runCatching { hangupCall(call) }
            val disconnected = awaitCallDisconnected(call)
            runCatching { call.delete() }
            disconnected
        } ?: true
        if (callDisconnected) {
            snapshot.transcriptPorts.forEach { runCatching { it.delete() } }
        }
        snapshot.recorder?.let { runCatching { it.delete() } }
        snapshot.player?.let { runCatching { it.delete() } }
        snapshot.account?.let { runCatching { it.shutdown() }; runCatching { it.delete() } }
        runCatching { snapshot.endpoint.libDestroy() }
        // If a call did not report DISCONNECTED in time, libDestroy has now stopped its media
        // workers and callback dispatch can no longer race port deletion.
        if (!callDisconnected) {
            snapshot.transcriptPorts.forEach { runCatching { it.delete() } }
        }
        runCatching { snapshot.endpoint.delete() }
        synchronized(lock) {
            recordingSessions.clear()
            tlsTransportId = -1
            sipThread = null
        }
        pendingMediaOperations.values.forEach { it.complete(false) }
        pendingMediaOperations.clear()
    }

    private fun validate(line: SipLineConfig) {
        val ca = File(line.caPath)
        val hostOk = line.host.isNotBlank() && line.host.none { it.isWhitespace() || it == '/' || it == '@' || it == ';' }
        val usernameOk = line.username.isNotBlank() && SIP_USER_PATTERN.matches(line.username)
        if (!hostOk || !usernameOk || line.password.isEmpty() || line.tlsPort !in 1..65535 ||
            !ca.isFile || !ca.canRead()
        ) {
            throw SipEngineException(SipEngineFailure.INVALID_LINE_CONFIG)
        }
    }

    private fun uriHost(host: String): String = if (host.contains(':')) "[$host]" else host

    private data class MediaOperationKey(val type: Int, val source: Long, val target: Long)

    private data class CallerRecordingSession(
        val token: Long,
        val callAudio: AudioMedia,
        val recorder: AudioMediaRecorder,
        @Volatile var callerDisconnected: Boolean = false,
        @Volatile var finalizationAttempted: Boolean = false,
        @Volatile var finalized: Boolean = false,
    )

    private data class EndpointCleanup(
        val endpoint: EngineEndpoint,
        val account: EngineAccount?,
        val call: EngineCall?,
        val recorder: AudioMediaRecorder?,
        val player: AudioMediaPlayer?,
        val transcriptPorts: List<TranscriptAudioMediaPort>,
    )

    private inner class EngineEndpoint(private val owner: Pjsua2SipEngine) : Endpoint() {
        override fun onAudioMediaOpCompleted(prm: OnAudioMediaOpCompletedParam) {
            owner.completeMediaOperation(prm)
        }
    }

    private inner class EngineAccount(private val owner: Pjsua2SipEngine) : Account() {
        override fun onIncomingCall(prm: OnIncomingCallParam) {
            owner.handleIncoming(this, prm.callId)
        }

        override fun onRegState(prm: OnRegStateParam) {
            owner.registrationChanged(this)
        }
    }

    private class EngineAudioPlayer(
        private val owner: Pjsua2SipEngine,
        val callToken: Long,
    ) : AudioMediaPlayer() {
        private val eofReported = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun onEof2() {
            if (eofReported.compareAndSet(false, true)) owner.onGreetingReachedEof(callToken, this)
        }
    }

    private class TranscriptAudioMediaPort(
        private val onPcmFrame: (ByteArray) -> Unit,
    ) : AudioMediaPort() {
        private val acceptingFrames = AtomicBoolean(true)

        fun stopAcceptingFrames() {
            acceptingFrames.set(false)
        }

        override fun onFrameReceived(frame: MediaFrame) {
            if (!acceptingFrames.get()) return
            runCatching {
                val bytes = frame.buf ?: return
                val length = minOf(
                    frame.size.coerceAtLeast(0L).toInt(),
                    bytes.size,
                ).coerceAtMost(MAX_TRANSCRIPT_FRAME_BYTES)
                if (length <= 0 || !acceptingFrames.get()) return
                val copy = ByteArray(length)
                for (index in 0 until length) copy[index] = bytes[index].toByte()
                onPcmFrame(copy)
            }
        }
    }

    private inner class EngineCall(
        private val owner: Pjsua2SipEngine,
        account: Account,
        val token: Long,
        val direction: SipCallDirection,
        callId: Int = -1,
    ) : Call(account, callId) {
        @Volatile
        var recipientAccepted: Boolean = direction == SipCallDirection.OUTGOING

        override fun onCallState(prm: org.pjsip.pjsua2.OnCallStateParam) {
            val info = runCatching { getInfo() }.getOrNull() ?: return
            try {
                owner.handleCallInfo(this, info)
                if (info.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                    owner.handleCallDisconnected(this)
                    delete()
                }
            } finally {
                info.delete()
            }
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            val info = runCatching { getInfo() }.getOrNull() ?: return
            try {
                owner.handleCallMediaState(this, info)
            } finally {
                info.delete()
            }
        }

        fun infoState(): Int {
            val info = runCatching { getInfo() }.getOrNull()
                ?: return pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED
            return try {
                info.state
            } finally {
                info.delete()
            }
        }
    }

    companion object {
        private const val PJ_SUCCESS = 0
        private const val DEFAULT_CAPTURE_DEVICE = -1
        private const val DEFAULT_PLAYBACK_DEVICE = -2
        private const val MEDIA_OPERATION_TIMEOUT_SECONDS = 3L
        private const val CALL_DISCONNECT_POLL_MILLIS = 20L
        private val SIP_USER_PATTERN = Regex("[+*#A-Za-z0-9._-]+")
        private const val TRANSCRIPT_SAMPLE_RATE = 16_000
        private const val TRANSCRIPT_FRAME_TIME_USEC = 20_000
        private const val MAX_TRANSCRIPT_FRAME_BYTES = TRANSCRIPT_SAMPLE_RATE * 2 / 10
    }
}

/** Same-live-call audio controls for an optional voicemail greeting/recording handoff. */
class Pjsua2LiveVoicemailMedia internal constructor(
    private val engine: Pjsua2SipEngine,
    private val callToken: Long,
) {
    val greetingPlayback: StateFlow<SipGreetingPlaybackSnapshot?> = engine.greetingPlayback

    fun disconnectRecipientMicrophone(): Boolean = engine.disconnectRecipientMicrophone(callToken)

    fun startCallerRecording(output: File): Boolean = engine.startCallerRecording(callToken, output)

    fun startCallerTranscription(onPcmFrame: (ByteArray) -> Unit): Boolean =
        engine.startCallerTranscription(callToken, onPcmFrame)

    fun stopCallerTranscription(): Boolean = engine.stopCallerTranscription(callToken)

    fun playGreeting(wavFile: File): Boolean = engine.playGreeting(callToken, wavFile)

    fun stopGreeting(): Boolean = engine.stopGreeting(callToken)

    fun stopAndFinalizeRecording(): Boolean = engine.stopAndFinalizeRecording(callToken)

    fun handoffSameCallToRecipientIfConnected(): Boolean = engine.handoffSameCallToRecipientIfConnected(callToken)

    fun terminateSameCall() = engine.terminateSameCall(callToken)
}

class SipEngineException(val failure: SipEngineFailure) : Exception("SIP operation failed: $failure")
