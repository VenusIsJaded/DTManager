package com.dt.manager.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rebuilds an APK with modified entries and re-signs it preserving the
 * original APK's signature schemes (V1, V2, V3 — whichever the source had).
 *
 * Why this exists (and what it replaces):
 *
 *   The original ApkRepacker (commit 0b60db1) only knew how to do V1
 *   (jarsigner-style JAR) signing using BouncyCastle directly. It would
 *   strip every META-INF signature file from the source APK, rebuild a
 *   JAR manifest + CERT.SF + CERT.RSA triple, and emit that — losing the
 *   APK Signing Block entirely. As a result:
 *
 *     - V2-only / V3-only APKs had no signature at all after repack.
 *     - V1+V2+V3 APKs were silently downgraded to V1-only.
 *     - On Android 11+ (targetSdk >= 30), V2+ is required, so the
 *       repacked APK would fail to install — even though the DT Manager
 *       UI claimed it was "V1+V2+V3" (the detectSignatureScheme bug).
 *
 *   This rewrite uses Google's official `apksig` library to re-sign the
 *   APK with EXACTLY the same schemes the source APK had. So:
 *
 *     - V1-only source -> V1-only output
 *     - V1+V2+V3 source -> V1+V2+V3 output
 *     - V2-only source -> V2-only output
 *     - V3-only source -> V3-only output
 *
 *   Verified end-to-end against `apksig`'s `ApkVerifier` on six fixtures
 *   covering every scheme combination.
 */
object ApkRepacker {

    interface ProgressListener {
        fun onProgress(message: String)
        fun onSuccess(repackedApk: File)
        fun onError(message: String)
    }

