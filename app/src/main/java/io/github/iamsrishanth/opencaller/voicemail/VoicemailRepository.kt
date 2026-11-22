package io.github.iamsrishanth.opencaller.voicemail

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

enum class VoicemailSyncState {
    LOCAL,
    SYNCED,
    DELETE_PENDING,
}

data class VoicemailMessage(
    val id: String,
    val recentCallId: String,
    val callerName: String,
    val callerAddress: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val audioFileName: String,
    val transcript: String? = null,
    val unread: Boolean = true,
    val serverMessageId: String? = null,
    val syncState: VoicemailSyncState = VoicemailSyncState.LOCAL,
)

/**
 * Private, file-backed voicemail store. Recordings and metadata live under the app's filesDir,
 * so Android upgrades preserve them and other apps cannot browse them. A stable id makes message
 * commits idempotent when a recorder finalizer is retried after a caller disconnect.
 */
class VoicemailRepository(private val directory: File) {
    private val lock = Any()

    init {
        require(directory.mkdirs() || directory.isDirectory) { "Could not create voicemail storage" }
    }

    fun list(): List<VoicemailMessage> = synchronized(lock) {
        metadataFiles().mapNotNull(::readMessage)
            .sortedWith(compareByDescending<VoicemailMessage> { it.createdAtEpochMs }.thenBy { it.id })
    }

    fun find(id: String): VoicemailMessage? = synchronized(lock) {
        safeId(id)?.let { readMessage(metadataFile(it)) }
    }

    fun findByServerMessageId(serverMessageId: String): VoicemailMessage? = synchronized(lock) {
        if (serverMessageId.isBlank()) return@synchronized null
        metadataFiles().asSequence()
            .mapNotNull(::readMessage)
            .firstOrNull { it.serverMessageId == serverMessageId }
    }

    fun isServerMessageSuppressed(serverMessageId: String): Boolean = synchronized(lock) {
        safeServerId(serverMessageId)?.let { deletedServerMarker(it).isFile } ?: false
    }

    fun newRecordingFile(id: String): File {
        val safe = safeId(id) ?: throw IllegalArgumentException("Invalid voicemail id")
        return File(directory, "$safe$TEMP_RECORDING_SUFFIX")
    }

    fun audioFile(message: VoicemailMessage): File = synchronized(lock) {
        require(safeId(message.id) == message.id)
        require(message.audioFileName.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]{1,8}")))
        File(directory, message.audioFileName)
    }

    /**
     * Commits a finalized, closed recording once. The source must be inside this repository
     * directory so the final move remains atomic on the same filesystem.
     */
    fun commit(
        id: String,
        recentCallId: String,
        callerName: String,
        callerAddress: String,
        createdAtEpochMs: Long,
        durationMs: Long,
        closedRecording: File,
        transcript: String? = null,
        serverMessageId: String? = null,
        syncState: VoicemailSyncState = VoicemailSyncState.LOCAL,
    ): VoicemailMessage? = synchronized(lock) {
        val safe = safeId(id) ?: return@synchronized null
        require(recentCallId.isNotBlank())
        require(durationMs >= 0L)
        val existing = readMessage(metadataFile(safe))
        if (existing != null) {
            if (closedRecording != existing.resolveAudioFile()) closedRecording.delete()
            return@synchronized existing
        }
        if (!closedRecording.isFile || closedRecording.length() <= 0L) return@synchronized null
        if (!closedRecording.toPath().toAbsolutePath().normalize().startsWith(directory.toPath().toAbsolutePath().normalize())) {
            return@synchronized null
        }

        val extension = closedRecording.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "wav"
        val audioName = "$safe.$extension"
        val finalAudio = File(directory, audioName)
        if (closedRecording != finalAudio && !closedRecording.renameTo(finalAudio)) return@synchronized null

        val message = VoicemailMessage(
            id = safe,
            recentCallId = recentCallId,
            callerName = callerName.trim().take(MAX_TEXT_LENGTH),
            callerAddress = callerAddress.trim().take(MAX_TEXT_LENGTH),
            createdAtEpochMs = createdAtEpochMs,
            durationMs = durationMs,
            audioFileName = audioName,
            transcript = transcript?.take(MAX_TRANSCRIPT_LENGTH),
            unread = true,
            serverMessageId = serverMessageId,
            syncState = syncState,
        )
        if (!writeMessage(message)) {
            finalAudio.delete()
            return@synchronized null
        }
        message
    }

    fun markRead(id: String): VoicemailMessage? = update(id) { it.copy(unread = false) }

    fun setTranscript(id: String, transcript: String?): VoicemailMessage? = update(id) {
        it.copy(transcript = transcript?.take(MAX_TRANSCRIPT_LENGTH))
    }

    /** Local deletion is immediate; synchronized server deletion can be queued by the sync layer. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val safe = safeId(id) ?: return@synchronized false
        val metadata = metadataFile(safe)
        val message = readMessage(metadata) ?: return@synchronized false
        if (!message.serverMessageId.isNullOrBlank() && !writeServerDeletionMarker(message.serverMessageId)) {
            return@synchronized false
        }
        val metadataRemoved = !metadata.exists() || metadata.delete()
        if (metadataRemoved) message.resolveAudioFile().delete()
        metadataRemoved
    }

    /** Marks an item as synchronized without changing its stable message identity. */
    fun markSynced(id: String, serverMessageId: String): VoicemailMessage? {
        if (serverMessageId.isBlank()) return null
        return update(id) { it.copy(serverMessageId = serverMessageId, syncState = VoicemailSyncState.SYNCED) }
    }

