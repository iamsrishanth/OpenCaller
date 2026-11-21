package io.github.iamsrishanth.opencaller.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

internal object InstallSourceDetector {
    fun detect(context: Context): InstallSource {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val apiLevel = Build.VERSION.SDK_INT
        val installerPackage = try {
            val installSourcePackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                modernInstallerPackage(packageManager, appContext.packageName)
            } else {
                null
            }
            val legacyInstaller = if (apiLevel < Build.VERSION_CODES.R) {
                legacyInstallerPackage(packageManager, appContext.packageName)
            } else {
                null
            }
            installerPackageForApi(apiLevel, installSourcePackage, legacyInstaller)
        } catch (_: Exception) {
            null
        }
        return classifyInstallerPackage(installerPackage)
    }

    @Suppress("DEPRECATION")
    private fun legacyInstallerPackage(packageManager: PackageManager, packageName: String): String? =
        packageManager.getInstallerPackageName(packageName)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun modernInstallerPackage(packageManager: PackageManager, packageName: String): String? =
        packageManager.getInstallSourceInfo(packageName).installingPackageName
}

internal fun classifyInstallerPackage(installerPackage: String?): InstallSource = when (installerPackage) {
    "org.fdroid.fdroid", "org.fdroid.basic" -> InstallSource.FDROID
    "com.android.vending" -> InstallSource.GOOGLE_PLAY
    else -> InstallSource.UNKNOWN
}

internal fun installerPackageForApi(
    apiLevel: Int,
    installSourcePackage: String?,
    legacyInstallerPackage: String?,
): String? = if (apiLevel >= 30) installSourcePackage else legacyInstallerPackage
