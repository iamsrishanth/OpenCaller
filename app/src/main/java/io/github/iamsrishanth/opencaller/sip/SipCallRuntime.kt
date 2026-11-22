package io.github.iamsrishanth.opencaller.sip

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class SipCallRuntimeState(
    val serviceSessionId: String? = null,
    val engine: SipEngineSnapshot = SipEngineSnapshot(),
    val leavingVoicemailCallToken: Long? = null,
    val liveTranscript: String? = null,
    val liveTranscriptCallToken: Long? = null,
    val liveTranscriptUnavailable: Boolean = false,
    val message: String? = null,
)

sealed interface SipCallRuntimeEvent {
    data class SendToVoicemailRequested(val serviceSessionId: String, val callToken: Long) : SipCallRuntimeEvent
    data class AnswerFromVoicemailRequested(val serviceSessionId: String, val callToken: Long) : SipCallRuntimeEvent
}

/**
 * Process-local, thread-safe facade shared by Compose and [SipLineForegroundService]. The
 * service owns the native endpoint; callers send explicit service actions instead of retaining
 * PJSUA2 objects across Activity lifetimes.
 */
object SipCallRuntime {
    private val lock = Any()
    private val mutableState = MutableStateFlow(SipCallRuntimeState())
    private val mutableEvents = MutableSharedFlow<SipCallRuntimeEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val state: StateFlow<SipCallRuntimeState> = mutableState.asStateFlow()
    val events: SharedFlow<SipCallRuntimeEvent> = mutableEvents.asSharedFlow()

    fun connect(context: Context) = startService(context, SipLineForegroundService.ACTION_CONNECT)

    fun disconnect(context: Context) = startService(context, SipLineForegroundService.ACTION_DISCONNECT)

    fun placeCall(context: Context, destination: String) {
        val normalized = destination.trim()
        if (normalized.isEmpty()) {
            setMessage("Enter an internet-line account or number.")
            return
        }
        startService(context, SipLineForegroundService.ACTION_PLACE_CALL) {
            putExtra(SipLineForegroundService.EXTRA_DESTINATION, normalized)
        }
    }

    fun answer(context: Context, callToken: Long, serviceSessionId: String? = state.value.serviceSessionId) =
        callCommand(context, SipLineForegroundService.ACTION_ANSWER, callToken, serviceSessionId)

    fun decline(context: Context, callToken: Long, serviceSessionId: String? = state.value.serviceSessionId) =
        callCommand(context, SipLineForegroundService.ACTION_DECLINE, callToken, serviceSessionId)

    fun hangup(context: Context, callToken: Long, serviceSessionId: String? = state.value.serviceSessionId) =
        callCommand(context, SipLineForegroundService.ACTION_HANGUP, callToken, serviceSessionId)

    fun sendToVoicemail(
        context: Context,
        callToken: Long,
        serviceSessionId: String? = state.value.serviceSessionId,
    ) = callCommand(context, SipLineForegroundService.ACTION_SEND_TO_VOICEMAIL, callToken, serviceSessionId)

    /**
     * The root UI must route this callback to its live-voicemail coordinator. It deliberately
     * does not call `engine.answer()` because that would connect the recipient microphone before
     * the recorder has finalized.
     */
    fun requestAnswerFromVoicemail(callToken: Long, serviceSessionId: String? = state.value.serviceSessionId) {
        if (serviceSessionId == null || !isCurrentCall(serviceSessionId, callToken, voicemailRequired = true)) return
        mutableEvents.tryEmit(SipCallRuntimeEvent.AnswerFromVoicemailRequested(serviceSessionId, callToken))
    }

    internal fun attachService(serviceSessionId: String) = synchronized(lock) {
        mutableState.value = SipCallRuntimeState(serviceSessionId = serviceSessionId)
    }

    internal fun detachService(serviceSessionId: String) = synchronized(lock) {
        if (mutableState.value.serviceSessionId == serviceSessionId) {
            mutableState.value = SipCallRuntimeState()
        }
    }

    internal fun publishEngineState(serviceSessionId: String, snapshot: SipEngineSnapshot) = synchronized(lock) {
        if (mutableState.value.serviceSessionId != serviceSessionId) return@synchronized
        val previous = mutableState.value
        val stillCurrent = snapshot.call?.token == previous.leavingVoicemailCallToken
        mutableState.value = previous.copy(
            engine = snapshot,
            leavingVoicemailCallToken = previous.leavingVoicemailCallToken.takeIf { stillCurrent },
            message = if (snapshot.registration == SipRegistrationState.REGISTERED) null else previous.message,
        )
    }

