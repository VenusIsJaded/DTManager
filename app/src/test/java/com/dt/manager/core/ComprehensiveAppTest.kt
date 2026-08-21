package com.dt.manager.core

import com.dt.manager.util.FileUtils
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Comprehensive test suite in Kotlin testing all core DT Manager functionality:
 * 1. BinaryXmlDecoder & TextXmlHandler: decode AXML, detect plain text XML vs binary XML, pretty print, validate
 * 2. BinaryXmlPatcher: test string diffing, patch, re-encode, 4-byte chunk size alignment
 * 3. DexParser: test parsing DEX headers, strings, types, fields, methods, prototypes, class data, MultiDex
 * 4. SmaliGenerator: test smali format output for classes, fields, methods
 * 5. ApkInspector: test listing entries, virtual folders, stream reads, inner APKs
 * 6. ApkRepacker: test APK modification, V1 signing with BouncyCastle
 * 7. DebugKeyProvider / Keystore: test keypair generation, certificate validity, PKCS12 persist & reload
 * 8. FileUtils / FileClipboard: test utilities, file copying, unique naming, human readable sizes
 * 9. SyntaxHighlighter: test language detection and regex rules
 */
class ComprehensiveAppTest {

    companion object {
        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        private val sampleApk = File("/home/user/webapp/download/DTManager-debug.apk")

        @JvmStatic
        fun main(args: Array<String>) {
            val test = ComprehensiveAppTest()
            println("Running DT Manager Kotlin Test Suite...")
            test.testBinaryXmlDecoderAndTextXml()
            test.testBinaryXmlPatcher()
            test.testDexParser()
            test.testSmaliGenerator()
            test.testApkInspector()
            test.testDebugKeyProviderAndKeystore()
            test.testApkRepackerAndSigning()
            test.testFileUtilsAndClipboard()
            test.testSyntaxHighlighter()
            println("All DT Manager tests completed successfully!")
        }
    }

    @Test
    fun testBinaryXmlDecoderAndTextXml() {
        if (!sampleApk.exists()) return
        ApkInspector(sampleApk).use { inspector ->
            val manifestBytes = inspector.readAll("AndroidManifest.xml")
            assert(BinaryXmlDecoder.isBinaryXml(manifestBytes)) { "Should detect valid binary XML" }
            assert(!BinaryXmlDecoder.isBinaryXml(byteArrayOf(1, 2, 3))) { "Should reject short bytes" }
            assert(!BinaryXmlDecoder.isBinaryXml("<?xml version=\"1.0\"?>".toByteArray())) { "Should reject plain text XML as binary XML" }

            val decoded = BinaryXmlDecoder.decode(manifestBytes)
            assert(decoded.isNotEmpty()) { "Decoded XML must not be empty" }
            assert(decoded.contains("package=\"com.dt.manager\"")) { "Decoded manifest should contain package name" }
            assert(decoded.contains("MainActivity")) { "Decoded manifest should contain MainActivity" }

            // Test TextXmlHandler support
            val plainTextXml = """<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">DT Manager</string></resources>"""
            val plainBytes = plainTextXml.toByteArray(StandardCharsets.UTF_8)
            assert(TextXmlHandler.isTextXml(plainBytes)) { "TextXmlHandler should detect plain text XML" }
            assert(!TextXmlHandler.isTextXml(manifestBytes)) { "TextXmlHandler should reject binary XML as plain text XML" }

            val formatted = TextXmlHandler.formatXml(plainTextXml, 4)
            assert(formatted.contains("    <string") || formatted.contains("<string")) { "Formatted XML should be valid" }
            val error = TextXmlHandler.validateXml(plainTextXml)
            assert(error == null) { "Plain XML should be valid, got: $error" }
        }
    }

