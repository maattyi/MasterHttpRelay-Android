package com.velum.vpn.cert

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.velum.vpn.core.LogBus
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.LinkedHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Velum MITM CA — RFC 5280 / CA/Browser Forum compliant.
 *
 * Root CA constraints (validated by the device's CertPathValidator before
 * any leaf is honoured as a trust anchor):
 *   - X.509 v3 with subject == issuer (self-signed)
 *   - Signature: SHA-256 with RSA-3072
 *   - basicConstraints   = critical, CA:TRUE
 *   - keyUsage           = critical, keyCertSign | cRLSign | digitalSignature
 *   - subjectKeyIdentifier present (for AKI linkage by leaves)
 *   - 10-year validity
 *
 * Leaf cert constraints (per CA/B Forum and BoringSSL):
 *   - basicConstraints      = critical, CA:FALSE
 *   - keyUsage              = critical, digitalSignature | keyEncipherment
 *   - extKeyUsage           = serverAuth (1.3.6.1.5.5.7.3.1)
 *   - subjectAlternativeName= critical, derived from SNI (DNS or iPAddress)
 *   - authorityKeyIdentifier matching the CA's subjectKeyIdentifier exactly
 *   - 30-day validity (well below the 398-day CA/B max; future-proof for the
 *     industry trend toward 47-day leaves by 2029)
 *   - EC P-256 key (shared, generated once — leaf identity is in the cert,
 *     not the key, and EC is ~50× faster than RSA-2048 on every keygen)
 *
 * BC provider safety: we own a private BouncyCastleProvider INSTANCE and
 * pass it explicitly into every JCA call. Looking up "BC" by string returns
 * Android's stub provider and crashes with NoClassDefFoundError.
 */
