package com.dt.manager.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rebuilds an APK with modified entries and re-signs it with V1
 * (jarsigner-style) signature using DT Manager's debug key.
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
        postProgress(listener, "Loading signing key...")
        val privateKey = DebugKeyProvider.getPrivateKey(ctx)
        val cert = DebugKeyProvider.getCertificate(ctx)

        val tempFile = File(ctx.cacheDir, "repack_${System.currentTimeMillis()}.apk")
        val finalFile = File(ctx.cacheDir, "final_${System.currentTimeMillis()}.apk")

        try {
            postProgress(listener, "Copying entries...")
            val entrySections = LinkedHashMap<String, ByteArray>()

            ZipFile(originalApk).use { zipIn ->
                ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                    val en = zipIn.entries()
                    while (en.hasMoreElements()) {
                        val inEntry = en.nextElement()
                        if (inEntry.isDirectory) continue
                        val name = inEntry.name

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

                        val hash = MessageDigest.getInstance("SHA-256").digest(content)
                        val b64 = Base64.encodeToString(hash, Base64.NO_WRAP)
                        val section = "Name: $name\r\nSHA-256-Digest: $b64\r\n\r\n"
                        entrySections[name] = section.toByteArray(StandardCharsets.UTF_8)
                    }
                }
            }

            postProgress(listener, "Building manifest...")
            val manifest = StringBuilder()
            manifest.append("Manifest-Version: 1.0\r\n")
            manifest.append("Created-By: DT Manager 0.1\r\n")
            manifest.append("\r\n")
            for (section in entrySections.values) {
                manifest.append(String(section, StandardCharsets.UTF_8))
            }
            val manifestBytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)

            postProgress(listener, "Building signature file...")
            val sf = StringBuilder()
            sf.append("Signature-Version: 1.0\r\n")
            sf.append("Created-By: DT Manager 0.1\r\n")
            val manifestDigest = MessageDigest.getInstance("SHA-256").digest(manifestBytes)
            sf.append("SHA-256-Digest-Manifest: ")
                .append(Base64.encodeToString(manifestDigest, Base64.NO_WRAP))
                .append("\r\n\r\n")

            for ((key, value) in entrySections) {
                val sectionHash = MessageDigest.getInstance("SHA-256").digest(value)
                sf.append("Name: ").append(key).append("\r\n")
                sf.append("SHA-256-Digest: ")
                    .append(Base64.encodeToString(sectionHash, Base64.NO_WRAP))
                    .append("\r\n\r\n")
            }
            val sfBytes = sf.toString().toByteArray(StandardCharsets.UTF_8)

            postProgress(listener, "Signing APK...")
            val rsaBytes = signSf(sfBytes, privateKey, cert)

            postProgress(listener, "Writing signed APK...")
            ZipFile(tempFile).use { tempZip ->
                ZipOutputStream(FileOutputStream(finalFile)).use { zos ->
                    val en = tempZip.entries()
                    while (en.hasMoreElements()) {
                        val inEntry = en.nextElement()
                        if (inEntry.isDirectory) continue
                        val data = readAll(tempZip.getInputStream(inEntry))
                        val outEntry = ZipEntry(inEntry.name)
                        outEntry.method = inEntry.method
                        if (inEntry.method == ZipEntry.STORED) {
                            outEntry.size = data.size.toLong()
                            outEntry.compressedSize = data.size.toLong()
                            val crc = CRC32()
                            crc.update(data)
                            outEntry.crc = crc.value
                        }
                        zos.putNextEntry(outEntry)
                        zos.write(data)
                        zos.closeEntry()
                    }

                    zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    zos.write(manifestBytes)
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("META-INF/CERT.SF"))
                    zos.write(sfBytes)
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
                    zos.write(rsaBytes)
                    zos.closeEntry()
                }
            }

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

            tempFile.delete()
            finalFile.delete()

            postSuccess(listener, originalApk)
        } finally {
            if (tempFile.exists()) tempFile.delete()
            if (finalFile.exists()) finalFile.delete()
        }
    }

    private fun signSf(sfBytes: ByteArray, privateKey: PrivateKey, cert: X509Certificate): ByteArray {
        val gen = CMSSignedDataGenerator()
        val certHolder = org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert)
        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
            ).build(
                JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(privateKey),
                certHolder
            )
        )
        gen.addCertificate(certHolder)
        val signed = gen.generate(CMSProcessableByteArray(sfBytes), false)
        return signed.encoded
    }

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
        FileInputStream(src).use { inStream ->
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
