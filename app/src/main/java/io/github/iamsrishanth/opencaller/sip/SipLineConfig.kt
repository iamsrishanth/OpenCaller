package io.github.iamsrishanth.opencaller.sip

/** Credentials and trust anchor for one TLS protected SIP line. */
class SipLineConfig(
    host: String,
    val tlsPort: Int,
    username: String,
    val password: String,
    caPath: String,
    val voicemailSyncPort: Int = 8443,
    voicemailSyncToken: String? = null,
) {
    val host: String = host.trim().removePrefix("[").removeSuffix("]")
    val username: String = username.trim()
    val caPath: String = caPath.trim()
    val voicemailSyncToken: String? = voicemailSyncToken?.takeIf(String::isNotBlank)

    override fun toString(): String =
        "SipLineConfig(host=$host, tlsPort=$tlsPort, username=$username, password=<redacted>, caPath=$caPath, voicemailSyncToken=<redacted>)"
}
