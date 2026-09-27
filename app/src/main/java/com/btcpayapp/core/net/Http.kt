package com.btcpayapp.core.net

import com.btcpayapp.BuildConfig
import com.btcpayapp.core.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The entire HTTP layer, built on the platform's `HttpURLConnection`.
 *
 * Why not OkHttp/Retrofit: on Android `HttpURLConnection` *is* OkHttp — a fork
 * maintained inside the platform and patched through system updates. Bundling a
 * second copy would add a large dependency, a second TLS configuration surface,
 * and an interceptor pipeline the app does not need, in exchange for
 * convenience this file provides in ~200 lines. Fewer moving parts is the
 * security property being bought here.
 *
 * Hardening applied to every request:
 *  - Redirects are *not* followed automatically. They are handled here, and only
 *    when they stay on the same origin and do not downgrade the scheme. An open
 *    redirect on the server therefore cannot leak the `Authorization` header to
 *    a third-party host.
 *  - Response bodies are capped, so a hostile or broken server cannot OOM the app.
 *  - Caching is off; API responses carry balances and payment data that have no
 *    business sitting in a disk cache.
 *  - Cookies are never sent or stored.
 *  - The `User-Agent` carries the app name and version only — no device model,
 *    no OS build, nothing that helps fingerprint the operator.
 *  - Every failure is an [HttpFailure] with fixed, plain text. The platform's
 *    own exception text ("ECONNREFUSED", "Trust anchor for certification path
 *    not found") stays in the cause and the debug log.
 */
// `open` for one reason: it is the only seam at which a test can assert what the
// API layer actually put on the wire — which request, carrying which headers —
// without opening a socket.
open class HttpEngine {

    open suspend fun execute(request: HttpRequest, options: TransportOptions): HttpResponse =
        withContext(Dispatchers.IO) { executeFollowingRedirects(request, options, hop = 0) }

    private suspend fun executeFollowingRedirects(
        request: HttpRequest,
        options: TransportOptions,
        hop: Int,
    ): HttpResponse {
        val response = executeOnce(request, options)
        if (response.code !in REDIRECT_CODES) return response
        if (hop >= MAX_REDIRECTS) throw redirectRefused("more than $MAX_REDIRECTS redirects")

        val location = response.header("Location") ?: throw redirectRefused("no Location header")
        val target = runCatching { URL(request.url, location) }
            .getOrElse { throw redirectRefused("a malformed Location header") }

        if (!isSameOrigin(request.url, target)) {
            // Refusing rather than stripping credentials: a BTCPay instance has
            // no legitimate reason to bounce an API call to another origin, so
            // this is either a misconfiguration or an attack.
            throw redirectRefused("cross-origin, from ${request.url.host} to ${target.host}")
        }

        return executeFollowingRedirects(redirectedRequest(request, response.code, target), options, hop + 1)
    }

    private suspend fun executeOnce(request: HttpRequest, options: TransportOptions): HttpResponse = coroutineScope {
        currentCoroutineContext().ensureActive()

        val proxy = options.proxy?.toJavaProxy() ?: Proxy.NO_PROXY
        val connection = try {
            request.url.openConnection(proxy) as HttpURLConnection
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw failureOf(e, requestSent = false)
        }

        // Set once `connect()` returns: from then on the server may have the
        // request, so a failure is no longer proof that nothing happened. The
        // client needs that line to tell "never sent, retry is safe" from "a
        // payment POST may have gone through".
        var connected = false
        val succeeded = AtomicBoolean(false)
        // Blocking socket I/O does not observe coroutine deadlines on its own.
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                if (!succeeded.get()) connection.disconnect()
            }
        }
        try {
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = Tls.socketFactory(options.pinnedSpki)
                connection.hostnameVerifier = Tls.hostnameVerifier(options.pinnedSpki, request.url.host)
            }

            connection.requestMethod = request.method
            connection.connectTimeout = options.connectTimeoutMs
            connection.readTimeout = options.readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.defaultUseCaches = false

            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", options.userAgent)
            connection.setRequestProperty("Cache-Control", "no-store")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            if (request.body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
            }

            // Explicit, so that DNS, the proxy, TCP and the whole TLS handshake
            // (chain, pin and hostname checks) finish here, before a byte of
            // the request body exists on the wire.
            connection.connect()
            connected = true

            if (request.body != null) connection.outputStream.use { it.write(request.body) }

            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.let { readCapped(it, connection.contentEncoding, options.maxResponseBytes) }
                ?: ByteArray(0)

            // Deliberately *not* calling `disconnect()` here. On Android
            // `HttpURLConnection.disconnect()` evicts the socket from the pool
            // instead of returning it, so calling it on the success path —
            // after the body has been fully drained by `readCapped`, which
            // closes the stream — would make keep-alive impossible and force a
            // new TCP connect and TLS handshake on every single API call.
            currentCoroutineContext().ensureActive()
            succeeded.set(true)
            HttpResponse(code = code, headers = connection.headerFields.orEmpty(), body = body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A cancelled caller disconnects the socket (above), and the blocked
            // read then fails with an IOException. That is the cancellation, not
            // a network fault, and it must stay one: a real exception thrown here
            // would win over it and reach the caller as an ordinary failure.
            currentCoroutineContext().ensureActive()
            // An `HttpFailure` is already classified: the response cap, raised
            // while reading an answer we did receive, keeps requestSent = false.
            if (e is HttpFailure) throw e
            Log.e("Http", e) { "request failed" }
            throw failureOf(e, requestSent = connected, viaProxy = options.proxy != null)
        } finally {
            // Only on the failure path, where the body was not drained and the
            // socket is not reusable anyway.
            cancellation.cancel()
            if (!succeeded.get()) connection.disconnect()
        }
    }

    private fun readCapped(stream: InputStream, contentEncoding: String?, cap: Int): ByteArray {
        val source = if (contentEncoding.equals("gzip", ignoreCase = true)) {
            java.util.zip.GZIPInputStream(stream)
        } else {
            stream
        }
        source.use { input ->
            val buffer = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                total += read
                if (total > cap) throw HttpFailure.Transport("The server's answer was too large for this app.")
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 3
    }
}

