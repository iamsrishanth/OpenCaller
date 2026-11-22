package io.github.iamsrishanth.opencaller

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.iamsrishanth.opencaller.telecom.CallNotifications
import io.github.iamsrishanth.opencaller.telecom.CallSession
import io.github.iamsrishanth.opencaller.sip.SipCallNotifications
import io.github.iamsrishanth.opencaller.sip.SipCallRuntime
import io.github.iamsrishanth.opencaller.ui.call.InternetCallScreen
import io.github.iamsrishanth.opencaller.ui.dialer.OpenCallerPhoneScreen
import io.github.iamsrishanth.opencaller.ui.settings.AboutScreen
import io.github.iamsrishanth.opencaller.ui.settings.InternetLineSettingsPage
import io.github.iamsrishanth.opencaller.ui.settings.SettingsScreen
import io.github.iamsrishanth.opencaller.ui.voicemail.RecentCallDetailPage
import io.github.iamsrishanth.opencaller.ui.voicemail.RecentCallsPage
import io.github.iamsrishanth.opencaller.ui.voicemail.VoicemailPage
import io.github.iamsrishanth.opencaller.updates.UpdatesPage
import io.github.iamsrishanth.opencaller.voicemail.VoicemailSyncRepository
import io.github.iamsrishanth.opencaller.voicemail.VoicemailSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val launchIntentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchIntentState.value = intent
        if (isCallLaunch(intent)) setLockScreenCallPresentation(true)

        setContent {
            MaterialTheme {
                OpenCallerRoot(
                    activity = this,
                    launchIntent = launchIntentState.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntentState.value = intent
        if (isCallLaunch(intent)) setLockScreenCallPresentation(true)
    }

    override fun onDestroy() {
        setLockScreenCallPresentation(false)
        super.onDestroy()
    }

    private fun isCallLaunch(intent: Intent?): Boolean =
        intent?.getBooleanExtra(CallNotifications.EXTRA_OPEN_CALL, false) == true ||
            intent?.getBooleanExtra(SipCallNotifications.EXTRA_OPEN_SIP_CALL, false) == true

    @Suppress("DEPRECATION")
    internal fun setLockScreenCallPresentation(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}

private enum class OpenCallerDestination {
    PHONE,
    SETTINGS,
    ABOUT,
    UPDATES,
    INTERNET_LINE,
    INTERNET_CALL,
    VOICEMAIL,
    RECENT_CALLS,
    RECENT_CALL,
}

@Composable
private fun OpenCallerRoot(
    activity: MainActivity,
    launchIntent: Intent?,
) {
    val callState by CallSession.state.collectAsState()
    val sipCallState by SipCallRuntime.state.collectAsState()
    var destination by rememberSaveable { mutableStateOf(OpenCallerDestination.PHONE) }
    var selectedVoicemailId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRecentCallId by rememberSaveable { mutableStateOf<String?>(null) }
    var recentCallDetailFromList by rememberSaveable { mutableStateOf(false) }
    var handledSipAnswerKey by rememberSaveable { mutableStateOf("") }
    val versionLabel = remember(activity) { readVersionLabel(activity) }

    LaunchedEffect(launchIntent) {
        if (launchIntent?.getBooleanExtra(SipCallNotifications.EXTRA_OPEN_SIP_CALL, false) == true) {
            destination = OpenCallerDestination.INTERNET_CALL
        } else if (launchIntent?.action == Intent.ACTION_DIAL || isCallLaunchIntent(launchIntent)) {
            destination = OpenCallerDestination.PHONE
        }
    }

    LaunchedEffect(activity) {
        val syncEnabled = withContext(Dispatchers.IO) {
            runCatching { VoicemailSyncRepository(activity).isConfigured() }.getOrDefault(false)
        }
        VoicemailSyncWorker.configure(activity, syncEnabled)
    }

    LaunchedEffect(launchIntent, sipCallState) {
        val intent = launchIntent ?: return@LaunchedEffect
        if (!intent.getBooleanExtra(SipCallNotifications.EXTRA_OPEN_SIP_CALL, false)) return@LaunchedEffect
        val token = intent.getLongExtra(SipCallNotifications.EXTRA_SIP_CALL_TOKEN, Long.MIN_VALUE)
        val sessionId = intent.getStringExtra(SipCallNotifications.EXTRA_SIP_SERVICE_SESSION) ?: return@LaunchedEffect
        val key = "$sessionId:$token"
        if (token != Long.MIN_VALUE &&
            intent.getBooleanExtra(SipCallNotifications.EXTRA_ANSWER_FROM_VOICEMAIL, false) &&
            handledSipAnswerKey != key &&
            SipCallRuntime.isCurrentCall(sessionId, token, voicemailRequired = true)
        ) {
            handledSipAnswerKey = key
            SipCallRuntime.requestAnswerFromVoicemail(token, sessionId)
        }
    }

    LaunchedEffect(callState.calls) {
        if (callState.calls.isNotEmpty()) destination = OpenCallerDestination.PHONE
    }

    LaunchedEffect(callState.calls, sipCallState.engine.call) {
        val showCall = callState.calls.isNotEmpty() || sipCallState.engine.call != null
        if (sipCallState.engine.call != null && callState.calls.isEmpty()) {
            destination = OpenCallerDestination.INTERNET_CALL
        }
        activity.setLockScreenCallPresentation(showCall)
    }

    when (destination) {
        OpenCallerDestination.PHONE -> OpenCallerPhoneScreen(
            activity = activity,
            launchIntent = launchIntent,
            onOpenSettings = { destination = OpenCallerDestination.SETTINGS },
            onOpenVoicemail = {
                selectedVoicemailId = null
                destination = OpenCallerDestination.VOICEMAIL
            },
            onOpenRecentCalls = { destination = OpenCallerDestination.RECENT_CALLS },
        )

        OpenCallerDestination.SETTINGS -> SettingsScreen(
            onBack = { destination = OpenCallerDestination.PHONE },
            onAbout = { destination = OpenCallerDestination.ABOUT },
            onInternetLine = { destination = OpenCallerDestination.INTERNET_LINE },
        )

        OpenCallerDestination.ABOUT -> AboutScreen(
            versionLabel = versionLabel,
            onBack = { destination = OpenCallerDestination.SETTINGS },
            onUpdates = { destination = OpenCallerDestination.UPDATES },
        )

        OpenCallerDestination.UPDATES -> UpdatesPage(
            onBack = { destination = OpenCallerDestination.ABOUT },
        )

        OpenCallerDestination.INTERNET_LINE -> InternetLineSettingsPage(
            onBack = { destination = OpenCallerDestination.SETTINGS },
            onConnect = { SipCallRuntime.connect(activity) },
            onDisconnect = { SipCallRuntime.disconnect(activity) },
        )

        OpenCallerDestination.INTERNET_CALL -> InternetCallScreen(
            onAnswerFromVoicemail = { model ->
                model.call?.let { SipCallRuntime.requestAnswerFromVoicemail(it.token, model.serviceSessionId) }
            },
            onBack = { destination = OpenCallerDestination.PHONE },
        )

        OpenCallerDestination.VOICEMAIL -> VoicemailPage(
            onBack = { destination = OpenCallerDestination.PHONE },
            onOpenRecentCall = { recentCallId ->
                selectedRecentCallId = recentCallId
                recentCallDetailFromList = false
                destination = OpenCallerDestination.RECENT_CALL
            },
            highlightedMessageId = selectedVoicemailId,
        )

        OpenCallerDestination.RECENT_CALL -> RecentCallDetailPage(
            recentCallId = selectedRecentCallId.orEmpty(),
            onBack = {
                destination = if (recentCallDetailFromList) OpenCallerDestination.RECENT_CALLS
                else OpenCallerDestination.VOICEMAIL
            },
            onOpenVoicemail = { messageId ->
                selectedVoicemailId = messageId
                destination = OpenCallerDestination.VOICEMAIL
            },
        )

        OpenCallerDestination.RECENT_CALLS -> RecentCallsPage(
            onBack = { destination = OpenCallerDestination.PHONE },
            onOpenRecentCall = { recentCallId ->
                selectedRecentCallId = recentCallId
                recentCallDetailFromList = true
                destination = OpenCallerDestination.RECENT_CALL
            },
        )
    }
}

private fun isCallLaunchIntent(intent: Intent?): Boolean =
    intent?.getBooleanExtra(CallNotifications.EXTRA_OPEN_CALL, false) == true ||
        intent?.getBooleanExtra(SipCallNotifications.EXTRA_OPEN_SIP_CALL, false) == true

private fun readVersionLabel(activity: MainActivity): String {
    val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return "Version ${info.versionName ?: "unknown"} · code $versionCode"
}
