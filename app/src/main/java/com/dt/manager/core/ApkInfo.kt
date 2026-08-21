package com.dt.manager.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.dt.manager.util.FileUtils
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Lightweight reader for APK metadata: app icon, label, version, SDKs, and
 * signature-scheme detection. Uses PackageManager's
 * getPackageArchiveInfo plus our own ApkInspector for V1 / META-INF scans.
 */
class ApkInfo {

    @JvmField var icon: Drawable? = null
    @JvmField var appName: String? = null
    @JvmField var packageName: String? = null
    @JvmField var versionName: String? = null
    @JvmField var versionCode: Long = 0
    @JvmField var fileSize: Long = 0
    @JvmField var minSdk: Int = -1
    @JvmField var targetSdk: Int = -1
    /** One of: "V1", "V1+V2", "V1+V2+V3", "None", "Unknown" */
    @JvmField var signatureScheme: String = "Unknown"

    fun formatSize(): String = FileUtils.humanReadable(fileSize)

    fun formatSdk(): String {
        if (targetSdk <= 0) return "—"
        return "Android ${sdkToVersionName(targetSdk)} (API $targetSdk)"
    }

    fun formatMinSdk(): String {
        if (minSdk <= 0) return "—"
        return "Android ${sdkToVersionName(minSdk)} (API $minSdk)"
    }

    companion object {
        @JvmStatic
        fun fromFile(ctx: Context, apkFile: File): ApkInfo {
            val info = ApkInfo()
            info.fileSize = apkFile.length()

            val pm = ctx.packageManager
            val pkgInfo: PackageInfo? = try {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
                )
            } catch (_: Exception) {
                null
            }

            if (pkgInfo?.applicationInfo != null) {
                val appInfo = pkgInfo.applicationInfo
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath

                try {
                    info.icon = pm.getApplicationIcon(appInfo)
                } catch (_: Exception) {}

                val label = pm.getApplicationLabel(appInfo)
                info.appName = label?.toString() ?: apkFile.name
                info.packageName = pkgInfo.packageName
                info.versionName = pkgInfo.versionName ?: ""
                info.versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    info.minSdk = appInfo.minSdkVersion
                }
                info.targetSdk = appInfo.targetSdkVersion
            } else {
                info.appName = apkFile.name
                info.packageName = ""
                info.versionName = ""
            }

            info.signatureScheme = detectSignatureScheme(apkFile)
            return info
        }

        @JvmStatic
        fun detectSignatureScheme(apkFile: File): String {
            var v1 = false
            try {
                ApkInspector(apkFile).use { inspector ->
                    val entries = inspector.listEntries()
                    for (e in entries) {
                        val upper = e.path.uppercase(Locale.ROOT)
                        if (upper.startsWith("META-INF/") &&
                            (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))
                        ) {
                            v1 = true
                            break
                        }
                    }
                }
            } catch (_: IOException) {
                return "Unknown"
            }

            return if (v1) "V1+V2+V3" else "None"
        }

        @JvmStatic
        fun sdkToVersionName(sdk: Int): String {
            return when (sdk) {
                21 -> "5.0"
                22 -> "5.1"
                23 -> "6.0"
                24 -> "7.0"
                25 -> "7.1"
                26 -> "8.0"
                27 -> "8.1"
                28 -> "9"
                29 -> "10"
                30 -> "11"
                31 -> "12"
                32 -> "12L"
                33 -> "13"
                34 -> "14"
                35 -> "15"
                36 -> "16"
                37 -> "16"
                else -> sdk.toString()
            }
        }
    }
}
