package io.github.iamsrishanth.opencaller.ui.voicemail

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iamsrishanth.opencaller.sip.SipCallRuntime
import io.github.iamsrishanth.opencaller.sip.phoneNumberForCallback
import io.github.iamsrishanth.opencaller.sip.sipUserForCallback
import io.github.iamsrishanth.opencaller.voicemail.VoicemailMessage
import io.github.iamsrishanth.opencaller.voicemail.VoicemailRepository
import io.github.iamsrishanth.opencaller.voicemail.VoicemailSyncRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoicemailPage(
    onBack: () -> Unit,
    onOpenRecentCall: (String) -> Unit = {},
    highlightedMessageId: String? = null,
) {
    val context = LocalContext.current
    val repository = remember(context) { VoicemailRepository(File(context.filesDir, "voicemail")) }
    val syncRepository = remember(context) { VoicemailSyncRepository(context) }
    val syncScope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(repository.list()) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var activeMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableFloatStateOf(0f) }
    var transcriptIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<VoicemailMessage?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    fun stopPlayback() {
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
        }
        player = null
        activeMessageId = null
        isPlaying = false
        durationMs = 0f
        positionMs = 0f
    }

    fun refresh() {
        messages = repository.list()
    }

    fun syncNow() {
        if (syncing) return
        syncScope.launch {
            syncing = true
            val configured = withContext(Dispatchers.IO) { runCatching { syncRepository.isConfigured() }.getOrDefault(false) }
            if (configured) {
                val result = runCatching { syncRepository.synchronize() }
                syncMessage = result.fold(
                    onSuccess = { count -> if (count > 0) "Synced $count voicemail${if (count == 1) "" else "s"}." else "Voicemail is up to date." },
                    onFailure = { "Could not sync server voicemail. It will retry when a connection is available." },
                )
            } else {
                syncMessage = "Server voicemail sync is not set up for this internet line."
            }
            refresh()
            syncing = false
        }
    }

    var syncAfterLocalNetworkPermission by remember { mutableStateOf(false) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (syncAfterLocalNetworkPermission) {
            syncAfterLocalNetworkPermission = false
            if (granted) {
                syncNow()
            } else {
                syncMessage = "Allow local network access to sync server voicemail."
            }
        }
    }

    fun requestSync() {
        if (Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            syncAfterLocalNetworkPermission = true
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            syncNow()
        }
    }

    fun play(message: VoicemailMessage) {
        if (activeMessageId == message.id) {
            val currentPlayer = player ?: return
            if (isPlaying) {
                currentPlayer.pause()
                isPlaying = false
                positionMs = currentPlayer.currentPosition.toFloat()
            } else {
                currentPlayer.start()
                isPlaying = true
            }
            repository.markRead(message.id)
            refresh()
            return
        }

        stopPlayback()
        val audioFile = repository.audioFile(message)
        val nextPlayer = MediaPlayer()
        try {
            nextPlayer.setDataSource(audioFile.absolutePath)
            nextPlayer.setOnPreparedListener { prepared ->
                durationMs = prepared.duration.coerceAtLeast(1).toFloat()
                positionMs = 0f
                isPlaying = true
                prepared.start()
            }
            nextPlayer.setOnCompletionListener {
                isPlaying = false
                positionMs = durationMs
            }
            nextPlayer.setOnErrorListener { _, _, _ ->
                playbackError = "This voicemail could not be played."
                stopPlayback()
                true
            }
            player = nextPlayer
            activeMessageId = message.id
            playbackError = null
            repository.markRead(message.id)
            refresh()
            nextPlayer.prepareAsync()
        } catch (_: Exception) {
            nextPlayer.release()
            playbackError = "This voicemail could not be played."
            stopPlayback()
        }
    }

    fun seekTo(fraction: Float) {
        val currentPlayer = player ?: return
        val target = (fraction.coerceIn(0f, 1f) * durationMs).toInt()
        runCatching { currentPlayer.seekTo(target) }
        positionMs = target.toFloat()
    }

    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    LaunchedEffect(player, isPlaying) {
        val currentPlayer = player ?: return@LaunchedEffect
        while (isPlaying && player === currentPlayer) {
            positionMs = runCatching { currentPlayer.currentPosition.toFloat() }.getOrDefault(positionMs)
            delay(250)
        }
    }

    LaunchedEffect(syncRepository) {
        if (Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            syncMessage = "Allow local network access, then tap Sync to check server voicemail."
        } else {
            syncNow()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Voicemail", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { requestSync() },
                        enabled = !syncing,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("voicemail.sync"),
                    ) { Text(if (syncing) "Syncing…" else "Sync") }
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                }
            }

            syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (messages.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No voicemail yet",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                messages.forEach { message ->
                    val isActive = activeMessageId == message.id
                    val sipCallbackUser = sipUserForCallback(message.callerAddress)
                    val phoneCallbackNumber = phoneNumberForCallback(message.callerAddress)
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("voicemail.${message.id}"),
                        colors = if (message.id == highlightedMessageId) {
                            androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            )
                        } else {
                            androidx.compose.material3.CardDefaults.cardColors()
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    message.callerName.ifBlank { message.callerAddress.ifBlank { "Unknown caller" } },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (message.unread) Text("Unread", style = MaterialTheme.typography.labelMedium)
                            }
                            if (message.callerAddress.isNotBlank() && message.callerAddress != message.callerName) {
                                Text(message.callerAddress, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("${formatDuration(message.durationMs)} · ${formatTimestamp(message.createdAtEpochMs)}")

                            if (isActive) {
                                Slider(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics { contentDescription = "Seek voicemail" }
                                        .testTag("voicemail.seek.${message.id}"),
                                    value = if (durationMs > 0f) positionMs / durationMs else 0f,
                                    onValueChange = { positionMs = it * durationMs },
                                    onValueChangeFinished = { seekTo(if (durationMs > 0f) positionMs / durationMs else 0f) },
                                )
                                Text("${formatDuration(positionMs.toLong())} / ${formatDuration(durationMs.toLong())}")
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("voicemail.play.${message.id}"),
                                    onClick = { play(message) },
                                ) { Text(if (isActive && isPlaying) "Pause" else if (isActive) "Resume" else "Play") }
                                OutlinedButton(
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                    enabled = sipCallbackUser != null || phoneCallbackNumber != null,
                                    onClick = {
                                        when {
                                            sipCallbackUser != null -> SipCallRuntime.placeCall(context, sipCallbackUser)
                                            phoneCallbackNumber != null -> runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneCallbackNumber, null)),
                                                )
                                            }
                                        }
                                    },
                                ) { Text("Call back") }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenRecentCall(message.recentCallId) },
                                ) { Text("Recent call") }
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = message.transcript != null,
                                    onClick = {
                                        transcriptIds = if (message.id in transcriptIds) transcriptIds - message.id
                                        else transcriptIds + message.id
                                    },
                                ) { Text(if (message.id in transcriptIds) "Hide transcript" else "Transcript") }
                                TextButton(onClick = { pendingDelete = message }) { Text("Delete") }
                            }

                            if (message.id in transcriptIds) {
                                Text(message.transcript.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            } else if (message.transcript == null) {
                                Text("Transcript not available", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete voicemail?") },
            text = { Text("The saved audio and transcript will be removed from this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (activeMessageId == message.id) stopPlayback()
                        repository.delete(message.id)
                        pendingDelete = null
                        refresh()
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatTimestamp(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMs))
