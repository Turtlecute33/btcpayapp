package com.btcpayapp.core.net

import com.btcpayapp.core.util.toHex
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
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
 * What a certificate probe found, for the trust-on-first-use dialog.
 *
 * Everything here is read from a certificate nobody has verified yet. The
 * names and dates are the *claims* of whoever answered the handshake, so
 * they may explain a failure or raise a warning, but they never make a key
 * trustworthy. Only [pin] matched against a value the operator read on the
 * server itself does that.
 */
data class CertificateProbe(
    val pin: String,
    val subject: String,
    val notBefore: Long,
    val notAfter: Long,
    val subjectAlternativeNames: List<String>,
    /**
     * Lowercase hex SHA-256 of the leaf's DER encoding: the value
     * `openssl x509 -noout -fingerprint -sha256` prints (there in capitals, with
     * colons), so an operator can compare it on the server without computing an
     * SPKI hash. Empty when unknown.
     */
    val certificateSha256Hex: String = "",
) {
    val commonName: String
        get() = subject.split(',')
            .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?: subject

    /**
     * Pinning replaces the authority check, not the validity check, so an
     * expired key can never be made to work by trusting it. Offering it anyway
     * would be a dead end that also teaches users to accept prompts.
     */
    fun isCurrentlyValid(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs in notBefore..notAfter

    /**
     * Whether the certificate claims [host] (RFC 6125, simplified): a
     * case-insensitive match on the subject alternative names, where `*.` covers
     * exactly one left-most label, and the common name only when there are no
     * SANs at all. IP literals are compared as text; nothing is resolved.
     */
    fun namesHost(host: String): Boolean {
        val wanted = host.normalisedHost()
        if (wanted.isEmpty()) return false
        val names = subjectAlternativeNames.ifEmpty { listOf(commonName) }
        return names.any { name -> matchesName(name.normalisedHost(), wanted) }
    }

    private fun matchesName(name: String, host: String): Boolean {
        if (name == host) return true
        // A wildcard never stands for part of an IP address.
        if (!name.startsWith("*.") || host.isIpLiteral()) return false
        val suffix = name.substring(1) // ".example.com"
        val label = host.removeSuffix(suffix)
        return host.endsWith(suffix) && label.isNotEmpty() && '.' !in label
    }
}

/**
 * Why a handshake failed. The UI says each one plainly, and only
 * [UntrustedIssuer] may ever lead to an offer to trust the key: a wrong name,
 * an expired certificate or a changed pinned key are exactly what an
 * interception looks like, and pinning cannot repair any of them.
 */
enum class TlsProblem(val userMessage: String) {
    UntrustedIssuer("The server's certificate is not signed by an authority this phone trusts."),
    HostnameMismatch("The server's certificate is for a different name than this address."),
    Expired("The server's certificate has expired or is not valid yet."),
    KeyChanged("The server presented a different key from the one this account trusts."),
    Other("A secure connection to the server could not be set up."),
}

/** A pinned account saw a key it has not accepted. Its own type, so it classifies as [TlsProblem.KeyChanged]. */
internal class PinMismatchException(message: String) : CertificateException(message)

/**
 * Whether [host] is, by its text alone, a private-network, loopback or onion
 * address: RFC 1918, loopback, link-local and CGNAT IPv4 literals; `::1`,
 * `fc00::/7` and `fe80::/10` (with or without brackets); a name without a dot;
 * or a name under a suffix only a local resolver or Tor answers.
 *
 * Never resolves a name: a lookup would send the host to the network's DNS,
 * and on a hostile network the attacker writes the answer.
 */
fun isLocalNetworkHost(host: String): Boolean {
    val name = host.normalisedHost().substringBefore('%') // an IPv6 zone id
    if (name.isEmpty()) return false

    if (':' in name) {
        if (name == "::1" || name == "0:0:0:0:0:0:0:1") return true
        val first = name.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
        return first in 0xfc00..0xfdff || first in 0xfe80..0xfebf
    }

    if (name.isIpLiteral()) {
        val octets = name.split('.').map(String::toInt)
        if (octets.any { it > 255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }

    return '.' !in name || LOCAL_SUFFIXES.any { name.endsWith(it) }
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain", ".onion")

private fun String.normalisedHost(): String =
    trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)

/** Text shaped like an IP literal: IPv6 has a colon, IPv4 is four groups of one to three ASCII digits. */
private fun String.isIpLiteral(): Boolean =
    ':' in this || split('.').let { parts -> parts.size == 4 && parts.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } } }

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
 *    self-signed certificate — the common case for a BTCPay box. The user runs
 *    one command on the server and types the start of the fingerprint it
 *    prints; only a match pins the key. The app never offers the fingerprint
 *    it received for the user to accept, because whoever answered the
 *    handshake chose it. From then on the pin *is* the server identity. This
 *    is deliberately narrower than adding a CA to the trust store: the pin
 *    authenticates exactly one key, for exactly one account, and nothing else
 *    in the app or the OS is affected.
 *
 * There is no third mode. "Accept all certificates" is not offered, because a
 * toggle that disables authentication is always eventually left on.
 */
object Tls {

    /** TLS 1.2 is the floor; anything older is off even where the OS allows it. */
    private val ENABLED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    /** SHA-256 over the DER SubjectPublicKeyInfo, base64. Pins the key, not the cert,
     *  so a routine certificate renewal that keeps the key does not break the pin.
     *
     *  `java.util.Base64` (API 26+) rather than `android.util.Base64`: the same
     *  unwrapped, padded output, and it runs in a JVM unit test. */
    fun spkiPin(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Human-readable form of a pin the account already trusts: `AB:CD:EF:…`.
     * A stored pin that is not valid base64 is shown as it is rather than
     * crashing the screen that displays it.
     */
    fun fingerprintForDisplay(pin: String): String =
        runCatching { Base64.getDecoder().decode(pin.trim()) }.getOrNull()
            ?.joinToString(":") { "%02X".format(it) }
            ?: pin

    /**
     * Opens a handshake purely to read back the certificate the server offers,
     * so a fingerprint the user reads on the server can be checked against it.
     *
     * This is the one place that talks to a server without validating it, and
     * it is deliberately a dead end: the socket is closed immediately, no
     * request is ever written to it, and nothing it returns is trusted until
     * the user's fingerprint matches. The resulting pin is then enforced on
     * every later connection by [PinnedTrustManager].
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
            val leaf = captured[0]?.firstOrNull() ?: return@runCatching null
            probeOf(leaf)
        }.getOrNull()
    }

    /** The dialog's view of [leaf]. Split out so a test can build one from a fixture. */
    internal fun probeOf(leaf: X509Certificate): CertificateProbe = CertificateProbe(
        pin = spkiPin(leaf),
        subject = leaf.subjectX500Principal.name,
        notBefore = leaf.notBefore.time,
        notAfter = leaf.notAfter.time,
        // DNS names (2) and IP addresses (7) only: the names a certificate can
        // be *for*. An e-mail or URI entry must never satisfy [namesHost].
        subjectAlternativeNames = runCatching {
            leaf.subjectAlternativeNames.orEmpty()
                .filter { it.getOrNull(0) == SAN_DNS || it.getOrNull(0) == SAN_IP }
                .mapNotNull { it.getOrNull(1)?.toString() }
        }.getOrDefault(emptyList()),
        certificateSha256Hex = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHex(),
    )

    private const val SAN_DNS = 2
    private const val SAN_IP = 7

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

    /** `internal` only so a unit test can hand it certificate chains. */
    internal class PinnedTrustManager(private val pins: Set<String>) : X509TrustManager {

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
                throw PinMismatchException(
                    "certificate pin mismatch: the server presented a key this account has not accepted",
                )
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client authentication is not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** `internal` only so a unit test can check it. */
    internal class PinnedHostnameVerifier(private val expectedHost: String) : HostnameVerifier {
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
