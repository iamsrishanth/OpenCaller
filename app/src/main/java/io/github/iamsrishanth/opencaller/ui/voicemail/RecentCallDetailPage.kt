package io.github.iamsrishanth.opencaller.ui.voicemail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.iamsrishanth.opencaller.voicemail.VoicemailRepository
import java.io.File

/** Recent-call detail for a call that has a linked voicemail message. */
@Composable
fun RecentCallDetailPage(
    recentCallId: String,
    onBack: () -> Unit,
    onOpenVoicemail: (String) -> Unit,
) {
    val context = LocalContext.current
    val message = remember(context, recentCallId) {
        VoicemailRepository(File(context.filesDir, "voicemail"))
            .list()
            .firstOrNull { it.recentCallId == recentCallId }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Recent call", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            if (message == null) {
                Text("Call details are no longer available.")
            } else {
                Text(
                    message.callerName.ifBlank {
                        message.callerAddress.ifBlank { "Unknown caller" }
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (message.callerAddress.isNotBlank()) Text(message.callerAddress)
                Text("${formatDuration(message.durationMs)} · ${formatTimestamp(message.createdAtEpochMs)}")
                Text(if (message.unread) "Voicemail · unread" else "Voicemail · listened")
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    onClick = { onOpenVoicemail(message.id) },
                ) { Text("Open voicemail") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = message.transcript != null,
                    onClick = { onOpenVoicemail(message.id) },
                ) { Text("View transcript") }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun formatTimestamp(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMs))