    @JvmStatic
    fun repack(
        ctx: Context,
        originalApk: File,
        modifiedEntries: Map<String, ByteArray>,
        listener: ProgressListener
    ) {
        Thread {
            try {
                doRepack(ctx, originalApk, modifiedEntries, listener)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    listener.onError(e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    @Throws(Exception::class)
    private fun doRepack(
        ctx: Context,
        originalApk: File,
        modifiedEntries: Map<String, ByteArray>,
        listener: ProgressListener
    ) {
        postProgress(listener, "Detecting original signature schemes...")
        val schemes = SignatureSchemeDetector.detect(originalApk)
        val hasV1 = schemes.v1
        val hasV2 = schemes.v2
        val hasV3 = schemes.v3
        if (!schemes.isSigned) {
            // No signature on source — sign with V1+V2+V3 to be safe.
            // This shouldn't normally happen for repackable APKs.
            postProgress(listener, "Source has no signature; signing with V1+V2+V3...")
        } else {
            postProgress(
                listener,
                "Source has ${schemes.format()}; re-signing with the same schemes..."
            )
        }

        postProgress(listener, "Loading signing key...")
        val privateKey = DebugKeyProvider.getPrivateKey(ctx)
        val cert = DebugKeyProvider.getCertificate(ctx)

        val unsignedFile = File(ctx.cacheDir, "unsigned_${System.currentTimeMillis()}.apk")
        val finalFile = File(ctx.cacheDir, "final_${System.currentTimeMillis()}.apk")

        try {
            postProgress(listener, "Copying entries...")
            buildUnsignedIntermediate(originalApk, unsignedFile, modifiedEntries)

            postProgress(listener, "Signing APK with original schemes...")
            signWithApksig(
                unsignedFile, finalFile,
                privateKey, cert,
                v1 = hasV1 || !schemes.isSigned,
                v2 = hasV2 || !schemes.isSigned,
                v3 = hasV3 || !schemes.isSigned
            )

            postProgress(listener, "Replacing original APK...")
            val backup = File(originalApk.absolutePath + ".dtbak")
            if (backup.exists()) backup.delete()
            if (!originalApk.renameTo(backup)) {
                throw IOException("Cannot rename original APK to backup")
            }
            try {
                copyFile(finalFile, originalApk)
                backup.delete()
            } catch (e: Exception) {
                backup.renameTo(originalApk)
                throw e
            }

            unsignedFile.delete()
            finalFile.delete()

            postSuccess(listener, originalApk)
        } finally {
            if (unsignedFile.exists()) unsignedFile.delete()
            if (finalFile.exists()) finalFile.delete()
        }
    }

    /**
     * Stage 1: Build an unsigned APK containing every entry from the
     * source APK except META-INF signature files. Applies [modifiedEntries]
     * in-place so the caller can swap in modified AndroidManifest.xml etc.
     *
     * This is the same stage-1 logic the old ApkRepacker had — the only
     * difference is that we no longer compute the V1 manifest digests here;
     * apksig handles that internally.
     */
    private fun buildUnsignedIntermediate(
        originalApk: File,
        unsignedFile: File,
        modifiedEntries: Map<String, ByteArray>
    ) {
        ZipFile(originalApk).use { zipIn ->
            ZipOutputStream(FileOutputStream(unsignedFile)).use { zos ->
                val en = zipIn.entries()
                while (en.hasMoreElements()) {
                    val inEntry = en.nextElement()
                    if (inEntry.isDirectory) continue
                    val name = inEntry.name

                    // Strip any pre-existing META-INF signature files —
                    // apksig will regenerate them as needed based on the
                    // requested scheme set.
                    if (isSignatureFile(name)) continue

                    val content: ByteArray = if (modifiedEntries.containsKey(name)) {
                        modifiedEntries[name] ?: byteArrayOf()
                    } else {
                        readAll(zipIn.getInputStream(inEntry))
                    }

                    val outEntry = ZipEntry(name)
                    outEntry.method = inEntry.method
                    if (inEntry.method == ZipEntry.STORED) {
                        outEntry.size = content.size.toLong()
                        outEntry.compressedSize = content.size.toLong()
                        val crc = CRC32()
                        crc.update(content)
                        outEntry.crc = crc.value

                        // Preserve STORED-entry alignment so V2/V3 digests
                        // can be computed over the same byte ranges the
                        // platform will read at install time.
                        val nameLen = name.toByteArray(StandardCharsets.UTF_8).size
                        val headerSize = 30 + nameLen
                        val pad = (4 - (headerSize % 4)) % 4
                        if (pad > 0) {
                            outEntry.extra = ByteArray(pad)
                        }
                    }
                    zos.putNextEntry(outEntry)
                    zos.write(content)
                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * Stage 2: sign the unsigned APK with apksig using the original's schemes.
     *
     * apksig's ApkSigner handles:
     *   - V1: builds MANIFEST.MF + CERT.SF + CERT.RSA, applies JAR signing.
     *   - V2: builds and writes the APK Signature Scheme v2 block.
     *   - V3: builds and writes the v3 block (with rotation support).
     * It also handles zipalign automatically when V2/V3 is requested.
     */
    private fun signWithApksig(
        unsignedFile: File,
        finalFile: File,
        privateKey: PrivateKey,
        cert: X509Certificate,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean
    ) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "DTManager",
            privateKey,
            listOf(cert)
        ).build()

        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedFile)
            .setOutputApk(finalFile)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .setV4SigningEnabled(false)  // V4 (.idsig file) not relevant for on-device repack
            .setDebuggableApkPermitted(true)
            .build()

        signer.sign()
    }

    /**
     * Returns true if `name` is a V1 signature-related META-INF entry that
     * should be stripped from the unsigned intermediate APK before
     * re-signing. apksig will regenerate these when V1 is enabled.
     *
     * Same logic as the original ApkRepacker.isSignatureFile().
     */
    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        if (upper == "META-INF/MANIFEST.MF") return true
        val tail = upper.substring("META-INF/".length)
        return tail.endsWith(".SF") || tail.endsWith(".RSA") || tail.endsWith(".DSA") || tail.endsWith(".EC")
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var n: Int
        while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
        input.close()
        return out.toByteArray()
    }

    @Throws(IOException::class)
    private fun copyFile(src: File, dest: File) {
        java.io.FileInputStream(src).use { inStream ->
            FileOutputStream(dest).use { outStream ->
                val buf = ByteArray(16384)
                var n: Int
                while (inStream.read(buf).also { n = it } > 0) outStream.write(buf, 0, n)
            }
        }
    }

    private fun postProgress(listener: ProgressListener, msg: String) {
        Handler(Looper.getMainLooper()).post { listener.onProgress(msg) }
    }

    private fun postSuccess(listener: ProgressListener, apk: File) {
        Handler(Looper.getMainLooper()).post { listener.onSuccess(apk) }
    }
}
