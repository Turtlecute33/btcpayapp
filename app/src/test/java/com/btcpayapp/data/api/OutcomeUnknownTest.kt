package com.btcpayapp.data.api

import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpFailure
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.PROXY_FAILED
import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.TlsProblem
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.net.failureOf
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.model.Credential
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * How the client turns a transport failure into what a screen shows.
 *
 * The line that matters: a POST that failed after the connection opened, or
 * whose answer was lost or unreadable, may have been done by the server (a
 * broadcast, a payout), so it must come back as [ApiException.OutcomeUnknown],
 * never as a failure the user would retry. Everything else keeps its
 * ordinary, retryable answer.
 */
class OutcomeUnknownTest {

    /** Answers from [answer] instead of the network, and counts what reached it. */
    private class FakeEngine(private val answer: suspend (HttpRequest) -> HttpResponse) : HttpEngine() {
        var calls = 0
            private set

        override suspend fun execute(request: HttpRequest, options: TransportOptions): HttpResponse {
            calls++
            return answer(request)
        }
    }

    private fun failing(failure: IOException) = FakeEngine { throw failure }

    private fun answering(code: Int, body: String = "") =
        FakeEngine { HttpResponse(code, emptyMap(), body.toByteArray(Charsets.UTF_8)) }

    private suspend fun outcome(
        engine: HttpEngine,
        method: String,
        endpoint: Endpoint = clearnet,
    ): Throwable? = runCatching {
        BtcPayClient(engine)
            .execute(endpoint, method, "api/v1/stores/s/payouts", body = if (method == "GET") null else BODY)
    }.exceptionOrNull()

    // --- POST after the connection opened ---------------------------------

    @Test
    fun `a POST that timed out after it was sent has an unknown outcome`() = runTest {
        val failure = outcome(failing(HttpFailure.Timeout("slow", requestSent = true)), "POST")
        assertTrue("got $failure", failure is ApiException.OutcomeUnknown)
    }

    @Test
    fun `a POST whose connection dropped after it was sent has an unknown outcome`() = runTest {
        assertTrue(outcome(failing(HttpFailure.Transport("dropped", requestSent = true)), "POST") is ApiException.OutcomeUnknown)
        // A reset during a TLS read is a drop too, not a certificate problem.
        val reset = failureOf(SSLException("Read error: ssl=0x7b2c: Connection reset by peer"), requestSent = true)
        assertTrue(outcome(failing(reset), "POST") is ApiException.OutcomeUnknown)
    }

    @Test
    fun `a POST that failed before it was sent can be retried`() = runTest {
        assertTrue(outcome(failing(HttpFailure.Timeout("slow", requestSent = false)), "POST") is ApiException.Timeout)
        assertTrue(outcome(failing(HttpFailure.Transport("refused", requestSent = false)), "POST") is ApiException.Transport)
    }

    @Test
    fun `idempotent methods never produce an unknown outcome`() = runTest {
        for (method in listOf("GET", "PUT", "DELETE", "PATCH")) {
            val failure = outcome(failing(HttpFailure.Timeout("slow", requestSent = true)), method)
            assertTrue("$method got $failure", failure is ApiException.Timeout)
        }
    }

    @Test
    fun `the whole-call deadline is an unknown outcome for a POST and a timeout for a GET`() = runTest {
        val silent = FakeEngine { awaitCancellation() }
        assertTrue(outcome(silent, "POST") is ApiException.OutcomeUnknown)
        assertTrue(outcome(silent, "GET") is ApiException.Timeout)
    }

    // --- Cancellation -----------------------------------------------------

    @Test
    fun `a cancelled caller gets the cancellation, not an ApiException`() = runTest {
        val caller = Job()
        // What a cancelled call looks like from here: the socket is closed
        // under the blocked read, which then fails with an IOException.
        val engine = FakeEngine {
            caller.cancel()
            throw HttpFailure.Transport("socket closed", requestSent = true)
        }

        val failure = runCatching {
            withContext(caller) { BtcPayClient(engine).execute(clearnet, "POST", "api/v1/x", body = BODY) }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is CancellationException)
    }

