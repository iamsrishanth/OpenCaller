package io.github.iamsrishanth.opencaller.sip

/** Returns the SIP display name when present, otherwise the address user part. */
internal fun displaySipPeer(rawIdentity: String): String {
    val identity = rawIdentity.trim()
    if (identity.isEmpty()) return ""

    val leftAngle = identity.indexOf('<')
    val rightAngle = identity.indexOf('>', startIndex = leftAngle.coerceAtLeast(0) + 1)
    if (leftAngle >= 0 && rightAngle > leftAngle) {
        identity.substring(0, leftAngle)
            .trim()
            .removeSurrounding("\"")
            .takeIf(String::isNotBlank)
            ?.let { return it }
    }

    val uri = if (leftAngle >= 0 && rightAngle > leftAngle) {
        identity.substring(leftAngle + 1, rightAngle)
    } else {
        identity
    }
    val withoutScheme = uri.removePrefix("sip:").removePrefix("sips:")
    return withoutScheme.substringBefore('@').substringBefore(';').trim()
}

/** Returns a safe user part for an outbound call through the saved SIP host. */
internal fun sipUserForCallback(rawIdentity: String): String? {
    val identity = rawIdentity.trim()
    if (identity.isEmpty()) return null
    val leftAngle = identity.indexOf('<')
    val rightAngle = identity.indexOf('>', startIndex = leftAngle.coerceAtLeast(0) + 1)
    val uri = if (leftAngle >= 0 && rightAngle > leftAngle) {
        identity.substring(leftAngle + 1, rightAngle)
    } else {
        identity
    }
    val match = Regex("(?i)^sips?:([^@;?]+)(?:@.*)?$").matchEntire(uri.trim()) ?: return null
    return match.groupValues[1].takeIf { CALLBACK_USER_PATTERN.matches(it) }
}

/** Allows a system dialer callback only for a plain telephone number. */
internal fun phoneNumberForCallback(rawIdentity: String): String? {
    val number = rawIdentity.trim()
    return number.takeIf { it.length <= 48 && PHONE_NUMBER_PATTERN.matches(it) }
}

private val CALLBACK_USER_PATTERN = Regex("[+*#A-Za-z0-9._-]{1,128}")
private val PHONE_NUMBER_PATTERN = Regex("[+0-9*#() -]{1,48}")
