package io.github.iamsrishanth.opencaller.ui.dialer

import android.Manifest
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iamsrishanth.opencaller.dialer.DefaultDialerRole
import io.github.iamsrishanth.opencaller.dialer.DialIntentParser
import io.github.iamsrishanth.opencaller.dialer.SimCallLaunchResult
import io.github.iamsrishanth.opencaller.dialer.SimCallLauncher
import io.github.iamsrishanth.opencaller.dialer.T9Key
import io.github.iamsrishanth.opencaller.dialer.T9Keypad
import io.github.iamsrishanth.opencaller.telecom.CallSession
import io.github.iamsrishanth.opencaller.ui.call.InCallScreen

/** Main phone surface for hosting from MainActivity. */
@Composable
fun OpenCallerPhoneScreen(
    activity: ComponentActivity,
    launchIntent: Intent? = activity.intent,
    onOpenSettings: () -> Unit = {},
    onOpenVoicemail: () -> Unit = {},
    onOpenRecentCalls: () -> Unit = {},
) {
    val callState by CallSession.state.collectAsState()
    if (callState.calls.isNotEmpty()) {
        InCallScreen()
    } else {
        DialerScreen(
            activity = activity,
            launchIntent = launchIntent,
            onOpenSettings = onOpenSettings,
            onOpenVoicemail = onOpenVoicemail,
            onOpenRecentCalls = onOpenRecentCalls,
        )
    }
}

/** Compose dial pad with T9 letter groups and a user-initiated default-dialer request. */
@Composable
fun DialerScreen(
    activity: ComponentActivity,
    launchIntent: Intent? = null,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenVoicemail: () -> Unit = {},
    onOpenRecentCalls: () -> Unit = {},
) {
    var number by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var isDefaultDialer by remember { mutableStateOf(DefaultDialerRole.isHeld(activity)) }
    var pendingCall by remember { mutableStateOf<String?>(null) }
    var hasNotificationPermission by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS))
    }
    var canUseFullScreenIntent by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 34 || fullScreenIntentAllowed(activity))
    }
    val hasTelephony = remember(activity) {
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
    val dialIntentNumber = remember(launchIntent) { DialIntentParser.numberFrom(launchIntent) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotificationPermission = granted
        message = if (granted) "Incoming call notifications are enabled." else "Incoming calls may not appear as notifications."
    }

    val fullScreenIntentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canUseFullScreenIntent = Build.VERSION.SDK_INT < 34 || fullScreenIntentAllowed(activity)
        message = if (canUseFullScreenIntent) {
            "Lock-screen incoming call screens are enabled."
        } else {
            "Full-screen access is still off. Android will use its available call notification instead."
        }
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultDialer = DefaultDialerRole.isHeld(activity)
        message = if (isDefaultDialer) {
            "OpenCaller is now the default phone app."
        } else {
            "OpenCaller was not selected as the default phone app."
        }
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val numberToCall = pendingCall
        pendingCall = null
        if (!granted || numberToCall == null) {
            message = "Phone permission was not granted. No SIM call was placed."
        } else {
            message = callMessage(SimCallLauncher.placeCall(activity, numberToCall))
        }
    }

    LaunchedEffect(launchIntent) {
        if (dialIntentNumber != null) {
            number = dialIntentNumber
            message = null
        }
    }

    fun placeSimCall() {
        when (val result = SimCallLauncher.placeCall(activity, number)) {
            SimCallLaunchResult.Started -> message = "Call request sent to Android Telecom."
            SimCallLaunchResult.NeedsPhonePermission -> {
                pendingCall = number
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
            is SimCallLaunchResult.Failed -> message = result.message
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "OpenCaller",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                )
                TextButton(
                    onClick = onOpenRecentCalls,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("dialer.recents"),
                ) {
                    Text("Recent")
                }
                TextButton(
                    onClick = onOpenVoicemail,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("dialer.voicemail"),
                ) {
                    Text("Voicemail")
                }
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Settings")
                }
            }
            Text("SIM dialer", style = MaterialTheme.typography.titleMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (isDefaultDialer) "Default phone app" else "Your current phone app remains the default",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (isDefaultDialer) {
                            "OpenCaller will show the Android call screen for SIM calls."
                        } else {
                            "Choose OpenCaller as the default phone app to use its incoming and ongoing call screens."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!isDefaultDialer) {
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            onClick = {
                                val request = DefaultDialerRole.createRequestIntent(activity)
                                if (request == null) {
                                    message = "Android does not offer the default phone app role on this device."
                                } else {
                                    roleLauncher.launch(request)
                                }
                            },
                        ) {
                            Text("Set OpenCaller as default phone app")
                        }
                    } else {
                        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                onClick = {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            ) {
                                Text("Enable incoming call notifications")
                            }
                        }
                        if (!canUseFullScreenIntent && Build.VERSION.SDK_INT >= 34) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                onClick = {
                                    val settingsIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.fromParts("package", activity.packageName, null)
                                    }
                                    fullScreenIntentLauncher.launch(settingsIntent)
                                },
                            ) {
                                Text("Allow lock-screen incoming call screen")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Calling line", style = MaterialTheme.typography.labelLarge)
                Text("SIM", style = MaterialTheme.typography.titleSmall)
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("dialer.number"),
                value = number,
                onValueChange = { value -> number = value.filter { it.isDigit() || it in "+*#,; ()-." } },
                label = { Text("Phone number") },
                placeholder = { Text("Enter a number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { number = ""; message = null }, enabled = number.isNotEmpty()) {
                    Text("Clear")
                }
                TextButton(onClick = { number = number.dropLast(1); message = null }, enabled = number.isNotEmpty()) {
                    Text("Delete last digit")
                }
            }

            T9Keypad.rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { key ->
                        DialKey(
                            key = key,
                            modifier = Modifier.weight(1f),
                            onPress = { number += key.digit },
                        )
                    }
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .testTag("dialer.call"),
                onClick = ::placeSimCall,
                enabled = hasTelephony && number.any { it.isDigit() || it == '*' || it == '#' },
            ) {
                Text("Call with SIM", fontSize = 18.sp)
            }

            if (!hasTelephony) {
                Text(
                    "SIM calling is unavailable on this device. The optional internet line is not connected yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            message?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DialKey(
    key: T9Key,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    val accessibilityLabel = if (key.letters.isBlank()) {
        "Key ${key.digit}"
    } else {
        "Key ${key.digit}, ${key.letters.toList().joinToString(" ")}"
    }
    Button(
        onClick = onPress,
        modifier = modifier
            .heightIn(min = 64.dp)
            .testTag("dialpad-key-${key.digit}")
            .semantics { contentDescription = accessibilityLabel },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.digit.toString(), fontSize = 26.sp, lineHeight = 28.sp)
            if (key.letters.isNotBlank()) {
                Text(key.letters, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

private fun callMessage(result: SimCallLaunchResult): String? = when (result) {
    SimCallLaunchResult.Started -> "Call request sent to Android Telecom."
    SimCallLaunchResult.NeedsPhonePermission -> "Phone permission is required to place a SIM call."
    is SimCallLaunchResult.Failed -> result.message
}

private fun hasPermission(activity: ComponentActivity, permission: String): Boolean =
    activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@androidx.annotation.RequiresApi(34)
private fun fullScreenIntentAllowed(activity: ComponentActivity): Boolean =
    activity.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
