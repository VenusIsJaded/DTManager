package com.dt.manager.core;

import android.content.Context;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Lazily generates and persists an RSA 2048-bit keypair with a
 * self-signed X.509 certificate. Used to re-sign APKs after they
 * are modified by the editor.
 *
 * The keypair is stored in PKCS12 format in the app's private
 * storage, so it persists across launches. Every APK signed by
 * DT Manager will be signed with the same key — meaning if you
 * update a signed APK, the new signature will match the old one
 * (provided the original was also signed with DT Manager's key).
 *
 * For APKs originally signed with someone else's key, the new
 * signature will differ from the original — installation as an
 * update will fail, but a fresh install will work.
 */
public final class DebugKeyProvider {

    private static final String KEYSTORE_FILE = "dtmanager_signing.p12";
    private static final String PASSWORD = "dtmanager";
    private static final String ALIAS = "dtmanager";

    private static PrivateKey cachedPrivateKey;
    private static X509Certificate cachedCert;

    static {
        // Install Bouncy Castle as a JCA provider (replacing Android's old
        // built-in BC if present).
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    private DebugKeyProvider() {}

    public static synchronized PrivateKey getPrivateKey(Context ctx) throws Exception {
        ensureLoaded(ctx);
        return cachedPrivateKey;
    }

    public static synchronized X509Certificate getCertificate(Context ctx) throws Exception {
        ensureLoaded(ctx);
        return cachedCert;
    }

    private static void ensureLoaded(Context ctx) throws Exception {
        if (cachedPrivateKey != null && cachedCert != null) return;

        File ksFile = new File(ctx.getFilesDir(), KEYSTORE_FILE);
        KeyStore ks = KeyStore.getInstance("PKCS12", "BC");
        if (ksFile.exists()) {
            try (FileInputStream in = new FileInputStream(ksFile)) {
                ks.load(in, PASSWORD.toCharArray());
            }
        } else {
            ks.load(null, null);
            generateAndStore(ks, ksFile);
        }
        cachedPrivateKey = (PrivateKey) ks.getKey(ALIAS, PASSWORD.toCharArray());
        cachedCert = (X509Certificate) ks.getCertificate(ALIAS);
    }

    private static void generateAndStore(KeyStore ks, File ksFile) throws Exception {
        // Generate RSA 2048-bit keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Build self-signed X.509 v3 cert valid for 30 years
        X500Name issuer = new X500Name("CN=DT Manager Debug, O=DT Manager, C=US");
        Date notBefore = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
        Date notAfter = new Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(kp.getPrivate());

        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(holder);

        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASSWORD.toCharArray(),
                new java.security.cert.Certificate[]{cert});

        try (FileOutputStream out = new FileOutputStream(ksFile)) {
            ks.store(out, PASSWORD.toCharArray());
        }
    }
}
