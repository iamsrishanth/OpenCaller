package io.github.iamsrishanth.opencaller.ui.call

import android.telecom.Call
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iamsrishanth.opencaller.dialer.T9Key
import io.github.iamsrishanth.opencaller.dialer.T9Keypad
import io.github.iamsrishanth.opencaller.telecom.AudioRouteOption
import io.github.iamsrishanth.opencaller.telecom.CallSession
import io.github.iamsrishanth.opencaller.telecom.CallUiModel

/** Incoming and ongoing SIM call controls backed by Telecom's Call objects. */
@Composable
fun InCallScreen(modifier: Modifier = Modifier) {
    val uiState by CallSession.state.collectAsState()
    val callIds = uiState.calls.map { it.id }
    var selectedCallId by rememberSaveable { mutableStateOf<String?>(null) }
    var keypadVisible by rememberSaveable { mutableStateOf(false) }
    var dtmfDisplay by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(callIds) {
        if (selectedCallId !in callIds) selectedCallId = callIds.firstOrNull()
    }
    val call = uiState.calls.firstOrNull { it.id == selectedCallId } ?: uiState.calls.firstOrNull()

    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("OpenCaller", style = MaterialTheme.typography.titleMedium)
            if (call == null) {
                Text("No active call", style = MaterialTheme.typography.headlineSmall)
            } else {
                if (uiState.calls.size > 1) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Calls", style = MaterialTheme.typography.labelLarge)
                        uiState.calls.forEach { item ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                onClick = {
                                    selectedCallId = item.id
                                    dtmfDisplay = ""
                                },
                            ) {
                                Text(
                                    text = "${item.title} · ${callStateLabel(item.state)}" +
                                        if (item.id == call.id) " · selected" else "",
                                )
                            }
                        }
                    }
                }

                Text(
                    text = call.title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("call.caller"),
                )
                if (call.address.isNotBlank() && call.address != call.title) {
                    Text(call.address, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = callStateLabel(call.state),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("call.state")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )

                if (call.state == Call.STATE_RINGING) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp).testTag("call.answer"),
                            onClick = { CallSession.answer(call.id) },
                        ) {
                            Text("Answer")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp).testTag("call.reject"),
                            onClick = { CallSession.reject(call.id) },
                        ) {
                            Text("Decline")
                        }
                    }
                } else {
                    if (call.supportsMute) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("call.mute"),
                            onClick = CallSession::toggleMute,
                        ) {
                            Text(if (uiState.muted) "Unmute microphone" else "Mute microphone")
                        }
                    }

                    if (call.supportsHold || call.canHoldNow) {
                        val onHold = call.state == Call.STATE_HOLDING
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("call.hold"),
                            enabled = if (onHold) call.supportsHold else call.canHoldNow,
                            onClick = { CallSession.toggleHold(call.id) },
                        ) {
                            Text(if (onHold) "Resume call" else "Hold call")
                        }
                    }

                    AudioRouteControls(
                        routes = uiState.audioRoutes,
                        onSelect = CallSession::selectAudioRoute,
                    )

                    if (call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("call.keypad.toggle"),
                            onClick = {
                                keypadVisible = !keypadVisible
                                dtmfDisplay = ""
                            },
                        ) {
                            Text(if (keypadVisible) "Hide keypad" else "Show keypad")
                        }
                        if (keypadVisible) {
                            Text(dtmfDisplay.ifBlank { "Keypad" }, style = MaterialTheme.typography.titleMedium)
                            T9Keypad.rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    row.forEach { key ->
                                        CallKey(
                                            key = key,
                                            modifier = Modifier.weight(1f),
                                            onPress = {
                                                dtmfDisplay += key.digit
                                                CallSession.sendDtmf(call.id, key.digit)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("call.end"),
                        onClick = { CallSession.disconnect(call.id) },
                    ) {
                        Text("End call", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioRouteControls(
    routes: List<AudioRouteOption>,
    onSelect: (String) -> Unit,
) {
    if (routes.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Audio route", style = MaterialTheme.typography.labelLarge)
        routes.forEach { route ->
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("call.route.${route.id}"),
                onClick = { onSelect(route.id) },
                enabled = !route.selected,
            ) {
                Text(if (route.selected) "${route.label} selected" else route.label)
            }
        }
    }
}

@Composable
private fun CallKey(
    key: T9Key,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    Button(
        modifier = modifier.heightIn(min = 64.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        onClick = onPress,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.digit.toString(), fontSize = 24.sp, lineHeight = 26.sp)
            if (key.letters.isNotBlank()) Text(key.letters, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun callStateLabel(state: Int): String = when (state) {
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_CONNECTING -> "Connecting"
    Call.STATE_DIALING -> "Calling"
    Call.STATE_ACTIVE -> "In call"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_DISCONNECTING -> "Ending call"
    Call.STATE_DISCONNECTED -> "Call ended"
    else -> "Call"
}
