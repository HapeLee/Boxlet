package dev.boxlet.core.crypto

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val KEYSTORE_PASSWORD = "boxlet-local-network"
private const val KEY_ALIAS = "boxlet"
private const val SUBJECT = "CN=LocalSend User"

/**
 * Persisted TLS identity. The keystore is stored base64-encoded so it can live in a
 * single JSON file on every platform.
 */
@Serializable
data class SecurityContext(
    val keystore: String,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object Certificates {
    internal fun localStateKeys(context: SecurityContext): java.security.KeyPair {
        val store = loadKeyStore(context)
        val certificate = store.getCertificate(KEY_ALIAS)
        require(fingerprintOf(certificate.encoded).equals(context.fingerprint, true)) { "Local identity mismatch" }
        return java.security.KeyPair(certificate.publicKey, store.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as java.security.PrivateKey)
    }

    /** Generates a fresh RSA-2048 self-signed identity and its uppercase SHA-256 fingerprint. */
    fun generate(): SecurityContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 100L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger(160, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(
            X500Name(SUBJECT),
            serial,
            notBefore,
            notAfter,
            X500Name(SUBJECT),
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.verify(keyPair.public)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(certificate))

        val encoded = java.io.ByteArrayOutputStream().use { out ->
            keyStore.store(out, KEYSTORE_PASSWORD.toCharArray())
            out.toByteArray()
        }

        return SecurityContext(
            keystore = encoded.toByteString().base64(),
            fingerprint = fingerprintOf(certificate.encoded),
        )
    }

    /** Uppercase hex SHA-256, matching the official LocalSend fingerprint format. */
    fun fingerprintOf(der: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(der)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    fun loadKeyStore(context: SecurityContext): KeyStore {
        val bytes = requireNotNull(context.keystore.decodeBase64()) { "Invalid keystore payload" }.toByteArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(bytes.inputStream(), KEYSTORE_PASSWORD.toCharArray())
        return keyStore
    }

    /** TLS server context with optional (not mandatory) client certificate authentication. */
    fun serverContext(context: SecurityContext): SSLContext {
        val keyStore = loadKeyStore(context)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, KEYSTORE_PASSWORD.toCharArray())
        }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(trust), SecureRandom())
        }
    }

    /**
     * TLS client context. When [expectedFingerprint] is set the handshake fails unless the
     * peer certificate SHA-256 matches exactly (hostname verification is intentionally off).
     */
    fun clientTrustManager(expectedFingerprint: String?): X509TrustManager {
        val pin = expectedFingerprint?.uppercase()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("Peer did not present a certificate")
                certificate.checkValidity()
                if (pin != null) {
                    val actual = fingerprintOf(certificate.encoded)
                    if (!actual.equals(pin, ignoreCase = true)) {
                        throw java.security.cert.CertificateException(
                            "Certificate fingerprint mismatch: expected $pin, got $actual",
                        )
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    /**
     * Client TLS context. Always presents this device certificate (official LocalSend
     * servers require mutual TLS) and optionally pins the peer fingerprint.
     */
    fun clientContext(identity: SecurityContext?, expectedFingerprint: String?): SSLContext {
        val trust = clientTrustManager(expectedFingerprint)
        val keyManagers = identity?.let { context ->
            val keyStore = loadKeyStore(context)
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, KEYSTORE_PASSWORD.toCharArray())
            }.keyManagers
            // JSSE clients skip their certificate when the server sends an empty CA list
            // with wantClientAuth. LocalSend relies on self-signed mutual TLS, so force
            // our single identity for every client alias request.
            val delegate = managers.filterIsInstance<javax.net.ssl.X509KeyManager>().firstOrNull()
            if (delegate == null) {
                managers
            } else {
                arrayOf<KeyManager>(ForcedClientKeyManager(delegate, KEY_ALIAS))
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf<TrustManager>(trust), SecureRandom())
        }
    }

    private class ForcedClientKeyManager(
        private val delegate: javax.net.ssl.X509KeyManager,
        private val alias: String,
    ) : javax.net.ssl.X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String = alias

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            engine: javax.net.ssl.SSLEngine?,
        ): String = alias

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = delegate.chooseServerAlias(keyType, issuers, socket)

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            engine: javax.net.ssl.SSLEngine?,
        ): String? = null

        override fun getCertificateChain(alias: String?): Array<java.security.cert.X509Certificate>? =
            delegate.getCertificateChain(alias)

        override fun getPrivateKey(alias: String?): java.security.PrivateKey? = delegate.getPrivateKey(alias)

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
        ): Array<String> = arrayOf(alias)

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
        ): Array<String>? = delegate.getServerAliases(keyType, issuers)
    }

    /** Extracts and normalizes the client certificate fingerprint of an established TLS session. */
    fun peerFingerprint(session: javax.net.ssl.SSLSession): String? = runCatching {
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
        certificate?.let { fingerprintOf(it.encoded) }
    }.getOrNull()

    private val HEX = "0123456789ABCDEF".toCharArray()
}
