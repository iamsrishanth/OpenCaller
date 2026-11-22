package io.github.iamsrishanth.opencaller.ui.voicemail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.iamsrishanth.opencaller.voicemail.VoicemailRepository
import java.io.File

/** Recent internet calls that left a saved message, linked to that exact voicemail. */
@Composable
fun RecentCallsPage(
    onBack: () -> Unit,
    onOpenRecentCall: (String) -> Unit,
) {
    val context = LocalContext.current
    val calls = remember(context) {
        VoicemailRepository(File(context.filesDir, "voicemail"))
            .list()
            .distinctBy { it.recentCallId }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Back to dialer")
            }
            Text("Recent calls", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Internet calls with saved voicemail appear here. Open a call to play its message or view the transcript.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (calls.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No recent internet calls with voicemail",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                calls.forEach { message ->
                    Card(modifier = Modifier.fillMaxWidth().testTag("recent_call.${message.recentCallId}")) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                message.callerName.ifBlank { message.callerAddress.ifBlank { "Unknown caller" } },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Incoming internet call · ${if (message.unread) "voicemail unread" else "voicemail listened"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${formatDuration(message.durationMs)} · ${formatTimestamp(message.createdAtEpochMs)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                onClick = { onOpenRecentCall(message.recentCallId) },
                            ) { Text("Open recent call") }
                        }
                    }
                }
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
