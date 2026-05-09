package com.velum.vpn

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.SecureRandom
import java.util.Date

class CertificateGeneratorTest {
    @Test
    fun generateCertificateFile() {
        Security.addProvider(BouncyCastleProvider())

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=VelumVPN Root CA, O=VelumVPN")
        val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 3600 * 1000L)
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()),
            notBefore, notAfter, name, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))

        // Write the certificate to ca.crt in the project root
        File("ca.crt").writeBytes(cert.encoded)
    }
}
