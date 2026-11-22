package io.github.iamsrishanth.opencaller.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.iamsrishanth.opencaller.sip.SipCallDirection
import io.github.iamsrishanth.opencaller.sip.SipCallPhase
import io.github.iamsrishanth.opencaller.sip.SipCallRuntime
import io.github.iamsrishanth.opencaller.sip.SipCallRuntimeState
import io.github.iamsrishanth.opencaller.sip.SipCallSnapshot
import io.github.iamsrishanth.opencaller.sip.SipEngineFailure
import io.github.iamsrishanth.opencaller.sip.SipRegistrationState
import io.github.iamsrishanth.opencaller.sip.displaySipPeer

/** Small screen model passed to call actions; the live-voicemail Answer callback stays injectable. */
data class InternetCallUiModel(
    val serviceSessionId: String?,
    val registration: SipRegistrationState,
    val call: SipCallSnapshot?,
    val leavingVoicemail: Boolean,
    val liveTranscript: String?,
    val liveTranscriptUnavailable: Boolean,
    val message: String?,
) {
    val callerLabel: String
        get() = call?.remoteUri?.let(::displaySipPeer).orEmpty().ifBlank { "Unknown caller" }

    val statusLabel: String
        get() = call?.phase?.let(::phaseLabel) ?: when (registration) {
            SipRegistrationState.DISCONNECTED -> "Internet line disconnected"
            SipRegistrationState.REGISTERING -> "Connecting internet line"
            SipRegistrationState.REGISTERED -> "Ready for internet calls"
            SipRegistrationState.FAILED -> "Internet line unavailable"
        }

    companion object {
        fun from(state: SipCallRuntimeState): InternetCallUiModel {
            val call = state.engine.call
            return InternetCallUiModel(
                serviceSessionId = state.serviceSessionId,
                registration = state.engine.registration,
                call = call,
                leavingVoicemail = call != null && state.leavingVoicemailCallToken == call.token,
                liveTranscript = state.liveTranscript,
                liveTranscriptUnavailable = state.liveTranscriptUnavailable,
                message = state.message ?: state.engine.failure?.toUserMessage(),
            )
        }
    }
}

/**
 * Internet-line call screen. Answering during the voicemail state is delegated to the caller so
 * it can finalize the recording and hand the same call to the recipient before connecting mic.
 */
