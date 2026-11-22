package io.github.iamsrishanth.opencaller.sip

/** Picks the freshest available transcript while preserving text already shown during the call. */
internal fun selectVoicemailTranscript(
    recognizerText: String?,
    sessionText: String?,
    runtimeText: String?,
): String? = sequenceOf(recognizerText, sessionText, runtimeText)
    .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
    .firstOrNull()

/** Keeps the last useful interim result when a speech service emits an empty update. */
internal fun preserveLastNonBlankTranscript(previousText: String?, updateText: String?): String? =
    updateText?.trim()?.takeIf(String::isNotBlank)
        ?: previousText?.trim()?.takeIf(String::isNotBlank)
