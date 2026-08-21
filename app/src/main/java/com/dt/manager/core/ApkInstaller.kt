package com.dt.manager.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * Installs APK / XAPK / APKM bundles.
 *
 * - APK: launches the system installer intent via FileProvider.
 * - XAPK/APKM: extracts the inner APK (and OBB if present) and stages the
 *   APK in cache, then triggers the system installer.
 */
class ApkInstaller(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onSuccess(message: String)
        fun onError(message: String)
    }

    fun install(file: File?) {
        if (file == null || !file.exists()) {
            cb.onError("File does not exist")
            return
        }
        val name = file.name.lowercase(Locale.ROOT)
        when {
            name.endsWith(".apk") -> installPlainApk(file)
            name.endsWith(".xapk") -> installBundle(file, "xapk")
            name.endsWith(".apkm") -> installBundle(file, "apkm")
            else -> cb.onError("Unsupported file type: $name")
        }
    }

    private fun installPlainApk(apk: File) {
        try {
            val intent = buildInstallIntent(apk, "application/vnd.android.package-archive")
            ctx.startActivity(intent)
            cb.onSuccess("Install requested for ${apk.name}")
        } catch (e: Exception) {
            cb.onError("Failed to launch installer: ${e.message}")
        }
    }

    private fun installBundle(bundle: File, kind: String) {
        val stage = File(ctx.cacheDir, "install_" + UUID.randomUUID().toString())
        stage.mkdirs()
        try {
            ApkInspector(bundle).use { inspector ->
                val apks = inspector.findInnerApks()
                if (apks.isEmpty()) {
                    cb.onError("No APK entries found inside ${bundle.name}")
                    return
                }
                val firstApkPath = apks[0]
                val staged = File(stage, File(firstApkPath).name)
                inspector.openStream(firstApkPath).use { inStream ->
                    FileOutputStream(staged).use { outStream ->
                        val buf = ByteArray(16384)
                        var n: Int
                        while (inStream.read(buf).also { n = it } > 0) {
                            outStream.write(buf, 0, n)
                        }
                    }
                }
                extractObbIfExists(inspector)
                val intent = buildInstallIntent(staged, "application/vnd.android.package-archive")
                ctx.startActivity(intent)
                cb.onSuccess("Install requested for ${staged.name} (${apks.size} APK(s) in bundle)")
            }
        } catch (e: IOException) {
            cb.onError("Failed to extract bundle: ${e.message}")
        }
    }

    private fun extractObbIfExists(inspector: ApkInspector) {
        for (e in inspector.listEntries()) {
            val p = e.path.lowercase(Locale.ROOT)
            if (p.startsWith("android/") && p.endsWith(".obb")) {
                try {
                    val obbDest = File(android.os.Environment.getExternalStorageDirectory(), e.path)
                    obbDest.parentFile?.mkdirs()
                    inspector.openStream(e.path).use { inStream ->
                        FileOutputStream(obbDest).use { outStream ->
                            val buf = ByteArray(16384)
                            var n: Int
                            while (inStream.read(buf).also { n = it } > 0) {
                                outStream.write(buf, 0, n)
                            }
                        }
                    }
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun buildInstallIntent(apk: File, mime: String): Intent {
        val uri: Uri
        val intent: Intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
            intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            uri = Uri.fromFile(apk)
            intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }
}