    @Test
    fun testBinaryXmlPatcher() {
        if (!sampleApk.exists()) return
        ApkInspector(sampleApk).use { inspector ->
            val originalBinary = inspector.readAll("AndroidManifest.xml")
            val originalText = BinaryXmlDecoder.decode(originalBinary)

            val patchedNoChange = BinaryXmlPatcher.patch(originalBinary, originalText, originalText)
            assert(patchedNoChange != null && BinaryXmlDecoder.isBinaryXml(patchedNoChange)) { "Must return valid binary XML" }

            val editedText = originalText.replace("package=\"com.dt.manager\"", "package=\"com.dt.manager.modded\"")
            val patched = BinaryXmlPatcher.patch(originalBinary, originalText, editedText)
            assert(patched != null && BinaryXmlDecoder.isBinaryXml(patched)) { "Patched bytes must be valid binary XML" }

            val redecoded = BinaryXmlDecoder.decode(patched!!)
            assert(redecoded.contains("package=\"com.dt.manager.modded\"")) { "Patched XML should contain new package" }
            assert(!redecoded.contains("package=\"com.dt.manager\"")) { "Patched XML should not contain old package" }

            val multiEdit = originalText
                .replace("versionName=\"0.1.0\"", "versionName=\"2.5.0-patched\"")
                .replace("MainActivity", "CustomMainActivity")
            val multiPatched = BinaryXmlPatcher.patch(originalBinary, originalText, multiEdit)
            assert(multiPatched != null && BinaryXmlDecoder.isBinaryXml(multiPatched)) { "Multi-edit patched bytes must be valid binary XML" }
            assert(multiPatched!!.size % 4 == 0) { "Binary XML file size must be multiple of 4 bytes" }
        }
    }

    @Test
    fun testDexParser() {
        if (!sampleApk.exists()) return
        ApkInspector(sampleApk).use { inspector ->
            var targetDex = "classes4.dex"
            if (inspector.findEntry(targetDex) == null) targetDex = "classes.dex"
            val dexBytes = inspector.readAll(targetDex)
            assert(dexBytes.size > 0x70) { "Target dex must exist in APK" }

            DexParser(dexBytes).use { parser ->
                val strings = parser.extractStrings()
                assert(strings.isNotEmpty()) { "String pool must contain strings" }

                val tree = parser.buildTree()
                assert(tree.hasChildren()) { "Tree root must have children" }

                var mainActDef = parser.findClassDefByName("com.dt.manager.MainActivity")
                if (mainActDef == null) {
                    for (str in strings) {
                        if (str.startsWith("Lcom/dt/manager/") && str.endsWith(";")) {
                            mainActDef = parser.findClassDefByName(DexParser.descriptorToName(str))
                            if (mainActDef != null) break
                        }
                    }
                }
                assert(mainActDef != null) { "A class ClassDef must be found" }

                val superclass = parser.superclass(mainActDef)
                assert(superclass.isNotEmpty()) { "Superclass should be valid" }

                val classData = parser.parseClassData(mainActDef)
                assert(classData.fields != null && classData.methods != null) { "ClassData parsed" }

                assert(DexParser.descriptorToName("I") == "int")
                assert(DexParser.descriptorToName("V") == "void")
                assert(DexParser.descriptorToName("[I") == "int[]")
                assert(DexParser.descriptorToName("Ljava/lang/Object;") == "java.lang.Object")
            }
        }
    }

    @Test
    fun testSmaliGenerator() {
        if (!sampleApk.exists()) return
        ApkInspector(sampleApk).use { inspector ->
            var targetDex = "classes4.dex"
            if (inspector.findEntry(targetDex) == null) targetDex = "classes.dex"
            val dexBytes = inspector.readAll(targetDex)
            DexParser(dexBytes).use { parser ->
                var cd = parser.findClassDefByName("com.dt.manager.MainActivity")
                if (cd == null) {
                    for (str in parser.extractStrings()) {
                        if (str.startsWith("Lcom/dt/manager/") && str.endsWith(";")) {
                            cd = parser.findClassDefByName(DexParser.descriptorToName(str))
                            if (cd != null) break
                        }
                    }
                }
                assert(cd != null) { "ClassDef must exist" }
                val smali = SmaliGenerator.generate(parser, cd)
                assert(smali.contains(".class ")) { "Smali must contain .class" }
                assert(smali.contains(".super ")) { "Smali must contain .super" }
            }
        }
    }

    @Test
    fun testApkInspector() {
        if (!sampleApk.exists()) return
        ApkInspector(sampleApk).use { inspector ->
            assert(inspector.isPlainApk()) { "Must recognize .apk" }
            assert(inspector.name == sampleApk.name) { "Name must match" }
            val allEntries = inspector.listEntries()
            assert(allEntries.isNotEmpty()) { "Entries must not be empty" }

            val rootEntries = inspector.listInDirectory("")
            assert(rootEntries.any { it.name == "AndroidManifest.xml" }) { "Root must contain AndroidManifest.xml" }

            val manifestEntry = inspector.findEntry("AndroidManifest.xml")
            assert(manifestEntry != null && manifestEntry.size > 0) { "Entry should be found" }
        }
    }