    /** Removes abandoned temporary audio after a process died before the final commit. */
    fun cleanTemporaryRecordings() = synchronized(lock) {
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(TEMP_RECORDING_SUFFIX) }
            ?.forEach(File::delete)
    }

    private fun update(id: String, transform: (VoicemailMessage) -> VoicemailMessage): VoicemailMessage? = synchronized(lock) {
        val safe = safeId(id) ?: return@synchronized null
        val file = metadataFile(safe)
        val existing = readMessage(file) ?: return@synchronized null
        val updated = transform(existing)
        if (writeMessage(updated)) updated else null
    }

    private fun writeMessage(message: VoicemailMessage): Boolean {
        val file = metadataFile(message.id)
        val temp = File(directory, "${message.id}.$TEMP_METADATA_SUFFIX")
        val properties = Properties().apply {
            setProperty("id", message.id)
            setProperty("recentCallId", message.recentCallId)
            setProperty("callerName", message.callerName)
            setProperty("callerAddress", message.callerAddress)
            setProperty("createdAtEpochMs", message.createdAtEpochMs.toString())
            setProperty("durationMs", message.durationMs.toString())
            setProperty("audioFileName", message.audioFileName)
            setProperty("transcript", message.transcript.orEmpty())
            setProperty("unread", message.unread.toString())
            setProperty("serverMessageId", message.serverMessageId.orEmpty())
            setProperty("syncState", message.syncState.name)
        }
        return try {
            FileOutputStream(temp).use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer -> properties.store(writer, "OpenCaller voicemail metadata") }
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun readMessage(file: File): VoicemailMessage? {
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties().apply {
                FileInputStream(file).use { input -> InputStreamReader(input, StandardCharsets.UTF_8).use(::load) }
            }
            val id = safeId(properties.getProperty("id")) ?: return null
            val audioName = properties.getProperty("audioFileName") ?: return null
            if (!audioName.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]{1,8}"))) return null
            val audioFile = File(directory, audioName)
            if (!audioFile.isFile || audioFile.length() <= 0L) return null
            VoicemailMessage(
                id = id,
                recentCallId = properties.getProperty("recentCallId")?.takeIf(String::isNotBlank) ?: return null,
                callerName = properties.getProperty("callerName").orEmpty().take(MAX_TEXT_LENGTH),
                callerAddress = properties.getProperty("callerAddress").orEmpty().take(MAX_TEXT_LENGTH),
                createdAtEpochMs = properties.getProperty("createdAtEpochMs")?.toLongOrNull() ?: return null,
                durationMs = properties.getProperty("durationMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: return null,
                audioFileName = audioName,
                transcript = properties.getProperty("transcript")?.takeIf(String::isNotEmpty)?.take(MAX_TRANSCRIPT_LENGTH),
                unread = properties.getProperty("unread")?.toBooleanStrictOrNull() ?: true,
                serverMessageId = properties.getProperty("serverMessageId")?.takeIf(String::isNotBlank),
                syncState = properties.getProperty("syncState")?.let { value -> runCatching { VoicemailSyncState.valueOf(value) }.getOrNull() }
                    ?: VoicemailSyncState.LOCAL,
            )
        }.getOrNull()
    }

    private fun VoicemailMessage.resolveAudioFile(): File = File(directory, audioFileName)
    private fun metadataFiles(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
        .orEmpty()

    private fun metadataFile(id: String) = File(directory, "$id$METADATA_SUFFIX")
    private fun deletedServerMarker(serverId: String) = File(directory, "$serverId$DELETED_SERVER_SUFFIX")
    private fun safeServerId(value: String?): String? = value?.takeIf { SERVER_ID_PATTERN.matches(it) }

    private fun writeServerDeletionMarker(serverId: String): Boolean {
        val safe = safeServerId(serverId) ?: return true
        val marker = deletedServerMarker(safe)
        if (marker.isFile) return true
        val temporary = File(directory, "$safe$DELETED_SERVER_TEMP_SUFFIX")
        return try {
            FileOutputStream(temporary).use { it.write(byteArrayOf(1)) }
            try {
                Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    private fun safeId(value: String?): String? = value?.takeIf { ID_PATTERN.matches(it) }

    companion object {
        const val TEMP_RECORDING_SUFFIX = ".tmp.wav"
        private const val METADATA_SUFFIX = ".properties"
        private const val TEMP_METADATA_SUFFIX = "properties.tmp"
        private const val DELETED_SERVER_SUFFIX = ".server-deleted"
        private const val DELETED_SERVER_TEMP_SUFFIX = ".server-deleted.tmp"
        private const val MAX_TEXT_LENGTH = 300
        private const val MAX_TRANSCRIPT_LENGTH = 16_000
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,96}")
        private val SERVER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
