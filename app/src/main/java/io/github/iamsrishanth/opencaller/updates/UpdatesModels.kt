package io.github.iamsrishanth.opencaller.updates

enum class UpdateSource(
    val title: String,
) {
    GITHUB("GitHub Releases"),
    FDROID("F-Droid"),
}

enum class InstallSource(
    val title: String,
    val matchingUpdateSource: UpdateSource? = null,
    val detail: String,
) {
    FDROID(
        title = "F-Droid",
        matchingUpdateSource = UpdateSource.FDROID,
        detail = "Android identifies the F-Droid client as the installer.",
    ),
    GOOGLE_PLAY(
        title = "Google Play",
        detail = "Android identifies Google Play as the installer.",
    ),
    UNKNOWN(
        title = "Unknown",
        detail = "Android cannot reliably identify the store for this installation.",
    ),
}

enum class UpdateStatus {
    NOT_CHECKED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    NOT_LISTED_YET,
    CANNOT_COMPARE,
    NO_STABLE_RELEASE,
    COULD_NOT_CHECK,
}

data class InstalledAppVersion(
    val name: String,
    val code: Long,
)

data class UpdateSourceState(
    val source: UpdateSource,
    val status: UpdateStatus = UpdateStatus.NOT_CHECKED,
    val lastSuccessfulStatus: UpdateStatus? = null,
    val latestVersionName: String? = null,
    val latestVersionCode: Long? = null,
    val sourceUrl: String? = null,
    val detail: String? = null,
    val lastSuccessfulCheckMillis: Long? = null,
    val lastAttemptMillis: Long? = null,
)

data class UpdatesPageState(
    val installedVersion: InstalledAppVersion,
    val sources: List<UpdateSourceState>,
    val installSource: InstallSource,
    val dailyChecksEnabled: Boolean,
)

internal fun compareVersionCodes(installedCode: Long, sourceCode: Long): UpdateStatus =
    if (sourceCode > installedCode) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE

/**
 * A release page can be opened for informational states that require inspection. An update action
 * is available only from the known installer source until certificate compatibility is verified.
 */
internal fun canOpenUpdateSourcePage(
    state: UpdateSourceState,
    installSource: InstallSource,
): Boolean {
    if (state.sourceUrl.isNullOrBlank()) return false
    if (state.source == installSource.matchingUpdateSource) return true
    return state.status in setOf(
        UpdateStatus.NOT_LISTED_YET,
        UpdateStatus.CANNOT_COMPARE,
        UpdateStatus.NO_STABLE_RELEASE,
    )
}

internal fun needsSigningCompatibilityNotice(
    state: UpdateSourceState,
    installSource: InstallSource,
): Boolean = state.status == UpdateStatus.UPDATE_AVAILABLE &&
    state.source != installSource.matchingUpdateSource