data class HttpRequest(
    val method: String,
    val url: URL,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
) {
    // Generated equals/hashCode on a ByteArray field compares by identity, which
    // is never what a caller wants. This class is a value carrier, so make it
    // behave like one.
    override fun equals(other: Any?): Boolean = this === other || (
        other is HttpRequest &&
            method == other.method &&
            url.toExternalForm() == other.url.toExternalForm() &&
            headers == other.headers &&
            contentType == other.contentType &&
            body.contentEqualsOrBothNull(other.body)
        )

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.toExternalForm().hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

class HttpResponse(
    val code: Int,
    /** The key is nullable: `HttpURLConnection` files the status line under `null`. */
    val headers: Map<String?, List<String>>,
    val body: ByteArray,
) {
    val isSuccess: Boolean get() = code in 200..299

    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
        ?.value?.firstOrNull()

    /**
     * Decoded once. The error path reads the body to classify the failure and
     * the success path reads it again to deserialise; without the cache that is
     * two full UTF-16 copies of every response body.
     */
    private val text: String by lazy(LazyThreadSafetyMode.NONE) { body.toString(Charsets.UTF_8) }

    fun bodyAsText(): String = text
}

data class TransportOptions(
    val proxy: ProxySpec? = null,
    val pinnedSpki: Set<String> = emptySet(),
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
    val maxResponseBytes: Int = 8 * 1024 * 1024,
    val userAgent: String = DEFAULT_USER_AGENT,
) {
    companion object {
        /**
         * Deliberately free of device identifiers. A self-hosted server's logs
         * should not become a device inventory.
         *
         * The build type is stripped, so a debug build is indistinguishable on
         * the wire from the release of the same version.
         */
        val DEFAULT_USER_AGENT: String = "BTCPayApp/" + BuildConfig.VERSION_NAME.substringBefore('-')
    }
}

data class ProxySpec(
    val host: String,
    val port: Int,
    val socks: Boolean = true,
) {
    fun toJavaProxy(): Proxy {
        val type = if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP
        // Unresolved on purpose: it makes the JDK hand the hostname to the proxy
        // instead of resolving it locally. That is what keeps .onion lookups —
        // and the DNS metadata of a normal host — off the local resolver.
        return Proxy(type, InetSocketAddress.createUnresolved(host, port))
    }

    companion object {
        /** Orbot's default SOCKS listener. */
        val ORBOT = ProxySpec(host = "127.0.0.1", port = 9050, socks = true)
    }
}

/**
 * Failures that happen below the API layer, before any response body exists.
 *
 * [message] is user text: fixed, plain, and never the platform's exception
 * text, which stays in [cause].
 *
 * [requestSent] is true once the connection was open, so the server may have
 * received the request and acted on it. It stays false for a failure raised
 * while handling an answer we did receive (a refused redirect, the size cap):
 * those are definite answers, not unknowns.
 */
internal sealed class HttpFailure(
    message: String,
    cause: Throwable?,
    val requestSent: Boolean,
) : IOException(message, cause) {
    class Transport(message: String, cause: Throwable? = null, requestSent: Boolean = false) :
        HttpFailure(message, cause, requestSent)

    class Timeout(message: String, cause: Throwable? = null, requestSent: Boolean = false) :
        HttpFailure(message, cause, requestSent)

    /** Always before the connection opened: the handshake is part of `connect()`. */
    class Tls(val problem: TlsProblem, cause: Throwable? = null) :
        HttpFailure(problem.userMessage, cause, requestSent = false)
}

/**
 * Maps a platform [IOException] to an [HttpFailure] with plain text.
 *
 * An `SSLException` is a certificate problem only before the connection
 * opened. After it, the handshake is long over, and an `SSLException` is the
 * TLS layer reporting a dropped or reset connection ("Read error", "Connection
 * reset by peer"), which must count as a dropped connection with
 * [HttpFailure.requestSent] so that a payment POST is not offered a plain retry.
 *
 * [viaProxy]: the request goes through a proxy, which is the only socket this
 * app opens for it.
 */
internal fun failureOf(e: IOException, requestSent: Boolean, viaProxy: Boolean = false): HttpFailure = when {
    e is SSLException && !requestSent -> HttpFailure.Tls(tlsProblemOf(e), e)
    // Before the connection opened, a socket error through a proxy means the
    // proxy refused the connection (the platform's SOCKS client reports that
    // as a plain SocketException, not a ConnectException) or could not reach
    // the server.
    // The usual cause is Orbot not running, and the server texts below would
    // send the user to check the server instead.
    e is SocketException && viaProxy && !requestSent -> HttpFailure.Transport(PROXY_FAILED, e)
    e is UnknownHostException -> HttpFailure.Transport("The server's address could not be found.", e, requestSent)
    e is ConnectException -> HttpFailure.Transport("The server refused the connection.", e, requestSent)
    e is SocketTimeoutException -> HttpFailure.Timeout("The server did not respond in time.", e, requestSent)
    // What the platform throws when the network security config refuses cleartext.
    e is UnknownServiceException -> HttpFailure.Transport(NEEDS_HTTPS, e, requestSent)
    else -> HttpFailure.Transport("The connection to the server failed.", e, requestSent)
}

/** Shared with the client's own early check, so both paths say the same thing. */
internal const val NEEDS_HTTPS = "This address needs https://. Only .onion addresses can use http://."

internal const val PROXY_FAILED =
    "Could not reach the server through the proxy. Check that Orbot (or your proxy) is running, then try again."

/**
 * Why a handshake failed, read from the exception and its causes. The platform
 * wraps the trust manager's exception (often twice), so the whole chain is
 * searched, and the order below goes from the most specific cause to the least.
 */
internal fun tlsProblemOf(e: Throwable): TlsProblem {
    val chain = generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
    return when {
        chain.any { it is SSLPeerUnverifiedException } -> TlsProblem.HostnameMismatch
        chain.any { it is CertificateExpiredException || it is CertificateNotYetValidException } -> TlsProblem.Expired
        chain.any { it is PinMismatchException } -> TlsProblem.KeyChanged
        chain.any { it is CertPathValidatorException || it.message?.contains("Trust anchor", ignoreCase = true) == true } ->
            TlsProblem.UntrustedIssuer
        else -> TlsProblem.Other
    }
}

/** A guard against a cause chain that loops; real chains are two or three deep. */
private const val MAX_CAUSES = 16

/** Builds a URL, percent-encoding every component and supporting repeated keys. */
internal fun buildUrl(baseUrl: String, path: String, query: List<Pair<String, Any?>> = emptyList()): URL {
    val base = baseUrl.trimEnd('/')
    val suffix = path.trimStart('/')
    val encodedQuery = query
        .mapNotNull { (key, value) -> value?.let { key to it } }
        .flatMap { (key, value) ->
            when (value) {
                is Iterable<*> -> value.filterNotNull().map { key to it.toString() }
                else -> listOf(key to value.toString())
            }
        }
        .joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }

    val url = if (encodedQuery.isEmpty()) "$base/$suffix" else "$base/$suffix?$encodedQuery"
    return try {
        URL(url)
    } catch (e: Exception) {
        Log.e("Http", e) { "malformed URL for path $suffix" }
        throw HttpFailure.Transport("The server address is not valid.")
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
