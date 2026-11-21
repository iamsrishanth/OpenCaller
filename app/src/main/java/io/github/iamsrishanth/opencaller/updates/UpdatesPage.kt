package io.github.iamsrishanth.opencaller.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A reusable Settings → About → Updates page. Links open only after a user taps them. */
@Composable
fun UpdatesPage(
    onBack: () -> Unit = {},
    repository: UpdatesRepository? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val updatesRepository = remember(context, repository) {
        repository ?: UpdatesRepository(context)
    }
    val pageState = remember(updatesRepository) { mutableStateOf(updatesRepository.loadState()) }
    val checking = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    suspend fun checkUpdates() {
        if (checking.value) return
        checking.value = true
        try {
            pageState.value = updatesRepository.checkForUpdates { updatedSource ->
                pageState.value = pageState.value.copy(
                    sources = pageState.value.sources.map { current ->
                        if (current.source == updatedSource.source) updatedSource else current
                    },
                )
            }
        } finally {
            checking.value = false
        }
    }

    LaunchedEffect(updatesRepository) {
        pageState.value = updatesRepository.loadState()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = "Back" },
        ) {
            Text("‹  Back")
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Updates", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Check the latest versions published by each source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Installed version", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pageState.value.installedVersion.name}  ·  version code ${pageState.value.installedVersion.code}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Install source: ${pageState.value.installSource.title}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    pageState.value.installSource.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val preferredSource = pageState.value.installSource.matchingUpdateSource
        pageState.value.sources
            .sortedBy { if (it.source == preferredSource) 0 else 1 }
            .forEach { sourceState ->
            UpdateSourceCard(
                state = sourceState,
                isInstallSource = sourceState.source == preferredSource,
                canOpenSourcePage = canOpenUpdateSourcePage(sourceState, pageState.value.installSource),
                needsSigningCompatibilityNotice = needsSigningCompatibilityNotice(
                    sourceState,
                    pageState.value.installSource,
                ),
                onOpenSource = { url -> uriHandler.openUri(url) },
            )
        }

        Button(
            onClick = { coroutineScope.launch { checkUpdates() } },
            enabled = !checking.value,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (checking.value) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text("Checking…")
                }
            } else {
                Text("Check both sources")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Daily checks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Off by default. When enabled, Android checks online about once per day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = pageState.value.dailyChecksEnabled,
                    onCheckedChange = { enabled ->
                        updatesRepository.setDailyChecksEnabled(enabled)
                        pageState.value = pageState.value.copy(dailyChecksEnabled = enabled)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Check for updates online about once per day in the background"
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun UpdateSourceCard(
    state: UpdateSourceState,
    isInstallSource: Boolean,
    canOpenSourcePage: Boolean,
    needsSigningCompatibilityNotice: Boolean,
    onOpenSource: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstallSource) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isInstallSource) {
                Text(
                    "Installed from this source",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(state.source.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = statusLabel(state.status),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )

            state.latestVersionCode?.let { code ->
                val name = state.latestVersionName
                Text(
                    text = if (name.isNullOrBlank()) {
                        "Published version code: $code"
                    } else {
                        "Published version: $name  ·  code $code"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } ?: state.latestVersionName?.let { name ->
                Text("Release: $name", style = MaterialTheme.typography.bodyMedium)
            }

            state.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (needsSigningCompatibilityNotice) {
                Text(
                    "A newer build is listed here, but updating from this source is disabled until signing compatibility with the installed source is verified.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.status == UpdateStatus.COULD_NOT_CHECK && state.lastSuccessfulStatus != null) {
                Text(
                    "Last successful result: ${statusLabel(state.lastSuccessfulStatus)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val lastChecked = state.lastSuccessfulCheckMillis
            Text(
                text = if (lastChecked == null) {
                    "Last successful check: none yet"
                } else {
                    "Last successful check: ${formatTime(lastChecked)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.sourceUrl?.takeIf { canOpenSourcePage }?.let { url ->
                OutlinedButton(onClick = { onOpenSource(url) }) {
                    Text(if (state.source == UpdateSource.GITHUB) "View GitHub release page" else "View F-Droid listing")
                }
            }
        }
    }
}

private fun statusLabel(status: UpdateStatus): String = when (status) {
    UpdateStatus.NOT_CHECKED -> "Not checked yet"
    UpdateStatus.UPDATE_AVAILABLE -> "Update available"
    UpdateStatus.UP_TO_DATE -> "Up to date"
    UpdateStatus.NOT_LISTED_YET -> "Not listed yet"
    UpdateStatus.CANNOT_COMPARE -> "Version could not be compared"
    UpdateStatus.NO_STABLE_RELEASE -> "No stable release yet"
    UpdateStatus.COULD_NOT_CHECK -> "Couldn't check"
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
