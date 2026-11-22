package io.github.iamsrishanth.opencaller.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.iamsrishanth.opencaller.sip.SavedSipLine
import io.github.iamsrishanth.opencaller.sip.SipLineConfigStore
import io.github.iamsrishanth.opencaller.voicemail.VoicemailAutoMode
import io.github.iamsrishanth.opencaller.voicemail.VoicemailAutoSettings
import io.github.iamsrishanth.opencaller.voicemail.VoicemailAutoSettingsStore
import io.github.iamsrishanth.opencaller.voicemail.VoicemailGreetingRecorder
import io.github.iamsrishanth.opencaller.voicemail.VoicemailSyncWorker
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InternetLineSettingsPage(
    onBack: () -> Unit,
    onConnect: (SavedSipLine) -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { SipLineConfigStore(context) }
    val voicemailSettingsStore = remember(context) { VoicemailAutoSettingsStore(context) }
    val initial = remember(store) { runCatching { store.load() }.getOrNull() }
    val initialVoicemailSettings = remember(voicemailSettingsStore) { voicemailSettingsStore.load() }
    var savedLine by remember { mutableStateOf(initial) }
    var voicemailSettings by remember { mutableStateOf(initialVoicemailSettings) }
    var selectedCallersText by remember {
        mutableStateOf(initialVoicemailSettings.selectedCallers.sorted().joinToString("\n"))
    }
    var server by remember { mutableStateOf(initial?.config?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.config?.tlsPort ?: 5061).toString()) }
    var voicemailSyncPort by remember { mutableStateOf((initial?.config?.voicemailSyncPort ?: 8443).toString()) }
    var voicemailSyncToken by remember { mutableStateOf("") }
    var clearVoicemailSyncToken by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(initial?.config?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var certificateBytes by remember {
        mutableStateOf(
            initial?.let { runCatching { File(it.config.caPath).takeIf(File::isFile)?.readBytes() }.getOrNull() },
        )
    }
    var certificateName by remember { mutableStateOf(if (initial != null) "Saved trusted certificate" else "No certificate selected") }
    var message by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var connectAfterNotificationsPermission by remember { mutableStateOf(false) }
    var connectAfterLocalNetworkPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (connectAfterLocalNetworkPermission) {
            connectAfterLocalNetworkPermission = false
            if (granted) {
                savedLine?.let(onConnect)
            } else {
                message = "Allow Nearby devices access to connect to your self-hosted line and sync voicemail."
            }
        }
    }

    fun connectWithLocalNetworkPermission(line: SavedSipLine) {
        if (Build.VERSION.SDK_INT < 37 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        ) {
            onConnect(line)
        } else {
            connectAfterLocalNetworkPermission = true
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (connectAfterNotificationsPermission) {
            connectAfterNotificationsPermission = false
            if (!granted) {
                message = "The line will connect, but incoming call alerts may be hidden while OpenCaller is closed."
            }
            savedLine?.let(::connectWithLocalNetworkPermission)
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            message = "Allow microphone access before connecting so you can answer internet calls."
        } else {
            val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (notificationsGranted) {
                savedLine?.let(::connectWithLocalNetworkPermission)
            } else if (Build.VERSION.SDK_INT >= 33) {
                connectAfterNotificationsPermission = true
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun connectWithRequiredPermissions(line: SavedSipLine) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (notificationsGranted) {
            connectWithLocalNetworkPermission(line)
        } else if (Build.VERSION.SDK_INT >= 33) {
            connectAfterNotificationsPermission = true
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun updateVoicemailSettings(updated: VoicemailAutoSettings) {
        voicemailSettings = updated
        voicemailSettingsStore.save(updated)
    }

    val certificatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readLimited(MAX_CA_BYTES + 1)
                        } ?: error("Could not read that certificate file.")
                        val displayName = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                        }
                        bytes to displayName
                    }
                }
                result.onSuccess { (bytes, displayName) ->
                    if (bytes.size > MAX_CA_BYTES) {
                        message = "Choose a certificate bundle smaller than 256 KB."
                    } else {
                        certificateBytes = bytes
                        certificateName = displayName?.takeIf { it.isNotBlank() } ?: "Selected certificate"
                        message = null
                    }
                }.onFailure { error ->
                    message = error.message ?: "Could not read that certificate file."
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to settings") }
        Text("Internet line", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect to a self-hosted SIP line. Calls use verified TLS signaling and required SRTP audio.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            Text(
                "Android may ask to let OpenCaller access devices on your local network so it can reach this line and sync voicemail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.host"),
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server name or IP address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.port"),
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("TLS port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.username"),
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("SIP account name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.password"),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("SIP password") },
                    placeholder = { Text(if (savedLine != null) "Saved securely; leave blank to keep it" else "Enter the SIP password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    onClick = {
                        certificatePicker.launch(
                            arrayOf(
                                "application/x-pem-file",
                                "application/pem-certificate-chain",
                                "application/x-x509-ca-cert",
                                "application/pkix-cert",
                                "application/octet-stream",
                                "text/plain",
                            ),
                        )
                    },
                ) { Text("Choose trusted server certificate") }
                Text(certificateName, style = MaterialTheme.typography.bodySmall)
                Text(
                    "The password is encrypted with this phone’s Android Keystore. OpenCaller will reject an untrusted or mismatched server certificate.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Offline voicemail sync uses a separate HTTPS service on the same server and the same trusted certificate.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.voicemail_sync_port"),
                    value = voicemailSyncPort,
                    onValueChange = { voicemailSyncPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Voicemail sync HTTPS port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.voicemail_sync_token"),
                    value = voicemailSyncToken,
                    onValueChange = { voicemailSyncToken = it; clearVoicemailSyncToken = false },
                    label = { Text("Voicemail sync token") },
                    placeholder = {
                        Text(
                            when {
                                clearVoicemailSyncToken -> "Voicemail sync is disabled"
                                savedLine?.config?.voicemailSyncToken != null -> "Saved securely; leave blank to keep it"
                                else -> "Optional server-issued token"
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (savedLine?.config?.voicemailSyncToken != null && !clearVoicemailSyncToken) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = {
                            clearVoicemailSyncToken = true
                            voicemailSyncToken = ""
                        },
                    ) { Text("Disable voicemail sync") }
                }
                Text(
                    "When configured, OpenCaller syncs messages from the fixed voicemail inbox over HTTPS. The token is encrypted with Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        VoicemailAutoRulesCard(
            settings = voicemailSettings,
            selectedCallersText = selectedCallersText,
            onModeChange = { updateVoicemailSettings(voicemailSettings.copy(mode = it)) },
            onTimeoutChange = { updateVoicemailSettings(voicemailSettings.copy(ringTimeoutSeconds = it)) },
            onSelectedCallersChange = { value ->
                selectedCallersText = value
                val callers = value.split(',', '\n')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
                updateVoicemailSettings(voicemailSettings.copy(selectedCallers = callers))
            },
            onTranscriptionChange = {
                updateVoicemailSettings(voicemailSettings.copy(liveTranscriptionEnabled = it))
            },
        )

        VoicemailGreetingCard()

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("internet.save"),
            enabled = !isSaving,
            onClick = {
                isSaving = true
                message = null
                scope.launch {
                    val currentCert = certificateBytes
                    val passwordToSave = password.ifEmpty { savedLine?.config?.password.orEmpty() }
                    val voicemailSyncTokenToSave = when {
                        clearVoicemailSyncToken -> null
                        voicemailSyncToken.isNotBlank() -> voicemailSyncToken
                        else -> savedLine?.config?.voicemailSyncToken
                    }
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            store.save(
                                host = server,
                                tlsPort = port.toIntOrNull() ?: -1,
                                username = username,
                                password = passwordToSave,
                                certificateBytes = currentCert ?: error("Choose a trusted server certificate."),
                                voicemailSyncPort = voicemailSyncPort.toIntOrNull() ?: -1,
                                voicemailSyncToken = voicemailSyncTokenToSave,
                            )
                        }
                    }
                    isSaving = false
                    result.onSuccess { saved ->
                        savedLine = saved
                        VoicemailSyncWorker.configure(context, saved.config.voicemailSyncToken != null)
                        password = ""
                        voicemailSyncToken = ""
                        clearVoicemailSyncToken = false
                        certificateName = "Saved trusted certificate · SHA-256 ${saved.caSha256}"
                        message = "Internet line settings saved securely."
                    }.onFailure { error ->
                        message = error.cause?.message ?: error.message ?: "Could not save the internet line."
                    }
                }
            },
        ) { Text(if (isSaving) "Saving…" else "Save secure line") }

        if (savedLine != null) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                onClick = { connectWithRequiredPermissions(savedLine!!) },
            ) { Text("Connect internet line") }
            TextButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    onDisconnect()
                    store.clear()
                    savedLine = null
                    certificateBytes = null
                    certificateName = "No certificate selected"
                    message = "Internet line settings removed from this phone."
                },
            ) { Text("Remove internet line") }
        }
    }
}

