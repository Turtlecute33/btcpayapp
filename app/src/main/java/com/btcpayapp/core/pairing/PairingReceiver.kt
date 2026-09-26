package com.btcpayapp.core.pairing

import com.btcpayapp.core.crypto.Keystore
import com.btcpayapp.core.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicReference
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A single-use loopback HTTP listener that receives the API key BTCPay hands
 * back at the end of its authorize flow.
 *
 * ### Why this and not a custom `myapp://` scheme
 *
 * BTCPay does not redirect at the end of `/api-keys/authorize`. It returns a
 * 200 with an auto-submitting HTML form that **POSTs** `apiKey`, `userId` and
 * repeated `permissions[]` fields to the `redirect` URL. A custom scheme cannot
 * receive a POST body — Android delivers only the URI to the intent, so the key
 * would be lost — and Chrome additionally blocks script-initiated navigation to
 * external schemes without a user gesture. A loopback listener is the pattern
 * RFC 8252 specifies for exactly this situation, and it receives the full body.
 *
 * ### Why it is safe
 *
 *  - Bound to `127.0.0.1` only, so nothing off-device can reach it.
 *  - Ephemeral port, open for one request and at most [DEFAULT_TIMEOUT_MS].
 *  - The path carries a 256-bit random nonce. Another app on the device that
 *    guessed the port still cannot post a forged key without the nonce, and it
 *    cannot read the response.
 *  - Request size and header count are capped, so a local process cannot use it
 *    to exhaust memory.
 *  - Only `POST` to the nonce path is answered; everything else gets a 404 and
 *    the listener keeps waiting.
 */
class PairingReceiver private constructor(
    private val server: ServerSocket,
    val nonce: String,
) : Closeable {

    val port: Int get() = server.localPort

    /** The `redirect` value to hand to BTCPay. */
    val redirectUri: String get() = "http://127.0.0.1:$port/$nonce"

    /**
     * Blocks until the browser posts the grant, the timeout expires, or the
     * caller's coroutine is cancelled.
     */
    suspend fun awaitGrant(timeoutMs: Long = DEFAULT_TIMEOUT_MS): ApiKeyGrant? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val activeSocket = AtomicReference<Socket?>(null)
                    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            close()
                            runCatching { activeSocket.getAndSet(null)?.close() }
                        }
                    }
                    try {
                        while (true) {
                            coroutineContext.ensureActive()
                            val socket = try {
                                server.accept()
                            } catch (e: IOException) {
                                Log.w("PairingReceiver") { "listener closed" }
                                coroutineContext.ensureActive()
                                return@coroutineScope null
                            }
                            activeSocket.set(socket)
                            if (!coroutineContext.isActive) {
                                socket.close()
                                coroutineContext.ensureActive()
                            }
                            val grant = socket.use { handle(it) }
                            activeSocket.compareAndSet(socket, null)
                            if (grant != null) return@coroutineScope grant
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    } finally {
                        cancellation.cancel()
                        close()
                    }
                }
            }
        }

    private fun handle(socket: Socket): ApiKeyGrant? {
        if (!socket.inetAddress.isLoopbackAddress) {
            Log.w("PairingReceiver") { "rejected a non-loopback connection" }
            return null
        }
        socket.soTimeout = SOCKET_TIMEOUT_MS

        return try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = input.readLine(MAX_LINE) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(socket, 400, "Bad request")
                return null
            }
            val method = parts[0]
            val target = parts[1].substringBefore('?').trimStart('/')

            var contentLength = 0
            var headerCount = 0
            while (true) {
                val header = input.readLine(MAX_LINE) ?: break
                if (header.isEmpty()) break
                if (++headerCount > MAX_HEADERS) {
                    respond(socket, 431, "Too many headers")
                    return null
                }
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            // Constant-time-ish comparison is overkill for a value the peer
            // already has to guess in one shot against a closing socket, but a
            // length check first costs nothing.
            if (method != "POST" || target.length != nonce.length || target != nonce) {
                respond(socket, 404, "Not found")
                return null
            }

            if (contentLength <= 0 || contentLength > MAX_BODY) {
                respond(socket, 413, "Unexpected body size")
                return null
            }

            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count == -1) break
                read += count
            }
            if (read != contentLength) {
                respond(socket, 400, "Truncated body")
                return null
            }

            val grant = parseForm(String(body, Charsets.UTF_8))
            body.fill(0)

            if (grant == null) {
                respond(socket, 400, "Missing API key")
                return null
            }

            respond(socket, 200, SUCCESS_PAGE, contentType = "text/html; charset=utf-8")
            grant
        } catch (e: IOException) {
            Log.w("PairingReceiver") { "connection failed: ${e.message}" }
            null
        }
    }

    private fun parseForm(body: String): ApiKeyGrant? {
        var apiKey: String? = null
        var userId: String? = null
        val permissions = mutableListOf<String>()

        body.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val name = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            when (name) {
                "apiKey" -> apiKey = value
                "userId" -> userId = value
                "permissions[]", "permissions" -> if (value.isNotBlank()) permissions += value
            }
        }

        val key = apiKey?.takeIf { it.isNotBlank() } ?: return null
        return ApiKeyGrant(apiKey = key, userId = userId.orEmpty(), permissions = permissions.toList())
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun respond(socket: Socket, status: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }
        runCatching {
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(payload)
                flush()
            }
        }
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_BODY = 64 * 1024

        fun open(): PairingReceiver {
            val nonce = Keystore.randomBytes(32).toHex()
            val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            return PairingReceiver(socket, nonce)
        }

        private val SUCCESS_PAGE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Connected</title>
            <style>
              body{font-family:system-ui,sans-serif;background:#0f1115;color:#e6e6e6;
                   display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
              div{text-align:center;padding:2rem}
              h1{font-size:1.25rem;margin:0 0 .5rem}
              p{opacity:.7;margin:0}
            </style></head>
            <body><div><h1>Connected</h1><p>You can close this tab and return to the app.</p></div></body>
            </html>
        """.trimIndent()
    }
}

data class ApiKeyGrant(
    val apiKey: String,
    val userId: String,
    val permissions: List<String>,
) {
    /**
     * Store-scoped permissions look like `btcpay.store.cancreateinvoice:STOREID`,
     * so the stores the user picked can be recovered without another call.
     */
    val scopedStoreIds: List<String>
        get() = permissions.mapNotNull { it.substringAfter(':', "").takeIf(String::isNotBlank) }.distinct()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Reads a CRLF-terminated line, refusing to grow past [limit] bytes. */
private fun BufferedInputStream.readLine(limit: Int): String? {
    val buffer = StringBuilder()
    while (true) {
        val byte = read()
        if (byte == -1) return buffer.takeIf { it.isNotEmpty() }?.toString()
        if (byte == '\n'.code) return buffer.toString().removeSuffix("\r")
        if (buffer.length >= limit) return null
        buffer.append(byte.toChar())
    }
}