    @Test
    fun `an outer deadline stays a cancellation`() = runTest {
        // A job's own time limit must end the job, not count as a server timeout.
        val result = withTimeoutOrNull(1_000) {
            BtcPayClient(FakeEngine { awaitCancellation() }).execute(clearnet, "GET", "api/v1/x")
        }
        assertNull(result)
    }

    // --- 404 codes ----------------------------------------------------------

    @Test
    fun `a 404 with a Greenfield code carries the code`() = runTest {
        val failure = outcome(answering(404, """{"code":"transaction-not-found"}"""), "POST")
        assertEquals("transaction-not-found", (failure as ApiException.NotFound).code)
    }

    @Test
    fun `an empty 404 has no code`() = runTest {
        val failure = outcome(answering(404), "GET")
        assertNull((failure as ApiException.NotFound).code)
    }

    // --- Plain messages -----------------------------------------------------

    @Test
    fun `platform exception text never reaches the user`() = runTest {
        val refused = failureOf(
            ConnectException("failed to connect to /10.0.0.2 (port 443) after 15000ms: isConnected failed: ECONNREFUSED"),
            requestSent = false,
        )
        assertEquals("The server refused the connection.", (outcome(failing(refused), "GET") as ApiException).userMessage)

        val raw = IOException("java.io.EOFException: \\n not found: limit=0 content=…")
        assertEquals("The connection to the server failed.", (outcome(failing(raw), "GET") as ApiException).userMessage)
    }

    @Test
    fun `a socket error through a proxy names the proxy, not the server`() {
        // What a SOCKS connect throws when nothing listens on Orbot's port.
        val stopped = SocketException("Connection refused")
        assertEquals(PROXY_FAILED, failureOf(stopped, requestSent = false, viaProxy = true).message)
        assertEquals(
            PROXY_FAILED,
            failureOf(ConnectException("ECONNREFUSED"), requestSent = false, viaProxy = true).message,
        )
        // Once the connection opened, it is a dropped connection as before.
        val dropped = failureOf(SocketException("Connection reset"), requestSent = true, viaProxy = true)
        assertEquals("The connection to the server failed.", dropped.message)
        assertTrue(dropped.requestSent)
        // Certificate and timeout answers keep their own text through a proxy.
        val untrusted = SSLHandshakeException("Trust anchor for certification path not found.")
        assertTrue(failureOf(untrusted, requestSent = false, viaProxy = true) is HttpFailure.Tls)
        assertTrue(failureOf(SocketTimeoutException("slow circuit"), requestSent = false, viaProxy = true) is HttpFailure.Timeout)
    }

    @Test
    fun `a certificate failure carries its problem and fixed text`() = runTest {
        val untrusted = failureOf(SSLHandshakeException("Trust anchor for certification path not found."), requestSent = false)
        val failure = outcome(failing(untrusted), "POST") as ApiException.Tls
        assertEquals(TlsProblem.UntrustedIssuer, failure.problem)
        assertEquals(TlsProblem.UntrustedIssuer.userMessage, failure.userMessage)
    }

    // --- Answers that do not settle a POST --------------------------------

    @Test
    fun `a gateway that lost the answer to a POST leaves the outcome unknown`() = runTest {
        for (code in listOf(502, 504, 520, 524)) {
            val failure = outcome(answering(code, "<html>Bad Gateway</html>"), "POST")
            assertTrue("$code got $failure", failure is ApiException.OutcomeUnknown)
            // The answer that did arrive stays the cause, for a read-only POST to show.
            assertEquals(code, (failure?.cause as ApiException.Server).status)
        }
        // Repeating a GET is safe, so it keeps the ordinary server error.
        assertEquals(504, (outcome(answering(504), "GET") as ApiException.Server).status)
    }

    @Test
    fun `a refusal from BTCPay itself stays a plain server error`() = runTest {
        val failure = outcome(answering(503, """{"code":"lightning-node-unavailable","message":"Down"}"""), "POST")
        assertEquals("lightning-node-unavailable", (failure as ApiException.Server).code)
        assertTrue(outcome(answering(500), "POST") is ApiException.Server)
    }

