package io.github.iamsrishanth.opencaller.voicemail

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

class VoicemailSyncCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun repeatedSyncImportsOnceAndDeletedServerMessageStaysSuppressed() = runBlocking {
        val directory = temporaryFolder.newFolder("sync-repeat")
        val local = VoicemailRepository(directory)
        val client = FixtureClient(listOf(remoteMessage(unread = false)))
        val coordinator = VoicemailSyncCoordinator(local, client)

        assertEquals(1, coordinator.synchronize())
        assertEquals(0, coordinator.synchronize())
        assertEquals(1, client.downloadCount)
        val saved = local.list().single()
        assertFalse(saved.unread)
        assertEquals(VoicemailSyncState.SYNCED, saved.syncState)
        assertTrue(local.audioFile(saved).readBytes().contentEquals(TEST_AUDIO))

        assertTrue(local.delete(saved.id))
        assertEquals(0, coordinator.synchronize())
        assertEquals(1, client.downloadCount)
        assertTrue(local.list().isEmpty())
        assertTrue(local.isServerMessageSuppressed(REMOTE_ID))
    }

    @Test
    fun offlineListFailureCanRetryAndStillImportsOnlyOnce() = runBlocking {
        val local = VoicemailRepository(temporaryFolder.newFolder("sync-retry"))
        val client = FixtureClient(listOf(remoteMessage())).apply { failList = true }
        val coordinator = VoicemailSyncCoordinator(local, client)

        try {
            coordinator.synchronize()
            throw AssertionError("An offline list request should fail")
        } catch (_: IOException) {
            // The next connected sync must be able to retry the same message.
        }
        assertTrue(local.list().isEmpty())

        client.failList = false
        assertEquals(1, coordinator.synchronize())
        assertEquals(0, coordinator.synchronize())
        assertEquals(1, client.downloadCount)
        assertEquals(1, local.list().size)
    }

    @Test
    fun failedAudioIntegrityCheckLeavesNoPartialMessageOrRecording() = runBlocking {
        val directory = temporaryFolder.newFolder("sync-invalid-audio")
        val local = VoicemailRepository(directory)
        val client = FixtureClient(listOf(remoteMessage())).apply { failDownload = true }
        val coordinator = VoicemailSyncCoordinator(local, client)

        try {
            coordinator.synchronize()
            throw AssertionError("An invalid audio download should fail")
        } catch (_: IOException) {
            // A later sync can retry after the server returns valid audio.
        }

        assertTrue(local.list().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(VoicemailRepository.TEMP_RECORDING_SUFFIX) })
    }

    private fun remoteMessage(unread: Boolean = true): RemoteVoicemail = RemoteVoicemail(
        id = REMOTE_ID,
        audioSha256 = TEST_AUDIO.sha256Hex(),
        callerAddress = "1002",
        callerName = "Synthetic caller",
        createdAtEpochMs = 1_800_000_000_000L,
        durationMs = 2_000L,
        unread = unread,
    )

    private class FixtureClient(
        private val messages: List<RemoteVoicemail>,
    ) : VoicemailSyncClient {
        var failList = false
        var failDownload = false
        var downloadCount = 0

        override fun listInbox(): List<RemoteVoicemail> {
            if (failList) throw IOException("fixture offline")
            return messages
        }

        override fun downloadAudio(message: RemoteVoicemail, destination: File): Boolean {
            downloadCount++
            if (failDownload) {
                destination.writeBytes(byteArrayOf(1, 2, 3))
                return false
            }
            destination.writeBytes(TEST_AUDIO)
            return TEST_AUDIO.sha256Hex().equals(message.audioSha256, ignoreCase = true)
        }
    }

    private companion object {
        val REMOTE_ID = "a".repeat(64)
        val TEST_AUDIO = ByteArray(64).also {
            "RIFF".encodeToByteArray().copyInto(it, destinationOffset = 0)
            "WAVE".encodeToByteArray().copyInto(it, destinationOffset = 8)
        }
    }
}
