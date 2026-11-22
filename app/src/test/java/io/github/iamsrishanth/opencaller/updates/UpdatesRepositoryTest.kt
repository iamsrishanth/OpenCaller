package io.github.iamsrishanth.opencaller.updates

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesRepositoryTest {
    @Test
    fun bothSourcesCurrentAreSavedAsUpToDate() = runBlocking {
        val store = MemoryUpdateStateStore()
        val repository = repository(sourceClient = currentSources(), stateStore = store)

        val result = repository.checkForUpdates()

        assertEquals(UpdateStatus.UP_TO_DATE, result.state(UpdateSource.GITHUB).status)
        assertEquals(UpdateStatus.UP_TO_DATE, result.state(UpdateSource.FDROID).status)
        assertEquals(CHECKED_AT, result.state(UpdateSource.GITHUB).lastSuccessfulCheckMillis ?: 0L)
        assertEquals(result.state(UpdateSource.FDROID), store.readSourceState(UpdateSource.FDROID))
    }

    @Test
    fun githubAheadWhileFdroidIsBehindIsReportedIndependently() = runBlocking {
        val sourceClient = currentSources(
            githubCode = INSTALLED_CODE + 1,
            fdroidCode = INSTALLED_CODE - 1,
        )

        val result = repository(sourceClient = sourceClient).checkForUpdates()

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.state(UpdateSource.GITHUB).status)
        assertEquals(UpdateStatus.UP_TO_DATE, result.state(UpdateSource.FDROID).status)
        assertTrue(result.state(UpdateSource.FDROID).detail.orEmpty().contains("installed version is newer"))
    }

    @Test
    fun githubAheadRemainsVisibleWhenFdroidIsNotListed() = runBlocking {
        val sourceClient = currentSources(githubCode = INSTALLED_CODE + 1).apply {
            fdroidPackage = HttpResult(statusCode = 404)
        }

        val result = repository(sourceClient = sourceClient).checkForUpdates()

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.state(UpdateSource.GITHUB).status)
        assertEquals(UpdateStatus.NOT_LISTED_YET, result.state(UpdateSource.FDROID).status)
    }

    @Test
    fun higherFdroidCodeIsReportedAsAnUpdate() = runBlocking {
        val sourceClient = currentSources(fdroidCode = INSTALLED_CODE + 1)

        val result = repository(sourceClient = sourceClient).checkForUpdates()

        assertEquals(UpdateStatus.UP_TO_DATE, result.state(UpdateSource.GITHUB).status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.state(UpdateSource.FDROID).status)
    }

    @Test
    fun fdroidNotFoundIsReportedAsNotListed() = runBlocking {
        val sourceClient = currentSources().apply {
            fdroidPackage = HttpResult(statusCode = 404)
        }

        val result = repository(sourceClient = sourceClient).checkForUpdates()

        assertEquals(UpdateStatus.NOT_LISTED_YET, result.state(UpdateSource.FDROID).status)
        assertEquals(UpdateStatus.UP_TO_DATE, result.state(UpdateSource.GITHUB).status)
        assertEquals(CHECKED_AT, result.state(UpdateSource.FDROID).lastSuccessfulCheckMillis ?: 0L)
        assertEquals(
            "https://f-droid.org/packages/$APP_ID",
            result.state(UpdateSource.FDROID).sourceUrl,
        )
    }

    @Test
    fun fdroidWithoutSuggestedVersionCodeCannotBeCompared() = runBlocking {
        val sourceClient = currentSources().apply {
            fdroidPackage = HttpResult(
                statusCode = 200,
                body = FdroidPackagePayload(
                    packageName = APP_ID,
                    suggestedVersionCode = null,
                    packages = listOf(PublishedVersion(INSTALLED_CODE + 20, "0.2.20")),
                ),
            )
        }

        val result = repository(sourceClient = sourceClient).checkForUpdates()
        val fdroid = result.state(UpdateSource.FDROID)

        assertEquals(UpdateStatus.CANNOT_COMPARE, fdroid.status)
        assertEquals(null, fdroid.latestVersionCode)
        assertTrue(fdroid.detail.orEmpty().contains("suggested version code"))
        assertEquals("https://f-droid.org/packages/$APP_ID", fdroid.sourceUrl)
    }

    @Test
    fun missingGithubMetadataKeepsReleaseLinkAndCannotCompareStatus() = runBlocking {
        val sourceClient = currentSources().apply {
            githubRelease = HttpResult(
                statusCode = 200,
                body = GITHUB_RELEASE.copy(updateMetadataUrl = null),
            )
        }

        val github = repository(sourceClient = sourceClient).checkForUpdates().state(UpdateSource.GITHUB)

        assertEquals(UpdateStatus.CANNOT_COMPARE, github.status)
        assertEquals(GITHUB_RELEASE.releaseUrl, github.sourceUrl)
        assertTrue(github.detail.orEmpty().contains("could not be compared"))
    }

    @Test
    fun missingGithubMetadataAssetKeepsReleaseLinkAndCannotCompareStatus() = runBlocking {
        val sourceClient = currentSources().apply {
            githubMetadata = HttpResult(statusCode = 404)
        }

        val github = repository(sourceClient = sourceClient).checkForUpdates().state(UpdateSource.GITHUB)

        assertEquals(UpdateStatus.CANNOT_COMPARE, github.status)
        assertEquals(GITHUB_RELEASE.releaseUrl, github.sourceUrl)
        assertEquals(GITHUB_RELEASE.tagName, github.latestVersionName)
        assertTrue(github.detail.orEmpty().contains("could not be compared"))
    }

    @Test
    fun githubRateLimitIsReportedAsCouldNotCheck() = runBlocking {
        val sourceClient = currentSources().apply {
            githubRelease = HttpResult(statusCode = 403)
        }

        val result = repository(sourceClient = sourceClient).checkForUpdates()

        assertEquals(UpdateStatus.COULD_NOT_CHECK, result.state(UpdateSource.GITHUB).status)
        assertTrue(result.state(UpdateSource.GITHUB).detail.orEmpty().contains("request limit"))
        assertEquals(UpdateStatus.UP_TO_DATE, result.state(UpdateSource.FDROID).status)
    }

    @Test
    fun offlineGithubDoesNotHideFdroidResultOrPreviousGithubResult() = runBlocking {
        val store = MemoryUpdateStateStore().apply {
            writeSourceState(
                UpdateSourceState(
                    source = UpdateSource.GITHUB,
                    status = UpdateStatus.UPDATE_AVAILABLE,
                    lastSuccessfulStatus = UpdateStatus.UPDATE_AVAILABLE,
                    latestVersionName = "0.9.0",
                    latestVersionCode = 90L,
                    sourceUrl = GITHUB_RELEASE.releaseUrl,
                    lastSuccessfulCheckMillis = PREVIOUS_CHECK,
                    lastAttemptMillis = PREVIOUS_CHECK,
                ),
            )
        }
        val sourceClient = currentSources(fdroidCode = INSTALLED_CODE + 1).apply {
            githubError = IOException("offline fixture")
        }

        val result = repository(sourceClient = sourceClient, stateStore = store).checkForUpdates()
        val github = result.state(UpdateSource.GITHUB)
        val fdroid = result.state(UpdateSource.FDROID)

        assertEquals(UpdateStatus.COULD_NOT_CHECK, github.status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, github.lastSuccessfulStatus)
        assertEquals(90L, github.latestVersionCode ?: -1L)
        assertEquals(PREVIOUS_CHECK, github.lastSuccessfulCheckMillis ?: -1L)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, fdroid.status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, store.readSourceState(UpdateSource.FDROID).status)
    }

    @Test
    fun offlineFdroidDoesNotHideGithubUpdateResult() = runBlocking {
        val store = MemoryUpdateStateStore()
        val sourceClient = currentSources(githubCode = INSTALLED_CODE + 1).apply {
            fdroidError = IOException("offline fixture")
        }

        val result = repository(sourceClient = sourceClient, stateStore = store).checkForUpdates()

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.state(UpdateSource.GITHUB).status)
        assertEquals(INSTALLED_CODE + 1, result.state(UpdateSource.GITHUB).latestVersionCode ?: -1L)
        assertEquals(UpdateStatus.COULD_NOT_CHECK, result.state(UpdateSource.FDROID).status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, store.readSourceState(UpdateSource.GITHUB).status)
    }

    @Test
    fun fastSourceIsPublishedToUiBeforeSlowSourceCompletes() = runBlocking {
        val githubGate = CompletableDeferred<Unit>()
        val sourceClient = object : UpdateSourceClient {
            override suspend fun fetchLatestGithubRelease(): HttpResult<GithubReleasePayload> {
                githubGate.await()
                return HttpResult(200, GITHUB_RELEASE)
            }

            override suspend fun fetchGithubUpdateMetadata(url: String): HttpResult<GithubUpdateMetadata> =
                HttpResult(200, GithubUpdateMetadata(APP_ID, INSTALLED_CODE, "0.2.0"))

            override suspend fun fetchFdroidPackage(packageName: String): HttpResult<FdroidPackagePayload> =
                HttpResult(
                    200,
                    FdroidPackagePayload(
                        packageName = APP_ID,
                        suggestedVersionCode = INSTALLED_CODE,
                        packages = listOf(PublishedVersion(INSTALLED_CODE, "0.2.0")),
                    ),
                )
        }
        val repository = repository(sourceClient = sourceClient)
        val visibleUpdates = mutableListOf<UpdateSourceState>()
        val firstVisibleUpdate = CompletableDeferred<UpdateSourceState>()

        val check = launch {
            repository.checkForUpdates { state ->
                visibleUpdates += state
                firstVisibleUpdate.complete(state)
            }
        }
        val firstVisible = withTimeout(1_000) { firstVisibleUpdate.await() }

        assertEquals(listOf(UpdateSource.FDROID), visibleUpdates.map(UpdateSourceState::source))
        assertEquals(UpdateStatus.UP_TO_DATE, firstVisible.status)

        githubGate.complete(Unit)
        check.join()
        assertEquals(setOf(UpdateSource.GITHUB, UpdateSource.FDROID), visibleUpdates.map(UpdateSourceState::source).toSet())
    }

    @Test
    fun dailyChecksAreOffAndUnscheduledUntilOptedIn() {
        val store = MemoryUpdateStateStore()
        val scheduler = RecordingDailyCheckScheduler()
        val repository = repository(sourceClient = currentSources(), stateStore = store, scheduler = scheduler)

        assertFalse(repository.loadState().dailyChecksEnabled)
        assertTrue(scheduler.requests.isEmpty())

        repository.setDailyChecksEnabled(true)
        assertTrue(repository.loadState().dailyChecksEnabled)
        assertEquals(listOf(true), scheduler.requests)

        repository.setDailyChecksEnabled(false)
        assertEquals(listOf(true, false), scheduler.requests)
    }

    private fun repository(
        sourceClient: UpdateSourceClient,
        stateStore: MemoryUpdateStateStore = MemoryUpdateStateStore(),
        scheduler: RecordingDailyCheckScheduler = RecordingDailyCheckScheduler(),
    ) = UpdatesRepository(
        applicationId = APP_ID,
        installedVersion = InstalledAppVersion("0.2.0", INSTALLED_CODE),
        stateStore = stateStore,
        sourceClient = sourceClient,
        dailyCheckScheduler = scheduler,
        clockMillis = { CHECKED_AT },
    )

    private fun currentSources(
        githubCode: Long = INSTALLED_CODE,
        fdroidCode: Long = INSTALLED_CODE,
    ) = FixtureUpdateSourceClient(
        githubRelease = HttpResult(statusCode = 200, body = GITHUB_RELEASE),
        githubMetadata = HttpResult(
            statusCode = 200,
            body = GithubUpdateMetadata(APP_ID, githubCode, "0.2.${githubCode - INSTALLED_CODE}"),
        ),
        fdroidPackage = HttpResult(
            statusCode = 200,
            body = FdroidPackagePayload(
                packageName = APP_ID,
                suggestedVersionCode = fdroidCode,
                packages = listOf(PublishedVersion(fdroidCode, "0.2.${fdroidCode - INSTALLED_CODE}")),
            ),
        ),
    )

    private fun UpdatesPageState.state(source: UpdateSource): UpdateSourceState =
        sources.first { it.source == source }

    private class FixtureUpdateSourceClient(
        var githubRelease: HttpResult<GithubReleasePayload>,
        var githubMetadata: HttpResult<GithubUpdateMetadata> = HttpResult(404),
        var fdroidPackage: HttpResult<FdroidPackagePayload>,
        var githubError: Exception? = null,
        var fdroidError: Exception? = null,
    ) : UpdateSourceClient {
        override suspend fun fetchLatestGithubRelease(): HttpResult<GithubReleasePayload> {
            githubError?.let { throw it }
            return githubRelease
        }

        override suspend fun fetchGithubUpdateMetadata(url: String): HttpResult<GithubUpdateMetadata> =
            githubMetadata

        override suspend fun fetchFdroidPackage(packageName: String): HttpResult<FdroidPackagePayload> {
            fdroidError?.let { throw it }
            return fdroidPackage
        }
    }

    private class MemoryUpdateStateStore : UpdateStateStore {
        private val states = UpdateSource.entries.associateWith { UpdateSourceState(source = it) }.toMutableMap()
        private var dailyChecks = false

        override fun isDailyChecksEnabled(): Boolean = dailyChecks

        override fun setDailyChecksEnabled(enabled: Boolean) {
            dailyChecks = enabled
        }

        override fun readSourceState(source: UpdateSource): UpdateSourceState = states.getValue(source)

        override fun writeSourceState(state: UpdateSourceState) {
            states[state.source] = state
        }
    }

    private class RecordingDailyCheckScheduler : DailyCheckScheduler {
        val requests = mutableListOf<Boolean>()
        override fun setEnabled(enabled: Boolean) {
            requests += enabled
        }
    }

    private companion object {
        const val APP_ID = "io.github.iamsrishanth.opencaller"
        const val INSTALLED_CODE = 42L
        const val CHECKED_AT = 1_700_000_000_000L
        const val PREVIOUS_CHECK = 1_600_000_000_000L
        val GITHUB_RELEASE = GithubReleasePayload(
            tagName = "v0.2.0",
            releaseUrl = "https://github.com/iamsrishanth/OpenCaller/releases/tag/v0.2.0",
            updateMetadataUrl = "https://github.com/iamsrishanth/OpenCaller/releases/download/v0.2.0/opencaller-update.json",
        )
    }
}
