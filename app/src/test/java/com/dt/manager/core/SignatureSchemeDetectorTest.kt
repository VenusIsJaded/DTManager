package com.dt.manager.core

import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Regression test for the signature-scheme detection bug.
 *
 * Reproduces the original failure: `ApkInfo.detectSignatureScheme()` returned
 * "V1+V2+V3" whenever any V1 META-INF entry existed (regardless of the
 * actual APK Signing Block contents), and "None" whenever V1 was absent
 * (even when V2 or V3 was present).
 *
 * This test signs a tiny in-memory APK with each scheme combination using
 * Google's official `apksig` library, then asserts that
 * [SignatureSchemeDetector.detect] reports exactly the schemes we signed with.
 *
 * The apksig-signed APKs themselves are authoritative — they're produced by
 * the same library Android Studio uses — so any disagreement between
 * apksig and our detector is a real bug in our detector.
 */
class SignatureSchemeDetectorTest {

    @Test
    fun testDetectsV1Only() {
        val apk = buildAndSignApk(v1 = true, v2 = false, v3 = false)
        val detected = SignatureSchemeDetector.detect(apk)
        assert(detected.v1) { "V1 should be detected" }
        assert(!detected.v2) { "V2 should NOT be detected" }
        assert(!detected.v3) { "V3 should NOT be detected" }
        assert(detected.format() == "V1") { "Expected 'V1', got '${detected.format()}'" }
    }

    @Test
    fun testDetectsV2Only() {
        val apk = buildAndSignApk(v1 = false, v2 = true, v3 = false)
        val detected = SignatureSchemeDetector.detect(apk)
        assert(!detected.v1) { "V1 should NOT be detected" }
        assert(detected.v2) { "V2 should be detected" }
        assert(!detected.v3) { "V3 should NOT be detected" }
        assert(detected.format() == "V2") { "Expected 'V2', got '${detected.format()}'" }
    }

    @Test
    fun testDetectsV3Only() {
        val apk = buildAndSignApk(v1 = false, v2 = false, v3 = true)
        val detected = SignatureSchemeDetector.detect(apk)
        assert(!detected.v1) { "V1 should NOT be detected" }
        assert(!detected.v2) { "V2 should NOT be detected" }
        assert(detected.v3) { "V3 should be detected" }
        assert(detected.format() == "V3") { "Expected 'V3', got '${detected.format()}'" }
    }

    @Test
    fun testDetectsV1V2() {
        val apk = buildAndSignApk(v1 = true, v2 = true, v3 = false)
        val detected = SignatureSchemeDetector.detect(apk)
        assert(detected.v1) { "V1 should be detected" }
        assert(detected.v2) { "V2 should be detected" }
        assert(!detected.v3) { "V3 should NOT be detected" }
        assert(detected.format() == "V1+V2") { "Expected 'V1+V2', got '${detected.format()}'" }
    }

    @Test
    fun testDetectsV2V3() {
        val apk = buildAndSignApk(v1 = false, v2 = true, v3 = true)
        val detected = SignatureSchemeDetector.detect(apk)
        assert(!detected.v1) { "V1 should NOT be detected" }
        assert(detected.v2) { "V2 should be detected" }
        assert(detected.v3) { "V3 should be detected" }
        assert(detected.format() == "V2+V3") { "Expected 'V2+V3', got '${detected.format()}'" }
    }

    @Test
    fun testDetectsV1V2V3() {
        val apk = buildAndSignApk(v1 = true, v2 = true, v3 = true)
        val detected = SignatureSchemeDetector.detect(apk)
        assert(detected.v1) { "V1 should be detected" }
        assert(detected.v2) { "V2 should be detected" }
        assert(detected.v3) { "V3 should be detected" }
        assert(detected.format() == "V1+V2+V3") { "Expected 'V1+V2+V3', got '${detected.format()}'" }
    }

    @Test
    fun testDetectsNoneOnUnsignedApk() {
        val apk = buildUnsignedApk()
        val detected = SignatureSchemeDetector.detect(apk)
        assert(!detected.isSigned) { "Unsigned APK should have no schemes detected" }
        assert(detected.format() == "None") { "Expected 'None', got '${detected.format()}'" }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Build a tiny APK (AndroidManifest.xml + a small file) and sign it with
     * apksig using the requested scheme set. Returns the signed APK file.
     */
    private fun buildAndSignApk(v1: Boolean, v2: Boolean, v3: Boolean): File {
        val unsigned = buildUnsignedApk()
        val signed = File.createTempFile("signed-test", ".apk").apply { deleteOnExit() }

        val (privateKey, cert) = generateDebugKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "DTManagerTest", privateKey, listOf(cert)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(30)  // bypass manifest parsing; P+ requires V2+
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .setV4SigningEnabled(false)
            .setDebuggableApkPermitted(true)
            .build()
            .sign()

        unsigned.delete()
        return signed
    }

    /** Build an unsigned minimal APK with a tiny AndroidManifest.xml. */
    private fun buildUnsignedApk(): File {
        val out = File.createTempFile("unsigned-test", ".apk").apply { deleteOnExit() }
        // Minimal AndroidManifest.xml as a binary AXML stub.
        // apksig doesn't care about the content; it only signs the ZIP entries.
        val manifestBytes = byteArrayOf(
            0x03, 0x00, 0x08, 0x00, // chunk type 0x0003 (XML), header size 8
            0x40, 0x00, 0x00, 0x00, // chunk size 64
        ) + ByteArray(64 - 8) { 0 }
        val helloBytes = "Hello, world!".toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(FileOutputStream(out)).use { zos ->
            // Helper: write a STORED entry (uncompressed, with explicit CRC/size).
            fun writeStored(name: String, data: ByteArray) {
                val crc = CRC32().apply { update(data) }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    setCrc(crc.value)
                }
                zos.putNextEntry(entry); zos.write(data); zos.closeEntry()
            }
            writeStored("AndroidManifest.xml", manifestBytes)
            writeStored("assets/hello.txt", helloBytes)
        }
        return out
    }

    private fun generateDebugKey(): Pair<PrivateKey, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=DT Manager Test, O=DT Manager, C=US")
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(System.currentTimeMillis()),
            notBefore, notAfter, name, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
        return Pair(kp.private, cert)
    }

    companion object {
        @BeforeClass @JvmStatic
        fun setupBc() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        @AfterClass @JvmStatic
        fun cleanup() {
            // Temp files auto-delete on JVM exit via deleteOnExit().
        }
    }
}
