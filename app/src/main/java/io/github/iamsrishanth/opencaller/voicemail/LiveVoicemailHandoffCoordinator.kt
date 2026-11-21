package io.github.iamsrishanth.opencaller.voicemail

/**
 * Coordinates the transfer of a caller who is leaving a live voicemail to the recipient.
 *
 * The call adapter must call [beginCallerRecording] before displaying the live voicemail
 * controls. That method disconnects the recipient microphone before it starts capture. The
 * recipient microphone must remain disconnected until [LiveVoicemailCall.handoffSameCallToRecipientIfConnected]
 * succeeds.
 *
 * Answering stops the greeting and asks the recorder to close and finalize its current message.
 * The same call is handed over only from the finalization callback, after the recorder is closed.
 * A caller disconnect racing with answer wins if it is observed before handoff; the callback
 * then cannot connect that caller. Caller disconnect also ends the live voicemail screen
 * immediately, while any already requested recording finalization can complete in the background.
 *
 * This class serializes events per call. Port methods other than finalization must be quick and
 * must not wait for user input. [VoicemailRecordingFinalizer.stopAndFinalize] must be asynchronous,
 * call its completion at most once, and call it only after the recording stream is closed and the
 * partial message has either been committed once as playable or discarded. The finalizer should
 * use a stable message identity and an atomic commit so retries cannot create duplicate playable
 * messages. A callback with [VoicemailFinalizationResult.recorderStopped] false means the stream
 * could not be confirmed closed; the coordinator ends the call instead of connecting its mic.
 */
class LiveVoicemailHandoffCoordinator(
    private val call: LiveVoicemailCall,
    private val finalizer: VoicemailRecordingFinalizer,
) {
    enum class State {
        NEW,
        RECORDING,
        FINALIZING,
        HANDED_OFF,
        ENDED,
    }

    private val lock = Any()
    private var currentState = State.NEW
    private var callerConnected = true
    private var answerRequested = false
    private var finalizationStarted = false
    private var finalizationCallbackConsumed = false
    private var liveScreenEnded = false
    private var callTerminationRequested = false
    private var abortAfterFinalization = false

    /** A snapshot suitable for diagnostics and UI state collection. */
    val state: State
        get() = synchronized(lock) { currentState }

    /**
     * Detaches the recipient mic before starting capture of the caller's message.
     *
     * Returns true when recording starts. If the adapter cannot prove the recipient mic is
     * disconnected, recording is not started and the call is closed to prevent audio leakage.
     * This is a one-shot operation; subsequent calls return false.
     */
    fun beginCallerRecording(): Boolean = synchronized(lock) {
        if (currentState != State.NEW) return@synchronized false

        val micDisconnected = try {
            call.disconnectRecipientMicrophone()
        } catch (_: Exception) {
            false
        }
        if (!micDisconnected) {
            currentState = State.ENDED
            terminateCallLocked()
            endLiveScreenLocked()
            return@synchronized false
        }

        val started = try {
            finalizer.startCallerRecording()
        } catch (_: Exception) {
            false
        }
        if (started) {
            currentState = State.RECORDING
            return@synchronized true
        }

        // startCallerRecording may have partially opened its backend before reporting failure.
        // Ask it to close/finalize exactly once, then keep the call closed.
        currentState = State.FINALIZING
        abortAfterFinalization = true
        terminateCallLocked()
        endLiveScreenLocked()
        startFinalizationLocked(stopGreeting = false)
        false
    }

    /**
     * Requests an answer. The recipient microphone remains disconnected until the recorder's
     * finalization callback confirms closure and the call adapter atomically verifies the caller
     * is still connected while handing over this same call.
     */
    fun requestAnswer() = synchronized(lock) {
        if (currentState != State.RECORDING) return@synchronized

        answerRequested = true
        currentState = State.FINALIZING
        startFinalizationLocked(stopGreeting = true)
    }

    /**
     * Reports that the remote caller disconnected. The live voicemail screen ends immediately.
     * If recording was active, it is stopped and finalized once so a valid partial message may be
     * saved. A later finalizer callback cannot hand the disconnected caller to the recipient.
     */
    fun onCallerDisconnected() = synchronized(lock) {
        if (currentState == State.HANDED_OFF || currentState == State.ENDED) return@synchronized

        callerConnected = false
        answerRequested = false
        endLiveScreenLocked()

        when (currentState) {
            State.NEW -> currentState = State.ENDED
            State.RECORDING -> {
                currentState = State.FINALIZING
                startFinalizationLocked(stopGreeting = true)
            }
            State.FINALIZING -> Unit // The existing one-shot finalization remains responsible.
            State.HANDED_OFF, State.ENDED -> Unit
        }
    }

    private fun startFinalizationLocked(stopGreeting: Boolean) {
        if (finalizationStarted) return
        finalizationStarted = true

        if (stopGreeting) {
            val greetingStopped = try {
                call.stopGreetingPlayback()
            } catch (_: Exception) {
                false
            }
            if (!greetingStopped) {
                abortAfterFinalization = true
                terminateCallLocked()
                endLiveScreenLocked()
            }
        }

        try {
            finalizer.stopAndFinalize(::onFinalizationCompleted)
        } catch (_: Exception) {
            // No completion means we cannot prove the recorder is closed. Fail closed and ignore
            // any later callback from a broken adapter.
            finalizationCallbackConsumed = true
            currentState = State.ENDED
            terminateCallLocked()
            endLiveScreenLocked()
        }
    }

    private fun onFinalizationCompleted(result: VoicemailFinalizationResult) = synchronized(lock) {
        if (finalizationCallbackConsumed) return@synchronized
        finalizationCallbackConsumed = true
        if (currentState != State.FINALIZING) return@synchronized

        if (!result.recorderStopped) {
            currentState = State.ENDED
            terminateCallLocked()
            endLiveScreenLocked()
            return@synchronized
        }

        if (!answerRequested || !callerConnected || abortAfterFinalization) {
            currentState = State.ENDED
            endLiveScreenLocked()
            return@synchronized
        }

        val handedOff = try {
            call.handoffSameCallToRecipientIfConnected()
        } catch (_: Exception) {
            false
        }
        if (handedOff) {
            currentState = State.HANDED_OFF
            endLiveScreenLocked()
        } else {
            // The adapter must leave the recipient mic disconnected when it returns false.
            callerConnected = false
            currentState = State.ENDED
            endLiveScreenLocked()
        }
    }

    private fun terminateCallLocked() {
        if (callTerminationRequested) return
        callTerminationRequested = true
        try {
            call.terminateSameCall()
        } catch (_: Exception) {
            // The call may already have ended; keep the state machine terminal regardless.
        }
    }

    private fun endLiveScreenLocked() {
        if (liveScreenEnded) return
        liveScreenEnded = true
        try {
            call.finishLiveVoicemailScreen()
        } catch (_: Exception) {
            // Screen dismissal is best-effort at the platform boundary, but is still one-shot.
        }
    }
}

