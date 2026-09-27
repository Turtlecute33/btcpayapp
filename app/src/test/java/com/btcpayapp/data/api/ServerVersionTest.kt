package com.btcpayapp.data.api

import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.data.api.endpoints.requireSupportedServer
import com.btcpayapp.data.model.Credential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server-version rule: what the app reads from `server/info`, the oldest
 * server it pairs with, and the releases that gate single features.
 */
class ServerVersionTest {

    @Test
    fun `parses the leading release number`() {
        assertEquals(ServerVersion(2, 4, 1), ServerVersion.parse("2.4.1"))
        // BTCPay reports a fourth assembly number; it does not change the API.
        assertEquals(ServerVersion(2, 3, 0), ServerVersion.parse("2.3.0.1"))
        assertEquals(ServerVersion(2, 2, 0), ServerVersion.parse("v2.2"))
        assertEquals(ServerVersion(2, 4, 1), ServerVersion.parse("2.4.1-rc1"))
    }

    @Test
    fun `anything without a release number is unknown`() {
        assertNull(ServerVersion.parse(""))
        assertNull(ServerVersion.parse("abc"))
        assertNull(ServerVersion.parse(null))
        // Too many digits to be a real release: unknown rather than an overflow.
        assertNull(ServerVersion.parse("99999999999.1"))
    }

    @Test
    fun `orders by major, then minor, then patch`() {
        val ordered = listOf("1.13.5", "2.0.0", "2.1.9", "2.2.0", "2.3.10", "2.10.0", "10.0.0")
            .map { requireNotNull(ServerVersion.parse(it)) }
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        assertEquals(0, ServerVersion(2, 2).compareTo(ServerVersion(2, 2, 0)))
        assertEquals("2.2.0", ServerVersion(2, 2).toString())
    }

    @Test
    fun `the minimum and the feature gates are the checked upstream releases`() {
        assertEquals(ServerVersion(2, 2, 0), ServerVersion.MINIMUM)
        assertEquals(ServerVersion(2, 3, 3), ServerVersion.SIGNED_BROADCAST)
        assertEquals(ServerVersion(2, 3, 7), ServerVersion.CROWDFUND_EDIT)
        assertEquals(ServerVersion(2, 4, 4), ServerVersion.STORE_INVITATIONS)
        // Every gate is above the minimum, or it would gate nothing.
        listOf(ServerVersion.SIGNED_BROADCAST, ServerVersion.CROWDFUND_EDIT, ServerVersion.STORE_INVITATIONS)
            .forEach { assertTrue("$it", it > ServerVersion.MINIMUM) }
    }

    @Test
    fun `pairing refuses a server below the minimum and names its version`() = runTest {
        val failure = runCatching { apiReporting("2.1.0.0").requireSupportedServer() }.exceptionOrNull()

        assertTrue("expected Unsupported, got $failure", failure is ApiException.Unsupported)
        assertEquals(
            "This app needs BTCPay Server 2.2 or later. This server runs 2.1.0. " +
                "Update the server, then connect again.",
            (failure as ApiException).userMessage,
        )
    }

    @Test
    fun `pairing accepts the minimum, newer servers and unknown versions`() = runTest {
        for (version in listOf("2.2.0", "2.4.4.0", "", "custom-build")) {
            assertEquals(version, apiReporting(version).requireSupportedServer().version)
        }
    }

    /** An API whose `server/info` reports [version]. */
    private fun apiReporting(version: String) = BtcPayApi(
        BtcPayClient(
            object : HttpEngine() {
                override suspend fun execute(request: HttpRequest, options: TransportOptions) =
                    HttpResponse(200, emptyMap(), """{"version":"$version"}""".toByteArray(Charsets.UTF_8))
            },
        ),
        Endpoint(baseUrl = "https://btcpay.example.org", credential = Credential.ApiKey(key = "test-key")),
    )
}
