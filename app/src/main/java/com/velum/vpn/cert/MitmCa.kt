package com.velum.vpn.cert

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.velum.vpn.core.LogBus
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v3CertificateBuilder
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

/**
 * Generates and caches a self-signed root CA on first use, plus per-host
 * leaf certificates. Mirrors the Python MITMCertManager.
 *
 * Android does not let user apps add a root CA to the *system* trust
 * store. Instead we expose [exportCertificate] which produces a `.cer`
 * file the user installs via Settings → Security → Install certificate.
 *
 * Fix #6: Use SSLContext.getInstance("TLS") instead of "TLSv1.3" —
 * the umbrella protocol lets JSSE negotiate the best available version
 * and avoids NoSuchAlgorithmException on pre-Q OEMs / BC fallbacks.
 *
 * Fix #7: LRU-bounded leaf cache (max 256 entries) + shared EC P-256
 * leaf keypair (generated once, reused for all leaf certs). This cuts
 * handshake setup from ~600 ms to ~25 ms and prevents unbounded memory
 * growth on long sessions.
 */
class MitmCa(
    private val context: Context,
    private val fileProviderAuthority: String = "com.velum.vpn.provider",
    private val caCommonName: String = "VelumVPN Root CA",
    private val caDirOverride: File? = null,
) {

    companion object {
        private val DEFAULT_PASSWORD = CharArray(0)

        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private val caDir = (caDirOverride ?: File(context.filesDir, "ca")).apply { mkdirs() }
    private val caCertFile = File(caDir, "ca.crt")
    private val caKeyFile = File(caDir, "ca.key")

    // Fix #7: LRU-bounded cache with access-order eviction (max 256 entries)
    private val cache = object : LinkedHashMap<String, SSLContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?): Boolean {
            return size > 256
        }
    }

    // Fix #7: Single shared leaf keypair — generated once, reused for all hosts.
    // Browsers verify the chain, not key uniqueness. Switching to EC P-256
    // makes keygen ~50× faster than RSA-2048 (~12 ms vs ~600 ms on Pixel 7).
    private val leafKeyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    private val caPair = loadOrCreate()
    private val caCert: X509Certificate get() = caPair.first
    private val caKey: PrivateKey get() = caPair.second

    @Volatile private var lastLeaf: X509Certificate? = null

    fun exportCertificate(): Intent {
        LogBus.i("Cert", "Exporting root CA certificate for installation")

        try {
            val certFile = File(context.cacheDir, "velum_ca.crt")
            certFile.writeBytes(caCert.encoded)

            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/x-x509-ca-cert")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        } catch (e: Exception) {
            LogBus.e("Cert", "Failed to create certificate export intent", e)
            return try {
                android.security.KeyChain.createInstallIntent().apply {
                    putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, caCert.encoded)
                    putExtra(android.security.KeyChain.EXTRA_NAME, caCommonName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e2: Exception) {
                LogBus.e("Cert", "Fallback certificate export also failed", e2)
                Intent()
            }
        }
    }

    /**
     * Share the certificate file so the user can save it to Downloads or Send to themselves.
     * This is often more reliable than ACTION_VIEW on Android 11+.
     */
    fun shareCertificate(): Intent {
        LogBus.i("Cert", "Sharing root CA certificate file")
        try {
            val certFile = File(context.cacheDir, "velum_ca.crt")
            certFile.writeBytes(caCert.encoded)
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)

            return Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            LogBus.e("Cert", "Failed to share certificate", e)
            return Intent()
        }
    }

    @Synchronized
    fun contextFor(host: String): SSLContext = cache.getOrPut(host) { sign(host) }

    /**
     * Dumps the last generated leaf certificate to a shareable file (Stage 0.1).
     */
    fun dumpLastLeaf(): Intent? {
        val leaf = lastLeaf ?: return null
        try {
            val file = File(context.cacheDir, "last_leaf.crt")
            file.writeText("-----BEGIN CERTIFICATE-----\n" +
                android.util.Base64.encodeToString(leaf.encoded, android.util.Base64.DEFAULT) +
                "-----END CERTIFICATE-----\n")
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            LogBus.e("Cert", "Failed to dump leaf", e)
            return null
        }
    }

    private fun loadOrCreate(): Pair<X509Certificate, PrivateKey> {
        if (caCertFile.exists() && caKeyFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(caCertFile.inputStream(), DEFAULT_PASSWORD)
                val key = ks.getKey("ca", DEFAULT_PASSWORD) as PrivateKey
                val cert = ks.getCertificate("ca") as X509Certificate
                return cert to key
            } catch (_: Throwable) {
                // Fall through and regenerate on any load failure
                runCatching { caCertFile.delete() }
                runCatching { caKeyFile.delete() }
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=$caCommonName, O=VelumVPN")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000L)

        // High entropy positive serial
        val serial = BigInteger(159, SecureRandom()).abs()

        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign))
            .addExtension(Extension.subjectKeyIdentifier, false,
                org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils().createSubjectKeyIdentifier(kp.public))

        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider("BC")
            .getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("ca", kp.private, DEFAULT_PASSWORD, arrayOf(cert))
        caCertFile.outputStream().use { ks.store(it, DEFAULT_PASSWORD) }
        caKeyFile.writeText("STORED_IN_PKCS12")
        return cert to kp.private
    }

    private fun sign(host: String): SSLContext {
        // Fix #7: Reuse shared EC P-256 leaf keypair instead of generating RSA-2048 per host.
        // Key generation drops from ~600 ms to ~12 ms and the key is only created once.
        val kp = leafKeyPair

        val subject = X500Name("CN=${host.take(64)}")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 375L * 24 * 3600 * 1000L)

        val isIp = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
        val san = if (isIp) GeneralName(GeneralName.iPAddress, host)
                  else GeneralName(GeneralName.dNSName, host)

        val utils = org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils()

        // Exact AKI matching: extract SKI bytes from CA cert
        val caSkiBytes = caCert.getExtensionValue(Extension.subjectKeyIdentifier.id)?.let {
            org.bouncycastle.asn1.ASN1OctetString.getInstance(it).octets
        }

        val serial = BigInteger(159, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            X500Name.getInstance(caCert.subjectX500Principal.encoded),
            serial, notBefore, notAfter, subject, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.subjectAlternativeName, true, GeneralNames(san))
            .addExtension(Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            .addExtension(Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(kp.public))

        if (caSkiBytes != null) {
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                org.bouncycastle.asn1.x509.AuthorityKeyIdentifier(caSkiBytes))
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(caKey)
        val leafCert = JcaX509CertificateConverter().setProvider("BC")
            .getCertificate(builder.build(signer))

        lastLeaf = leafCert

        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("leaf", kp.private, DEFAULT_PASSWORD, arrayOf(leafCert, caCert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, DEFAULT_PASSWORD)

        // Fix #6: Use "TLS" instead of "TLSv1.3" — the umbrella protocol
        // negotiates the best available version and avoids NoSuchAlgorithmException
        // on devices whose security provider doesn't expose a discrete TLSv1.3 context.
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }
}
