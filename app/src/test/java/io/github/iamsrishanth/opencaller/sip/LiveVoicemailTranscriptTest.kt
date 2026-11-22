package io.github.iamsrishanth.opencaller.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveVoicemailTranscriptTest {
    @Test
    fun finalRecognizerTextIsPreferredAndTrimmed() {
        assertEquals(
            "final phrase",
            selectVoicemailTranscript(" final phrase ", "live phrase", "visible phrase"),
        )
    }

    @Test
    fun visibleRuntimeTranscriptIsSavedWhenRecognizerHasNoFinalText() {
        assertEquals(
            "visible phrase",
            selectVoicemailTranscript(null, "  ", " visible phrase "),
        )
    }

    @Test
    fun returnsNullWhenNoTranscriptWasProduced() {
        assertNull(selectVoicemailTranscript(null, "", "  "))
    }

    @Test
    fun emptySpeechUpdateKeepsLastUsefulInterimText() {
        assertEquals("known phrase", preserveLastNonBlankTranscript(" known phrase ", "  "))
    }

    @Test
    fun firstUsefulSpeechUpdateIsTrimmed() {
        assertEquals("known phrase", preserveLastNonBlankTranscript(null, " known phrase "))
    }
}
