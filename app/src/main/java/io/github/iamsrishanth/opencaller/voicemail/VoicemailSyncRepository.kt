package io.github.iamsrishanth.opencaller.voicemail

import android.content.Context
import android.net.Uri
import android.security.KeyStoreException
import io.github.iamsrishanth.opencaller.sip.SipLineConfig
import io.github.iamsrishanth.opencaller.sip.SipLineConfigStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteVoicemail(
    val id: String,
    val audioSha256: String,
    val callerAddress: String,
    val callerName: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val unread: Boolean,
)

internal interface VoicemailSyncClient {
    fun listInbox(): List<RemoteVoicemail>
    fun downloadAudio(message: RemoteVoicemail, destination: File): Boolean
}

/** Fetches only the fixed, token-authorized mailbox configured on the encrypted SIP line. */
class VoicemailSyncRepository(context: Context) {
    private val appContext = context.applicationContext

    fun isConfigured(): Boolean = SipLineConfigStore(appContext).load()?.config?.voicemailSyncToken != null

    suspend fun synchronize(): Int = withContext(Dispatchers.IO) {
        val line = SipLineConfigStore(appContext).load()?.config ?: return@withContext 0
        val token = line.voicemailSyncToken ?: return@withContext 0
        val local = VoicemailRepository(File(appContext.filesDir, VOICEMAIL_DIRECTORY))
        VoicemailSyncCoordinator(local, HttpsVoicemailSyncClient(line, token)).synchronize()
    }

    private companion object {
        const val VOICEMAIL_DIRECTORY = "voicemail"
    }
}

/** Idempotently copies one account's server messages into the private local voicemail store. */
internal class VoicemailSyncCoordinator(
    private val local: VoicemailRepository,
    private val client: VoicemailSyncClient,
) {
    suspend fun synchronize(): Int = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
            var added = 0
            client.listInbox().forEach { remote ->
                if (local.isServerMessageSuppressed(remote.id)) return@forEach
                if (local.findByServerMessageId(remote.id) != null) return@forEach
                val localId = "server_${remote.id}"
                val tempAudio = local.newRecordingFile(localId)
                if (!client.downloadAudio(remote, tempAudio)) {
                    tempAudio.delete()
                    throw IOException("The server voicemail audio failed its integrity check")
                }
                val saved = runCatching {
                    local.commit(
                        id = localId,
                        recentCallId = localId,
                        callerName = remote.callerName,
                        callerAddress = remote.callerAddress,
                        createdAtEpochMs = remote.createdAtEpochMs,
                        durationMs = remote.durationMs,
                        closedRecording = tempAudio,
                        transcript = null,
                        serverMessageId = remote.id,
                        syncState = VoicemailSyncState.SYNCED,
                    )
                }.getOrNull()
                if (saved == null) {
                    tempAudio.delete()
                    throw IOException("The voicemail could not be saved on this phone")
                }
                if (!remote.unread) local.markRead(localId)
                added++
            }
            added
        }
    }

    private companion object {
        val synchronizationMutex = Mutex()
    }
}

private class HttpsVoicemailSyncClient(
    private val line: SipLineConfig,
    private val token: String,
) : VoicemailSyncClient {
    private val sslSocketFactory by lazy { createSslSocketFactory(File(line.caPath)) }

    override fun listInbox(): List<RemoteVoicemail> {
        val response = request("/v1/inbox", "application/json")
        val status = response.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            response.disconnect()
            throw IOException("Voicemail server returned HTTP $status")
        }
        val body = response.inputStream.use { it.readBounded(MAX_INBOX_BYTES) }
        response.disconnect()
        val entries = JSONObject(body.toString(Charsets.UTF_8)).optJSONArray("messages") ?: JSONArray()
        return (0 until entries.length()).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val hash = item.optString("audioSha256")
            if (!ID_PATTERN.matches(id) || !SHA256_PATTERN.matches(hash)) return@mapNotNull null
            val createdAt = item.optLong("createdAtEpochMs", -1L)
            val duration = item.optLong("durationMs", -1L)
            if (createdAt <= 0L || duration !in 0L..MAX_DURATION_MS) return@mapNotNull null
            RemoteVoicemail(
                id = id,
                audioSha256 = hash,
                callerAddress = item.optString("callerAddress").take(MAX_FIELD_LENGTH),
                callerName = item.optString("callerName").take(MAX_FIELD_LENGTH).ifBlank { "Internet caller" },
                createdAtEpochMs = createdAt,
                durationMs = duration,
                unread = item.optBoolean("unread", true),
            )
        }
    }

    override fun downloadAudio(message: RemoteVoicemail, destination: File): Boolean {
        val connection = request("/v1/inbox/${Uri.encode(message.id)}/audio", "audio/wav")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_AUDIO_BYTES) return false
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            return total > WAV_HEADER_BYTES && actualHash.equals(message.audioSha256, ignoreCase = true)
        } catch (_: Exception) {
            destination.delete()
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, accept: String): HttpsURLConnection {
        val uri = URI("https", null, line.host, line.voicemailSyncPort, path, null, null)
        require(uri.scheme == "https" && uri.host != null)
        val connection = URL(uri.toASCIIString()).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.sslSocketFactory = sslSocketFactory
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("User-Agent", "OpenCaller-Android")
        return connection
    }

    private fun createSslSocketFactory(caFile: File) = runCatching {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = caFile.inputStream().use(factory::generateCertificates)
        require(certificates.isNotEmpty())
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("opencaller-ca-$index", certificate) }
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
    }.getOrElse { throw IOException("The saved server certificate could not be used for voicemail sync", it) }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Voicemail inbox response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        val ID_PATTERN = Regex("[a-f0-9]{64}")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}", RegexOption.IGNORE_CASE)
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
        const val MAX_INBOX_BYTES = 2 * 1024 * 1024
        const val MAX_AUDIO_BYTES = 20 * 1024 * 1024
        const val WAV_HEADER_BYTES = 44L
        const val MAX_DURATION_MS = 3_600_000L
        const val MAX_FIELD_LENGTH = 300
    }
}