    @Test
    fun `a success answer to a POST that cannot be read leaves the outcome unknown`() = runTest {
        val api = BtcPayApi(BtcPayClient(answering(200, "<html>not json</html>")), clearnet)
        val post = runCatching { api.post<InvoiceData>("api/v1/stores/s/invoices", "{}") }.exceptionOrNull()
        assertTrue("got $post", post is ApiException.OutcomeUnknown)
        assertTrue("cause ${post?.cause}", post?.cause is ApiException.Decoding)
        // A read changes nothing, so it keeps the plain decoding error.
        val get = runCatching { api.get<InvoiceData>("api/v1/stores/s/invoices/i") }.exceptionOrNull()
        assertTrue("got $get", get is ApiException.Decoding)
    }

    // --- The real engine: where "sent" begins -------------------------------

    @Test
    fun `a POST to a port nobody listens on was never sent`() = runBlocking {
        val port = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        val failure = outcome(HttpEngine(), "POST", local(port))
        assertTrue("got $failure", failure is ApiException.Transport)
    }

    @Test
    fun `a POST whose body arrived before the connection dropped has an unknown outcome`() = runBlocking {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            // Reads the whole request, then hangs up without an answer.
            val peer = thread { server.accept().use { readRequest(it) } }
            val failure = outcome(HttpEngine(), "POST", local(server.localPort))
            peer.join(5_000)
            assertTrue("got $failure", failure is ApiException.OutcomeUnknown)
        }
    }

    @Test
    fun `a POST through a stopped proxy names the proxy and was never sent`() = runBlocking {
        val port = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
        for (socks in listOf(true, false)) {
            val endpoint = onion.copy(
                transport = TransportOptions(
                    proxy = ProxySpec("127.0.0.1", port, socks),
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 2_000,
                ),
            )
            val failure = outcome(HttpEngine(), "POST", endpoint)
            assertEquals("socks=$socks", PROXY_FAILED, (failure as ApiException.Transport).userMessage)
        }
    }

    /** Reads the head and a Content-Length body, and nothing after. */
    private fun readRequest(socket: Socket) {
        val input = socket.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) return
            head.append(byte.toChar())
        }
        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { if (input.read() == -1) return }
    }

    /** Plain http to this machine, with short timeouts so a regression fails fast. */
    private fun local(port: Int) = Endpoint(
        baseUrl = "http://127.0.0.1:$port",
        credential = KEY,
        transport = TransportOptions(connectTimeoutMs = 2_000, readTimeoutMs = 2_000),
    )

    // --- Proxy on this phone ------------------------------------------------

    @Test
    fun `plain http through a proxy on another machine is refused before anything is sent`() = runTest {
        val engine = answering(200)
        val failure = outcome(engine, "GET", onion.via(ProxySpec("192.168.1.2", 9050)))
        assertTrue(
            "got $failure",
            (failure as ApiException.Transport).userMessage.startsWith("This account's proxy is not on this phone"),
        )
        assertEquals(0, engine.calls)
    }

    @Test
    fun `plain http through a proxy on this phone goes out`() = runTest {
        val engine = answering(200)
        assertNull(outcome(engine, "GET", onion.via(ProxySpec("127.0.0.1", 8118, socks = false))))
        assertEquals(1, engine.calls)
    }

    @Test
    fun `https through a remote proxy goes out, because TLS protects the key`() = runTest {
        val engine = answering(200)
        assertNull(outcome(engine, "GET", clearnet.via(ProxySpec("192.168.1.2", 3128, socks = false))))
        assertEquals(1, engine.calls)
    }

    private fun Endpoint.via(proxy: ProxySpec) = copy(transport = transport.copy(proxy = proxy))

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val BODY = "{}".toByteArray(Charsets.UTF_8)
        val KEY = Credential.ApiKey(key = "secret-key")
        val clearnet = Endpoint(baseUrl = "https://btcpay.example.org", credential = KEY)
        val onion = Endpoint(
            baseUrl = "http://btcpayabcdefghijklmnop.onion",
            credential = KEY,
            transport = TransportOptions(proxy = ProxySpec.ORBOT),
        )
    }
}
