package io.github.iamsrishanth.opencaller.sip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class SavedSipLine(
    val config: SipLineConfig,
    val caSha256: String,
)

/** Stores the SIP password and server details encrypted with an Android Keystore AES key. */
class SipLineConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val certificateFile = File(File(appContext.filesDir, SIP_DIRECTORY), CA_FILE_NAME)

    fun load(): SavedSipLine? {
        val encrypted = preferences.getString(ENCRYPTED_CONFIG, null) ?: return null
        val caBytes = runCatching { certificateFile.readBytes() }.getOrNull() ?: return null
        val value = runCatching {
            JSONObject(decrypt(encrypted))
        }.getOrNull() ?: return null
        val host = value.optString("host").trim()
        val username = value.optString("username").trim()
        val password = value.optString("password")
        val port = value.optInt("tlsPort", -1)
        if (host.isBlank() || username.isBlank() || password.isEmpty() || port !in 1..65535) return null
        val syncToken = value.optString("voicemailSyncToken").takeIf(String::isNotBlank)
        val syncPort = value.optInt("voicemailSyncPort", DEFAULT_SYNC_PORT)
        if (syncToken != null && (syncPort !in 1..65535 || syncToken.length !in MIN_SYNC_TOKEN_LENGTH..MAX_SYNC_TOKEN_LENGTH)) {
            return null
        }
        val certificateInfo = runCatching { validateCa(caBytes) }.getOrNull() ?: return null
        return SavedSipLine(
            config = SipLineConfig(
                host = host,
                tlsPort = port,
                username = username,
                password = password,
                caPath = certificateFile.absolutePath,
                voicemailSyncPort = syncPort.takeIf { it in 1..65535 } ?: DEFAULT_SYNC_PORT,
                voicemailSyncToken = syncToken,
            ),
            caSha256 = certificateInfo,
        )
    }

    /**
     * Copies a PEM/DER X.509 trust bundle to app-private storage and encrypts all line settings.
     * No plaintext password is written to preferences, logs, or external storage.
     */
    fun save(
        host: String,
        tlsPort: Int,
        username: String,
        password: String,
        certificateBytes: ByteArray,
        voicemailSyncPort: Int = DEFAULT_SYNC_PORT,
        voicemailSyncToken: String? = null,
    ): SavedSipLine {
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        val normalizedUser = username.trim()
        require(normalizedHost.isNotBlank() && normalizedHost.none { it.isWhitespace() || it == '/' || it == '@' || it == ';' }) {
            "Enter a valid server name or IP address."
        }
        require(tlsPort in 1..65535) { "Enter a valid TLS port." }
        require(normalizedUser.isNotBlank() && normalizedUser.length <= MAX_USERNAME_LENGTH) {
            "Enter a valid SIP account name."
        }
        require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH) { "Enter the SIP password." }
        require(certificateBytes.isNotEmpty() && certificateBytes.size <= MAX_CA_BYTES) {
            "The certificate file must be smaller than 256 KB."
        }
        val normalizedSyncToken = voicemailSyncToken?.trim()?.takeIf(String::isNotBlank)
        if (normalizedSyncToken != null) {
            require(voicemailSyncPort in 1..65535) { "Enter a valid voicemail sync HTTPS port." }
            require(normalizedSyncToken.length in MIN_SYNC_TOKEN_LENGTH..MAX_SYNC_TOKEN_LENGTH) {
                "The voicemail sync token must contain at least 32 characters."
            }
        }
        val fingerprint = validateCa(certificateBytes)

        val directory = certificateFile.parentFile ?: error("SIP storage is unavailable")
        require(directory.mkdirs() || directory.isDirectory) { "SIP storage is unavailable" }
        val tempCertificate = File(directory, "$CA_FILE_NAME.tmp")
        FileOutputStream(tempCertificate).use { it.write(certificateBytes) }
        try {
            Files.move(
                tempCertificate.toPath(),
                certificateFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempCertificate.toPath(), certificateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            tempCertificate.delete()
            throw IllegalStateException("Could not save the SIP certificate.")
        }

        val json = JSONObject()
            .put("host", normalizedHost)
            .put("tlsPort", tlsPort)
            .put("username", normalizedUser)
            .put("password", password)
            .put("voicemailSyncPort", voicemailSyncPort.takeIf { it in 1..65535 } ?: DEFAULT_SYNC_PORT)
            .put("voicemailSyncToken", normalizedSyncToken.orEmpty())
        val saved = preferences.edit().putString(ENCRYPTED_CONFIG, encrypt(json.toString())).commit()
        if (!saved) throw IllegalStateException("Could not save the encrypted SIP line settings.")
        return SavedSipLine(
            config = SipLineConfig(
                host = normalizedHost,
                tlsPort = tlsPort,
                username = normalizedUser,
                password = password,
                caPath = certificateFile.absolutePath,
                voicemailSyncPort = voicemailSyncPort.takeIf { it in 1..65535 } ?: DEFAULT_SYNC_PORT,
                voicemailSyncToken = normalizedSyncToken,
            ),
            caSha256 = fingerprint,
        )
    }

    fun clear() {
        preferences.edit().remove(ENCRYPTED_CONFIG).commit()
        certificateFile.delete()
        certificateFile.parentFile?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$CA_FILE_NAME.") }
            ?.forEach(File::delete)
    }

    private fun encrypt(clearText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(clearText.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipherText, Base64.NO_WRAP),
        ).joinToString(":")
    }

    private fun decrypt(value: String): String {
        val fields = value.split(':', limit = 3)
        require(fields.size == 3 && fields[0] == FORMAT_VERSION) { "Unsupported encrypted SIP settings." }
        val iv = Base64.decode(fields[1], Base64.NO_WRAP)
        val cipherText = Base64.decode(fields[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun validateCa(bytes: ByteArray): String {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
        require(certificates.isNotEmpty()) { "Select a valid PEM or DER X.509 certificate bundle." }
        certificates.forEach { certificate ->
            require(certificate is X509Certificate) { "Select X.509 certificates only." }
            certificate.checkValidity()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val PREFERENCES_NAME = "sip_line_encrypted"
        private const val ENCRYPTED_CONFIG = "encrypted_config_v1"
        private const val SIP_DIRECTORY = "sip"
        private const val CA_FILE_NAME = "server-ca.pem"
        private const val KEY_ALIAS = "io.github.iamsrishanth.opencaller.SIP_LINE_AES"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = "v1"
        private const val GCM_TAG_BITS = 128
        private const val MAX_CA_BYTES = 256 * 1024
        private const val MAX_USERNAME_LENGTH = 128
        private const val MAX_PASSWORD_LENGTH = 1024
        private const val DEFAULT_SYNC_PORT = 8443
        private const val MIN_SYNC_TOKEN_LENGTH = 32
        private const val MAX_SYNC_TOKEN_LENGTH = 512
    }
}
