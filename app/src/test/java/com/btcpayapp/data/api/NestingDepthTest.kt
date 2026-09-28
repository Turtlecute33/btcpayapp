package com.btcpayapp.data.api

import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.data.api.dto.InvoiceData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The JSON parser reads a nested array by recursion, so a free-form field
 * (an invoice's metadata) nested some thousand levels deep overflows the
 * stack. That is an Error, which no screen or sync job catches, and the app
 * stops. It must come back as a response the app cannot read.
 */
class NestingDepthTest {

    private val client = BtcPayClient(object : HttpEngine() {
        override suspend fun execute(request: HttpRequest, options: TransportOptions): HttpResponse = error("not used")
    })

    private fun invoices(levels: Int) =
        """[{"id":"i","metadata":{"a":${"[".repeat(levels)}0${"]".repeat(levels)}}}]"""

    private suspend fun decode(body: String): List<InvoiceData> =
        client.decode(HttpResponse(200, emptyMap(), body.toByteArray()), ListSerializer(InvoiceData.serializer()))

    @Test
    fun `a deeply nested response is a decode failure, not a stack overflow`() = runTest {
        try {
            decode(invoices(100_000))
            fail("decoded")
        } catch (e: ApiException.Decoding) {
            // Expected.
        }
    }

    @Test
    fun `ordinary nesting still decodes`() = runTest {
        assertEquals("i", decode(invoices(20)).single().id)
    }

    @Test
    fun `brackets in strings are not nesting`() {
        assertFalse(nestsDeeperThan(""""[[[[\"[[[["""", 2))
        assertFalse(nestsDeeperThan("""{"a":["[[[["],"b":{"c":"{{{{"}}""", 2))
        assertTrue(nestsDeeperThan("""{"a":[[1]]}""", 2))
    }
}
