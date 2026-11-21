package io.github.iamsrishanth.opencaller.voicemail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoicemailHandoffCoordinatorTest {
    @Test
    fun beginDisconnectsRecipientMicrophoneBeforeCallerRecordingStarts() {
        val fixture = Fixture()

        assertTrue(fixture.coordinator.beginCallerRecording())

        assertEquals(
            listOf("disconnect_recipient_mic", "start_caller_recording"),
            fixture.events,
        )
        assertFalse(fixture.call.recipientMicrophoneConnected)
        assertEquals(LiveVoicemailHandoffCoordinator.State.RECORDING, fixture.coordinator.state)
    }

    @Test
    fun answerWaitsForFinalizationThenHandsOffTheSameCall() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()

        fixture.coordinator.requestAnswer()

        assertFalse(fixture.call.recipientMicrophoneConnected)
        assertEquals(0, fixture.call.handoffCount)
        assertEquals(1, fixture.recorder.finalizationRequestCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.FINALIZING, fixture.coordinator.state)

        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = "message-1"))

        assertTrue(fixture.call.recipientMicrophoneConnected)
        assertEquals(1, fixture.call.handoffCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.HANDED_OFF, fixture.coordinator.state)
        assertTrue(fixture.events.indexOf("finalizer_complete") < fixture.events.indexOf("handoff_same_call"))
    }

    @Test
    fun callerHangupRacingAnswerFinalizesOnceAndNeverHandsOff() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()
        fixture.coordinator.requestAnswer()

        fixture.coordinator.onCallerDisconnected()
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(1, fixture.recorder.finalizationRequestCount)

        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = "partial-1"))

        assertFalse(fixture.call.recipientMicrophoneConnected)
        assertEquals(0, fixture.call.handoffCount)
        assertEquals(0, fixture.call.terminationCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)

        // A misbehaving adapter that delivers the same completion twice cannot trigger another
        // transfer or duplicate the live-screen completion.
        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = "partial-2"))
        assertEquals(0, fixture.call.handoffCount)
        assertEquals(1, fixture.call.screenFinishCount)
    }

    @Test
    fun callerHangupFinalizesPartialAndEndsScreenBeforeFinalizerCompletes() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()

        fixture.coordinator.onCallerDisconnected()

        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(1, fixture.recorder.finalizationRequestCount)
        assertEquals(0, fixture.call.handoffCount)
        assertFalse(fixture.call.recipientMicrophoneConnected)

        fixture.coordinator.requestAnswer()
        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = "partial-1"))

        assertEquals(0, fixture.call.handoffCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)
    }

    @Test
    fun lateHangupCallbackIsProtectedByAtomicCallLivenessCheck() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()
        fixture.coordinator.requestAnswer()

        // Model the transport learning that the caller is gone before the app receives its
        // disconnect callback. The call adapter's check must still refuse to connect the mic.
        fixture.call.callerConnected = false
        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = null))

        assertEquals(1, fixture.call.handoffCount)
        assertFalse(fixture.call.recipientMicrophoneConnected)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)
    }

    @Test
    fun recorderThatCannotConfirmClosureFailsClosed() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()
        fixture.coordinator.requestAnswer()

        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = false, playableMessageId = null))

        assertEquals(0, fixture.call.handoffCount)
        assertFalse(fixture.call.recipientMicrophoneConnected)
        assertEquals(1, fixture.call.terminationCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)
    }

    @Test
    fun failureToDisconnectRecipientMicrophoneNeverStartsRecording() {
        val fixture = Fixture()
        fixture.call.canDisconnectMicrophone = false

        assertFalse(fixture.coordinator.beginCallerRecording())

        assertEquals(listOf("disconnect_recipient_mic", "terminate_same_call", "finish_live_screen"), fixture.events)
        assertEquals(0, fixture.recorder.startCount)
        assertEquals(0, fixture.recorder.finalizationRequestCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)
    }

    @Test
    fun failedHandoffEndsScreenAndDoesNotConnectMicrophone() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()
        fixture.coordinator.requestAnswer()
        fixture.call.canHandoff = false

        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = "message-1"))

        assertFalse(fixture.call.recipientMicrophoneConnected)
        assertEquals(1, fixture.call.handoffCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)
    }

    @Test
    fun answerIsIgnoredAfterHandoffAndRecorderIsFinalizedOnlyOnce() {
        val fixture = Fixture()
        fixture.coordinator.beginCallerRecording()
        fixture.coordinator.requestAnswer()
        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = "message-1"))

        fixture.coordinator.requestAnswer()
        fixture.coordinator.onCallerDisconnected()

        assertEquals(1, fixture.recorder.finalizationRequestCount)
        assertEquals(1, fixture.call.handoffCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.HANDED_OFF, fixture.coordinator.state)
    }

    @Test
    fun recorderStartFailureClosesCallAndStillRequestsOneFinalization() {
        val fixture = Fixture()
        fixture.recorder.canStart = false

        assertFalse(fixture.coordinator.beginCallerRecording())

        assertEquals(1, fixture.call.terminationCount)
        assertEquals(1, fixture.call.screenFinishCount)
        assertEquals(1, fixture.recorder.finalizationRequestCount)

        fixture.recorder.complete(VoicemailFinalizationResult(recorderStopped = true, playableMessageId = null))
        assertEquals(0, fixture.call.handoffCount)
        assertEquals(LiveVoicemailHandoffCoordinator.State.ENDED, fixture.coordinator.state)
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val call = FakeCall(events)
        val recorder = FakeRecorder(events, call)
        val coordinator = LiveVoicemailHandoffCoordinator(call, recorder)
    }

    private class FakeCall(private val events: MutableList<String>) : LiveVoicemailCall {
        var recipientMicrophoneConnected = true
        var callerConnected = true
        var canDisconnectMicrophone = true
        var canStopGreeting = true
        var canHandoff = true
        var handoffCount = 0
        var terminationCount = 0
        var screenFinishCount = 0

        override fun disconnectRecipientMicrophone(): Boolean {
            events += "disconnect_recipient_mic"
            if (canDisconnectMicrophone) recipientMicrophoneConnected = false
            return canDisconnectMicrophone
        }

        override fun stopGreetingPlayback(): Boolean {
            events += "stop_greeting"
            return canStopGreeting
        }

        override fun handoffSameCallToRecipientIfConnected(): Boolean {
            events += "handoff_same_call"
            handoffCount += 1
            if (!callerConnected || !canHandoff) return false
            recipientMicrophoneConnected = true
            return true
        }

        override fun terminateSameCall() {
            events += "terminate_same_call"
            terminationCount += 1
            callerConnected = false
        }

        override fun finishLiveVoicemailScreen() {
            events += "finish_live_screen"
            screenFinishCount += 1
        }
    }

    private class FakeRecorder(
        private val events: MutableList<String>,
        private val call: FakeCall,
    ) : VoicemailRecordingFinalizer {
        var canStart = true
        var startCount = 0
        var finalizationRequestCount = 0
        var completion: ((VoicemailFinalizationResult) -> Unit)? = null

        override fun startCallerRecording(): Boolean {
            events += "start_caller_recording"
            startCount += 1
            assertFalse("recipient mic must be disconnected before recording", call.recipientMicrophoneConnected)
            return canStart
        }

        override fun stopAndFinalize(completion: (VoicemailFinalizationResult) -> Unit) {
            events += "stop_and_finalize"
            finalizationRequestCount += 1
            this.completion = completion
        }

        fun complete(result: VoicemailFinalizationResult) {
            events += "finalizer_complete"
            assertNotNull("finalization callback should be retained", completion)
            completion?.invoke(result)
        }
    }
}
