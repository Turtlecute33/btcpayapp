package com.btcpayapp.data.api

import com.btcpayapp.data.api.endpoints.pathSegment
import org.junit.Assert.*
import org.junit.Test

class PathSegmentTest {
    @Test fun `identifiers cannot introduce path or query components`() {
        assertEquals("a%2Fb%3Fx%3Dy", "a/b?x=y".pathSegment())
    }
    @Test fun `dot path segments are refused`() {
        assertThrows(IllegalArgumentException::class.java) { "..".pathSegment() }
        assertThrows(IllegalArgumentException::class.java) { ".".pathSegment() }
    }
}
