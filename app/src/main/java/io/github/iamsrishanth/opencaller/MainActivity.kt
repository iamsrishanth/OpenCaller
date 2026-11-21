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
import io.github.iamsrishanth.opencaller.ui.dialer.OpenCallerPhoneScreen
import io.github.iamsrishanth.opencaller.ui.settings.AboutScreen
import io.github.iamsrishanth.opencaller.ui.settings.SettingsScreen
import io.github.iamsrishanth.opencaller.updates.UpdatesPage

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
        intent?.getBooleanExtra(CallNotifications.EXTRA_OPEN_CALL, false) == true

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
}

@Composable
private fun OpenCallerRoot(
    activity: MainActivity,
    launchIntent: Intent?,
) {
    val callState by CallSession.state.collectAsState()
    var destination by rememberSaveable { mutableStateOf(OpenCallerDestination.PHONE) }
    val versionLabel = remember(activity) { readVersionLabel(activity) }

    LaunchedEffect(launchIntent) {
        if (launchIntent?.action == Intent.ACTION_DIAL || isCallLaunchIntent(launchIntent)) {
            destination = OpenCallerDestination.PHONE
        }
    }

    LaunchedEffect(callState.calls) {
        if (callState.calls.isNotEmpty()) destination = OpenCallerDestination.PHONE
        activity.setLockScreenCallPresentation(callState.calls.isNotEmpty())
    }

    when (destination) {
        OpenCallerDestination.PHONE -> OpenCallerPhoneScreen(
            activity = activity,
            launchIntent = launchIntent,
            onOpenSettings = { destination = OpenCallerDestination.SETTINGS },
        )

        OpenCallerDestination.SETTINGS -> SettingsScreen(
            onBack = { destination = OpenCallerDestination.PHONE },
            onAbout = { destination = OpenCallerDestination.ABOUT },
        )

        OpenCallerDestination.ABOUT -> AboutScreen(
            versionLabel = versionLabel,
            onBack = { destination = OpenCallerDestination.SETTINGS },
            onUpdates = { destination = OpenCallerDestination.UPDATES },
        )

        OpenCallerDestination.UPDATES -> UpdatesPage(
            onBack = { destination = OpenCallerDestination.ABOUT },
        )
    }
}

private fun isCallLaunchIntent(intent: Intent?): Boolean =
    intent?.getBooleanExtra(CallNotifications.EXTRA_OPEN_CALL, false) == true

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
