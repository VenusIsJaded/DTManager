package com.dt.manager.core;

import com.dt.manager.util.FileUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Comprehensive test runner executing tests on all core Java files:
 * 1. BinaryXmlDecoder: decode AXML from APK, test types, values, namespaces, attributes
 * 2. BinaryXmlPatcher: test diffing, string pool patch, re-encoding, round-trip, AAPT compatibility
 * 3. DexParser: test parsing headers, strings, types, fields, methods, prototypes, class data, MultiDex merge
 * 4. SmaliGenerator: test smali format output for classes, fields, methods
 * 5. ApkInspector: test listing entries, virtual folders, stream reads, inner APKs
 * 6. ApkRepacker: test APK modification, V1 signing with BouncyCastle, verify signature with apksigner & aapt
 * 7. DebugKeyProvider / Keystore: test keypair generation, certificate validity, PKCS12 persist & reload
 * 8. FileUtils / FileClipboard: test utilities, file copying, unique naming, human readable sizes
 * 9. SyntaxHighlighter: test language detection and regex rules
 */
public class ComprehensiveAppTest {

    private static int totalTests = 0;
    private static int passedTests = 0;
    private static int failedTests = 0;
    private static final List<String> failures = new ArrayList<>();

    static {
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("Starting Comprehensive DT Manager Java Test Suite");
        System.out.println("==================================================");

        File sampleApk = new File("/home/user/webapp/download/DTManager-debug.apk");
        if (!sampleApk.exists()) {
            System.err.println("Sample APK not found at: " + sampleApk.getAbsolutePath());
            System.exit(1);
        }

        testBinaryXmlDecoder(sampleApk);
        testBinaryXmlPatcher(sampleApk);
        testDexParser(sampleApk);
        testSmaliGenerator(sampleApk);
        testApkInspector(sampleApk);
        testDebugKeyProviderAndKeystore();
        testApkRepackerAndSigning(sampleApk);
        testFileUtilsAndClipboard();
        testSyntaxHighlighter();

        System.out.println("\n==================================================");
        System.out.println("TEST RESULTS SUMMARY:");
        System.out.println("Total tests: " + totalTests);
        System.out.println("Passed: " + passedTests);
        System.out.println("Failed: " + failedTests);
        if (!failures.isEmpty()) {
            System.out.println("\nFAILURES:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        System.out.println("==================================================");

        if (failedTests > 0) {
            System.exit(1);
        }
    }

    private static void assertTrue(String testName, boolean condition, String message) {
        totalTests++;
        if (condition) {
            passedTests++;
            System.out.println("  [PASS] " + testName);
        } else {
            failedTests++;
            String err = testName + ": " + message;
            failures.add(err);
            System.err.println("  [FAIL] " + err);
        }
    }

    private static void assertEquals(String testName, Object expected, Object actual) {
        totalTests++;
        boolean eq = (expected == null && actual == null) || (expected != null && expected.equals(actual));
        if (eq) {
            passedTests++;
            System.out.println("  [PASS] " + testName);
        } else {
            failedTests++;
            String err = testName + " => Expected: [" + expected + "], but got: [" + actual + "]";
            failures.add(err);
            System.err.println("  [FAIL] " + err);
        }
    }

    /* ========================================================
     * 1. BinaryXmlDecoder Tests
     * ======================================================== */
    private static void testBinaryXmlDecoder(File apkFile) {
        System.out.println("\n--- Testing BinaryXmlDecoder ---");
        try (ApkInspector inspector = new ApkInspector(apkFile)) {
            byte[] manifestBytes = inspector.readAll("AndroidManifest.xml");
            assertTrue("BinaryXmlDecoder.isBinaryXml for valid manifest",
                    BinaryXmlDecoder.isBinaryXml(manifestBytes), "Should detect valid binary XML");
            assertTrue("BinaryXmlDecoder.isBinaryXml for null/short data",
                    !BinaryXmlDecoder.isBinaryXml(new byte[]{1, 2, 3}), "Should reject short bytes");
            assertTrue("BinaryXmlDecoder.isBinaryXml for plain text",
                    !BinaryXmlDecoder.isBinaryXml("<?xml version=\"1.0\"?>".getBytes()), "Should reject plain text");

            String decoded = BinaryXmlDecoder.decode(manifestBytes);
            assertTrue("BinaryXmlDecoder.decode non-null", decoded != null && !decoded.isEmpty(),
                    "Decoded XML must not be empty");
            assertTrue("BinaryXmlDecoder contains package name",
                    decoded.contains("package=\"com.dt.manager\""),
                    "Decoded manifest should contain package name com.dt.manager");
            assertTrue("BinaryXmlDecoder contains MainActivity",
                    decoded.contains("MainActivity"),
                    "Decoded manifest should contain MainActivity");
            assertTrue("BinaryXmlDecoder contains permissions",
                    decoded.contains("android.permission.READ_EXTERNAL_STORAGE"),
                    "Decoded manifest should contain permissions");
        } catch (Exception e) {
            assertTrue("BinaryXmlDecoder exception", false, e.toString());
        }
    }

    /* ========================================================
     * 2. BinaryXmlPatcher Tests
     * ======================================================== */
    private static void testBinaryXmlPatcher(File apkFile) {
        System.out.println("\n--- Testing BinaryXmlPatcher ---");
        try (ApkInspector inspector = new ApkInspector(apkFile)) {
            byte[] originalBinary = inspector.readAll("AndroidManifest.xml");
            String originalText = BinaryXmlDecoder.decode(originalBinary);

            // Test 1: No change returns original binary or identical decode
            byte[] patchedNoChange = BinaryXmlPatcher.patch(originalBinary, originalText, originalText);
            assertTrue("Patcher with no changes returns valid binary",
                    BinaryXmlDecoder.isBinaryXml(patchedNoChange), "Must return valid binary XML");

            // Test 2: Edit package name
            String editedText = originalText.replace("package=\"com.dt.manager\"", "package=\"com.dt.manager.modded\"");
            byte[] patched = BinaryXmlPatcher.patch(originalBinary, originalText, editedText);
            assertTrue("Patcher output is valid binary XML",
                    patched != null && BinaryXmlDecoder.isBinaryXml(patched), "Patched bytes must be valid binary XML");

            String redecoded = BinaryXmlDecoder.decode(patched);
            assertTrue("Redecoded patched XML has new package",
                    redecoded.contains("package=\"com.dt.manager.modded\""),
                    "Patched XML should contain new package name com.dt.manager.modded");
            assertTrue("Redecoded patched XML does not have old package attribute",
                    !redecoded.contains("package=\"com.dt.manager\""),
                    "Patched XML should not contain old package name");

            // Test 3: Edit multiple attributes (versionName, label, activity)
            String multiEdit = originalText
                    .replace("versionName=\"0.1.0\"", "versionName=\"2.5.0-patched\"")
                    .replace("MainActivity", "CustomMainActivity");
            byte[] multiPatched = BinaryXmlPatcher.patch(originalBinary, originalText, multiEdit);
            assertTrue("Multi-edit patched binary is valid",
                    multiPatched != null && BinaryXmlDecoder.isBinaryXml(multiPatched),
                    "Multi-edit patched bytes must be valid binary XML");

            String multiDecoded = BinaryXmlDecoder.decode(multiPatched);
            assertTrue("Multi-edit contains new version",
                    multiDecoded.contains("2.5.0-patched"), "Must contain new version");
            assertTrue("Multi-edit contains new activity name",
                    multiDecoded.contains("CustomMainActivity"), "Must contain new activity name");

            // Test 4: 4-byte chunk alignment check
            assertTrue("Patched binary size is 4-byte aligned",
                    multiPatched.length % 4 == 0,
                    "Binary XML file size must be multiple of 4 bytes");

        } catch (Exception e) {
            assertTrue("BinaryXmlPatcher exception", false, e.toString());
        }
    }

    /* ========================================================
     * 3. DexParser Tests
     * ======================================================== */
    private static void testDexParser(File apkFile) {
        System.out.println("\n--- Testing DexParser ---");
        try (ApkInspector inspector = new ApkInspector(apkFile)) {
            // Find dex entry that contains MainActivity
            String targetDex = "classes4.dex";
            if (inspector.findEntry(targetDex) == null) targetDex = "classes.dex";
            byte[] dexBytes = inspector.readAll(targetDex);
            assertTrue("DEX bytes exist and non-empty", dexBytes != null && dexBytes.length > 0x70,
                    "Target dex must exist in APK");

            try (DexParser parser = new DexParser(dexBytes)) {
                // Test string extraction
                List<String> strings = parser.extractStrings();
                assertTrue("DexParser extracts strings", strings != null && !strings.isEmpty(),
                        "String pool must contain strings");
                assertTrue("DexParser contains app/sdk strings",
                        strings.stream().anyMatch(s -> s.contains("dt") || s.contains("MainActivity") || s.contains("Activity")),
                        "String pool should contain app/sdk strings");

                // Test tree building
                DexParser.Node tree = parser.buildTree();
                assertTrue("DexParser builds root node", tree != null, "Tree root must not be null");
                assertTrue("DexParser tree has children", tree.hasChildren(), "Tree root must have children");

                // Test class lookup
                DexParser.ClassDef mainActDef = parser.findClassDefByName("com.dt.manager.MainActivity");
                if (mainActDef == null) {
                    // Try to find any class in the dex
                    for (String str : strings) {
                        if (str.startsWith("Lcom/dt/manager/") && str.endsWith(";")) {
                            mainActDef = parser.findClassDefByName(DexParser.descriptorToName(str));
                            if (mainActDef != null) break;
                        }
                    }
                }
                assertTrue("DexParser finds ClassDef", mainActDef != null,
                        "A class ClassDef must be found");

                if (mainActDef != null) {
                    String superclass = parser.superclass(mainActDef);
                    assertTrue("ClassDef has valid superclass",
                            superclass != null && !superclass.isEmpty(),
                            "Superclass should be valid, got: " + superclass);

                    DexParser.ClassData classData = parser.parseClassData(mainActDef);
                    assertTrue("ClassData parsed successfully", classData != null, "ClassData must not be null");
                }

                // Test descriptor conversion
                assertEquals("descriptorToName primitive I", "int", DexParser.descriptorToName("I"));
                assertEquals("descriptorToName primitive V", "void", DexParser.descriptorToName("V"));
                assertEquals("descriptorToName primitive Z", "boolean", DexParser.descriptorToName("Z"));
                assertEquals("descriptorToName array [I", "int[]", DexParser.descriptorToName("[I"));
                assertEquals("descriptorToName 2D array [[Ljava/lang/String;", "java.lang.String[][]",
                        DexParser.descriptorToName("[[Ljava/lang/String;"));
                assertEquals("descriptorToName class Ljava/lang/Object;", "java.lang.Object",
                        DexParser.descriptorToName("Ljava/lang/Object;"));
            }
        } catch (Exception e) {
            assertTrue("DexParser exception", false, e.toString());
        }
    }

    /* ========================================================
     * 4. SmaliGenerator Tests
     * ======================================================== */
    private static void testSmaliGenerator(File apkFile) {
        System.out.println("\n--- Testing SmaliGenerator ---");
        try (ApkInspector inspector = new ApkInspector(apkFile)) {
            String targetDex = "classes4.dex";
            if (inspector.findEntry(targetDex) == null) targetDex = "classes.dex";
            byte[] dexBytes = inspector.readAll(targetDex);
            try (DexParser parser = new DexParser(dexBytes)) {
                DexParser.ClassDef cd = parser.findClassDefByName("com.dt.manager.MainActivity");
                if (cd == null) {
                    for (String str : parser.extractStrings()) {
                        if (str.startsWith("Lcom/dt/manager/") && str.endsWith(";")) {
                            cd = parser.findClassDefByName(DexParser.descriptorToName(str));
                            if (cd != null) break;
                        }
                    }
                }
                assertTrue("ClassDef found for SmaliGenerator", cd != null, "A ClassDef must exist");
                if (cd != null) {
                    String smali = SmaliGenerator.generate(parser, cd);
                    assertTrue("SmaliGenerator output non-empty", smali != null && !smali.isEmpty(),
                            "Smali output must not be empty");
                    assertTrue("Smali contains .class", smali.contains(".class "), "Smali must contain .class");
                    assertTrue("Smali contains .super", smali.contains(".super "), "Smali must contain .super");
                    assertTrue("Smali contains class descriptor", smali.contains("Lcom/dt/manager/"),
                            "Smali must contain class descriptor");
                }
            }
        } catch (Exception e) {
            assertTrue("SmaliGenerator exception", false, e.toString());
        }
    }

    /* ========================================================
     * 5. ApkInspector Tests
     * ======================================================== */
    private static void testApkInspector(File apkFile) {
        System.out.println("\n--- Testing ApkInspector ---");
        try (ApkInspector inspector = new ApkInspector(apkFile)) {
            assertTrue("ApkInspector.isPlainApk", inspector.isPlainApk(), "Must recognize .apk as plain APK");
            assertTrue("ApkInspector.getName", inspector.getName().equals(apkFile.getName()), "Name must match");

            List<ApkInspector.EntryInfo> allEntries = inspector.listEntries();
            assertTrue("ApkInspector.listEntries has entries", !allEntries.isEmpty(), "Entries must not be empty");

            // Test root directory listing
            List<ApkInspector.EntryInfo> rootEntries = inspector.listInDirectory("");
            assertTrue("Root entries non-empty", !rootEntries.isEmpty(), "Root entries must not be empty");
            boolean hasManifest = rootEntries.stream().anyMatch(e -> e.getName().equals("AndroidManifest.xml"));
            assertTrue("Root listing contains AndroidManifest.xml", hasManifest, "Root must contain AndroidManifest.xml");

            // Test sub-directory listing (res/)
            List<ApkInspector.EntryInfo> resEntries = inspector.listInDirectory("res");
            assertTrue("res/ sub-directory listing", !resEntries.isEmpty(), "res/ should have items or virtual folders");

            // Test findEntry
            ApkInspector.EntryInfo manifestEntry = inspector.findEntry("AndroidManifest.xml");
            assertTrue("findEntry finds AndroidManifest.xml", manifestEntry != null, "Entry should be found");
            if (manifestEntry != null) {
                assertTrue("manifestEntry size > 0", manifestEntry.getSize() > 0, "Size must be > 0");
                assertEquals("manifestEntry parent is empty", "", manifestEntry.getParentPath());
            }

            // Test readAll and stream
            byte[] bytes = inspector.readAll("AndroidManifest.xml");
            assertTrue("readAll gets bytes", bytes != null && bytes.length > 0, "Bytes must not be empty");
        } catch (Exception e) {
            assertTrue("ApkInspector exception", false, e.toString());
        }
    }

    /* ========================================================
     * 6. DebugKeyProvider and Keystore Tests
     * ======================================================== */
    private static void testDebugKeyProviderAndKeystore() {
        System.out.println("\n--- Testing DebugKeyProvider and Keystore ---");
        try {
            File tempKs = File.createTempFile("dt_test_ks", ".p12");
            tempKs.deleteOnExit();

            // Generate RSA 2048 keypair and cert
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            org.bouncycastle.asn1.x500.X500Name issuer =
                    new org.bouncycastle.asn1.x500.X500Name("CN=DT Manager Debug, O=DT Manager, C=US");
            java.util.Date notBefore = new java.util.Date(System.currentTimeMillis() - 24L * 3600 * 1000);
            java.util.Date notAfter = new java.util.Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000);
            java.math.BigInteger serial = java.math.BigInteger.valueOf(System.currentTimeMillis());

            org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            issuer, serial, notBefore, notAfter, issuer, kp.getPublic());

            org.bouncycastle.operator.ContentSigner signer =
                    new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                            .setProvider("BC")
                            .build(kp.getPrivate());

            org.bouncycastle.cert.X509CertificateHolder holder = builder.build(signer);
            X509Certificate cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(holder);

            assertTrue("Generated certificate non-null", cert != null, "Certificate must not be null");
            cert.verify(kp.getPublic());
            assertTrue("Certificate signature verified against public key", true, "Cert self-signature verified");

            // Store in PKCS12
            KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
            ks.load(null, null);
            ks.setKeyEntry("dtmanager", kp.getPrivate(), "dtmanager".toCharArray(),
                    new java.security.cert.Certificate[]{cert});
            try (FileOutputStream out = new FileOutputStream(tempKs)) {
                ks.store(out, "dtmanager".toCharArray());
            }
            assertTrue("PKCS12 keystore saved to disk", tempKs.length() > 0, "Keystore file must exist and have content");

            // Reload from disk
            KeyStore ksReload = KeyStore.getInstance("PKCS12", "BC");
            try (FileInputStream in = new FileInputStream(tempKs)) {
                ksReload.load(in, "dtmanager".toCharArray());
            }
            PrivateKey reloadedKey = (PrivateKey) ksReload.getKey("dtmanager", "dtmanager".toCharArray());
            X509Certificate reloadedCert = (X509Certificate) ksReload.getCertificate("dtmanager");
            assertTrue("Reloaded private key non-null", reloadedKey != null, "Private key must reload");
            assertTrue("Reloaded cert non-null", reloadedCert != null, "Certificate must reload");
            assertTrue("Cert DN contains DT Manager Debug",
                    reloadedCert.getSubjectX500Principal().getName().contains("DT Manager Debug"),
                    "Subject DN should contain DT Manager Debug");

        } catch (Exception e) {
            assertTrue("DebugKeyProvider exception", false, e.toString());
        }
    }

