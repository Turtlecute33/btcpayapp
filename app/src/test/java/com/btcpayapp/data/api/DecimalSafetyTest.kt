package com.btcpayapp.data.api

import com.btcpayapp.core.util.Amounts
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class DecimalSafetyTest {
    @Test fun `invalid amounts are errors rather than zero`() {
        for (input in listOf("\"garbage\"", "{}", "true", "\"1e2147483647\"")) {
            assertThrows(SerializationException::class.java) { ApiJson.instance.decodeFromString(BigDecimalSerializer, input) }
        }
    }
    @Test fun `extreme user input is rejected before formatting`() {
        assertNull(Amounts.parse("1e2147483647"))
        assertNull(Amounts.parse("9".repeat(1000)))
        assertEquals("12.50", Amounts.parse("12,50")?.toPlainString())
    }
}