class MitmCa(
    private val context: Context,
    private val fileProviderAuthority: String = "com.velum.vpn.provider",
    private val caCommonName: String = "VelumVPN Interception Root CA",
    private val caOrganization: String = "VelumVPN Security Architecture",
    private val caDirOverride: File? = null,
) {

    data class SaveResult(
        val success: Boolean,
        val displayPath: String,
        val message: String,
        val detail: String? = null,
    )

    companion object {
        private val DEFAULT_PASSWORD = CharArray(0)
        private const val CERT_FILE_NAME = "velum_ca.crt"
        private const val DOWNLOADS_SUBDIR = "Velum"
        private const val LEAF_VALIDITY_DAYS = 30L
        private const val CA_VALIDITY_YEARS = 10L
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
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SSLContext>?,
        ): Boolean = size > 256
    }

    private val leafKeyPair: KeyPair by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    private val caPair: Pair<X509Certificate, PrivateKey> = loadOrCreate()
    private val caCert: X509Certificate get() = caPair.first
    private val caKey: PrivateKey get() = caPair.second

    @Volatile private var lastLeaf: X509Certificate? = null

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the SHA-256 SPKI fingerprint (Base64, no padding) of the root
     * CA's public key. Used for the Chrome --ignore-certificate-errors-spki-list
     * advanced bypass workflow on rooted/dev devices, and for diagnostics.
     */
    fun rootSpkiSha256(): String {
        return try {
            val spki = caCert.publicKey.encoded
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(spki)
            android.util.Base64.encodeToString(
                digest,
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
        } catch (e: Throwable) {
            LogBus.e("Cert", "Failed to compute SPKI fingerprint", e)
            ""
        }
    }

    /** Subject DN + SHA-256 fingerprint of the root, for the diagnostics card. */
    fun rootCertSummary(): String = try {
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(caCert.encoded)
            .joinToString(":") { "%02X".format(it) }
        "${caCert.subjectX500Principal.name}\nSHA-256: $sha256"
    } catch (_: Throwable) {
        "(unavailable)"
    }

    fun saveCertificateToDownloads(): SaveResult {
        return try {
            val bytes = caCert.encoded
            val displayPath = "Downloads/$DOWNLOADS_SUBDIR/$CERT_FILE_NAME"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                runCatching {
                    resolver.delete(
                        collection,
                        "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
                            "${MediaStore.Downloads.DISPLAY_NAME}=?",
                        arrayOf(
                            "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBDIR/",
                            CERT_FILE_NAME,
                        ),
                    )
                }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, CERT_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBDIR",
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(collection, values)
                    ?: return SaveResult(
                        success = false,
                        displayPath = displayPath,
                        message = "Could not create file in Downloads",
                        detail = "MediaStore.insert returned null. Try the SHARE button.",
                    )

                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("openOutputStream returned null")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                LogBus.i("Cert", "CA saved to $displayPath via MediaStore")
                SaveResult(
                    success = true,
                    displayPath = displayPath,
                    message = "Saved to $displayPath",
                    detail = "Now: Settings -> Security -> Encryption & credentials -> " +
                        "Install a certificate -> CA certificate -> pick velum_ca.crt " +
                        "from Downloads/Velum/. Use 'CA certificate' (not 'User certificate').",
                )
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                val targetDir = File(downloads, DOWNLOADS_SUBDIR).apply { mkdirs() }
                val target = File(targetDir, CERT_FILE_NAME)
                FileOutputStream(target).use { it.write(bytes) }

                LogBus.i("Cert", "CA saved to ${target.absolutePath} (legacy path)")
                SaveResult(
                    success = true,
                    displayPath = displayPath,
                    message = "Saved to $displayPath",
                    detail = "Now: Settings -> Security -> Install certificate -> " +
                        "pick velum_ca.crt.",
                )
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "saveCertificateToDownloads failed", e)
            SaveResult(
                success = false,
                displayPath = "Downloads/$DOWNLOADS_SUBDIR/$CERT_FILE_NAME",
                message = "Save failed",
                detail = (e.message ?: e.toString()).take(400),
            )
        }
    }

    fun exportCertificate(): Intent {
        return try {
            val certFile = File(context.cacheDir, CERT_FILE_NAME)
                .apply { writeBytes(caCert.encoded) }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-x509-ca-cert")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "exportCertificate failed", e)
            try {
                android.security.KeyChain.createInstallIntent().apply {
                    putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, caCert.encoded)
                    putExtra(android.security.KeyChain.EXTRA_NAME, caCommonName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e2: Throwable) {
                LogBus.e("Cert", "Fallback install intent failed", e2)
                Intent()
            }
        }
    }

    fun shareCertificate(): Intent {
        return try {
            val certFile = File(context.cacheDir, CERT_FILE_NAME)
                .apply { writeBytes(caCert.encoded) }
            val uri = FileProvider.getUriForFile(context, fileProviderAuthority, certFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Throwable) {
            LogBus.e("Cert", "shareCertificate failed", e)
            Intent()
        }
    }

    @Synchronized
    fun contextFor(host: String): SSLContext = try {
        cache.getOrPut(host) { sign(host) }
    } catch (e: Throwable) {
        LogBus.e("Cert", "contextFor($host) failed; using fallback", e)
        cache.getOrPut("fallback") { sign("fallback.local") }
    }

    fun dumpLastLeaf(): Intent? {
        val leaf = lastLeaf ?: return null
        return try {
            val file = File(context.cacheDir, "last_leaf.crt").apply {
                writeText(
                    "-----BEGIN CERTIFICATE-----\n" +
                        android.util.Base64.encodeToString(
                            leaf.encoded, android.util.Base64.DEFAULT,
                        ) +
                        "-----END CERTIFICATE-----\n",
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
            LogBus.e("Cert", "dumpLastLeaf failed", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CA load / generation
    // ─────────────────────────────────────────────────────────────────────

    private fun loadOrCreate(): Pair<X509Certificate, PrivateKey> {
        if (caCertFile.exists() && caKeyFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12", bcProvider)
                ks.load(caCertFile.inputStream(), DEFAULT_PASSWORD)
                val key = ks.getKey("ca", DEFAULT_PASSWORD) as? PrivateKey
                val cert = ks.getCertificate("ca") as? X509Certificate
                if (key != null && cert != null && certIsCompliant(cert)) {
                    return cert to key
                }
                LogBus.w("Cert", "Existing CA fails compliance check; regenerating.")
            } catch (e: Throwable) {
                LogBus.w("Cert", "Failed to load existing CA, regenerating: ${e.message}")
            }
            runCatching { caCertFile.delete() }
            runCatching { caKeyFile.delete() }
        }

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=$caCommonName, O=$caOrganization, C=US")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(
            System.currentTimeMillis() +
                CA_VALIDITY_YEARS * 365 * 24 * 3600 * 1000L,
        )
        val serial = BigInteger(159, SecureRandom()).abs()

        val builder = JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, kp.public,
        )
            // CRITICAL — RFC 5280 §4.2.1.9: a CA cert MUST mark BasicConstraints critical.
            .addExtension(
                Extension.basicConstraints, true,
                BasicConstraints(true),
            )
            // CRITICAL — RFC 5280 §4.2.1.3: keyCertSign + cRLSign for a CA.
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(
                    KeyUsage.digitalSignature
                        or KeyUsage.keyCertSign
                        or KeyUsage.cRLSign,
                ),
            )
            // SKI required so leaves can carry a matching AKI.
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                JcaX509ExtensionUtils().createSubjectKeyIdentifier(kp.public),
            )

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(bcProvider)
            .build(kp.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12", bcProvider).apply { load(null, null) }
        ks.setKeyEntry("ca", kp.private, DEFAULT_PASSWORD, arrayOf(cert))
        caCertFile.outputStream().use { ks.store(it, DEFAULT_PASSWORD) }
        runCatching { caKeyFile.writeText("STORED_IN_PKCS12") }

        LogBus.i("Cert", "Generated new RFC-5280-compliant root CA: ${cert.subjectX500Principal.name}")
        return cert to kp.private
    }

    /**
     * Sanity-check an existing CA against our compliance policy. If a CA was
     * generated by an older Velum build and is missing critical flags, we
     * regenerate so the device's CertPathValidator will actually trust it.
     */
    private fun certIsCompliant(cert: X509Certificate): Boolean {
        return try {
            // BasicConstraints must be present, critical, and CA:TRUE.
            val bcOid = Extension.basicConstraints.id
            val criticalOids = cert.criticalExtensionOIDs ?: emptySet()
            if (bcOid !in criticalOids) return false
            if (cert.basicConstraints < 0) return false   // -1 means "not a CA"

            // KeyUsage must be present, critical, with keyCertSign.
            val ku = cert.keyUsage ?: return false
            // KeyUsage[5] == keyCertSign per RFC 5280 §4.2.1.3
            if (!ku[5]) return false
            if (Extension.keyUsage.id !in criticalOids) return false

            // Signature algorithm must not be SHA-1.
            val algo = cert.sigAlgName.lowercase()
            if ("sha1" in algo || "md5" in algo) return false

            true
        } catch (_: Throwable) {
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-host leaf signing
    // ─────────────────────────────────────────────────────────────────────

    private fun sign(host: String): SSLContext {
        val kp = leafKeyPair
        val safeHost = host.ifBlank { "localhost" }.take(253)
        val isIp = safeHost.matches(Regex("^[0-9.]+$")) || safeHost.contains(':')

        // CN is informational only on modern stacks. SAN is the hostname source of truth.
        val subject = X500Name("CN=${safeHost.take(64)}")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(
            System.currentTimeMillis() +
                LEAF_VALIDITY_DAYS * 24 * 3600 * 1000L,
        )

        val sanGeneralName = if (isIp) {
            GeneralName(GeneralName.iPAddress, safeHost)
        } else {
            GeneralName(GeneralName.dNSName, safeHost)
        }

        val utils = JcaX509ExtensionUtils()
        val caSkiBytes: ByteArray? = try {
            caCert.getExtensionValue(Extension.subjectKeyIdentifier.id)?.let {
                ASN1OctetString.getInstance(it).octets
            }
        } catch (_: Throwable) { null }

        val serial = BigInteger(159, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            X500Name.getInstance(caCert.subjectX500Principal.encoded),
            serial, notBefore, notAfter, subject, kp.public,
        )
            // CRITICAL — leaf must explicitly NOT be a CA.
            .addExtension(
                Extension.basicConstraints, true,
                BasicConstraints(false),
            )
            // CRITICAL — required by CA/B Forum + BoringSSL hostname check.
            .addExtension(
                Extension.subjectAlternativeName, true,
                GeneralNames(sanGeneralName),
            )
            // Server auth EKU — BoringSSL refuses TLS without it.
            .addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            // CRITICAL — keyUsage for a TLS server.
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                utils.createSubjectKeyIdentifier(kp.public),
            )

        if (caSkiBytes != null) {
            // Exact byte-for-byte AKI = CA's SKI, per RFC 5280 §4.2.1.1.
            builder.addExtension(
                Extension.authorityKeyIdentifier, false,
                AuthorityKeyIdentifier(caSkiBytes),
            )
        }

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
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
