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
    @Test fun `a refused redirect says so in plain text`() {
        val ambiguous = assertThrows(HttpFailure.Transport::class.java) { redirectedRequest(request, 302, target) }
        val withUser = assertThrows(HttpFailure.Transport::class.java) {
            redirectedRequest(request, 307, URL("https://user:pass@example.com/result"))
        }
        assertEquals(REDIRECT_NOT_FOLLOWED, ambiguous.message)
        assertEquals(REDIRECT_NOT_FOLLOWED, withUser.message)
    }
    @Test fun `307 preserves the explicit method and body`() {
        val next = redirectedRequest(request, 307, target)
        assertEquals("POST", next.method)
        assertArrayEquals(request.body, next.body)
    }

    // Only a same-origin redirect may carry the Authorization header.
    @Test fun `host case does not change the origin`() {
        assertTrue(isSameOrigin(URL("https://BTCPay.Example.com/a"), URL("https://btcpay.example.com/b")))
    }
    @Test fun `another host is another origin`() {
        assertFalse(isSameOrigin(URL("https://btcpay.example.com/a"), URL("https://evil.example.com/a")))
    }
    @Test fun `a scheme change is another origin, so https cannot be downgraded`() {
        assertFalse(isSameOrigin(URL("https://btcpay.example.com/a"), URL("http://btcpay.example.com/a")))
        assertFalse(isSameOrigin(URL("http://btcpay.example.com:443/a"), URL("https://btcpay.example.com/a")))
    }
    @Test fun `an explicit default port equals no port`() {
        assertTrue(isSameOrigin(URL("https://btcpay.example.com/a"), URL("https://btcpay.example.com:443/b")))
        assertTrue(isSameOrigin(URL("http://xyz.onion:80/a"), URL("http://xyz.onion/b")))
    }
    @Test fun `a different port is another origin`() {
        assertFalse(isSameOrigin(URL("https://btcpay.example.com/a"), URL("https://btcpay.example.com:8443/a")))
        assertFalse(isSameOrigin(URL("http://xyz.onion/a"), URL("http://xyz.onion:8080/a")))
    }
}
