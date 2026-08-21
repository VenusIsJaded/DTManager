package com.dt.manager.core;

import android.content.Context;
import android.util.Base64;

import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rebuilds an APK with modified entries and re-signs it with V1
 * (jarsigner-style) signature using DT Manager's debug key.
 *
 * The resulting APK:
 *  - Has all original entries (except META-INF signature files)
 *  - Has the modified entries replaced with the new content
 *  - Has a fresh MANIFEST.MF, CERT.SF, CERT.RSA (V1 signature)
 *  - Has NO V2/V3 signature (those live in the APK Signing Block,
 *    which is not emitted by java.util.zip)
 *
 * The original APK file on disk is replaced atomically.
 */
public class ApkRepacker {

    public interface ProgressListener {
        void onProgress(String message);
        void onSuccess(File repackedApk);
        void onError(String message);
    }

    private ApkRepacker() {}

    public static void repack(final Context ctx, final File originalApk,
                              final Map<String, byte[]> modifiedEntries,
                              final ProgressListener listener) {
        new Thread(() -> {
            try {
                doRepack(ctx, originalApk, modifiedEntries, listener);
            } catch (final Exception e) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        listener.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }).start();
    }

    private static void doRepack(Context ctx, File originalApk,
                                  Map<String, byte[]> modifiedEntries,
                                  ProgressListener listener) throws Exception {
        postProgress(listener, "Loading signing key...");
        PrivateKey privateKey = DebugKeyProvider.getPrivateKey(ctx);
        X509Certificate cert = DebugKeyProvider.getCertificate(ctx);

        File tempFile = new File(ctx.getCacheDir(),
                "repack_" + System.currentTimeMillis() + ".apk");
        File finalFile = new File(ctx.getCacheDir(),
                "final_" + System.currentTimeMillis() + ".apk");

        try {
            // Step 1: First pass — copy entries to temp file, building MANIFEST.MF
            // We need two passes because MANIFEST.MF hashes all entries, but
            // we want to stream rather than hold all entries in memory.
            postProgress(listener, "Copying entries...");

            // We'll write entries to tempFile while collecting manifest section data.
            LinkedHashMap<String, byte[]> entrySections = new LinkedHashMap<>();

            try (ZipFile zipIn = new ZipFile(originalApk);
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempFile))) {

                Enumeration<? extends ZipEntry> en = zipIn.entries();
                while (en.hasMoreElements()) {
                    ZipEntry inEntry = en.nextElement();
                    if (inEntry.isDirectory()) continue;
                    String name = inEntry.getName();

                    // Skip existing signature files — we'll write fresh ones
                    if (isSignatureFile(name)) continue;

                    byte[] content;
                    if (modifiedEntries.containsKey(name)) {
                        content = modifiedEntries.get(name);
                    } else {
                        content = readAll(zipIn.getInputStream(inEntry));
                    }

                    // Write the entry
                    ZipEntry outEntry = new ZipEntry(name);
                    outEntry.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(outEntry);
                    zos.write(content);
                    zos.closeEntry();

                    // Build manifest section for this entry
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
                    String b64 = Base64.encodeToString(hash, Base64.NO_WRAP);
                    String section = "Name: " + name + "\r\nSHA-256-Digest: " + b64 + "\r\n\r\n";
                    entrySections.put(name, section.getBytes(StandardCharsets.UTF_8));
                }
            }

            // Step 2: Build MANIFEST.MF
            postProgress(listener, "Building manifest...");
            StringBuilder manifest = new StringBuilder();
            manifest.append("Manifest-Version: 1.0\r\n");
            manifest.append("Created-By: DT Manager 0.1\r\n");
            manifest.append("\r\n");
            for (byte[] section : entrySections.values()) {
                manifest.append(new String(section, StandardCharsets.UTF_8));
            }
            byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);

            // Step 3: Build CERT.SF
            postProgress(listener, "Building signature file...");
            StringBuilder sf = new StringBuilder();
            sf.append("Signature-Version: 1.0\r\n");
            sf.append("Created-By: DT Manager 0.1\r\n");
            sf.append("SHA-256-Digest-Manifest: ")
              .append(Base64.encodeToString(
                      MessageDigest.getInstance("SHA-256").digest(manifestBytes),
                      Base64.NO_WRAP))
              .append("\r\n\r\n");

            for (Map.Entry<String, byte[]> e : entrySections.entrySet()) {
                byte[] sectionHash = MessageDigest.getInstance("SHA-256").digest(e.getValue());
                sf.append("Name: ").append(e.getKey()).append("\r\n");
                sf.append("SHA-256-Digest: ")
                  .append(Base64.encodeToString(sectionHash, Base64.NO_WRAP))
                  .append("\r\n\r\n");
            }
            byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);

            // Step 4: Sign CERT.SF using Bouncy Castle CMS (PKCS7 SignedData)
            postProgress(listener, "Signing APK...");
            byte[] rsaBytes = signSf(sfBytes, privateKey, cert);

            // Step 5: Build the final APK — copy all entries from tempFile, then add META-INF
            postProgress(listener, "Writing signed APK...");
            try (ZipFile tempZip = new ZipFile(tempFile);
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(finalFile))) {

                Enumeration<? extends ZipEntry> en = tempZip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry inEntry = en.nextElement();
                    if (inEntry.isDirectory()) continue;
                    zos.putNextEntry(new ZipEntry(inEntry.getName()));
                    byte[] data = readAll(tempZip.getInputStream(inEntry));
                    zos.write(data);
                    zos.closeEntry();
                }

                // Add the signature files
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

            // Step 6: Replace the original APK atomically
            postProgress(listener, "Replacing original APK...");
            File backup = new File(originalApk.getAbsolutePath() + ".dtbak");
            if (backup.exists()) backup.delete();
            if (!originalApk.renameTo(backup)) {
                throw new IOException("Cannot rename original APK to backup");
            }
            try {
                copyFile(finalFile, originalApk);
                // success — delete the backup
                backup.delete();
            } catch (Exception e) {
                // restore from backup
                backup.renameTo(originalApk);
                throw e;
            }

            // Clean up temp files
            tempFile.delete();
            finalFile.delete();

            postSuccess(listener, originalApk);
        } finally {
            // Best effort cleanup
            if (tempFile.exists()) tempFile.delete();
            if (finalFile.exists()) finalFile.delete();
        }
    }

    /** Sign the SF bytes with the private key and return DER-encoded PKCS7 SignedData. */
    private static byte[] signSf(byte[] sfBytes, PrivateKey privateKey, X509Certificate cert)
            throws Exception {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        org.bouncycastle.cert.X509CertificateHolder certHolder =
                new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert);
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                        new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                        .build(
                                new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(privateKey),
                                certHolder));
        gen.addCertificate(certHolder);
        CMSSignedData signed = gen.generate(new CMSProcessableByteArray(sfBytes), false);
        return signed.getEncoded();
    }

    /** True if the given entry name is a JAR signature file we should strip on repack. */
    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase();
        // Manifest itself is regenerated, so strip the existing one
        if (upper.equals("META-INF/MANIFEST.MF")) return true;
        // Old signature files
        if (upper.startsWith("META-INF/")) {
            String tail = upper.substring("META-INF/".length());
            if (tail.endsWith(".SF")) return true;
            if (tail.endsWith(".RSA")) return true;
            if (tail.endsWith(".DSA")) return true;
            if (tail.endsWith(".EC")) return true;
        }
        return false;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void postProgress(final ProgressListener listener, final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> listener.onProgress(msg));
    }

    private static void postSuccess(final ProgressListener listener, final File apk) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> listener.onSuccess(apk));
    }
}
