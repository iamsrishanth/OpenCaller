package io.github.iamsrishanth.opencaller.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceDetectorTest {
    @Test
    fun recognizedStoreInstallersAreReported() {
        assertEquals(InstallSource.FDROID, classifyInstallerPackage("org.fdroid.fdroid"))
        assertEquals(InstallSource.FDROID, classifyInstallerPackage("org.fdroid.basic"))
        assertEquals(InstallSource.GOOGLE_PLAY, classifyInstallerPackage("com.android.vending"))
    }

    @Test
    fun sideloadInstallerIsKeptUnknown() {
        assertEquals(InstallSource.UNKNOWN, classifyInstallerPackage("com.google.android.packageinstaller"))
        assertEquals(InstallSource.UNKNOWN, classifyInstallerPackage(null))
    }

    @Test
    fun preAndroid11UsesLegacyInstallerLookup() {
        assertEquals(
            "org.fdroid.fdroid",
            installerPackageForApi(apiLevel = 29, installSourcePackage = null, legacyInstallerPackage = "org.fdroid.fdroid"),
        )
    }

    @Test
    fun Android11AndNewerUsesInstallSourceInfoOnly() {
        assertEquals(
            "com.android.vending",
            installerPackageForApi(apiLevel = 30, installSourcePackage = "com.android.vending", legacyInstallerPackage = null),
        )
        assertNull(installerPackageForApi(apiLevel = 30, installSourcePackage = null, legacyInstallerPackage = "org.fdroid.fdroid"))
    }

    @Test
    fun dailyWorkSpecIsOptInNetworkConstrainedAndAtLeastADay() {
        assertEquals(24L, DailyUpdateCheckSchedule.INTERVAL_HOURS)
        assertTrue(DailyUpdateCheckSchedule.REQUIRES_NETWORK)
        assertEquals("open-caller-daily-update-check", DailyUpdateCheckSchedule.UNIQUE_WORK_NAME)
    }

    @Test
    fun updateActionIsLimitedToKnownInstallSourceUntilSigningCompatibilityIsVerified() {
        val githubUpdate = UpdateSourceState(
            source = UpdateSource.GITHUB,
            status = UpdateStatus.UPDATE_AVAILABLE,
            sourceUrl = "https://github.com/iamsrishanth/OpenCaller/releases/tag/v2",
        )
        val fdroidUpdate = githubUpdate.copy(
            source = UpdateSource.FDROID,
            sourceUrl = "https://f-droid.org/packages/io.github.iamsrishanth.opencaller",
        )

        assertTrue(!canOpenUpdateSourcePage(githubUpdate, InstallSource.FDROID))
        assertTrue(canOpenUpdateSourcePage(fdroidUpdate, InstallSource.FDROID))
        assertTrue(!canOpenUpdateSourcePage(githubUpdate, InstallSource.UNKNOWN))
        assertTrue(needsSigningCompatibilityNotice(githubUpdate, InstallSource.FDROID))
        assertTrue(!needsSigningCompatibilityNotice(fdroidUpdate, InstallSource.FDROID))
    }

    @Test
    fun informationalReleasePagesRemainAvailableForUnlistedOrUncomparableResults() {
        val githubOldRelease = UpdateSourceState(
            source = UpdateSource.GITHUB,
            status = UpdateStatus.CANNOT_COMPARE,
            sourceUrl = "https://github.com/iamsrishanth/OpenCaller/releases/tag/legacy",
        )
        val fdroidNotListed = UpdateSourceState(
            source = UpdateSource.FDROID,
            status = UpdateStatus.NOT_LISTED_YET,
            sourceUrl = "https://f-droid.org/packages/io.github.iamsrishanth.opencaller",
        )

        assertTrue(canOpenUpdateSourcePage(githubOldRelease, InstallSource.UNKNOWN))
        assertTrue(canOpenUpdateSourcePage(fdroidNotListed, InstallSource.GOOGLE_PLAY))
    }
}