/** Platform adapter for the same incoming call being screened and then answered. */
interface LiveVoicemailCall {
    /**
     * Removes the recipient microphone from the caller's uplink. Return false unless this is
     * confirmed complete. No caller recording may begin when this returns false.
     */
    fun disconnectRecipientMicrophone(): Boolean

    /** Stops and confirms the end of the greeting before returning true. */
    fun stopGreetingPlayback(): Boolean

    /**
     * Atomically confirms this same remote call is still connected, connects the recipient
     * microphone, and hands the call to the recipient. Return false without connecting the mic if
     * the caller has gone. Implementations must not create a replacement/outgoing call.
     */
    fun handoffSameCallToRecipientIfConnected(): Boolean

    /** Closes the current call when its media or recorder cannot be safely handed off. */
    fun terminateSameCall()

    /** Dismisses only the live voicemail screen; it must not end an already handed-off call. */
    fun finishLiveVoicemailScreen()
}

/** Recorder boundary; an implementation can wrap PJSIP, Asterisk, or another media backend. */
interface VoicemailRecordingFinalizer {
    /** Starts the caller-side recording after the recipient mic has been disconnected. */
    fun startCallerRecording(): Boolean

    /**
     * Stops recording and finalizes at most one playable message. This must return promptly and
     * invoke [completion] once, only after the recorder is closed and the message is either
     * committed or discarded. A partial recording is eligible for commit on caller hangup.
     */
    fun stopAndFinalize(completion: (VoicemailFinalizationResult) -> Unit)
}

/** Result of closing and committing the current caller message. */
data class VoicemailFinalizationResult(
    /** True only when the recording stream is fully closed and no longer consumes call audio. */
    val recorderStopped: Boolean,
    /** Stable ID of the single playable message committed, or null if no message was saved. */
    val playableMessageId: String?,
)

