package com.dt.manager.core

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Lazily generates and persists an RSA 2048-bit keypair with a
 * self-signed X.509 certificate. Used to re-sign APKs after they
 * are modified by the editor.
 */
object DebugKeyProvider {

    private const val KEYSTORE_FILE = "dtmanager_signing.p12"
    private const val PASSWORD = "dtmanager"
    private const val ALIAS = "dtmanager"

    @Volatile
    private var cachedPrivateKey: PrivateKey? = null
    @Volatile
    private var cachedCert: X509Certificate? = null

    init {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    @Synchronized
    @Throws(Exception::class)
    fun getPrivateKey(ctx: Context): PrivateKey {
        ensureLoaded(ctx)
        return cachedPrivateKey!!
    }

    @Synchronized
    @Throws(Exception::class)
    fun getCertificate(ctx: Context): X509Certificate {
        ensureLoaded(ctx)
        return cachedCert!!
    }

    @Synchronized
    @Throws(Exception::class)
    private fun ensureLoaded(ctx: Context) {
        if (cachedPrivateKey != null && cachedCert != null) return

        val ksFile = File(ctx.filesDir, KEYSTORE_FILE)
        val ks = KeyStore.getInstance("PKCS12", "BC")
        if (ksFile.exists()) {
            FileInputStream(ksFile).use { inStream ->
                ks.load(inStream, PASSWORD.toCharArray())
            }
        } else {
            ks.load(null, null)
            generateAndStore(ks, ksFile)
        }
        cachedPrivateKey = ks.getKey(ALIAS, PASSWORD.toCharArray()) as PrivateKey
        cachedCert = ks.getCertificate(ALIAS) as X509Certificate
    }

    @Throws(Exception::class)
    private fun generateAndStore(ks: KeyStore, ksFile: File) {
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
        val cert = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(holder)

        ks.setKeyEntry(
            ALIAS, kp.private, PASSWORD.toCharArray(),
            arrayOf<java.security.cert.Certificate>(cert)
        )

        ksFile.parentFile?.mkdirs()
        FileOutputStream(ksFile).use { out ->
            ks.store(out, PASSWORD.toCharArray())
        }
    }
}
