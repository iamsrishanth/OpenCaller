package io.github.iamsrishanth.opencaller.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.iamsrishanth.opencaller.MainActivity

/** Notifications for the optional internet line; SIM-call notifications stay in Telecom. */
internal object SipCallNotifications {
    const val EXTRA_OPEN_SIP_CALL = "io.github.iamsrishanth.opencaller.extra.OPEN_SIP_CALL"
    const val EXTRA_SIP_CALL_TOKEN = "io.github.iamsrishanth.opencaller.extra.SIP_CALL_TOKEN"
    const val EXTRA_SIP_SERVICE_SESSION = "io.github.iamsrishanth.opencaller.extra.SIP_SERVICE_SESSION"
    const val EXTRA_ANSWER_FROM_VOICEMAIL = "io.github.iamsrishanth.opencaller.extra.ANSWER_FROM_VOICEMAIL"

    private const val LINE_CHANNEL_ID = "opencaller_internet_line"
    private const val CALL_CHANNEL_ID = "opencaller_internet_calls"
    private const val CALL_NOTIFICATION_BASE = 0x52000000

    fun foreground(context: Context, snapshot: SipEngineSnapshot, message: String?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(context, manager)
        val title = when {
            message != null -> "Internet line needs attention"
            snapshot.call != null -> "Internet call in progress"
            snapshot.registration == SipRegistrationState.REGISTERED -> "Internet line connected"
            snapshot.registration == SipRegistrationState.REGISTERING -> "Connecting internet line"
            snapshot.registration == SipRegistrationState.FAILED -> "Internet line unavailable"
            else -> "Internet line is offline"
        }
        val body = message ?: when (snapshot.registration) {
            SipRegistrationState.REGISTERED -> "Ready for internet calls"
            SipRegistrationState.REGISTERING -> "Registering securely"
            SipRegistrationState.FAILED -> "Check the saved line settings"
            SipRegistrationState.DISCONNECTED -> "Open OpenCaller to connect"
        }
        return Notification.Builder(context, LINE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification(context, "Internet calling", "Open OpenCaller to view line status"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateCall(context: Context, serviceSessionId: String, call: SipCallSnapshot, leavingVoicemail: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context, manager)

        val incoming = call.direction == SipCallDirection.INCOMING &&
            call.phase in setOf(SipCallPhase.INCOMING, SipCallPhase.RINGING)
        val open = openPendingIntent(
            context = context,
            serviceSessionId = serviceSessionId,
            callToken = call.token,
            answerFromVoicemail = incoming && leavingVoicemail,
            requestSuffix = 0,
        )
        val builder = Notification.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (incoming && leavingVoicemail) "Leaving a voicemail" else "Internet call")
            .setContentText(if (incoming) "Incoming call" else callStatusText(call))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification(context, "Internet call", if (incoming) "Incoming call" else "Call in progress"))
            .setContentIntent(open)
            .setOngoing(incoming || call.phase in setOf(SipCallPhase.ACTIVE, SipCallPhase.ANSWERING))
            .setOnlyAlertOnce(!incoming)

        if (incoming) {
            val decline = servicePendingIntent(
                context,
                serviceSessionId,
                call.token,
                SipLineForegroundService.ACTION_DECLINE,
                suffix = 1,
            )
            val answer = if (leavingVoicemail) {
                openPendingIntent(context, serviceSessionId, call.token, answerFromVoicemail = true, requestSuffix = 2)
            } else {
                servicePendingIntent(
                    context,
                    serviceSessionId,
                    call.token,
                    SipLineForegroundService.ACTION_ANSWER,
                    suffix = 2,
                )
            }
            builder.setFullScreenIntent(open, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val person = Person.Builder().setName("Internet call").setImportant(true).build()
                builder.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
            } else {
                builder.addAction(Notification.Action.Builder(android.R.drawable.sym_action_call, "Answer", answer).build())
                builder.addAction(
                    Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline).build(),
                )
            }
            if (!leavingVoicemail) {
                val sendToVoicemail = servicePendingIntent(
                    context,
                    serviceSessionId,
                    call.token,
                    SipLineForegroundService.ACTION_SEND_TO_VOICEMAIL,
                    suffix = 3,
                )
                builder.addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_btn_speak_now,
                        "Send to voicemail",
                        sendToVoicemail,
                    ).build(),
                )
            }
        } else if (call.phase in setOf(SipCallPhase.ACTIVE, SipCallPhase.ANSWERING, SipCallPhase.DISCONNECTING)) {
            val end = servicePendingIntent(
                context,
                serviceSessionId,
                call.token,
                SipLineForegroundService.ACTION_HANGUP,
                suffix = 4,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val person = Person.Builder().setName("Internet call").setImportant(true).build()
                builder.setStyle(Notification.CallStyle.forOngoingCall(person, end))
            } else {
                builder.addAction(
                    Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "End call", end).build(),
                )
            }
        }

        runCatching { manager.notify(notificationId(call.token), builder.build()) }
    }

    fun cancelCall(context: Context, callToken: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(callToken))
    }

    private fun servicePendingIntent(
        context: Context,
        serviceSessionId: String,
        callToken: Long,
        action: String,
        suffix: Int,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode(callToken, suffix),
        Intent(context, SipLineForegroundService::class.java).apply {
            this.action = action
            data = Uri.parse("opencaller-sip://$serviceSessionId/$callToken/$action")
            putExtra(SipLineForegroundService.EXTRA_CALL_TOKEN, callToken)
            putExtra(SipLineForegroundService.EXTRA_SERVICE_SESSION_ID, serviceSessionId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openPendingIntent(
        context: Context,
        serviceSessionId: String,
        callToken: Long,
        answerFromVoicemail: Boolean,
        requestSuffix: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data = Uri.parse("opencaller-sip-open://$serviceSessionId/$callToken/$requestSuffix")
            putExtra(EXTRA_OPEN_SIP_CALL, true)
            putExtra(EXTRA_SIP_CALL_TOKEN, callToken)
            putExtra(EXTRA_SIP_SERVICE_SESSION, serviceSessionId)
            putExtra(EXTRA_ANSWER_FROM_VOICEMAIL, answerFromVoicemail)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(callToken, 10 + requestSuffix),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannels(context: Context, manager: NotificationManager?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager == null) return
        if (manager.getNotificationChannel(LINE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(LINE_CHANNEL_ID, "Internet line", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows the optional internet calling line connection state"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        if (manager.getNotificationChannel(CALL_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CALL_CHANNEL_ID, "Internet calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming and ongoing OpenCaller internet calls"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    private fun publicNotification(context: Context, title: String, text: String): Notification =
        Notification.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

    private fun callStatusText(call: SipCallSnapshot): String = when (call.phase) {
        SipCallPhase.INCOMING -> "Incoming call"
        SipCallPhase.CALLING -> "Calling"
        SipCallPhase.RINGING -> "Ringing"
        SipCallPhase.ANSWERING -> "Connecting"
        SipCallPhase.ACTIVE -> "Call in progress"
        SipCallPhase.DISCONNECTING -> "Ending call"
    }

    private fun requestCode(callToken: Long, suffix: Int): Int =
        (callToken xor (callToken ushr 32)).toInt() * 31 + suffix

    private fun notificationId(callToken: Long): Int = CALL_NOTIFICATION_BASE or
        ((callToken xor (callToken ushr 32)).toInt() and 0x00FFFFFF)
}