@Composable
fun InternetCallScreen(
    modifier: Modifier = Modifier,
    onAnswer: ((InternetCallUiModel) -> Unit)? = null,
    onDecline: ((InternetCallUiModel) -> Unit)? = null,
    onSendToVoicemail: ((InternetCallUiModel) -> Unit)? = null,
    onAnswerFromVoicemail: (InternetCallUiModel) -> Unit,
    onHangup: ((InternetCallUiModel) -> Unit)? = null,
    onConnectLine: (() -> Unit)? = null,
    onDisconnectLine: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by SipCallRuntime.state.collectAsState()
    val model = InternetCallUiModel.from(state)
    var connectAfterLocalNetworkPermission by remember { mutableStateOf(false) }
    var localNetworkPermissionMessage by remember { mutableStateOf<String?>(null) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (connectAfterLocalNetworkPermission) {
            connectAfterLocalNetworkPermission = false
            if (granted) {
                localNetworkPermissionMessage = null
                SipCallRuntime.connect(context)
            } else {
                localNetworkPermissionMessage = "Allow Nearby devices access to connect to your self-hosted line and sync voicemail."
            }
        }
    }
    val answerAction = onAnswer ?: {
        model.call?.let { SipCallRuntime.answer(context, it.token, model.serviceSessionId) }
    }
    val declineAction = onDecline ?: {
        model.call?.let { SipCallRuntime.decline(context, it.token, model.serviceSessionId) }
    }
    val voicemailAction = onSendToVoicemail ?: {
        model.call?.let { SipCallRuntime.sendToVoicemail(context, it.token, model.serviceSessionId) }
    }
    val hangupAction = onHangup ?: {
        model.call?.let { SipCallRuntime.hangup(context, it.token, model.serviceSessionId) }
    }
    val connectAction = onConnectLine ?: {
        if (Build.VERSION.SDK_INT < 37 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        ) {
            SipCallRuntime.connect(context)
        } else {
            connectAfterLocalNetworkPermission = true
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
    val disconnectAction = onDisconnectLine ?: { SipCallRuntime.disconnect(context) }

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
            Text("Internet calling", style = MaterialTheme.typography.titleMedium)
            Text(
                text = model.call?.let { if (it.direction == SipCallDirection.INCOMING) "Incoming call" else "Outgoing call" }
                    ?: "Optional internet line",
                style = MaterialTheme.typography.headlineSmall,
            )

            if (model.call != null) {
                Text(
                    text = model.callerLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("internet.call.peer"),
                )
                val visibleStatus = if (model.leavingVoicemail) "Leaving a voicemail" else model.statusLabel
                Text(
                    text = visibleStatus,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("internet.call.state")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )

                if (model.leavingVoicemail) {
                    model.liveTranscript?.let { transcript ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("internet.call.voicemail.transcript"),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Live transcript", style = MaterialTheme.typography.titleSmall)
                            Text(transcript, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (model.liveTranscriptUnavailable) {
                        Text(
                            "Live transcript is unavailable on this device. The voicemail audio can still be saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("internet.call.voicemail.answer"),
                        onClick = { onAnswerFromVoicemail(model) },
                    ) {
                        Text("Answer", fontSize = 18.sp)
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("internet.call.voicemail.decline"),
                        onClick = { declineAction(model) },
                    ) {
                        Text("Decline", fontSize = 17.sp)
                    }
                } else if (model.call.direction == SipCallDirection.INCOMING &&
                    model.call.phase in setOf(SipCallPhase.INCOMING, SipCallPhase.RINGING)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp).testTag("internet.call.answer"),
                            onClick = { answerAction(model) },
                        ) { Text("Answer", fontSize = 18.sp) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp).testTag("internet.call.decline"),
                            onClick = { declineAction(model) },
                        ) { Text("Decline", fontSize = 18.sp) }
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("internet.call.send_to_voicemail"),
                        onClick = { voicemailAction(model) },
                    ) { Text("Send to voicemail", fontSize = 16.sp) }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("internet.call.hangup"),
                        onClick = { hangupAction(model) },
                    ) { Text("End call", fontSize = 18.sp) }
                }
            } else {
                Text(
                    text = model.statusLabel,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .testTag("internet.line.state")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (Build.VERSION.SDK_INT >= 37 &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                ) {
                    Text(
                        "Allow local network access to reach your self-hosted line and sync voicemail.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                localNetworkPermissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (model.registration == SipRegistrationState.REGISTERED) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("internet.line.disconnect"),
                        onClick = disconnectAction,
                    ) { Text("Disconnect internet line") }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("internet.line.connect"),
                        onClick = connectAction,
                    ) { Text("Connect internet line", fontSize = 17.sp) }
                }
                onBack?.let { backAction ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("internet.line.back"),
                        onClick = backAction,
                    ) { Text("Back", fontSize = 17.sp) }
                }
            }
        }
    }
}

private fun phaseLabel(phase: SipCallPhase): String = when (phase) {
    SipCallPhase.INCOMING -> "Incoming call"
    SipCallPhase.CALLING -> "Calling"
    SipCallPhase.RINGING -> "Ringing"
    SipCallPhase.ANSWERING -> "Connecting"
    SipCallPhase.ACTIVE -> "Call in progress"
    SipCallPhase.DISCONNECTING -> "Ending call"
}

private fun SipEngineFailure.toUserMessage(): String = when (this) {
    SipEngineFailure.INVALID_LINE_CONFIG -> "Check the internet line settings and trusted certificate."
    SipEngineFailure.NATIVE_INITIALIZATION -> "The internet calling service could not start."
    SipEngineFailure.REGISTRATION -> "Could not connect to the internet line."
    SipEngineFailure.CALL_START -> "Could not start the internet call."
    SipEngineFailure.CALL_ACTION -> "Could not complete that call action."
    SipEngineFailure.MEDIA -> "Call audio could not be safely connected."
}
