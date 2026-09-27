package com.btcpayapp.data.api

import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.data.api.endpoints.invoiceWithPaymentMethods
import com.btcpayapp.data.model.Credential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checkout needs the invoice's payment methods for its QR. Servers before
 * 2.4.1 ignore `includePaymentMethods` on the single-invoice GET and leave the
 * list out, or send it empty (2.3.6 to 2.4.0), so both must fall back to the
 * `.../payment-methods` route.
 */
class InvoicePaymentMethodsTest {

    /** Answers the invoice GET with [invoice], the fallback route with one method, and records the paths. */
    private class FakeServer(private val invoice: String) : HttpEngine() {
        val paths = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest, options: TransportOptions): HttpResponse {
            val path = request.url.path
            paths += path
            val body = if (path.endsWith("/payment-methods")) FALLBACK else invoice
            return HttpResponse(200, emptyMap(), body.toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun load(invoice: String): Pair<List<String>, List<String>> {
        val server = FakeServer(invoice)
        val api = BtcPayApi(BtcPayClient(server), Endpoint("https://btcpay.example.org", Credential.ApiKey(key = "k")))
        val methods = api.invoiceWithPaymentMethods("s", "i").paymentMethods.orEmpty().map { it.paymentMethodId }
        return methods to server.paths
    }

    @Test
    fun `a missing list is fetched from the payment-methods route`() = runTest {
        val (methods, paths) = load("""{"id":"i"}""")
        assertEquals(listOf("BTC-CHAIN"), methods)
        assertEquals(listOf(INVOICE, "$INVOICE/payment-methods"), paths)
    }

    @Test
    fun `an empty list is fetched from the payment-methods route`() = runTest {
        val (methods, paths) = load("""{"id":"i","paymentMethods":[]}""")
        assertEquals(listOf("BTC-CHAIN"), methods)
        assertEquals(listOf(INVOICE, "$INVOICE/payment-methods"), paths)
    }

    @Test
    fun `a list the server sent is used as it is`() = runTest {
        val (methods, paths) = load("""{"id":"i","paymentMethods":[{"paymentMethodId":"BTC-LN"}]}""")
        assertEquals(listOf("BTC-LN"), methods)
        assertEquals(listOf(INVOICE), paths)
    }

    private companion object {
        const val INVOICE = "/api/v1/stores/s/invoices/i"
        const val FALLBACK = """[{"paymentMethodId":"BTC-CHAIN","destination":"bc1qexample"}]"""
    }
}
