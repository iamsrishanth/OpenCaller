package io.github.iamsrishanth.opencaller.sip

import android.content.Context
import io.github.iamsrishanth.opencaller.voicemail.LiveVoicemailCall
import io.github.iamsrishanth.opencaller.voicemail.LiveVoicemailHandoffCoordinator
import io.github.iamsrishanth.opencaller.voicemail.VoicemailFinalizationResult
import io.github.iamsrishanth.opencaller.voicemail.VoicemailRecordingFinalizer
import io.github.iamsrishanth.opencaller.voicemail.VoicemailRepository
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Owns one incoming SIP call while its caller leaves a message or is handed back to the user. */
internal class SipLiveVoicemailSession(
    context: Context,
    private val serviceSessionId: String,
    private val call: SipCallSnapshot,
    private val engine: Pjsua2SipEngine,
    private val repository: VoicemailRepository,
    private val workerScope: CoroutineScope,
    private val liveTranscriptionEnabled: Boolean,
    private val onScreenFinished: (Long) -> Unit,
    private val onTerminal: (Long) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    val callToken: Long get() = call.token

    private val appContext = context.applicationContext
    private val messageId = "sip_${serviceSessionId}_${call.token}"
    private val recordingFile = repository.newRecordingFile(messageId)
    private val createdAtEpochMs = System.currentTimeMillis()
    private val screenFinished = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val finalizationStarted = AtomicBoolean(false)
    private val finalizationComplete = AtomicBoolean(false)
    private val recordingStarted = AtomicBoolean(false)
    @Volatile private var media: Pjsua2LiveVoicemailMedia? = null
    @Volatile private var transcriber: OnDeviceLiveTranscriber? = null
    @Volatile private var currentTranscript: String? = null
    @Volatile private var recordingStartedAtEpochMs: Long? = null
    @Volatile private var coordinator: LiveVoicemailHandoffCoordinator? = null

    suspend fun begin(): Boolean {
        if (call.direction != SipCallDirection.INCOMING ||
            !SipCallRuntime.isCurrentCall(serviceSessionId, call.token, voicemailRequired = true)
        ) {
            finishWithoutRecorder()
            return false
        }

        val greeting = SipDefaultVoicemailGreeting.select(appContext)
        if (greeting == null) {
            onFailure("Could not prepare a voicemail greeting. The call is still ringing.")
            finishWithoutRecorder()
            return false
        }

        val accepted = runCatching {
            withContext(Dispatchers.IO) {
                engine.answer(call.token, connectMicrophone = false).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }.getOrDefault(false)
        if (!accepted) {
            onFailure("The incoming call could not be sent to voicemail.")
            finishWithoutRecorder()
            return false
        }

        val activeCall = withTimeoutOrNull(MEDIA_READY_TIMEOUT_MILLIS) {
            engine.state.first { state ->
                state.call?.token != call.token ||
                    (state.call.phase == SipCallPhase.ACTIVE && state.call.mediaReady)
            }
        }?.call
        if (activeCall?.token != call.token || activeCall.phase != SipCallPhase.ACTIVE || !activeCall.mediaReady) {
            runCatching { withContext(Dispatchers.IO) { engine.hangup(call.token).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS) } }
            onFailure("The voicemail audio path did not become ready. The call was ended safely.")
            finishWithoutRecorder()
            return false
        }

        val callMedia = engine.createLiveVoicemailMedia(call.token)
        if (callMedia == null) {
            runCatching { withContext(Dispatchers.IO) { engine.hangup(call.token).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS) } }
            onFailure("Could not prepare voicemail audio for this call.")
            finishWithoutRecorder()
            return false
        }
        media = callMedia

        val callPort = object : LiveVoicemailCall {
            override fun disconnectRecipientMicrophone(): Boolean = callMedia.disconnectRecipientMicrophone()
            override fun stopGreetingPlayback(): Boolean = callMedia.stopGreeting()
            override fun handoffSameCallToRecipientIfConnected(): Boolean = callMedia.handoffSameCallToRecipientIfConnected()
            override fun terminateSameCall() = callMedia.terminateSameCall()
            override fun finishLiveVoicemailScreen() = finishScreen()
        }

        val finalizer = object : VoicemailRecordingFinalizer {
            override fun startCallerRecording(): Boolean {
                recordingStartedAtEpochMs = System.currentTimeMillis()
                val started = callMedia.startCallerRecording(recordingFile)
                if (started) recordingStarted.set(true) else recordingStartedAtEpochMs = null
                return started
            }

            override fun stopAndFinalize(completion: (VoicemailFinalizationResult) -> Unit) {
                if (!finalizationStarted.compareAndSet(false, true)) return
                workerScope.launch(Dispatchers.IO) {
                    runCatching { callMedia.stopCallerTranscription() }
                    val finalTranscript = runCatching { transcriber?.stopAndCollect() }.getOrNull()
                    val runtimeState = SipCallRuntime.state.value
                    val runtimeTranscript = runtimeState.liveTranscript.takeIf {
                        runtimeState.serviceSessionId == serviceSessionId &&
                            runtimeState.liveTranscriptCallToken == call.token
                    }
                    val transcript = selectVoicemailTranscript(
                        recognizerText = finalTranscript,
                        sessionText = currentTranscript,
                        runtimeText = runtimeTranscript,
                    )
                    val recorderStopped = runCatching { callMedia.stopAndFinalizeRecording() }.getOrDefault(false)
                    var playableMessageId: String? = null
                    if (recorderStopped) {
                        if (recordingFile.isFile && recordingFile.length() > WAV_HEADER_SIZE) {
                            val startedAt = recordingStartedAtEpochMs ?: createdAtEpochMs
                            val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                            playableMessageId = runCatching {
                                repository.commit(
                                    id = messageId,
                                    recentCallId = messageId,
                                    callerName = displaySipPeer(call.remoteUri).ifBlank { "Internet caller" },
                                    callerAddress = call.remoteUri,
                                    createdAtEpochMs = createdAtEpochMs,
                                    durationMs = durationMs,
                                    closedRecording = recordingFile,
                                    transcript = transcript,
                                )?.id
                            }.getOrNull()
                        }
                        if (playableMessageId == null) recordingFile.delete()
                    }
                    runCatching {
                        completion(
                            VoicemailFinalizationResult(
                                recorderStopped = recorderStopped,
                                playableMessageId = playableMessageId,
                            ),
                        )
                    }
                    finalizationComplete.set(true)
                    completeTerminalIfReady()
                }
            }
        }

        val handoff = LiveVoicemailHandoffCoordinator(callPort, finalizer)
        coordinator = handoff
        if (!handoff.beginCallerRecording()) {
            if (handoff.state != LiveVoicemailHandoffCoordinator.State.FINALIZING) {
                finalizationComplete.set(true)
                completeTerminalIfReady()
            }
            return false
        }

        if (liveTranscriptionEnabled) {
            val speech = OnDeviceLiveTranscriber(
                context = appContext,
                onTranscript = { text ->
                    val preserved = preserveLastNonBlankTranscript(currentTranscript, text)
                    currentTranscript = preserved
                    if (preserved != null) {
                        SipCallRuntime.publishLiveTranscript(serviceSessionId, call.token, preserved)
                    }
                },
                onUnavailable = {
                    workerScope.launch(Dispatchers.IO) { callMedia.stopCallerTranscription() }
                    SipCallRuntime.publishLiveTranscriptUnavailable(serviceSessionId, call.token)
                },
            )
            transcriber = speech
            if (speech.start()) {
                if (!callMedia.startCallerTranscription(speech::offerPcmFrame)) {
                    speech.stopAndCollect()
                    SipCallRuntime.publishLiveTranscriptUnavailable(serviceSessionId, call.token)
                }
            } else {
                SipCallRuntime.publishLiveTranscriptUnavailable(serviceSessionId, call.token)
            }
        }

        val greetingStarted = runCatching {
            withContext(Dispatchers.IO) { callMedia.playGreeting(greeting) }
        }.getOrDefault(false)
        if (!greetingStarted) {
            onFailure("The greeting could not be played. The call was ended safely.")
            callMedia.terminateSameCall()
            handoff.onCallerDisconnected()
            return false
        }
        return true
    }

    fun answerFromVoicemail() {
        coordinator?.requestAnswer()
    }

    fun callerDisconnected() {
        coordinator?.onCallerDisconnected()
    }

    fun serviceStopping() {
        val currentMedia = media
        val currentCoordinator = coordinator
        if (currentCoordinator == null) {
            currentMedia?.terminateSameCall()
            finishWithoutRecorder()
            return
        }
        currentMedia?.terminateSameCall()
        currentCoordinator.onCallerDisconnected()
    }

    private fun finishWithoutRecorder() {
        finalizationComplete.set(true)
        finishScreen()
        completeTerminalIfReady()
    }

    private fun finishScreen() {
        if (screenFinished.compareAndSet(false, true)) onScreenFinished(call.token)
        completeTerminalIfReady()
    }

    private fun completeTerminalIfReady() {
        val handoff = coordinator
        val coordinatorFinalizing = handoff?.state == LiveVoicemailHandoffCoordinator.State.FINALIZING
        val recordingNeedsClosing = recordingStarted.get() && !finalizationComplete.get()
        if (screenFinished.get() && !coordinatorFinalizing && !recordingNeedsClosing && finalizationComplete.get() &&
            terminal.compareAndSet(false, true)
        ) {
            onTerminal(call.token)
        }
    }

    companion object {
        private const val OPERATION_TIMEOUT_SECONDS = 20L
        private const val MEDIA_READY_TIMEOUT_MILLIS = 20_000L
        private const val WAV_HEADER_SIZE = 44L
    }
}
