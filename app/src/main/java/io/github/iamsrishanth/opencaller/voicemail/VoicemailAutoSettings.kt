package io.github.iamsrishanth.opencaller.voicemail

import android.content.Context

enum class VoicemailAutoMode {
    OFF,
    SELECTED_CALLERS,
    ALL_CALLERS,
}

data class VoicemailAutoSettings(
    val mode: VoicemailAutoMode = VoicemailAutoMode.OFF,
    /** Zero means answer with voicemail immediately; otherwise use the selected ring delay. */
    val ringTimeoutSeconds: Int = 20,
    val selectedCallers: Set<String> = emptySet(),
    val liveTranscriptionEnabled: Boolean = false,
)

/** App-private preferences for voicemail rules on calls handled by the self-hosted SIP line. */
class VoicemailAutoSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): VoicemailAutoSettings {
        val mode = preferences.getString(KEY_MODE, null)
            ?.let { value -> runCatching { VoicemailAutoMode.valueOf(value) }.getOrNull() }
            ?: VoicemailAutoMode.OFF
        return VoicemailAutoSettings(
            mode = mode,
            ringTimeoutSeconds = preferences.getInt(KEY_TIMEOUT, 20).coerceIn(0, 60),
            selectedCallers = preferences.getStringSet(KEY_SELECTED_CALLERS, emptySet())
                .orEmpty()
                .mapNotNull(::normalizeCaller)
                .toSet(),
            liveTranscriptionEnabled = preferences.getBoolean(KEY_TRANSCRIPTION, false),
        )
    }

    fun save(settings: VoicemailAutoSettings) {
        preferences.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putInt(KEY_TIMEOUT, settings.ringTimeoutSeconds.coerceIn(0, 60))
            .putStringSet(KEY_SELECTED_CALLERS, settings.selectedCallers.mapNotNull(::normalizeCaller).toSet())
            .putBoolean(KEY_TRANSCRIPTION, settings.liveTranscriptionEnabled)
            .apply()
    }

    fun shouldAutoSendToVoicemail(remoteIdentity: String, settings: VoicemailAutoSettings = load()): Boolean {
        return when (settings.mode) {
            VoicemailAutoMode.OFF -> false
            VoicemailAutoMode.ALL_CALLERS -> true
            VoicemailAutoMode.SELECTED_CALLERS -> normalizeCaller(remoteIdentity)
                ?.let { it in settings.selectedCallers } == true
        }
    }

    companion object {
        private const val PREFERENCES = "voicemail_auto_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_TIMEOUT = "ring_timeout_seconds"
        private const val KEY_SELECTED_CALLERS = "selected_callers"
        private const val KEY_TRANSCRIPTION = "live_transcription_enabled"

        /** Keep the SIP user or phone number only so calls from the same line can be matched. */
        fun normalizeCaller(value: String): String? {
            val identity = value.trim()
                .removePrefix("sips:")
                .removePrefix("sip:")
                .substringBefore('@')
                .substringBefore(';')
                .trim()
                .lowercase()
            return identity.takeIf { it.isNotBlank() && it.length <= MAX_IDENTITY_LENGTH }
        }

        private const val MAX_IDENTITY_LENGTH = 256
    }
}
