package io.github.iamsrishanth.opencaller.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles explicit answer/reject/end actions from OpenCaller's call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(CallNotifications.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> CallSession.answer(callId)
            ACTION_REJECT -> CallSession.reject(callId)
            ACTION_END -> CallSession.disconnect(callId)
        }
    }

    companion object {
        const val ACTION_ANSWER = "io.github.iamsrishanth.opencaller.action.ANSWER_CALL"
        const val ACTION_REJECT = "io.github.iamsrishanth.opencaller.action.REJECT_CALL"
        const val ACTION_END = "io.github.iamsrishanth.opencaller.action.END_CALL"
    }
}
