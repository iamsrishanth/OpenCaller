package io.github.iamsrishanth.opencaller.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

class UpdatesRepository private constructor(
    private val applicationId: String,
    private val installedVersionProvider: () -> InstalledAppVersion,
    private val installSourceProvider: () -> InstallSource,
    private val stateStore: UpdateStateStore,
    private val sourceClient: UpdateSourceClient,
    private val dailyCheckScheduler: DailyCheckScheduler,
    private val clockMillis: () -> Long,
) {
    constructor(context: Context) : this(
        applicationId = context.applicationContext.packageName,
        installedVersionProvider = { readInstalledVersion(context.applicationContext) },
        installSourceProvider = { InstallSourceDetector.detect(context.applicationContext) },
        stateStore = SharedPreferencesUpdateStateStore(context.applicationContext),
        sourceClient = HttpUpdateSourceClient(),
        dailyCheckScheduler = WorkManagerDailyCheckScheduler(context.applicationContext),
        clockMillis = System::currentTimeMillis,
    )

    internal constructor(
        applicationId: String,
        installedVersion: InstalledAppVersion,
        stateStore: UpdateStateStore,
        sourceClient: UpdateSourceClient,
        dailyCheckScheduler: DailyCheckScheduler,
        installSource: InstallSource = InstallSource.UNKNOWN,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        applicationId = applicationId,
        installedVersionProvider = { installedVersion },
        installSourceProvider = { installSource },
        stateStore = stateStore,
        sourceClient = sourceClient,
        dailyCheckScheduler = dailyCheckScheduler,
        clockMillis = clockMillis,
    )

    fun loadState(): UpdatesPageState = UpdatesPageState(
        installedVersion = installedVersionProvider(),
        sources = UpdateSource.entries.map(stateStore::readSourceState),
        installSource = installSourceProvider(),
        dailyChecksEnabled = stateStore.isDailyChecksEnabled(),
    )

    fun setDailyChecksEnabled(enabled: Boolean) {
        stateStore.setDailyChecksEnabled(enabled)
        dailyCheckScheduler.setEnabled(enabled)
    }

    /** Checks each source independently and persists its result before returning the combined snapshot. */
    suspend fun checkForUpdates(
        onSourceUpdated: (UpdateSourceState) -> Unit = {},
    ): UpdatesPageState {
        val previous = loadState()
        val checkedAt = clockMillis()
        val results = coroutineScope {
            UpdateSource.entries.map { source ->
                async {
                    val previousSource = previous.sources.first { it.source == source }
                    val state = try {
                        fetchSource(source, previous.installedVersion, checkedAt).also(stateStore::writeSourceState)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        previousSource.copy(
                            status = UpdateStatus.COULD_NOT_CHECK,
                            detail = sourceFailureDetail(source, (error as? UpdateSourceException)?.statusCode),
                            lastAttemptMillis = checkedAt,
                        ).also(stateStore::writeSourceState)
                    }
                    onSourceUpdated(state)
                    state
                }
            }.awaitAll()
        }

        return previous.copy(
            sources = results,
            dailyChecksEnabled = stateStore.isDailyChecksEnabled(),
        )
    }

    private suspend fun fetchSource(
        source: UpdateSource,
        installed: InstalledAppVersion,
        checkedAtMillis: Long,
    ): UpdateSourceState = when (source) {
        UpdateSource.GITHUB -> fetchGithubRelease(installed, checkedAtMillis)
        UpdateSource.FDROID -> fetchFdroidListing(installed, checkedAtMillis)
    }

    private suspend fun fetchGithubRelease(
        installed: InstalledAppVersion,
        checkedAtMillis: Long,
    ): UpdateSourceState {
        val response = sourceClient.fetchLatestGithubRelease()
        if (response.statusCode == HTTP_NOT_FOUND) {
            return successfulState(
                source = UpdateSource.GITHUB,
                status = UpdateStatus.NO_STABLE_RELEASE,
                checkedAtMillis = checkedAtMillis,
                sourceUrl = UpdateApiUrls.GITHUB_RELEASES_PAGE,
                detail = "No stable GitHub release is published yet.",
            )
        }
        requireSuccessfulResponse(response.statusCode, UpdateSource.GITHUB)

        val release = response.body ?: throw IOException("GitHub returned invalid release data")
        val releaseUrl = release.releaseUrl.takeIf(::isGithubReleasePageUrl)
            ?: UpdateApiUrls.GITHUB_RELEASES_PAGE
        val metadataUrl = release.updateMetadataUrl
        if (metadataUrl.isNullOrBlank()) {
            return successfulState(
                source = UpdateSource.GITHUB,
                status = UpdateStatus.CANNOT_COMPARE,
                checkedAtMillis = checkedAtMillis,
                latestVersionName = release.tagName,
                sourceUrl = releaseUrl,
                detail = "This release is missing a valid opencaller-update.json, so its version could not be compared.",
            )
        }

        val metadataResponse = sourceClient.fetchGithubUpdateMetadata(metadataUrl)
        if (metadataResponse.statusCode == HTTP_NOT_FOUND) {
            return successfulState(
                source = UpdateSource.GITHUB,
                status = UpdateStatus.CANNOT_COMPARE,
                checkedAtMillis = checkedAtMillis,
                latestVersionName = release.tagName,
                sourceUrl = releaseUrl,
                detail = "This release is missing opencaller-update.json, so its version could not be compared.",
            )
        }
        requireSuccessfulResponse(metadataResponse.statusCode, UpdateSource.GITHUB)

        val metadata = metadataResponse.body
        val releaseCode = metadata?.versionCode?.takeIf { it > 0L }
        val releaseName = metadata?.versionName?.takeIf(String::isNotBlank)
        if (metadata?.applicationId != applicationId || releaseCode == null || releaseName == null) {
            return successfulState(
                source = UpdateSource.GITHUB,
                status = UpdateStatus.CANNOT_COMPARE,
                checkedAtMillis = checkedAtMillis,
                latestVersionName = release.tagName,
                sourceUrl = releaseUrl,
                detail = "This release has invalid opencaller-update.json metadata, so its version could not be compared.",
            )
        }

        val status = compareVersionCodes(installed.code, releaseCode)
        return successfulState(
            source = UpdateSource.GITHUB,
            status = status,
            checkedAtMillis = checkedAtMillis,
            latestVersionName = releaseName,
            latestVersionCode = releaseCode,
            sourceUrl = releaseUrl,
            detail = comparisonDetail(status, installed.code, releaseCode),
        )
    }

    private suspend fun fetchFdroidListing(
        installed: InstalledAppVersion,
        checkedAtMillis: Long,
    ): UpdateSourceState {
        val response = sourceClient.fetchFdroidPackage(applicationId)
        if (response.statusCode == HTTP_NOT_FOUND) {
            return successfulState(
                source = UpdateSource.FDROID,
                status = UpdateStatus.NOT_LISTED_YET,
                checkedAtMillis = checkedAtMillis,
                sourceUrl = UpdateApiUrls.FDROID_APP_PAGE + applicationId,
                detail = "This app is not listed in the official F-Droid repository yet.",
            )
        }
        requireSuccessfulResponse(response.statusCode, UpdateSource.FDROID)

        val packageInfo = response.body ?: throw IOException("F-Droid returned invalid package data")
        if (packageInfo.packageName != applicationId) {
            throw IOException("F-Droid returned a different package")
        }

        val suggestedCode = packageInfo.suggestedVersionCode?.takeIf { it > 0L }
            ?: return successfulState(
                source = UpdateSource.FDROID,
                status = UpdateStatus.CANNOT_COMPARE,
                checkedAtMillis = checkedAtMillis,
                sourceUrl = UpdateApiUrls.FDROID_APP_PAGE + applicationId,
                detail = "F-Droid did not include a suggested version code, so its version could not be compared.",
            )
        val publishedVersion = packageInfo.packages.firstOrNull { it.versionCode == suggestedCode }
            ?: PublishedVersion(suggestedCode, null)

        val status = compareVersionCodes(installed.code, publishedVersion.versionCode)
        return successfulState(
            source = UpdateSource.FDROID,
            status = status,
            checkedAtMillis = checkedAtMillis,
            latestVersionName = publishedVersion.versionName,
            latestVersionCode = publishedVersion.versionCode,
            sourceUrl = UpdateApiUrls.FDROID_APP_PAGE + applicationId,
            detail = comparisonDetail(status, installed.code, publishedVersion.versionCode),
        )
    }

    private fun successfulState(
        source: UpdateSource,
        status: UpdateStatus,
        checkedAtMillis: Long,
        latestVersionName: String? = null,
        latestVersionCode: Long? = null,
        sourceUrl: String? = null,
        detail: String? = null,
    ): UpdateSourceState = UpdateSourceState(
        source = source,
        status = status,
        lastSuccessfulStatus = status,
        latestVersionName = latestVersionName,
        latestVersionCode = latestVersionCode,
        sourceUrl = sourceUrl,
        detail = detail,
        lastSuccessfulCheckMillis = checkedAtMillis,
        lastAttemptMillis = checkedAtMillis,
    )

    private fun comparisonDetail(status: UpdateStatus, installedCode: Long, sourceCode: Long): String? = when {
        status == UpdateStatus.UPDATE_AVAILABLE -> "A newer version code is available from this source."
        sourceCode < installedCode -> "The installed version is newer than this source's published version."
        else -> null
    }

    private fun requireSuccessfulResponse(statusCode: Int, source: UpdateSource) {
        if (statusCode !in 200..299) throw UpdateSourceException(source, statusCode)
    }

    private fun sourceFailureDetail(source: UpdateSource, statusCode: Int?): String =
        if (source == UpdateSource.GITHUB && statusCode == HTTP_FORBIDDEN) {
            "GitHub temporarily declined this check, possibly because its request limit was reached. Try again later."
        } else {
            "This source could not be reached. Try again later."
        }

    private fun isGithubReleasePageUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == UpdateApiUrls.GITHUB_HOST &&
            uri.path.orEmpty().startsWith("/iamsrishanth/OpenCaller/releases/")
    }

    private class UpdateSourceException(
        val source: UpdateSource,
        val statusCode: Int,
    ) : IOException("${source.title} returned HTTP $statusCode")

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_FORBIDDEN = 403
    }
}

@Suppress("DEPRECATION")
private fun readInstalledVersion(context: Context): InstalledAppVersion {
    val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
    return InstalledAppVersion(name = info.versionName ?: "Unknown", code = code)
}