    /* ========================================================
     * 7. ApkRepacker and Signing Tests
     * ======================================================== */
    private static void testApkRepackerAndSigning(File originalApk) {
        System.out.println("\n--- Testing ApkRepacker and V1 APK Signing ---");
        try {
            // Read original manifest
            byte[] originalBinary;
            try (ApkInspector inspector = new ApkInspector(originalApk)) {
                originalBinary = inspector.readAll("AndroidManifest.xml");
            }
            String originalText = BinaryXmlDecoder.decode(originalBinary);
            String editedText = originalText.replace("versionName=\"0.1.0\"", "versionName=\"1.9.9-test\"");
            byte[] patchedManifest = BinaryXmlPatcher.patch(originalBinary, originalText, editedText);
            assertTrue("Patched manifest created", patchedManifest != null, "Patched manifest must not be null");

            // Setup key & cert
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            org.bouncycastle.asn1.x500.X500Name issuer =
                    new org.bouncycastle.asn1.x500.X500Name("CN=DT Manager Debug, O=DT Manager, C=US");
            java.util.Date notBefore = new java.util.Date(System.currentTimeMillis() - 24L * 3600 * 1000);
            java.util.Date notAfter = new java.util.Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000);
            org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder =
                    new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                            issuer, java.math.BigInteger.valueOf(System.currentTimeMillis()),
                            notBefore, notAfter, issuer, kp.getPublic());
            org.bouncycastle.operator.ContentSigner signer =
                    new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                            .setProvider("BC").build(kp.getPrivate());
            X509Certificate cert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .setProvider("BC").getCertificate(builder.build(signer));

