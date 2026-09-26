package com.btcpayapp.core.net

import com.btcpayapp.BuildConfig
import com.btcpayapp.core.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

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
        if (hop >= MAX_REDIRECTS) throw HttpFailure.Transport("too many redirects")

        val location = response.header("Location")
            ?: throw HttpFailure.Transport("redirect without a Location header")
        val target = runCatching { URL(request.url, location) }
            .getOrElse { throw HttpFailure.Transport("malformed redirect target") }

        if (!isSameOrigin(request.url, target)) {
            // Refusing rather than stripping credentials: a BTCPay instance has
            // no legitimate reason to bounce an API call to another origin, so
            // this is either a misconfiguration or an attack.
            throw HttpFailure.Transport(
                "refused a cross-origin redirect from ${request.url.host} to ${target.host}",
            )
        }

        return executeFollowingRedirects(redirectedRequest(request, response.code, target), options, hop + 1)
    }

    private suspend fun executeOnce(request: HttpRequest, options: TransportOptions): HttpResponse = coroutineScope {
        coroutineContext.ensureActive()

        val proxy = options.proxy?.toJavaProxy() ?: Proxy.NO_PROXY
        val connection = try {
            request.url.openConnection(proxy) as HttpURLConnection
        } catch (e: IOException) {
            throw HttpFailure.Transport("could not open a connection", e)
        }

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
                connection.outputStream.use { it.write(request.body) }
            }

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
            coroutineContext.ensureActive()
            succeeded.set(true)
            HttpResponse(code = code, headers = connection.headerFields.orEmpty(), body = body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpFailure) {
            // Already classified (the response cap, a bad redirect target).
            // Without this clause the `IOException` catch below would flatten a
            // `Tls` or `Timeout` back down to a generic `Transport`.
            throw e
        } catch (e: SSLException) {
            throw HttpFailure.Tls(e.message ?: "TLS handshake failed", e)
        } catch (e: UnknownHostException) {
            throw HttpFailure.Transport("host not found: ${request.url.host}", e)
        } catch (e: SocketTimeoutException) {
            throw HttpFailure.Timeout("the server did not respond in time", e)
        } catch (e: IOException) {
            throw HttpFailure.Transport(e.message ?: "network error", e)
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
                if (total > cap) throw HttpFailure.Transport("response exceeded $cap bytes")
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    private fun isSameOrigin(from: URL, to: URL): Boolean {
        if (!from.host.equals(to.host, ignoreCase = true)) return false
        if (!from.protocol.equals(to.protocol, ignoreCase = true)) return false
        return from.effectivePort() == to.effectivePort()
    }

    private fun URL.effectivePort(): Int =
        if (port != -1) port else if (protocol == "https") 443 else 80

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

/** Failures that happen below the API layer, before any response body exists. */
internal sealed class HttpFailure(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Transport(message: String, cause: Throwable? = null) : HttpFailure(message, cause)
    class Timeout(message: String, cause: Throwable? = null) : HttpFailure(message, cause)
    class Tls(message: String, cause: Throwable? = null) : HttpFailure(message, cause)
}

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
        throw HttpFailure.Transport("malformed server URL")
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
