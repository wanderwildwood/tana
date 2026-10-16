package com.wanderwildwood.tana.nearby

import android.content.Context
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.net.Socket
import java.security.Principal
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Who this phone is to LocalSend: a self-signed certificate, made once and kept. LocalSend has no
 * authority to vouch for anyone; a device is known by the SHA-256 of its certificate, and that
 * is what it announces as its fingerprint. The same certificate serves the phone's own server and
 * is shown when it sends, because a LocalSend that receives asks the sender for one and checks
 * the fingerprint it claimed against it.
 */
internal class Identity private constructor(private val key: PrivateKey, val certificate: X509Certificate) {

    /** Upper-case hex, as LocalSend writes it. */
    val fingerprint: String = fingerprintOf(certificate)

    /**
     * Always this phone's certificate. Android's own key manager offers a client certificate only
     * when it was issued by one of the names the server lists, and LocalSend lists its own; a
     * self-signed certificate of ours is never among them, and the handshake failed for want of one.
     */
    private val keyManagers = arrayOf<javax.net.ssl.KeyManager>(object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
    })

    /** For the server. Another device's certificate, when it shows one, is asked for and taken as it is. */
    fun serverContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(AnyCertificate), null) }

    /** For sending to one device: it must show the certificate its fingerprint names. */
    fun clientContext(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), null) }

    companion object {
        private val PASSWORD = "tana".toCharArray()
        private const val ALIAS = "nearby"
        private const val DAY = 24L * 60 * 60 * 1000

        /** The kept one, or a new one when there is none yet. Slow the first time: an RSA key is made. */
        fun load(context: Context): Identity {
            val file = File(context.noBackupFilesDir, "nearby.p12")
            if (file.isFile) {
                runCatching {
                    val ks = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
                    val key = ks.getKey(ALIAS, PASSWORD) as PrivateKey
                    val cert = ks.getCertificate(ALIAS) as X509Certificate
                    cert.checkValidity()
                    return Identity(key, cert)
                }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=Files")
            // Written out field by field with the Bouncy Castle the app already carries, rather
            // than with a second library for it whose versions would have to be kept in step.
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(63, SecureRandom())))
                setIssuer(name)
                setSubject(name)
                // A day early, so a clock a little behind the other device's does not make it
                // "not valid yet" there.
                setStartDate(Time(Date(now - DAY)))
                setEndDate(Time(Date(now + 10 * 365 * DAY)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
                setSignature(algorithm)
            }.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(pair.private)
                update(tbs.getEncoded(ASN1Encoding.DER))
            }.sign()
            val der = DERSequence(ASN1EncodableVector().apply {
                add(tbs)
                add(algorithm)
                add(DERBitString(signature))
            }).getEncoded(ASN1Encoding.DER)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            val part = File(file.parentFile, file.name + ".part")
            part.outputStream().use { store(pair.private, cert).store(it, PASSWORD) }
            part.renameTo(file)
            return Identity(pair.private, cert)
        }

        private fun store(key: PrivateKey, cert: X509Certificate): KeyStore =
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, key, PASSWORD, arrayOf(cert))
            }

        fun fingerprintOf(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
    }
}

/** Takes any certificate. Only for the server, where a client's certificate is read, never relied on. */
internal object AnyCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The device at the other end must be the one that announced itself: its certificate has to hash
 * to the fingerprint it gave. No authority is asked, as there is none to ask.
 */
internal class FingerprintTrust(private val expected: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (!Identity.fingerprintOf(cert).equals(expected, ignoreCase = true)) throw CertificateException("not the device that announced itself")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