@Composable
private fun VoicemailAutoRulesCard(
    settings: VoicemailAutoSettings,
    selectedCallersText: String,
    onModeChange: (VoicemailAutoMode) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onSelectedCallersChange: (String) -> Unit,
    onTranscriptionChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Internet call voicemail", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose which calls go to voicemail automatically. You can also send one ringing internet call from its call screen.",
                style = MaterialTheme.typography.bodyMedium,
            )

            VoicemailAutoMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onModeChange(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.mode == mode,
                        onClick = { onModeChange(mode) },
                    )
                    Text(
                        text = when (mode) {
                            VoicemailAutoMode.OFF -> "Off"
                            VoicemailAutoMode.SELECTED_CALLERS -> "Selected callers"
                            VoicemailAutoMode.ALL_CALLERS -> "All callers"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (settings.mode == VoicemailAutoMode.SELECTED_CALLERS) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("internet.voicemail.selected_callers"),
                    value = selectedCallersText,
                    onValueChange = onSelectedCallersChange,
                    label = { Text("Caller IDs, one per line") },
                    placeholder = { Text("1002 or +15551234567") },
                    supportingText = { Text("Use the SIP account or phone number shown for the caller.") },
                    minLines = 2,
                )
            }

            if (settings.mode != VoicemailAutoMode.OFF) {
                Text(
                    if (settings.ringTimeoutSeconds == 0) "Send immediately" else "Send after ${settings.ringTimeoutSeconds} seconds",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    modifier = Modifier.testTag("internet.voicemail.ring_timeout"),
                    value = settings.ringTimeoutSeconds.toFloat(),
                    onValueChange = { onTimeoutChange(it.toInt()) },
                    valueRange = 0f..60f,
                    steps = 59,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live transcription", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Uses Android's on-device speech service. A provider that ignores caller-audio input may open the handset microphone; keep this off until verified on your device. Recording still works without a transcript, and transcript text stays off lock-screen notifications.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    modifier = Modifier.testTag("internet.voicemail.transcription"),
                    checked = settings.liveTranscriptionEnabled,
                    onCheckedChange = onTranscriptionChange,
                )
            }
        }
    }
}