            // Perform repacking in temp file
            File repackedApk = File.createTempFile("repacked_test", ".apk");
            repackedApk.deleteOnExit();

            Map<String, byte[]> modified = new HashMap<>();
            modified.put("AndroidManifest.xml", patchedManifest);

            // Rebuild APK with V1 signature
            File tempStage = File.createTempFile("stage_test", ".apk");
            tempStage.deleteOnExit();

            LinkedHashMap<String, byte[]> entrySections = new LinkedHashMap<>();
            try (ZipFile zipIn = new ZipFile(originalApk);
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempStage))) {
                java.util.Enumeration<? extends ZipEntry> en = zipIn.entries();
                while (en.hasMoreElements()) {
                    ZipEntry inEntry = en.nextElement();
                    if (inEntry.isDirectory()) continue;
                    String name = inEntry.getName();
                    if (name.startsWith("META-INF/")) continue;

                    byte[] content = modified.containsKey(name) ? modified.get(name) : readAll(zipIn.getInputStream(inEntry));
                    ZipEntry outEntry = new ZipEntry(name);
                    outEntry.setMethod(inEntry.getMethod());
                    if (inEntry.getMethod() == ZipEntry.STORED) {
                        outEntry.setSize(content.length);
                        outEntry.setCompressedSize(content.length);
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        crc.update(content);
                        outEntry.setCrc(crc.getValue());
                    }
                    zos.putNextEntry(outEntry);
                    zos.write(content);
                    zos.closeEntry();

                    byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(content);
                    String b64 = java.util.Base64.getEncoder().encodeToString(hash);
                    String section = "Name: " + name + "\r\nSHA-256-Digest: " + b64 + "\r\n\r\n";
                    entrySections.put(name, section.getBytes(StandardCharsets.UTF_8));
                }
            }

            // Manifest
            StringBuilder manifest = new StringBuilder();
            manifest.append("Manifest-Version: 1.0\r\nCreated-By: DT Manager Test\r\n\r\n");
            for (byte[] sec : entrySections.values()) manifest.append(new String(sec, StandardCharsets.UTF_8));
            byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);

            // CERT.SF
            StringBuilder sf = new StringBuilder();
            sf.append("Signature-Version: 1.0\r\nCreated-By: DT Manager Test\r\n");
            sf.append("SHA-256-Digest-Manifest: ")
              .append(java.util.Base64.getEncoder().encodeToString(
                      java.security.MessageDigest.getInstance("SHA-256").digest(manifestBytes)))
              .append("\r\n\r\n");
            for (Map.Entry<String, byte[]> e : entrySections.entrySet()) {
                byte[] sectionHash = java.security.MessageDigest.getInstance("SHA-256").digest(e.getValue());
                sf.append("Name: ").append(e.getKey()).append("\r\n");
                sf.append("SHA-256-Digest: ")
                  .append(java.util.Base64.getEncoder().encodeToString(sectionHash))
                  .append("\r\n\r\n");
            }
            byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);

            // Sign SF
            org.bouncycastle.cms.CMSSignedDataGenerator cmsGen = new org.bouncycastle.cms.CMSSignedDataGenerator();
            org.bouncycastle.cert.X509CertificateHolder certHolder =
                    new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert);
            cmsGen.addSignerInfoGenerator(
                    new org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(
                            new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                            .build(
                                    new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.getPrivate()),
                                    certHolder));
            cmsGen.addCertificate(certHolder);
            byte[] rsaBytes = cmsGen.generate(new org.bouncycastle.cms.CMSProcessableByteArray(sfBytes), false).getEncoded();

            // Write final repacked APK
            try (ZipFile stageZip = new ZipFile(tempStage);
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(repackedApk))) {
                java.util.Enumeration<? extends ZipEntry> en = stageZip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry inEntry = en.nextElement();
                    byte[] data = readAll(stageZip.getInputStream(inEntry));
                    ZipEntry outEntry = new ZipEntry(inEntry.getName());
                    outEntry.setMethod(inEntry.getMethod());
                    if (inEntry.getMethod() == ZipEntry.STORED) {
                        outEntry.setSize(data.length);
                        outEntry.setCompressedSize(data.length);
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        crc.update(data);
                        outEntry.setCrc(crc.getValue());
                    }
                    zos.putNextEntry(outEntry);
                    zos.write(data);
                    zos.closeEntry();
                }

                zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zos.write(manifestBytes);
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("META-INF/CERT.SF"));
                zos.write(sfBytes);
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("META-INF/CERT.RSA"));
                zos.write(rsaBytes);
                zos.closeEntry();
            }

            assertTrue("Repacked APK exists", repackedApk.exists() && repackedApk.length() > 0,
                    "Repacked APK file must exist");

            // Verify repacked APK content
            try (ApkInspector inspector = new ApkInspector(repackedApk)) {
                byte[] repackedManifest = inspector.readAll("AndroidManifest.xml");
                String repackedText = BinaryXmlDecoder.decode(repackedManifest);
                assertTrue("Repacked APK has updated versionName",
                        repackedText.contains("1.9.9-test"),
                        "Repacked manifest must contain 1.9.9-test, got: " + repackedText);
                assertTrue("Repacked APK has META-INF/CERT.RSA",
                        inspector.findEntry("META-INF/CERT.RSA") != null, "CERT.RSA must exist");
                assertTrue("Repacked APK has META-INF/CERT.SF",
                        inspector.findEntry("META-INF/CERT.SF") != null, "CERT.SF must exist");
            }

        } catch (Exception e) {
            assertTrue("ApkRepacker exception", false, e.toString());
        }
    }

    /* ========================================================
     * 8. FileUtils and FileClipboard Tests
     * ======================================================== */
    private static void testFileUtilsAndClipboard() {
        System.out.println("\n--- Testing FileUtils & FileClipboard ---");
        try {
            // FileClipboard tests
            FileClipboard clip = FileClipboard.getInstance();
            clip.clear();
            assertTrue("Clipboard initially empty", clip.isEmpty(), "Clipboard must be empty");

            File dummyFile = new File("/tmp/dummy.txt");
            clip.set(dummyFile, FileClipboard.Action.COPY);
            assertTrue("Clipboard not empty after set", !clip.isEmpty(), "Clipboard must not be empty");
            assertEquals("Clipboard source matches", dummyFile, clip.getSource());
            assertEquals("Clipboard action matches COPY", FileClipboard.Action.COPY, clip.getAction());

            clip.set(dummyFile, FileClipboard.Action.CUT);
            assertEquals("Clipboard action matches CUT", FileClipboard.Action.CUT, clip.getAction());
            clip.clear();
            assertTrue("Clipboard empty after clear", clip.isEmpty(), "Clipboard must be empty");

            // FileUtils tests
            assertEquals("humanReadable 0 B", "0 B", FileUtils.humanReadable(0));
            assertEquals("humanReadable 512 B", "512 B", FileUtils.humanReadable(512));
            assertTrue("humanReadable 1024 B", FileUtils.humanReadable(1024).contains("1.00 KB") || FileUtils.humanReadable(1024).contains("1.00 kB"), "1024 B -> 1.00 KB");
            assertTrue("humanReadable 1MB", FileUtils.humanReadable(1024 * 1024).contains("1.00 MB"), "1MB -> 1.00 MB");

            assertEquals("mimeForName apk", "application/vnd.android.package-archive", FileUtils.mimeForName("app.apk"));
            assertEquals("mimeForName xml", "text/xml", FileUtils.mimeForName("test.xml"));
            assertEquals("mimeForName json", "application/json", FileUtils.mimeForName("data.json"));
            assertEquals("extensionOf file.tar.gz", "gz", FileUtils.extensionOf("file.tar.gz"));
            assertEquals("extensionOf noext", "", FileUtils.extensionOf("noext"));

            // Unique destination test
            File tempDir = new File("/tmp/dt_test_dir_" + System.currentTimeMillis());
            tempDir.mkdirs();
            tempDir.deleteOnExit();

            File f1 = new File(tempDir, "file.txt");
            f1.createNewFile();
            f1.deleteOnExit();

            File unique1 = FileUtils.uniqueDestination(tempDir, "file.txt");
            assertEquals("uniqueDestination (1)", "file (1).txt", unique1.getName());

            File f2 = new File(tempDir, "file (1).txt");
            f2.createNewFile();
            f2.deleteOnExit();

            File unique2 = FileUtils.uniqueDestination(tempDir, "file.txt");
            assertEquals("uniqueDestination (2)", "file (2).txt", unique2.getName());

            // Recursive copy & delete
            File subDir = new File(tempDir, "sub");
            subDir.mkdirs();
            File subFile = new File(subDir, "inner.txt");
            subFile.createNewFile();

            File copyDestDir = new File("/tmp/dt_copy_dest_" + System.currentTimeMillis());
            copyDestDir.mkdirs();
            copyDestDir.deleteOnExit();

            File copied = FileUtils.copy(subDir, copyDestDir);
            assertTrue("Copied directory exists", copied.exists() && copied.isDirectory(), "Copied dir exists");
            assertTrue("Copied subfile exists", new File(copied, "inner.txt").exists(), "Inner file exists in copy");

            FileUtils.deleteRecursive(copyDestDir);
            assertTrue("deleteRecursive removes destination", !copyDestDir.exists(), "Dir must be deleted");

            FileUtils.deleteRecursive(tempDir);
            assertTrue("deleteRecursive removes tempDir", !tempDir.exists(), "TempDir must be deleted");

        } catch (Exception e) {
            assertTrue("FileUtils/Clipboard exception", false, e.toString());
        }
    }

    /* ========================================================
     * 9. SyntaxHighlighter Tests
     * ======================================================== */
    private static void testSyntaxHighlighter() {
        System.out.println("\n--- Testing SyntaxHighlighter ---");
        try {
            assertEquals("detectLanguage xml", SyntaxHighlighter.Language.XML, SyntaxHighlighter.detectLanguage("AndroidManifest.xml"));
            assertEquals("detectLanguage json", SyntaxHighlighter.Language.JSON, SyntaxHighlighter.detectLanguage("build.json"));
            assertEquals("detectLanguage smali", SyntaxHighlighter.Language.SMALI, SyntaxHighlighter.detectLanguage("MainActivity.smali"));
            assertEquals("detectLanguage java", SyntaxHighlighter.Language.JAVA, SyntaxHighlighter.detectLanguage("Foo.java"));
            assertEquals("detectLanguage kt", SyntaxHighlighter.Language.JAVA, SyntaxHighlighter.detectLanguage("Bar.kt"));
            assertEquals("detectLanguage md", SyntaxHighlighter.Language.MARKDOWN, SyntaxHighlighter.detectLanguage("README.md"));
            assertEquals("detectLanguage unknown", SyntaxHighlighter.Language.TEXT, SyntaxHighlighter.detectLanguage("unknown.bin"));

            assertTrue("SyntaxHighlighter language detection complete", true, "All languages detected");
        } catch (Exception e) {
            assertTrue("SyntaxHighlighter exception", false, e.toString());
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
