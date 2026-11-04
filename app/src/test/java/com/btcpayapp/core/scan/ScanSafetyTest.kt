package com.btcpayapp.core.scan

import org.junit.Assert.*
import org.junit.Test

class ScanSafetyTest {
    @Test fun `scanned server retains reverse proxy base path`() {
        assertEquals("https://example.com/btcpay", (ScanParser.parse("https://example.com/btcpay/") as ScannedPayload.ServerUrl).baseUrl)
    }
    @Test fun `login QR retains base path and decodes login code`() {
        val result = ScanParser.parse("https://example.com/pay/login?LoginCode=a%2Bb") as ScannedPayload.ServerUrl
        assertEquals("https://example.com/pay", result.baseUrl)
        assertEquals("a+b", result.loginCode)
    }
    @Test fun `userinfo and invalid ports are rejected`() {
        assertNull(ScanParser.normaliseServerUrl("https://user:pass@example.com"))
        assertNull(ScanParser.normaliseServerUrl("https://example.com:65536"))
        assertNull(ScanParser.normaliseServerUrl("https://example.com:0"))
        assertTrue(ScanParser.parse("https://user@example.com") is ScannedPayload.Unknown)
    }
    @Test fun `onion with port defaults to HTTP`() {
        assertEquals("http://example.onion:8080/pay", ScanParser.normaliseServerUrl("example.onion:8080/pay"))
    }
    @Test fun `encoded base path stays encoded`() {
        assertEquals("https://example.com/pay%20here", ScanParser.normaliseServerUrl("https://example.com/pay%20here"))
    }
    @Test fun `required BIP21 extensions are not ignored`() {
        assertTrue(ScanParser.parse("bitcoin:address?req-feature=true") is ScannedPayload.Unknown)
    }
    @Test fun `invalid BIP21 amounts are rejected`() {
        for (amount in listOf("-1", "garbage", "0.000000001", "1e2147483647", "1e2", "1E-3", "+1", ".", "")) {
            assertTrue(ScanParser.parse("bitcoin:address?amount=$amount") is ScannedPayload.Unknown)
        }
    }
    @Test fun `repeated BIP21 amount is rejected`() {
        assertTrue(ScanParser.parse("bitcoin:address?amount=0.0001&amount=1") is ScannedPayload.Unknown)
    }
    @Test fun `valid BIP21 amounts are read`() {
        for ((text, expected) in listOf("1" to "1", "0.5" to "0.5", ".5" to "0.5", "2." to "2", "0.00000001" to "0.00000001")) {
            val result = ScanParser.parse("bitcoin:address?amount=$text") as ScannedPayload.Bip21
            assertEquals(0, result.amountBtc?.compareTo(expected.toBigDecimal()))
        }
    }
}
