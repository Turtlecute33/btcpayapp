package com.btcpayapp.core.net

import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class RedirectsTest {
    private val request = HttpRequest("POST", URL("https://example.com/invoices"), body = byteArrayOf(1), contentType = "application/json")
    private val target = URL("https://example.com/result")
    @Test fun `303 reads the result without replaying a write`() {
        val next = redirectedRequest(request, 303, target)
        assertEquals("GET", next.method)
        assertNull(next.body)
        assertNull(next.contentType)
    }
    @Test fun `ambiguous redirects cannot repeat writes`() {
        for (status in listOf(301, 302)) assertThrows(HttpFailure.Transport::class.java) { redirectedRequest(request, status, target) }
    }
    @Test fun `307 preserves the explicit method and body`() {
        val next = redirectedRequest(request, 307, target)
        assertEquals("POST", next.method)
        assertArrayEquals(request.body, next.body)
    }
}
