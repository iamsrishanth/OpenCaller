package io.github.iamsrishanth.opencaller.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class SipPeerIdentityTest {
    @Test
    fun usesDisplayNameWhenAddressHasOne() {
        assertEquals("Caller 1002", displaySipPeer("\"Caller 1002\" <sips:1002@zeus-server;transport=tls>"))
    }

    @Test
    fun fallsBackToSipUserWhenDisplayNameIsMissing() {
        assertEquals("1002", displaySipPeer("sip:1002@zeus-server;transport=tls"))
    }

    @Test
    fun handlesBareIdentityAndBlankInput() {
        assertEquals("1002", displaySipPeer("1002"))
        assertEquals("", displaySipPeer("  "))
    }

    @Test
    fun extractsSafeSipCallbackUserPart() {
        assertEquals("1002", sipUserForCallback("\"Caller 1002\" <sips:1002@zeus-server;transport=tls>"))
        assertEquals("1002", sipUserForCallback("sip:1002@zeus-server"))
        assertEquals("1002", sipUserForCallback("sip:1002@example.com;transport=tls?x=y"))
        assertEquals(null, sipUserForCallback("\"sip:1002@zeus-server\""))
    }

    @Test
    fun onlyPlainTelephoneNumbersUseSystemDialer() {
        assertEquals("+1 (555) 010-1234", phoneNumberForCallback("+1 (555) 010-1234"))
        assertEquals(null, phoneNumberForCallback("Alice <sip:1002@zeus-server>"))
    }
}
