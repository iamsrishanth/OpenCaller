package io.github.iamsrishanth.opencaller.voicemail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoicemailRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun committedMessageSurvivesReloadAndKeepsStableAudio() {
        val directory = temporaryFolder.newFolder("voicemail")
        val repository = VoicemailRepository(directory)
        val recording = repository.newRecordingFile("call-123")
        recording.writeBytes(byteArrayOf(1, 2, 3, 4))

        val saved = repository.commit(
            id = "call-123",
            recentCallId = "call-123",
            callerName = "Caller",
            callerAddress = "1002",
            createdAtEpochMs = 1234L,
            durationMs = 4200L,
            closedRecording = recording,
            transcript = "known phrase",
        )

        assertNotNull(saved)
        assertEquals("call-123.wav", saved!!.audioFileName)
        assertEquals(listOf(saved), VoicemailRepository(directory).list())
        assertTrue(File(directory, saved.audioFileName).isFile)
    }

    @Test
    fun duplicateFinalizerCommitProducesOnePlayableMessage() {
        val directory = temporaryFolder.newFolder("voicemail")
        val repository = VoicemailRepository(directory)
        val first = repository.newRecordingFile("stable-call")
        first.writeBytes(byteArrayOf(1, 2))
        val saved = repository.commit(
            id = "stable-call",
            recentCallId = "recent-1",
            callerName = "Caller",
            callerAddress = "1002",
            createdAtEpochMs = 12L,
            durationMs = 1000L,
            closedRecording = first,
        )
        val duplicate = File(directory, "stable-call-retry.wav").apply { writeBytes(byteArrayOf(3, 4)) }

        val result = repository.commit(
            id = "stable-call",
            recentCallId = "recent-1",
            callerName = "Caller",
            callerAddress = "1002",
            createdAtEpochMs = 12L,
            durationMs = 1000L,
            closedRecording = duplicate,
        )

        assertEquals(saved, result)
        assertFalse(duplicate.exists())
        assertEquals(1, repository.list().size)
    }

    @Test
    fun readTranscriptAndSyncStateChangesPersist() {
        val directory = temporaryFolder.newFolder("voicemail")
        val repository = VoicemailRepository(directory)
        val recording = repository.newRecordingFile("call-456")
        recording.writeBytes(byteArrayOf(1))
        repository.commit(
            id = "call-456",
            recentCallId = "call-456",
            callerName = "Mira",
            callerAddress = "+15551234567",
            createdAtEpochMs = 99L,
            durationMs = 500L,
            closedRecording = recording,
        )

        repository.markRead("call-456")
        repository.setTranscript("call-456", "Hello, OpenCaller")
        repository.markSynced("call-456", "server-8")

        val reloaded = VoicemailRepository(directory).find("call-456")
        assertNotNull(reloaded)
        assertFalse(reloaded!!.unread)
        assertEquals("Hello, OpenCaller", reloaded.transcript)
        assertEquals("server-8", reloaded.serverMessageId)
        assertEquals(VoicemailSyncState.SYNCED, reloaded.syncState)
    }

    @Test
    fun deleteRemovesMetadataAndAudio() {
        val directory = temporaryFolder.newFolder("voicemail")
        val repository = VoicemailRepository(directory)
        val recording = repository.newRecordingFile("call-delete")
        recording.writeBytes(byteArrayOf(1, 2, 3))
        val saved = repository.commit(
            id = "call-delete",
            recentCallId = "call-delete",
            callerName = "Caller",
            callerAddress = "1002",
            createdAtEpochMs = 1L,
            durationMs = 1L,
            closedRecording = recording,
        )!!

        assertTrue(repository.delete(saved.id))
        assertTrue(repository.list().isEmpty())
        assertFalse(File(directory, saved.audioFileName).exists())
        assertNull(repository.find(saved.id))
    }

    @Test
    fun deletingASyncedMessageLeavesATombstoneToPreventResync() {
        val directory = temporaryFolder.newFolder("voicemail-synced-delete")
        val repository = VoicemailRepository(directory)
        val recording = repository.newRecordingFile("server_message")
        recording.writeBytes(byteArrayOf(1, 2, 3))
        val serverId = "a".repeat(64)
        val saved = repository.commit(
            id = "server_message",
            recentCallId = "server_message",
            callerName = "Caller",
            callerAddress = "1002",
            createdAtEpochMs = 1L,
            durationMs = 1L,
            closedRecording = recording,
            serverMessageId = serverId,
            syncState = VoicemailSyncState.SYNCED,
        )!!

        assertTrue(repository.delete(saved.id))

        val reloaded = VoicemailRepository(directory)
        assertTrue(reloaded.list().isEmpty())
        assertTrue(reloaded.isServerMessageSuppressed(serverId))
        assertNull(reloaded.findByServerMessageId(serverId))
    }

    @Test
    fun rejectsInvalidIdsAndRecordingsOutsidePrivateStore() {
        val directory = temporaryFolder.newFolder("voicemail")
        val outside = temporaryFolder.newFile("outside.wav").apply { writeBytes(byteArrayOf(1)) }
        val repository = VoicemailRepository(directory)

        assertNull(repository.commit(
            id = "../escape",
            recentCallId = "call-1",
            callerName = "Caller",
            callerAddress = "1002",
            createdAtEpochMs = 1L,
            durationMs = 1L,
            closedRecording = outside,
        ))
        assertTrue(outside.exists())
        assertTrue(repository.list().isEmpty())
    }
}
