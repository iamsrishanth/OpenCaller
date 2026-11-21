package io.github.iamsrishanth.opencaller.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.Call
import io.github.iamsrishanth.opencaller.MainActivity

/** Minimal Telecom call notification. The system remains responsible for SIM ringing audio. */
internal object CallNotifications {
    const val EXTRA_CALL_ID = "io.github.iamsrishanth.opencaller.extra.CALL_ID"
    const val EXTRA_OPEN_CALL = "io.github.iamsrishanth.opencaller.extra.OPEN_CALL"

    private const val CHANNEL_ID = "opencaller_calls"
    private const val CHANNEL_NAME = "Calls"

    fun update(context: Context, call: CallUiModel) {
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) {
            cancel(context, call.id)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val incoming = call.state == Call.STATE_RINGING
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data = Uri.parse("opencaller-call://call/${Uri.encode(call.id)}")
            putExtra(EXTRA_OPEN_CALL, true)
            putExtra(EXTRA_CALL_ID, call.id)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            requestCode(call.id, 0),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(call.title)
            .setContentText(if (incoming) "Incoming call" else callStateDescription(call.state))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openPendingIntent)
            .setOngoing(incoming || call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING)
            .setShowWhen(true)
            .setPublicVersion(
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Phone call")
                    .setContentText(if (incoming) "Incoming call" else "Call in progress")
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build(),
            )

        if (incoming) {
            val answer = actionPendingIntent(context, call.id, CallActionReceiver.ACTION_ANSWER, 1)
            val decline = actionPendingIntent(context, call.id, CallActionReceiver.ACTION_REJECT, 2)
            val fullScreen = PendingIntent.getActivity(
                context,
                requestCode(call.id, 3),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreen, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val person = Person.Builder().setName(call.title).setImportant(true).build()
                builder.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
            } else {
                builder
                    .addAction(Notification.Action.Builder(android.R.drawable.sym_action_call, "Answer", answer).build())
                    .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline).build())
            }
        } else if (call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING) {
            val end = actionPendingIntent(context, call.id, CallActionReceiver.ACTION_END, 4)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val person = Person.Builder().setName(call.title).setImportant(true).build()
                builder.setStyle(Notification.CallStyle.forOngoingCall(person, end))
            } else {
                builder.addAction(
                    Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "End call", end).build(),
                )
            }
        }

        runCatching { manager.notify(notificationId(call.id), builder.build()) }
    }

    fun cancel(context: Context, callId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(callId))
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming and ongoing phone calls"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            // Telecom supplies the SIM ringtone; keeping this channel silent avoids playing it twice.
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun actionPendingIntent(context: Context, callId: String, action: String, salt: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("opencaller-call://call/${Uri.encode(callId)}/$action")
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(callId, salt),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(callId: String): Int = (callId.hashCode() and 0x7fffffff) + 1000

    private fun requestCode(callId: String, salt: Int): Int =
        ((callId.hashCode() and 0x000fffff) shl 3) or salt

    private fun callStateDescription(state: Int): String = when (state) {
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling"
        Call.STATE_ACTIVE -> "Call in progress"
        Call.STATE_HOLDING -> "Call on hold"
        else -> "Phone call"
    }
}