@Composable
private fun VoicemailGreetingCard() {
    val context = LocalContext.current
    val directory = remember(context) { File(context.filesDir, "voicemail") }
    val greetingFile = remember(directory) { File(directory, VoicemailGreetingRecorder.GREETING_FILE_NAME) }
    var hasCustomGreeting by remember(greetingFile) { mutableStateOf(greetingFile.isFile && greetingFile.length() > 44L) }
    var isRecording by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<VoicemailGreetingRecorder?>(null) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val mainHandler = remember(context) { Handler(Looper.getMainLooper()) }

    val startRecording: () -> Unit = remember(context, directory, mainHandler) {
        {
            val session = VoicemailGreetingRecorder(context, directory) { savedFile ->
                mainHandler.post {
                    isRecording = false
                    recorder = null
                    hasCustomGreeting = savedFile?.isFile == true
                    status = if (hasCustomGreeting) {
                        "Greeting saved with the voicemail tone."
                    } else {
                        "Record a little longer, then save again."
                    }
                }
            }
            if (session.start()) {
                recorder = session
                isRecording = true
                status = "Recording. Speak clearly, then choose Stop and save."
            } else {
                status = "Could not start greeting recording. Check microphone access and try again."
            }
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording() else status = "Allow microphone access to record a custom greeting."
    }

    fun playPreview() {
        val file = greetingFile.takeIf { it.isFile } ?: return
        runCatching {
            previewPlayer?.release()
            val next = MediaPlayer()
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { prepared ->
                prepared.start()
                isPreviewing = true
            }
            next.setOnCompletionListener { completed ->
                completed.release()
                if (previewPlayer === completed) previewPlayer = null
                isPreviewing = false
            }
            next.setOnErrorListener { failed, _, _ ->
                failed.release()
                if (previewPlayer === failed) previewPlayer = null
                isPreviewing = false
                status = "Could not play the greeting preview."
                true
            }
            previewPlayer = next
            next.prepareAsync()
        }.onFailure { status = "Could not play the greeting preview." }
    }

    DisposableEffect(recorder, previewPlayer) {
        onDispose {
            recorder?.stop()
            previewPlayer?.let { player ->
                runCatching { player.stop() }
                player.release()
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Voicemail greeting", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasCustomGreeting) {
                    "Your custom greeting is stored on this phone. A short tone is added after the recording."
                } else {
                    "Record a custom greeting for internet callers. A short tone is added after the recording."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isRecording) {
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("internet.voicemail.greeting.stop"),
                    onClick = { recorder?.stop() },
                ) { Text("Stop and save greeting") }
            } else {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("internet.voicemail.greeting.record"),
                    onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startRecording()
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                ) { Text(if (hasCustomGreeting) "Record a new greeting" else "Record custom greeting") }
            }
            if (hasCustomGreeting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        onClick = ::playPreview,
                        enabled = !isRecording && !isPreviewing,
                    ) { Text("Play preview") }
                    TextButton(
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        onClick = {
                            previewPlayer?.let { runCatching { it.stop() }; it.release() }
                            previewPlayer = null
                            isPreviewing = false
                            val removed = !greetingFile.exists() || greetingFile.delete()
                            hasCustomGreeting = !removed
                            status = if (!hasCustomGreeting) "Using the default greeting." else "Could not remove the custom greeting."
                        },
                        enabled = !isRecording,
                    ) { Text("Use default") }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val MAX_CA_BYTES = 256 * 1024

private fun java.io.InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}
