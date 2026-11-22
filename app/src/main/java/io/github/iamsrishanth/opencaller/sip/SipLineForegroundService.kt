package io.github.iamsrishanth.opencaller.sip

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import io.github.iamsrishanth.opencaller.voicemail.VoicemailAutoSettingsStore
import io.github.iamsrishanth.opencaller.voicemail.VoicemailRepository

/** Owns the PJSUA2 endpoint while the user has connected the optional internet line. */
class SipLineForegroundService : Service() {
    private val serviceSessionId = UUID.randomUUID().toString()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val voicemailFinalizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationMutex = Mutex()
    private val voicemailMutex = Mutex()
    private val voicemailSessions = ConcurrentHashMap<Long, SipLiveVoicemailSession>()
    private val autoVoicemailJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val evaluatedAutoPolicyCallTokens = ConcurrentHashMap.newKeySet<Long>()
    private val manuallyHandledCallTokens = ConcurrentHashMap.newKeySet<Long>()
    private val voicemailRepository by lazy { VoicemailRepository(File(filesDir, "voicemail")) }
    private lateinit var engine: Pjsua2SipEngine
    private var foregroundStarted = false
    private var lastCallToken: Long? = null

    override fun onCreate() {
        super.onCreate()
        engine = Pjsua2SipEngine()
        SipCallRuntime.attachService(serviceSessionId)
        startAsForeground()
        if (foregroundStarted) {
            observeEngine()
            observeVoicemailEvents()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action ?: ACTION_CONNECT) {
            ACTION_CONNECT -> serviceScope.launch { ensureRegistered() }
            ACTION_DISCONNECT -> disconnectLine(startId)
            ACTION_PLACE_CALL -> placeCall(intent?.getStringExtra(EXTRA_DESTINATION).orEmpty())
            ACTION_ANSWER -> withCurrentCall(intent) { token ->
                cancelAutoVoicemail(token, manualAction = true)
                if (SipCallRuntime.state.value.leavingVoicemailCallToken == token) {
                    SipCallRuntime.publishVoicemailAnswerRequest(serviceSessionId, token)
                } else {
                    engine.answer(token, connectMicrophone = true)
                }
            }
            ACTION_DECLINE, ACTION_HANGUP -> withCurrentCall(intent) { token ->
                cancelAutoVoicemail(token, manualAction = true)
                engine.hangup(token)
            }
            ACTION_SEND_TO_VOICEMAIL -> withCurrentCall(intent) { token ->
                cancelAutoVoicemail(token, manualAction = true)
                if (SipCallRuntime.markLeavingVoicemail(serviceSessionId, token)) updateCallNotification()
            }
            ACTION_ANSWER_FROM_VOICEMAIL -> withCurrentCall(intent) { token ->
                cancelAutoVoicemail(token, manualAction = true)
                SipCallRuntime.publishVoicemailAnswerRequest(serviceSessionId, token)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        autoVoicemailJobs.values.forEach { it.cancel() }
        autoVoicemailJobs.clear()
        val sessionsStopping = voicemailSessions.values.toList()
        voicemailFinalizationScope.launch { sessionsStopping.forEach { it.serviceStopping() } }
        lastCallToken?.let { SipCallNotifications.cancelCall(this, it) }
        lastCallToken = null
        SipCallRuntime.detachService(serviceSessionId)
        val endpoint = if (::engine.isInitialized) engine else null
        if (endpoint != null) {
            voicemailFinalizationScope.launch {
                withTimeoutOrNull(SHUTDOWN_FINALIZATION_TIMEOUT_MILLIS) {
                    while (voicemailSessions.isNotEmpty()) delay(SHUTDOWN_POLL_MILLIS)
                }
                runCatching { endpoint.close() }
                voicemailFinalizationScope.cancel()
            }
        } else {
            voicemailFinalizationScope.cancel()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = SipCallNotifications.foreground(
            context = this,
            snapshot = SipEngineSnapshot(),
            message = null,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (_: Exception) {
            SipCallRuntime.publishMessage(serviceSessionId, "Android could not start the internet calling service.")
            stopSelf()
        }
    }

    private fun observeEngine() {
        serviceScope.launch {
            engine.state.collect { snapshot ->
                SipCallRuntime.publishEngineState(serviceSessionId, snapshot)
                updateForegroundNotification(snapshot)
                val call = snapshot.call
                if (call == null) {
                    cancelAllAutoVoicemail(clearPolicy = true)
                    manuallyHandledCallTokens.clear()
                    voicemailSessions.values.forEach(::scheduleCallerDisconnect)
                    lastCallToken?.let { SipCallNotifications.cancelCall(this@SipLineForegroundService, it) }
                    lastCallToken = null
                } else {
                    autoVoicemailJobs.keys.filter { it != call.token }.forEach { cancelAutoVoicemail(it) }
                    voicemailSessions.values.filter { it.callToken != call.token }.forEach(::scheduleCallerDisconnect)
                    if (lastCallToken != null && lastCallToken != call.token) {
                        lastCallToken?.let { SipCallNotifications.cancelCall(this@SipLineForegroundService, it) }
                    }
                    lastCallToken = call.token
                    SipCallNotifications.updateCall(
                        context = this@SipLineForegroundService,
                        serviceSessionId = serviceSessionId,
                        call = call,
                        leavingVoicemail = SipCallRuntime.state.value.leavingVoicemailCallToken == call.token,
                    )
                    if (call.direction == SipCallDirection.INCOMING &&
                        call.phase in setOf(SipCallPhase.INCOMING, SipCallPhase.RINGING) &&
                        SipCallRuntime.state.value.leavingVoicemailCallToken != call.token
                    ) {
                        scheduleAutoVoicemail(call)
                    } else {
                        cancelAutoVoicemail(call.token)
                    }
                }
            }
        }
    }

    private fun observeVoicemailEvents() {
        serviceScope.launch {
            SipCallRuntime.events.collect { event ->
                when (event) {
                    is SipCallRuntimeEvent.SendToVoicemailRequested -> {
                        if (event.serviceSessionId == serviceSessionId) scheduleStartVoicemail(event.callToken)
                    }
                    is SipCallRuntimeEvent.AnswerFromVoicemailRequested -> {
                        if (event.serviceSessionId == serviceSessionId) scheduleVoicemailAnswer(event.callToken)
                    }
                }
            }
        }
    }

    private fun scheduleStartVoicemail(callToken: Long) {
        serviceScope.launch(Dispatchers.IO) {
            voicemailMutex.withLock {
                if (!SipCallRuntime.isCurrentCall(serviceSessionId, callToken, voicemailRequired = true)) return@withLock
                if (voicemailSessions.containsKey(callToken)) return@withLock
                val call = engine.state.value.call?.takeIf { it.token == callToken } ?: return@withLock
                if (call.direction != SipCallDirection.INCOMING ||
                    call.phase !in setOf(SipCallPhase.INCOMING, SipCallPhase.RINGING)
                ) return@withLock

                val session = SipLiveVoicemailSession(
                    context = this@SipLineForegroundService,
                    serviceSessionId = serviceSessionId,
                    call = call,
                    engine = engine,
                    repository = voicemailRepository,
                    workerScope = voicemailFinalizationScope,
                    liveTranscriptionEnabled = runCatching {
                        VoicemailAutoSettingsStore(this@SipLineForegroundService).load().liveTranscriptionEnabled
                    }.getOrDefault(false),
                    onScreenFinished = ::onVoicemailScreenFinished,
                    onTerminal = ::onVoicemailFinished,
                    onFailure = ::setMessage,
                )
                voicemailSessions[callToken] = session
                val started = session.begin()
                if (!started) {
                    SipCallRuntime.finishVoicemail(serviceSessionId, callToken)
                    updateCallNotification()
                } else if (engine.state.value.call?.token != callToken) {
                    session.callerDisconnected()
                }
            }
        }
    }

    private fun scheduleVoicemailAnswer(callToken: Long) {
        serviceScope.launch(Dispatchers.IO) {
            voicemailMutex.withLock {
                if (!SipCallRuntime.isCurrentCall(serviceSessionId, callToken, voicemailRequired = true)) return@withLock
                voicemailSessions[callToken]?.answerFromVoicemail()
            }
        }
    }

    private fun scheduleCallerDisconnect(session: SipLiveVoicemailSession) {
        serviceScope.launch(Dispatchers.IO) {
            voicemailMutex.withLock {
                if (voicemailSessions[session.callToken] === session) session.callerDisconnected()
            }
        }
    }

    private fun onVoicemailFinished(callToken: Long) {
        voicemailSessions.remove(callToken)
        onVoicemailScreenFinished(callToken)
    }

    private fun onVoicemailScreenFinished(callToken: Long) {
        SipCallRuntime.finishVoicemail(serviceSessionId, callToken)
        updateCallNotification()
    }

    private fun scheduleAutoVoicemail(call: SipCallSnapshot) {
        if (call.token in manuallyHandledCallTokens) return
        if (!evaluatedAutoPolicyCallTokens.add(call.token)) return
        val settings = runCatching { VoicemailAutoSettingsStore(this).load() }.getOrNull() ?: return
        val settingsStore = VoicemailAutoSettingsStore(this)
        if (!settingsStore.shouldAutoSendToVoicemail(call.remoteUri, settings)) return

        val job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            if (settings.ringTimeoutSeconds > 0) delay(settings.ringTimeoutSeconds * 1_000L)
            if (call.token in manuallyHandledCallTokens) return@launch
            val current = engine.state.value.call
            if (current?.token != call.token || current.direction != SipCallDirection.INCOMING ||
                current.phase !in setOf(SipCallPhase.INCOMING, SipCallPhase.RINGING) ||
                SipCallRuntime.state.value.leavingVoicemailCallToken == call.token
            ) return@launch
            if (SipCallRuntime.markLeavingVoicemail(serviceSessionId, call.token)) updateCallNotification()
        }
        autoVoicemailJobs[call.token] = job
        job.invokeOnCompletion { autoVoicemailJobs.remove(call.token, job) }
        job.start()
    }

    private fun cancelAutoVoicemail(callToken: Long, manualAction: Boolean = false) {
        if (manualAction) manuallyHandledCallTokens += callToken
        autoVoicemailJobs.remove(callToken)?.cancel()
    }

    private fun cancelAllAutoVoicemail(clearPolicy: Boolean) {
        autoVoicemailJobs.values.forEach { it.cancel() }
        autoVoicemailJobs.clear()
        if (clearPolicy) evaluatedAutoPolicyCallTokens.clear()
    }

    private fun updateForegroundNotification(snapshot: SipEngineSnapshot = engine.state.value) {
        if (!foregroundStarted) return
        val notification = SipCallNotifications.foreground(this, snapshot, SipCallRuntime.state.value.message)
        runCatching { getSystemService(android.app.NotificationManager::class.java)?.notify(FOREGROUND_NOTIFICATION_ID, notification) }
    }

    private fun updateCallNotification() {
        val call = engine.state.value.call ?: return
        SipCallNotifications.updateCall(
            context = this,
            serviceSessionId = serviceSessionId,
            call = call,
            leavingVoicemail = SipCallRuntime.state.value.leavingVoicemailCallToken == call.token,
        )
    }

    private fun placeCall(destination: String) {
        if (destination.isBlank()) {
            setMessage("Enter an internet-line account or number.")
            return
        }
        serviceScope.launch {
            if (!ensureRegistered()) return@launch
            if (engine.state.value.call != null) {
                setMessage("An internet call is already in progress.")
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) { engine.placeCall(destination).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }.onFailure { setMessage("Could not start the internet call.") }
        }
    }

    private suspend fun ensureRegistered(): Boolean = registrationMutex.withLock {
        when (engine.state.value.registration) {
            SipRegistrationState.REGISTERED -> return@withLock true
            SipRegistrationState.FAILED -> {
                setMessage("The internet line could not register. Disconnect and reconnect to try again.")
                return@withLock false
            }
            else -> Unit
        }

        val saved = withContext(Dispatchers.IO) { runCatching { SipLineConfigStore(this@SipLineForegroundService).load() }.getOrNull() }
        if (saved == null) {
            setMessage("Save a secure internet line before connecting.")
            return@withLock false
        }

        if (engine.state.value.registration == SipRegistrationState.DISCONNECTED) {
            val initialized = runCatching {
                withContext(Dispatchers.IO) {
                    engine.register(saved.config).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }.isSuccess
            if (!initialized) {
                setMessage("The internet line could not start. Check its settings and trusted certificate.")
                return@withLock false
            }
        }

        val result = withTimeoutOrNull(REGISTRATION_TIMEOUT_MILLIS) {
            engine.state.first { it.registration == SipRegistrationState.REGISTERED || it.registration == SipRegistrationState.FAILED }
        }
        if (result?.registration == SipRegistrationState.REGISTERED) {
            SipCallRuntime.publishMessage(serviceSessionId, null)
            updateForegroundNotification()
            true
        } else {
            setMessage("The internet line could not register. Check the server and network connection.")
            false
        }
    }

    private fun disconnectLine(startId: Int) {
        if (engine.state.value.call != null || voicemailSessions.isNotEmpty()) {
            setMessage("End the current internet call before disconnecting the line.")
            return
        }
        engine.disconnect().whenComplete { _, _ ->
            serviceScope.launch(Dispatchers.Main.immediate) {
                SipCallRuntime.publishEngineState(serviceSessionId, engine.state.value)
                SipCallRuntime.publishMessage(serviceSessionId, null)
                lastCallToken?.let { SipCallNotifications.cancelCall(this@SipLineForegroundService, it) }
                lastCallToken = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf(startId)
            }
        }
    }

    private fun withCurrentCall(intent: Intent?, action: (Long) -> Unit) {
        val token = intent?.getLongExtra(EXTRA_CALL_TOKEN, Long.MIN_VALUE) ?: return
        val suppliedSession = intent.getStringExtra(EXTRA_SERVICE_SESSION_ID) ?: return
        if (!SipCallRuntime.isCurrentCall(suppliedSession, token) || suppliedSession != serviceSessionId) return
        action(token)
    }

    private fun setMessage(message: String) {
        SipCallRuntime.publishMessage(serviceSessionId, message)
        updateForegroundNotification()
    }

    companion object {
        const val ACTION_CONNECT = "io.github.iamsrishanth.opencaller.sip.action.CONNECT"
        const val ACTION_DISCONNECT = "io.github.iamsrishanth.opencaller.sip.action.DISCONNECT"
        const val ACTION_PLACE_CALL = "io.github.iamsrishanth.opencaller.sip.action.PLACE_CALL"
        const val ACTION_ANSWER = "io.github.iamsrishanth.opencaller.sip.action.ANSWER"
        const val ACTION_DECLINE = "io.github.iamsrishanth.opencaller.sip.action.DECLINE"
        const val ACTION_HANGUP = "io.github.iamsrishanth.opencaller.sip.action.HANGUP"
        const val ACTION_SEND_TO_VOICEMAIL = "io.github.iamsrishanth.opencaller.sip.action.SEND_TO_VOICEMAIL"
        const val ACTION_ANSWER_FROM_VOICEMAIL = "io.github.iamsrishanth.opencaller.sip.action.ANSWER_FROM_VOICEMAIL"
        const val EXTRA_CALL_TOKEN = "io.github.iamsrishanth.opencaller.extra.SIP_CALL_TOKEN"
        const val EXTRA_SERVICE_SESSION_ID = "io.github.iamsrishanth.opencaller.extra.SIP_SERVICE_SESSION_ID"
        const val EXTRA_DESTINATION = "io.github.iamsrishanth.opencaller.extra.SIP_DESTINATION"

        private const val FOREGROUND_NOTIFICATION_ID = 0x51000001
        private const val OPERATION_TIMEOUT_SECONDS = 20L
        private const val REGISTRATION_TIMEOUT_MILLIS = 45_000L
        private const val SHUTDOWN_FINALIZATION_TIMEOUT_MILLIS = 15_000L
        private const val SHUTDOWN_POLL_MILLIS = 50L
    }
}
