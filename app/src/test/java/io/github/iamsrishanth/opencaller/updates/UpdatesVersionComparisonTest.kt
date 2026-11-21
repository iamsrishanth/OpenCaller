package io.github.iamsrishanth.opencaller.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatesVersionComparisonTest {
    @Test
    fun higherNumericVersionCodeMeansUpdateAvailable() {
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, compareVersionCodes(installedCode = 9L, sourceCode = 10L))
    }

    @Test
    fun equalNumericVersionCodeMeansUpToDate() {
        assertEquals(UpdateStatus.UP_TO_DATE, compareVersionCodes(installedCode = 10L, sourceCode = 10L))
    }

    @Test
    fun lowerSourceVersionCodeDoesNotOfferAnUpdate() {
        assertEquals(UpdateStatus.UP_TO_DATE, compareVersionCodes(installedCode = 11L, sourceCode = 10L))
    }
}
