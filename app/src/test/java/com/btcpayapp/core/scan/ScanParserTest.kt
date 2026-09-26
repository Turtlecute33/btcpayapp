package com.btcpayapp.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The scanner feeds four different screens, so a misclassification here shows up
 * as "the app closed the camera and did nothing". Each case below is a payload
 * shape BTCPay or a wallet actually emits.
 */
class ScanParserTest {

    @Test
    fun `bare bech32 address is recognised`() {
        val result = ScanParser.parse("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq")
        assertTrue(result is ScannedPayload.BitcoinAddress)
    }

    @Test
    fun `bip21 carries amount and lightning fallback`() {
        val raw = "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" +
            "?amount=0.00015&label=Coffee&lightning=lnbc150n1pjxyz&pj=https://pay.example/BTC/pj"

        val result = ScanParser.parse(raw)
        assertTrue(result is ScannedPayload.Bip21)
        result as ScannedPayload.Bip21

        assertEquals(BigDecimal("0.00015"), result.amountBtc)
        assertEquals("Coffee", result.label)
        assertEquals("lnbc150n1pjxyz", result.lightning)
        assertEquals("https://pay.example/BTC/pj", result.payjoinEndpoint)
    }

    @Test
    fun `uppercase bip21 from a qr still parses`() {
        // QR encoders uppercase bech32 to reach alphanumeric mode, and scanners
        // hand that back verbatim.
        val result = ScanParser.parse("BITCOIN:BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ")
        assertTrue(result is ScannedPayload.Bip21)
    }

    @Test
    fun `lightning prefix is stripped from a bolt11`() {
        val bolt11 = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs"
        val result = ScanParser.parse("lightning:$bolt11")
        assertTrue(result is ScannedPayload.Bolt11)
        assertEquals(bolt11, (result as ScannedPayload.Bolt11).invoice)
    }

    @Test
    fun `lnurl is recognised in both forms`() {
        assertTrue(ScanParser.parse("lnurl1dp68gurn8ghj7") is ScannedPayload.Lnurl)
        assertTrue(ScanParser.parse("lightning:LNURL1DP68GURN8GHJ7") is ScannedPayload.Lnurl)
    }

    @Test
    fun `node uri is recognised`() {
        val pubkey = "03".padEnd(66, 'a')
        val result = ScanParser.parse("$pubkey@node.example.com:9735")
        assertTrue(result is ScannedPayload.NodeUri)
    }

    @Test
    fun `login code url yields server and code`() {
        val result = ScanParser.parse(
            "https://pay.example.com/login?LoginCode=abcdef0123456789&returnUrl=%2F",
        )
        assertTrue(result is ScannedPayload.ServerUrl)
        result as ScannedPayload.ServerUrl
        assertEquals("https://pay.example.com", result.baseUrl)
        assertEquals("abcdef0123456789", result.loginCode)
    }

    @Test
    fun `semicolon triple is the compact login form`() {
        val result = ScanParser.parse("abc123;https://pay.example.com;me@example.com")
        assertTrue(result is ScannedPayload.ServerUrl)
        result as ScannedPayload.ServerUrl
        assertEquals("https://pay.example.com", result.baseUrl)
        assertEquals("abc123", result.loginCode)
        assertEquals("me@example.com", result.email)
    }

    @Test
    fun `invitation link is kept whole`() {
        val raw = "https://pay.example.com/invite/user-id/code"
        assertTrue(ScanParser.parse(raw) is ScannedPayload.Invitation)
    }

    @Test
    fun `nonsense is not turned into a server`() {
        assertTrue(ScanParser.parse("hello world") is ScannedPayload.Unknown)
        assertTrue(ScanParser.parse("") is ScannedPayload.Unknown)
    }
}

class NormaliseServerUrlTest {

    @Test
    fun `bare host defaults to https`() {
        assertEquals("https://pay.example.com", ScanParser.normaliseServerUrl("pay.example.com"))
    }

    @Test
    fun `onion defaults to http because tor already authenticates`() {
        val onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxyz234567.onion"
        assertEquals("http://$onion", ScanParser.normaliseServerUrl(onion))
    }

    @Test
    fun `explicit scheme and port survive`() {
        assertEquals(
            "https://pay.example.com:8443",
            ScanParser.normaliseServerUrl("https://pay.example.com:8443/"),
        )
    }

    @Test
    fun `trailing slashes are trimmed`() {
        assertEquals("https://pay.example.com", ScanParser.normaliseServerUrl("https://pay.example.com///"))
    }

    @Test
    fun `sub path is preserved for reverse-proxied instances`() {
        assertEquals(
            "https://example.com/btcpay",
            ScanParser.normaliseServerUrl("https://example.com/btcpay/"),
        )
    }

    @Test
    fun `garbage is rejected rather than guessed at`() {
        assertNull(ScanParser.normaliseServerUrl(""))
        assertNull(ScanParser.normaliseServerUrl("not a host"))
        assertNull(ScanParser.normaliseServerUrl("nodots"))
    }
}
