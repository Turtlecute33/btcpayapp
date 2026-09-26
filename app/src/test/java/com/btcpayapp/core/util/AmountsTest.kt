package com.btcpayapp.core.util

import com.btcpayapp.data.model.BitcoinUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * Money handling. Every case here is one where a `Double` implementation would
 * be wrong by a satoshi or two, which is exactly the kind of error that is
 * invisible in testing and infuriating in production.
 */
class AmountsTest {

    private val uk = Locale.UK

    @Test
    fun `btc to sats does not lose precision`() {
        assertEquals(BigDecimal("2100000000000000"), Amounts.btcToSats(BigDecimal("21000000")))
        assertEquals(BigDecimal("1"), Amounts.btcToSats(BigDecimal("0.00000001")))
    }

    @Test
    fun `sats round trip`() {
        val btc = BigDecimal("0.12345678")
        assertEquals(btc, Amounts.satsToBtc(Amounts.btcToSats(btc)))
    }

    @Test
    fun `millisatoshi strings convert without rounding away a satoshi`() {
        assertEquals(BigDecimal("1000.000"), Amounts.msatToSats("1000000"))
        // 1500 msat is one and a half satoshi, and must not silently become two.
        assertEquals(BigDecimal("1.500"), Amounts.msatToSats("1500"))
        assertEquals(BigDecimal.ZERO.setScale(3), Amounts.msatToSats(null))
    }

    @Test
    fun `satoshi to millisatoshi is an integer string`() {
        assertEquals("21000", Amounts.satsToMsat(BigDecimal("21")))
    }

    @Test
    fun `bitcoin formatting honours the unit`() {
        val btc = BigDecimal("0.00021")
        assertEquals("0.00021 BTC", Amounts.formatBitcoin(btc, BitcoinUnit.Btc, uk))
        assertEquals("21,000 sat", Amounts.formatBitcoin(btc, BitcoinUnit.Sat, uk))
    }

    @Test
    fun `crypto codes fall back to a suffix because they are not ISO 4217`() {
        assertEquals("0.5 BTC", Amounts.format(BigDecimal("0.50000000"), "BTC", uk))
        assertEquals("1500 SATS", Amounts.format(BigDecimal("1500"), "SATS", uk))
    }

    @Test
    fun `an unknown currency code does not throw`() {
        assertEquals("12.34 XYZ", Amounts.format(BigDecimal("12.34"), "XYZ", uk))
    }

    @Test
    fun `trim drops trailing zeros without scientific notation`() {
        assertEquals("0.0001", Amounts.trim(BigDecimal("0.00010000"), 8))
        assertEquals("1000", Amounts.trim(BigDecimal("1000.00"), 2))
        assertEquals("0", Amounts.trim(BigDecimal.ZERO, 8))
    }

    @Test
    fun `masking keeps a stable width`() {
        val masked = Amounts.masked("£1,234.56")
        assertEquals(8, masked.length)
    }
}

class DatesTest {

    @Test
    fun `relative time reads naturally`() {
        val now = 1_710_000_000L
        assertEquals("just now", Dates.relative(now - 10, now))
        assertEquals("5 min ago", Dates.relative(now - 300, now))
        assertEquals("2 h ago", Dates.relative(now - 7_200, now))
        assertEquals("3 d ago", Dates.relative(now - 3 * 86_400, now))
        assertEquals("in 5 min", Dates.relative(now + 300, now))
    }

    @Test
    fun `countdown is null once expired`() {
        val now = 1_710_000_000L
        assertEquals(null, Dates.countdown(now - 1, now))
        assertEquals("5:00", Dates.countdown(now + 300, now))
        assertEquals("1:00:00", Dates.countdown(now + 3600, now))
    }
}

class TextTest {

    @Test
    fun `html is stripped rather than rendered`() {
        // Notification bodies are server-supplied HTML. Rendering them in a
        // WebView would be a needless injection surface.
        assertEquals(
            "Invoice paid\nView it",
            Text.stripHtml("<p>Invoice <b>paid</b></p>View it"),
        )
        assertEquals("a & b", Text.stripHtml("a &amp; b"))
        assertEquals("<script>", Text.stripHtml("&lt;script&gt;"))
    }

    @Test
    fun `middle ellipsis keeps both ends`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val short = Text.middleEllipsis(address, 8, 6)
        assertEquals("bc1qar0s…wf5mdq", short)
    }

    @Test
    fun `short values are left alone`() {
        assertEquals("abc", Text.middleEllipsis("abc"))
    }

    @Test
    fun `camel case becomes readable`() {
        assertEquals("Awaiting approval", Text.sentenceCase("AwaitingApproval"))
    }
}