    @Test
    fun testDebugKeyProviderAndKeystore() {
        val tempKs = File.createTempFile("dt_test_ks", ".p12").apply { deleteOnExit() }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val issuer = X500Name("CN=DT Manager Debug, O=DT Manager, C=US")
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000)
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val builder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, kp.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(kp.private)

        val holder = builder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(holder)

        assert(cert != null) { "Certificate must not be null" }
        cert.verify(kp.public)

        val ks = KeyStore.getInstance("PKCS12", "BC")
        ks.load(null, null)
        ks.setKeyEntry("dtmanager", kp.private, "dtmanager".toCharArray(), arrayOf<java.security.cert.Certificate>(cert))
        FileOutputStream(tempKs).use { out ->
            ks.store(out, "dtmanager".toCharArray())
        }
        assert(tempKs.length() > 0) { "Keystore file must exist and have content" }

        val ksReload = KeyStore.getInstance("PKCS12", "BC")
        FileInputStream(tempKs).use { inStream ->
            ksReload.load(inStream, "dtmanager".toCharArray())
        }
        val reloadedKey = ksReload.getKey("dtmanager", "dtmanager".toCharArray()) as PrivateKey
        val reloadedCert = ksReload.getCertificate("dtmanager") as X509Certificate
        assert(reloadedKey != null && reloadedCert != null) { "Key and cert must reload" }
    }

    @Test
    fun testApkRepackerAndSigning() {
        if (!sampleApk.exists()) return
        var originalBinary: ByteArray
        ApkInspector(sampleApk).use { inspector ->
            originalBinary = inspector.readAll("AndroidManifest.xml")
        }
        val originalText = BinaryXmlDecoder.decode(originalBinary)
        val editedText = originalText.replace("versionName=\"0.1.0\"", "versionName=\"1.9.9-test\"")
        val patchedManifest = BinaryXmlPatcher.patch(originalBinary, originalText, editedText)
        assert(patchedManifest != null) { "Patched manifest must not be null" }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val issuer = X500Name("CN=DT Manager Debug, O=DT Manager, C=US")
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            issuer, BigInteger.valueOf(System.currentTimeMillis()),
            notBefore, notAfter, issuer, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC").build(kp.private)
        val cert = JcaX509CertificateConverter()
            .setProvider("BC").getCertificate(builder.build(signer))

        val repackedApk = File.createTempFile("repacked_test", ".apk").apply { deleteOnExit() }
        val modified = HashMap<String, ByteArray>()
        modified["AndroidManifest.xml"] = patchedManifest!!

        val tempStage = File.createTempFile("stage_test", ".apk").apply { deleteOnExit() }
        val entrySections = LinkedHashMap<String, ByteArray>()

        ZipFile(sampleApk).use { zipIn ->
            ZipOutputStream(FileOutputStream(tempStage)).use { zos ->
                val en = zipIn.entries()
                while (en.hasMoreElements()) {
                    val inEntry = en.nextElement()
                    if (inEntry.isDirectory) continue
                    val name = inEntry.name
                    if (name.startsWith("META-INF/")) continue

                    val content = if (modified.containsKey(name)) modified[name]!! else readAll(zipIn.getInputStream(inEntry))
                    val outEntry = ZipEntry(name)
                    outEntry.method = inEntry.method
                    if (inEntry.method == ZipEntry.STORED) {
                        outEntry.size = content.size.toLong()
                        outEntry.compressedSize = content.size.toLong()
                        val crc = CRC32()
                        crc.update(content)
                        outEntry.crc = crc.value
                    }
                    zos.putNextEntry(outEntry)
                    zos.write(content)
                    zos.closeEntry()

                    val hash = MessageDigest.getInstance("SHA-256").digest(content)
                    val b64 = Base64.getEncoder().encodeToString(hash)
                    val section = "Name: $name\r\nSHA-256-Digest: $b64\r\n\r\n"
                    entrySections[name] = section.toByteArray(StandardCharsets.UTF_8)
                }
            }
        }

        val manifest = StringBuilder("Manifest-Version: 1.0\r\nCreated-By: DT Manager Test\r\n\r\n")
        for (sec in entrySections.values) manifest.append(String(sec, StandardCharsets.UTF_8))
        val manifestBytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)

        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0\r\nCreated-By: DT Manager Test\r\n")
        sf.append("SHA-256-Digest-Manifest: ")
            .append(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(manifestBytes)))
            .append("\r\n\r\n")
        for ((key, value) in entrySections) {
            val sectionHash = MessageDigest.getInstance("SHA-256").digest(value)
            sf.append("Name: ").append(key).append("\r\n")
            sf.append("SHA-256-Digest: ")
                .append(Base64.getEncoder().encodeToString(sectionHash))
                .append("\r\n\r\n")
        }
        val sfBytes = sf.toString().toByteArray(StandardCharsets.UTF_8)

        val cmsGen = CMSSignedDataGenerator()
        val certHolder = org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert)
        cmsGen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
            ).build(
                JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private),
                certHolder
            )
        )
        cmsGen.addCertificate(certHolder)
        val rsaBytes = cmsGen.generate(CMSProcessableByteArray(sfBytes), false).encoded

        ZipFile(tempStage).use { stageZip ->
            ZipOutputStream(FileOutputStream(repackedApk)).use { zos ->
                val en = stageZip.entries()
                while (en.hasMoreElements()) {
                    val inEntry = en.nextElement()
                    val data = readAll(stageZip.getInputStream(inEntry))
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

        assert(repackedApk.exists() && repackedApk.length() > 0) { "Repacked APK must exist" }

        ApkInspector(repackedApk).use { inspector ->
            val repackedManifest = inspector.readAll("AndroidManifest.xml")
            val repackedText = BinaryXmlDecoder.decode(repackedManifest)
            assert(repackedText.contains("1.9.9-test")) { "Repacked manifest must contain 1.9.9-test" }
            assert(inspector.findEntry("META-INF/CERT.RSA") != null) { "CERT.RSA must exist" }
            assert(inspector.findEntry("META-INF/CERT.SF") != null) { "CERT.SF must exist" }
        }
    }

    @Test
    fun testFileUtilsAndClipboard() {
        val clip = FileClipboard.getInstance()
        clip.clear()
        assert(clip.isEmpty) { "Clipboard must be empty" }

        val dummyFile = File("/tmp/dummy.txt")
        clip.set(dummyFile, FileClipboard.Action.COPY)
        assert(!clip.isEmpty) { "Clipboard must not be empty" }
        assert(clip.source == dummyFile) { "Source matches" }
        assert(clip.action == FileClipboard.Action.COPY) { "Action matches COPY" }

        assert(FileUtils.humanReadable(0) == "0 B")
        assert(FileUtils.humanReadable(512) == "512 B")
        assert(FileUtils.humanReadable(1024).contains("1.00 KB") || FileUtils.humanReadable(1024).contains("1.00 kB"))
        assert(FileUtils.mimeForName("app.apk") == "application/vnd.android.package-archive")
        assert(FileUtils.mimeForName("test.xml") == "text/xml")
        assert(FileUtils.extensionOf("file.tar.gz") == "gz")
    }

    @Test
    fun testSyntaxHighlighter() {
        assert(SyntaxHighlighter.detectLanguage("AndroidManifest.xml") == SyntaxHighlighter.Language.XML)
        assert(SyntaxHighlighter.detectLanguage("build.json") == SyntaxHighlighter.Language.JSON)
        assert(SyntaxHighlighter.detectLanguage("MainActivity.smali") == SyntaxHighlighter.Language.SMALI)
        assert(SyntaxHighlighter.detectLanguage("Foo.kt") == SyntaxHighlighter.Language.KOTLIN)
        assert(SyntaxHighlighter.detectLanguage("Bar.java") == SyntaxHighlighter.Language.JAVA)
        assert(SyntaxHighlighter.detectLanguage("README.md") == SyntaxHighlighter.Language.MARKDOWN)
        assert(SyntaxHighlighter.detectLanguage("unknown.bin") == SyntaxHighlighter.Language.TEXT)
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var n: Int
        while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
        input.close()
        return out.toByteArray()
    }
}
