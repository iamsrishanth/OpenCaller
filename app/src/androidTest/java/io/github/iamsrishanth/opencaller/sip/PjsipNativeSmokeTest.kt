package io.github.iamsrishanth.opencaller.sip

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.Endpoint

@RunWith(AndroidJUnit4::class)
class PjsipNativeSmokeTest {
    @Test
    fun nativeEndpointStartsAndStopsOnDevice() {
        val endpoint = Endpoint()
        var libraryCreated = false
        try {
            endpoint.libCreate()
            libraryCreated = true
            endpoint.libInit(EpConfig())
            endpoint.libStart()
            assertTrue(endpoint.libVersion().full.isNotBlank())
        } finally {
            if (libraryCreated) runCatching { endpoint.libDestroy() }
            endpoint.delete()
        }
    }
}
