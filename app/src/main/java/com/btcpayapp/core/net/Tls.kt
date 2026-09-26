package com.btcpayapp.core.net

import android.util.Base64
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Transport-security policy.
 *
 * Two modes, and only two:
 *
 *  1. **System trust** (default). The platform CA store validates the chain and
 *    the platform verifies the hostname. User-added CAs are excluded by the
 *    network security config, so an MDM profile or a sideloaded root cannot
 *    intercept.
 *
 *  2. **Pinned trust**. Used for self-hosted instances with a private CA or a
 *    self-signed certificate — the common case for a BTCPay box. The user is
 *    shown the SPKI SHA-256 fingerprint once and accepts it; from then on the
 *    pin *is* the server identity. This is deliberately narrower than adding a
 *    CA to the trust store: the pin authenticates exactly one key, for exactly
 *    one account, and nothing else in the app or the OS is affected.
 *
 * There is no third mode. "Accept all certificates" is not offered, because a
 * toggle that disables authentication is always eventually left on.
 */
/** What a certificate probe found, for the trust-on-first-use dialog. */
data class CertificateProbe(
    val pin: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val subjectAlternativeNames: List<String>,
    val selfSigned: Boolean,
) {
    val fingerprint: String get() = Tls.fingerprintForDisplay(pin)
    val commonName: String
        get() = subject.split(',')
            .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?: subject
}

object Tls {

    /** TLS 1.2 is the floor; anything older is off even where the OS allows it. */
    private val ENABLED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    /** SHA-256 over the DER SubjectPublicKeyInfo, base64. Pins the key, not the cert,
     *  so a routine certificate renewal that keeps the key does not break the pin. */
    fun spkiPin(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /** Human-readable form for the confirmation dialog: `AB:CD:EF:…`. */
    fun fingerprintForDisplay(pin: String): String =
        Base64.decode(pin, Base64.NO_WRAP).joinToString(":") { "%02X".format(it) }

    /**
     * Opens a handshake purely to read back the certificate the server offers,
     * so the user can be shown a fingerprint to accept.
     *
     * This is the one place that talks to a server without validating it, and
     * it is deliberately a dead end: the socket is closed immediately, no
     * request is ever written to it, and nothing it returns is trusted until
     * the user says so. The resulting pin is then enforced on every later
     * connection by [PinnedTrustManager].
     */
    fun probeCertificate(host: String, port: Int, timeoutMs: Int = 15_000): CertificateProbe? {
        val context = SSLContext.getInstance("TLS")
        val captured = arrayOfNulls<Array<out X509Certificate>>(1)

        context.init(
            null,
            arrayOf(
                object : X509TrustManager {
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        captured[0] = chain
                    }

                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            ),
            null,
        )

        return runCatching {
            val socket = context.socketFactory.createSocket() as SSLSocket
            socket.use {
                it.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                it.soTimeout = timeoutMs
                it.startHandshake()
            }
            val chain = captured[0]?.toList().orEmpty()
            val leaf = chain.firstOrNull() ?: return@runCatching null
            CertificateProbe(
                pin = spkiPin(leaf),
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                notBefore = leaf.notBefore.time,
                notAfter = leaf.notAfter.time,
                subjectAlternativeNames = runCatching {
                    leaf.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1)?.toString() }
                }.getOrDefault(emptyList()),
                selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
            )
        }.getOrNull()
    }

    /**
     * Cached per pin set, and the verifiers per (pin set, host).
     *
     * This is not only about the cost of `SSLContext.init`. Android's
     * `HttpsURLConnection` is OkHttp underneath, and its connection-pool key
     * compares `sslSocketFactory` and `hostnameVerifier` **by identity**. A
     * fresh factory per request would mean no pooled connection ever matches,
     * so every single API call would pay a new TCP connect plus a full TLS
     * handshake, and session resumption would be impossible because the TLS
     * session cache lives on the `SSLContext`. Holding one instance per pin set
     * for the process lifetime keeps both keep-alive and resumption.
     */
    private val socketFactories = ConcurrentHashMap<Set<String>, SSLSocketFactory>()
    private val hostnameVerifiers = ConcurrentHashMap<Pair<Set<String>, String>, HostnameVerifier>()

    fun socketFactory(pins: Set<String>): SSLSocketFactory =
        socketFactories.getOrPut(pins) {
            val context = SSLContext.getInstance("TLS")
            context.init(null, trustManagers(pins), null)
            ProtocolRestrictingSocketFactory(context.socketFactory)
        }

    fun hostnameVerifier(pins: Set<String>, expectedHost: String): HostnameVerifier =
        hostnameVerifiers.getOrPut(pins to expectedHost) { buildHostnameVerifier(pins, expectedHost) }

    private fun buildHostnameVerifier(pins: Set<String>, expectedHost: String): HostnameVerifier =
        if (pins.isEmpty()) {
            // Platform default: full RFC 6125 name matching.
            HostnameVerifier { host, session -> defaultVerifier.verify(host, session) }
        } else {
            // With a pin the key is the identity, so a certificate whose CN/SAN
            // does not match (normal for a self-signed box) is acceptable — but
            // only for the exact host this account was configured with, and only
            // after the pin check in the trust manager has already passed.
            PinnedHostnameVerifier(expectedHost)
        }

    private val defaultVerifier: HostnameVerifier
        get() = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()

    private fun trustManagers(pins: Set<String>): Array<TrustManager> =
        if (pins.isEmpty()) arrayOf(systemTrustManager) else arrayOf(PinnedTrustManager(pins))

    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class PinnedTrustManager(private val pins: Set<String>) : X509TrustManager {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificates = chain?.toList().orEmpty()
            if (certificates.isEmpty()) throw CertificateException("empty certificate chain")

            val leaf = certificates.first()

            // Expiry is still enforced. Pinning replaces the authority check,
            // not the validity check.
            leaf.checkValidity()

            // The *leaf* only, never `chain.any { }`. Nothing here validates
            // that the chain links cryptographically — with pins set this is
            // the sole trust manager, so the system chain check never runs.
            // Matching any element would therefore let an attacker present
            // [theirLeaf, realServerCert]: the appended real certificate is
            // public, it would satisfy the pin, and the session would run
            // under the attacker's key. Only the key that actually terminates
            // the connection may be compared against the pin set.
            if (spkiPin(leaf) !in pins) {
                throw CertificateException(
                    "certificate pin mismatch: the server presented a key this account has not accepted",
                )
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client authentication is not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class PinnedHostnameVerifier(private val expectedHost: String) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean =
            hostname != null && hostname.equals(expectedHost, ignoreCase = true)
    }

    /**
     * Wraps the platform factory so every socket comes up with a known-good
     * protocol set. The platform default is already sane on modern releases;
     * this makes it explicit and survives a vendor that re-enables TLS 1.0.
     */
    private class ProtocolRestrictingSocketFactory(
        private val delegate: SSLSocketFactory,
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        // Used by the stack when connecting through a proxy.
        override fun createSocket(): Socket = harden(delegate.createSocket())

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            harden(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            harden(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            harden(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            harden(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            harden(delegate.createSocket(address, port, localAddress, localPort))

        private fun harden(socket: Socket): Socket = socket.also {
            if (it is SSLSocket) {
                val supported = it.supportedProtocols.toSet()
                it.enabledProtocols = ENABLED_PROTOCOLS.filter { p -> p in supported }.toTypedArray()
            }
        }
    }
}
