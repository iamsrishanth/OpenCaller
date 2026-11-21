package io.github.iamsrishanth.opencaller.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi

/** Extracts an optional number from an external ACTION_DIAL request. */
object DialIntentParser {
    fun numberFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_DIAL) return null
        val data = intent.data ?: return null
        if (!data.scheme.equals("tel", ignoreCase = true)) return null
        return data.schemeSpecificPart?.let(Uri::decode)?.takeIf(String::isNotBlank)
    }
}

/** Default-phone role helpers. The user must explicitly tap the setup button. */
object DefaultDialerRole {
    fun isHeld(context: Context): Boolean =
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName

    fun createRequestIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createRoleIntentApi29(context)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createRoleIntentApi29(context: Context): Intent? {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            null
        }
    }
}

sealed interface SimCallLaunchResult {
    data object Started : SimCallLaunchResult
    data object NeedsPhonePermission : SimCallLaunchResult
    data class Failed(val message: String) : SimCallLaunchResult
}

/** Places cellular calls through Telecom so emergency routing remains a platform decision. */
object SimCallLauncher {
    fun placeCall(context: Context, rawNumber: String): SimCallLaunchResult {
        val number = normalize(rawNumber)
        if (number.isEmpty() || number.none { it.isDigit() || it == '*' || it == '#' }) {
            return SimCallLaunchResult.Failed("Enter a phone number before calling.")
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return SimCallLaunchResult.Failed("SIM calling is not available on this device.")
        }
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return SimCallLaunchResult.NeedsPhonePermission
        }

        val telecomManager = context.getSystemService(TelecomManager::class.java)
            ?: return SimCallLaunchResult.Failed("Android Telecom is not available on this device.")
        return try {
            telecomManager.placeCall(Uri.fromParts("tel", number, null), Bundle())
            SimCallLaunchResult.Started
        } catch (_: SecurityException) {
            SimCallLaunchResult.Failed("Android denied the SIM call. Check the phone permission and dialer role.")
        } catch (_: IllegalArgumentException) {
            SimCallLaunchResult.Failed("Android could not use that phone number.")
        } catch (_: UnsupportedOperationException) {
            SimCallLaunchResult.Failed("SIM calling is not supported by this device.")
        }
    }

    private fun normalize(value: String): String = buildString(value.length) {
        value.forEach { character ->
            if (character.isDigit() || character in "+*#,;" ) append(character)
        }
    }
}
