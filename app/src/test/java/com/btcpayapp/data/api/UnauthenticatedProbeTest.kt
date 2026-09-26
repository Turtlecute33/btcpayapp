package com.btcpayapp.data.api

import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.data.api.endpoints.health
import com.btcpayapp.data.model.Credential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding health probe.
 *
 * `GET /api/v1/health` is the only endpoint the app can call before an account
 * exists, and the Connect screen builds its [Endpoint] with `credential = null`
 * precisely because of that. [BtcPayClient.execute] guards authenticated calls
 * with a missing credential by throwing [ApiException.NoAccount] *before* any
 * socket is opened — correct for every other endpoint, fatal for this one.
 *
 * If the probe does not pass `authenticate = false`, it is rejected locally
 * and every server, however healthy, reports a connection failure. These tests
 * exercise the transport boundary, which nothing else in the unit suite does.
 */
class UnauthenticatedProbeTest {

    /** Records the request instead of sending it. */
    private class RecordingEngine(
        private val body: String = """{"synchronized":true}""",
        private val code: Int = 200,
    ) : HttpEngine() {
        var request: HttpRequest? = null
            private set

        override suspend fun execute(request: HttpRequest, options: TransportOptions): HttpResponse {
            this.request = request
            return HttpResponse(code, emptyMap(), body.toByteArray(Charsets.UTF_8))
        }
    }

    private fun apiFor(engine: HttpEngine, credential: Credential?) = BtcPayApi(
        BtcPayClient(engine),
        Endpoint(baseUrl = "https://btcpay.example.org", credential = credential),
    )

    @Test
    fun `health probe reaches the network with no credential`() = runTest {
        val engine = RecordingEngine()

        val result = apiFor(engine, credential = null).health()

        val sent = requireNonNull(engine.request)
        assertEquals("GET", sent.method)
        assertEquals("https://btcpay.example.org/api/v1/health", sent.url.toString())
        assertTrue("the probe must be synchronized-aware", result.synchronized)
    }

    @Test
    fun `health probe sends no Authorization header even when a key is held`() = runTest {
        val engine = RecordingEngine()

        apiFor(engine, credential = Credential.ApiKey(key = "secret-key")).health()

        val sent = requireNonNull(engine.request)
        assertNull(
            "probing a URL must not spend the account's key",
            sent.headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) },
        )
    }

    /**
     * The other half of the contract: the missing-credential guard is right for
     * every endpoint that does need a key, and must stay.
     */
    @Test
    fun `an authenticated endpoint with no credential still fails before any request`() = runTest {
        val engine = RecordingEngine()

        val failure = runCatching {
            apiFor(engine, credential = null).get<Unit>("api/v1/server/info")
        }.exceptionOrNull()

        assertTrue("expected NoAccount, got $failure", failure is ApiException.NoAccount)
        assertNull("nothing may reach the transport without a credential", engine.request)
    }

    private fun <T : Any> requireNonNull(value: T?): T =
        value ?: throw AssertionError("the request never reached the transport")
}