    internal fun publishMessage(serviceSessionId: String, message: String?) = synchronized(lock) {
        if (mutableState.value.serviceSessionId == serviceSessionId) {
            mutableState.value = mutableState.value.copy(message = message)
        }
    }

    internal fun publishLiveTranscript(serviceSessionId: String, callToken: Long, transcript: String?) = synchronized(lock) {
        val current = mutableState.value
        val preservedTranscript = preserveLastNonBlankTranscript(current.liveTranscript, transcript)
        if (current.serviceSessionId == serviceSessionId &&
            current.engine.call?.token == callToken &&
            current.leavingVoicemailCallToken == callToken
        ) {
            mutableState.value = current.copy(
                liveTranscript = preservedTranscript,
                liveTranscriptCallToken = preservedTranscript?.let { callToken } ?: current.liveTranscriptCallToken,
                liveTranscriptUnavailable = if (preservedTranscript == null) current.liveTranscriptUnavailable else false,
            )
        }
    }

    internal fun publishLiveTranscriptUnavailable(serviceSessionId: String, callToken: Long) = synchronized(lock) {
        val current = mutableState.value
        if (current.serviceSessionId == serviceSessionId &&
            current.engine.call?.token == callToken &&
            current.leavingVoicemailCallToken == callToken &&
            current.liveTranscript.isNullOrBlank()
        ) {
            mutableState.value = current.copy(liveTranscriptUnavailable = true)
        }
    }

    internal fun markLeavingVoicemail(serviceSessionId: String, callToken: Long): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.serviceSessionId != serviceSessionId || current.engine.call?.token != callToken ||
            current.engine.call.direction != SipCallDirection.INCOMING
        ) {
            return@synchronized false
        }
        if (current.engine.call.phase !in setOf(SipCallPhase.INCOMING, SipCallPhase.RINGING)) {
            return@synchronized false
        }
        mutableState.value = current.copy(
            leavingVoicemailCallToken = callToken,
            liveTranscript = null,
            liveTranscriptCallToken = callToken,
            liveTranscriptUnavailable = false,
            message = null,
        )
        mutableEvents.tryEmit(SipCallRuntimeEvent.SendToVoicemailRequested(serviceSessionId, callToken))
        true
    }

    internal fun finishVoicemail(serviceSessionId: String, callToken: Long) = synchronized(lock) {
        val current = mutableState.value
        if (current.serviceSessionId == serviceSessionId && current.leavingVoicemailCallToken == callToken) {
            mutableState.value = current.copy(
                leavingVoicemailCallToken = null,
                liveTranscript = null,
                liveTranscriptCallToken = null,
                liveTranscriptUnavailable = false,
                message = null,
            )
        }
    }

    internal fun isCurrentCall(
        serviceSessionId: String,
        callToken: Long,
        voicemailRequired: Boolean = false,
    ): Boolean = synchronized(lock) {
        val current = mutableState.value
        current.serviceSessionId == serviceSessionId &&
            current.engine.call?.token == callToken &&
            (!voicemailRequired || current.leavingVoicemailCallToken == callToken)
    }

    internal fun publishVoicemailAnswerRequest(serviceSessionId: String, callToken: Long): Boolean {
        if (!isCurrentCall(serviceSessionId, callToken, voicemailRequired = true)) return false
        mutableEvents.tryEmit(SipCallRuntimeEvent.AnswerFromVoicemailRequested(serviceSessionId, callToken))
        return true
    }

    private fun callCommand(
        context: Context,
        action: String,
        callToken: Long,
        serviceSessionId: String?,
    ) {
        if (serviceSessionId == null) return
        startService(context, action) {
            putExtra(SipLineForegroundService.EXTRA_CALL_TOKEN, callToken)
            putExtra(SipLineForegroundService.EXTRA_SERVICE_SESSION_ID, serviceSessionId)
        }
    }

    private fun startService(context: Context, action: String, configure: Intent.() -> Unit = {}) {
        val appContext = context.applicationContext
        val sessionId = state.value.serviceSessionId ?: "new"
        val command = Intent(appContext, SipLineForegroundService::class.java).apply {
            this.action = action
            data = Uri.parse("opencaller-sip://$action/${UUID.randomUUID()}/$sessionId")
            configure()
        }
        try {
            appContext.startForegroundService(command)
        } catch (_: Exception) {
            setMessage("Could not start the internet calling service.")
        }
    }

    private fun setMessage(message: String) = synchronized(lock) {
        mutableState.value = mutableState.value.copy(message = message)
    }
}
