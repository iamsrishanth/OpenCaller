package io.github.iamsrishanth.opencaller.voicemail

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side fixture for checking that app updates keep private voicemail data playable. */
@RunWith(AndroidJUnit4::class)
class VoicemailUpgradePersistenceTest {
    @Test
    fun seedSyntheticVoicemailBeforeUpdate() {
        val repository = repository()
        repository.find(FIXTURE_ID)?.let { repository.delete(it.id) }
        val recording = repository.newRecordingFile(FIXTURE_ID)
        recording.writeBytes(shortSilentWav())

        val saved = repository.commit(
            id = FIXTURE_ID,
            recentCallId = FIXTURE_ID,
            callerName = "Upgrade fixture",
            callerAddress = "1002",
            createdAtEpochMs = 1_800_000_000_000L,
            durationMs = 500L,
            closedRecording = recording,
            transcript = "Synthetic voicemail used only for the update check",
        )

        assertNotNull(saved)
    }

    @Test
    fun savedVoicemailRemainsPlayableAfterUpdate() {
        val repository = repository()
        val message = repository.find(FIXTURE_ID)
        assertNotNull("The pre-update voicemail metadata must remain in app-private storage", message)
        val audioFile = repository.audioFile(message!!)
        assertTrue("The pre-update audio must remain in app-private storage", audioFile.isFile)

        val player = MediaPlayer()
        try {
            player.setDataSource(audioFile.absolutePath)
            player.prepare()
            assertTrue("The saved WAV must still decode to playable audio", player.duration > 0)
            player.start()
            assertTrue("MediaPlayer must accept the saved message", player.isPlaying)
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    @Test
    fun removeSyntheticUpgradeFixture() {
        repository().find(FIXTURE_ID)?.let { repository().delete(it.id) }
    }

    private fun repository(): VoicemailRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return VoicemailRepository(File(context.filesDir, "voicemail"))
    }

    private fun shortSilentWav(): ByteArray {
        val sampleRate = 8_000
        val pcm = ByteArray(sampleRate / 2 * 2)
        val wav = ByteArrayOutputStream()
        wav.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        wav.writeIntLe(36 + pcm.size)
        wav.write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII))
        wav.writeIntLe(16)
        wav.writeShortLe(1)
        wav.writeShortLe(1)
        wav.writeIntLe(sampleRate)
        wav.writeIntLe(sampleRate * 2)
        wav.writeShortLe(2)
        wav.writeShortLe(16)
        wav.write("data".toByteArray(StandardCharsets.US_ASCII))
        wav.writeIntLe(pcm.size)
        wav.write(pcm)
        return wav.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private companion object {
        const val FIXTURE_ID = "upgrade_fixture_voicemail"
    }
}
