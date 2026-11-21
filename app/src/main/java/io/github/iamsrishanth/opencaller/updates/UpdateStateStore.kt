package io.github.iamsrishanth.opencaller.updates

import android.content.Context
import android.content.SharedPreferences

internal interface UpdateStateStore {
    fun isDailyChecksEnabled(): Boolean
    fun setDailyChecksEnabled(enabled: Boolean)
    fun readSourceState(source: UpdateSource): UpdateSourceState
    fun writeSourceState(state: UpdateSourceState)
}

internal class SharedPreferencesUpdateStateStore(context: Context) : UpdateStateStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isDailyChecksEnabled(): Boolean = preferences.getBoolean(KEY_DAILY_CHECKS, false)

    override fun setDailyChecksEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DAILY_CHECKS, enabled).apply()
    }

    override fun readSourceState(source: UpdateSource): UpdateSourceState {
        val prefix = "source.${source.name.lowercase()}"
        return UpdateSourceState(
            source = source,
            status = preferences.getString("$prefix.status", null).toUpdateStatus() ?: UpdateStatus.NOT_CHECKED,
            lastSuccessfulStatus = preferences.getString("$prefix.successStatus", null).toUpdateStatus(),
            latestVersionName = preferences.getString("$prefix.versionName", null).orNullIfBlank(),
            latestVersionCode = preferences.getLong("$prefix.versionCode", INVALID_VERSION_CODE)
                .takeIf { it > 0L },
            sourceUrl = preferences.getString("$prefix.url", null).orNullIfBlank(),
            detail = preferences.getString("$prefix.detail", null).orNullIfBlank(),
            lastSuccessfulCheckMillis = preferences.getLong("$prefix.successAt", 0L).takeIf { it > 0L },
            lastAttemptMillis = preferences.getLong("$prefix.attemptAt", 0L).takeIf { it > 0L },
        )
    }

    override fun writeSourceState(state: UpdateSourceState) {
        val prefix = "source.${state.source.name.lowercase()}"
        preferences.edit()
            .putString("$prefix.status", state.status.name)
            .putString("$prefix.successStatus", state.lastSuccessfulStatus?.name)
            .putString("$prefix.versionName", state.latestVersionName)
            .putLong("$prefix.versionCode", state.latestVersionCode ?: INVALID_VERSION_CODE)
            .putString("$prefix.url", state.sourceUrl)
            .putString("$prefix.detail", state.detail)
            .putLong("$prefix.successAt", state.lastSuccessfulCheckMillis ?: 0L)
            .putLong("$prefix.attemptAt", state.lastAttemptMillis ?: 0L)
            .apply()
    }

    private fun String?.toUpdateStatus(): UpdateStatus? =
        this?.let { name -> UpdateStatus.entries.firstOrNull { it.name == name } }

    private fun String?.orNullIfBlank(): String? = this?.takeIf(String::isNotBlank)

    private companion object {
        const val PREFERENCES_NAME = "open_caller_updates"
        const val KEY_DAILY_CHECKS = "daily_checks_enabled"
        const val INVALID_VERSION_CODE = -1L
    }
}
