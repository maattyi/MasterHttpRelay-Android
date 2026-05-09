package com.velum.vpn.cert

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.velum.vpn.core.LogBus
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class MitmCa(
    private val context: Context,
    private val fileProviderAuthority: String = "com.velum.vpn.provider",
    private val caCommonName: String = "VelumVPN Root CA",
    private val caDirOverride: File? = null,
) {

    companion object {
        private val DEFAULT_PASSWORD = CharArray(0)
    }

    private val bcProvider: BouncyCastleProvider = BouncyCastleProvider()

    private val caDir: File = try {
        (caDirOverride ?: File(context.filesDir, "ca")).apply { mkdirs() }
    } catch (e: Throwable) {
        LogBus.e("Cert", "Cannot create CA dir, falling back to cacheDir", e)
        File(context.cacheDir, "ca").apply { runCatching { mkdirs() } }
    }
    private val caCertFile = File(caDir, "ca.crt")
    private val caKeyFile = File(caDir, "ca.key")

    private val cache = object : LinkedHashMap<String, SSLContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?): Boolean {
            return size > 256
        }
    }

    private val leafKeyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    private val caPair: Pair<X509Certificate, PrivateKey> = loadOrCreate()
    private val caCert: X509Certificate get() = caPair.first
    private val caKey: PrivateKey get() = caPair.second

    @Volatile private var lastLeaf: X509Certificate? = null

    fun exportCertificate(): Intent {
        return try {
            LogBus.i("Cert", "Exporting root CA certificate for installation")
            val certFile = File(context.cacheDir, "velum_ca.crt").apply {
                writeBytes(caCert.encoded)
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-x509-ca-cert")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to create cert export intent", e)
            try {
                android.security.KeyChain.createInstallIntent().apply {
                    putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, caCert.encoded)
                    putExtra(android.security.KeyChain.EXTRA_NAME, caCommonName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e2: Throwable) {
                LogBus.e("Cert", "Fallback cert export also failed", e2)
                Intent()
            }
        }
    }

    fun shareCertificate(): Intent {
        return try {
            LogBus.i("Cert", "Sharing root CA certificate file")
            val certFile = File(context.cacheDir, "velum_ca.crt").apply {
                writeBytes(caCert.encoded)
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to share certificate", e)
            Intent()
        }
    }

    @Synchronized
    fun contextFor(host: String): SSLContext {
        return try {
            cache.getOrPut(host) { sign(host) }
        } catch (e: Throwable) {
            LogBus.e("Cert", "contextFor($host) failed, retrying with fallback host", e)
            cache.getOrPut("fallback") { sign("fallback.local") }
        }
    }

    fun dumpLastLeaf(): Intent? {
        val leaf = lastLeaf ?: return null
        return try {
            val file = File(context.cacheDir, "last_leaf.crt").apply {
                writeText(
                    "-----BEGIN CERTIFICATE-----\n" +
                        android.util.Base64.encodeToString(leaf.encoded, android.util.Base64.DEFAULT) +
                        "-----END CERTIFICATE-----\n"
                )
            }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to dump leaf", e)
            null
        }
    }

    private fun loadOrCreate(): Pair<X509Certificate, PrivateKey> {
        if (caCertFile.exists() && caKeyFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12", bcProvider)
                ks.load(caCertFile.inputStream(), DEFAULT_PASSWORD)
                val key = ks.getKey("ca", DEFAULT_PASSWORD) as? PrivateKey
                val cert = ks.getCertificate("ca") as? X509Certificate
                if (key != null && cert != null) {
                    return cert to key
                }
            } catch (e: Throwable) {
                LogBus.w("Cert", "Failed to load existing CA, regenerating: ${e.message}")
                runCatching { caCertFile.delete() }
                runCatching { caKeyFile.delete() }
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=$caCommonName, O=VelumVPN")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000L)
        val serial = BigInteger(159, SecureRandom()).abs()

        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign)
            )
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()
                    .createSubjectKeyIdentifier(kp.public)
            )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(kp.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12", bcProvider).apply { load(null, null) }
        ks.setKeyEntry("ca", kp.private, DEFAULT_PASSWORD, arrayOf(cert))
        caCertFile.outputStream().use { ks.store(it, DEFAULT_PASSWORD) }
        runCatching { caKeyFile.writeText("STORED_IN_PKCS12") }
        return cert to kp.private
    }

    private fun sign(host: String): SSLContext {
        val kp = leafKeyPair
        val safeHost = host.ifBlank { "localhost" }.take(253)

        val subject = X500Name("CN=${safeHost.take(64)}")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 375L * 24 * 3600 * 1000L)

        val isIp = safeHost.matches(Regex("^[0-9.]+$")) || safeHost.contains(':')
        val san = if (isIp) GeneralName(GeneralName.iPAddress, safeHost)
        else GeneralName(GeneralName.dNSName, safeHost)

        val utils = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()

        val caSkiBytes: ByteArray? = try {
            caCert.getExtensionValue(Extension.subjectKeyIdentifier.id)?.let {
                org.bouncycastle.asn1.ASN1OctetString.getInstance(it).octets
            }
        } catch (_: Throwable) { null }

        val serial = BigInteger(159, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            X500Name.getInstance(caCert.subjectX500Principal.encoded),
            serial, notBefore, notAfter, subject, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.subjectAlternativeName, true, GeneralNames(san))
            .addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            )
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(kp.public)
            )

        if (caSkiBytes != null) {
            builder.addExtension(
                Extension.authorityKeyIdentifier, false,
                org.bouncycastle.asn1.x509.AuthorityKeyIdentifier(caSkiBytes)
            )
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(caKey)
        val leafCert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))

        lastLeaf = leafCert

        val ks = KeyStore.getInstance("PKCS12", bcProvider).apply { load(null, null) }
        ks.setKeyEntry("leaf", kp.private, DEFAULT_PASSWORD, arrayOf(leafCert, caCert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, DEFAULT_PASSWORD)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }
}
