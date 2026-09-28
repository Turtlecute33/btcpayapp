package com.btcpayapp.core.util

import com.btcpayapp.data.model.BitcoinUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // --- An amount in a stated currency ------------------------------------

    @Test
    fun `an amount in a currency is kept up to its decimals`() {
        assertEquals(BigDecimal("10.01"), Amounts.inCurrency("10.01", "USD"))
        assertEquals(BigDecimal("10.50"), Amounts.inCurrency("10.50", "USD"))
        assertEquals(BigDecimal("0.12345678"), Amounts.inCurrency("0.12345678", "BTC"))
        assertEquals(BigDecimal("100"), Amounts.inCurrency("100", "JPY"))
    }

    @Test
    fun `an amount in a currency is refused past its decimals, and at zero or less`() {
        assertNull(Amounts.inCurrency("10.0051", "USD"))
        assertNull(Amounts.inCurrency("0.123456789", "BTC"))
        assertNull(Amounts.inCurrency("1.5", "JPY"))
        assertNull(Amounts.inCurrency("0", "USD"))
        assertNull(Amounts.inCurrency("-1", "USD"))
    }

    @Test
    fun `the field error names the decimal limit`() {
        assertEquals("Use at most 2 decimal places for USD.", Amounts.inCurrencyProblem("10.0051", "USD"))
        assertEquals("Enter a whole number of JPY.", Amounts.inCurrencyProblem("1.5", "JPY"))
        assertEquals("Enter a number, for example 12.50.", Amounts.inCurrencyProblem("abc", "USD"))
        // Left to the caller, and with no currency yet no limit is known.
        assertNull(Amounts.inCurrencyProblem("", "USD"))
        assertNull(Amounts.inCurrencyProblem("0", "USD"))
        assertNull(Amounts.inCurrencyProblem("10.0051", ""))
    }

    // --- A fee rate --------------------------------------------------------

    @Test
    fun `a fee rate reads a comma or a point as the decimal`() {
        assertEquals(0, BigDecimal("2.5").compareTo(Amounts.feeRate("2,5")))
        assertEquals(0, BigDecimal("12").compareTo(Amounts.feeRate(" 12 ")))
        // Refused as an amount, where it can mean 1125; a rate has no thousands.
        assertEquals(0, BigDecimal("1.125").compareTo(Amounts.feeRate("1.125")))
        assertEquals(0, BigDecimal("2.5").compareTo(Amounts.feeRate("2,500")))
        assertEquals(0, BigDecimal("0.125").compareTo(Amounts.feeRate(".125")))
        for (text in listOf("", "0", "0.000", "-1.125", "abc", "1.2.3", "1e")) assertNull(text, Amounts.feeRate(text))
    }

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
    fun `trim holds a server's divisibility to a sane scale`() {
        assertEquals("0.001", Amounts.trim(BigDecimal("0.001"), Int.MAX_VALUE))
        assertEquals("1", Amounts.trim(BigDecimal("1.4"), Int.MIN_VALUE))
    }

    @Test
    fun `the mask has one width whatever it hides`() {
        // A mask as long as the amount told a shoulder-surfer its magnitude.
        assertEquals(6, Amounts.MASK.length)
    }

    @Test
    fun `btc follows the locale decimal separator like sats do`() {
        val btc = BigDecimal("0.00021")
        assertEquals("0,00021 BTC", Amounts.formatBitcoin(btc, BitcoinUnit.Btc, Locale.GERMANY))
        assertEquals("21.000 sat", Amounts.formatBitcoin(btc, BitcoinUnit.Sat, Locale.GERMANY))
        assertEquals("0,5 BTC", Amounts.format(BigDecimal("0.5"), "BTC", Locale.GERMANY))
    }

    @Test
    fun `the other unit echoes the value both ways`() {
        val btc = BigDecimal("0.00021")
        assertEquals("21,000 sat", Amounts.inOtherUnit(btc, BitcoinUnit.Btc, uk))
        assertEquals("0.00021 BTC", Amounts.inOtherUnit(btc, BitcoinUnit.Sat, uk))
    }

    // --- Server numbers ----------------------------------------------------

    @Test
    fun `an absurd server number is refused instead of crashing the screen`() {
        // A rescale past 2^31 throws inside the msat divide; Home draws the
        // Lightning balance in composition, so this used to crash every launch.
        assertNull(Amounts.serverDecimal("1e2147483647"))
        assertNull(Amounts.serverDecimal("1e999999999"))
        assertNull(Amounts.serverDecimal("not a number"))
        assertNull(Amounts.serverDecimal(null))
        assertEquals(0, Amounts.msatToSats("1e2147483647").signum())
        assertEquals(BigDecimal("1500"), Amounts.serverDecimal(" 1500 "))
    }

    // --- Parsing typed amounts ---------------------------------------------

    @Test
    fun `unambiguous amounts parse as decimals`() {
        mapOf(
            "0.001" to "0.001",
            ".001" to "0.001",
            "1234.567" to "1234.567",
            "1,5" to "1.5",
            "12,50" to "12.50",
            "1.2345" to "1.2345",
            "1.1250" to "1.1250",
            "-0,125" to "-0.125",
            "١٢,٥٠" to "12.50",
        ).forEach { (typed, expected) ->
            assertEquals("\"$typed\"", BigDecimal(expected), Amounts.parse(typed))
        }
    }

    @Test
    fun `a lone separator before three digits is refused both ways`() {
        // Read as a decimal, "21,000" sat was 21 sat; read as a group, "1.125"
        // BTC would be 1125 BTC. Either guess moves the wrong amount.
        // "٢١,٠٠٠" is 21,000 in Arabic-Indic digits, which the JDK reads too.
        listOf("1,000", "21,000", "1.000", "500,000", "1.125", "1,125", "-1,000", "+1.000", " 21,000 ", "٢١,٠٠٠").forEach {
            assertNull("\"$it\"", Amounts.parse(it))
        }
    }

    @Test
    fun `the Arabic decimal mark that Persian screens show parses back`() {
        val shown = Amounts.formatBitcoin(BigDecimal("0.001"), BitcoinUnit.Btc, Locale.forLanguageTag("fa"))
        assertEquals("0٫001 BTC", shown)
        assertEquals(BigDecimal("0.001"), Amounts.parse(shown.removeSuffix(" BTC")))
        // Never a thousands mark, so three digits after it are not ambiguous.
        assertEquals(BigDecimal("1.125"), Amounts.parse("١٫١٢٥"))
        assertNull(Amounts.parseProblem("12٫50"))
        // U+066C is the thousands mark; it and a second separator stay refused.
        assertNull(Amounts.parse("1٬000"))
        assertNull(Amounts.parse("1,000٫5"))
    }

    @Test
    fun `the field error names both readings and how to type each`() {
        assertEquals(
            "1.125 can mean 1125 or a decimal. Type 1125, or 1.1250 for the decimal.",
            Amounts.parseProblem("1.125"),
        )
        assertEquals(
            "-1,000 can mean -1000 or a decimal. Type -1000, or -1,0000 for the decimal.",
            Amounts.parseProblem("-1,000"),
        )
        assertEquals("Enter a number, for example 12.50.", Amounts.parseProblem("abc"))
        assertNull(Amounts.parseProblem(""))
        assertNull(Amounts.parseProblem("12,50"))
    }

    @Test
    fun `both suggested spellings parse to what they say`() {
        assertEquals(BigDecimal("1125"), Amounts.parse("1125"))
        assertEquals(0, BigDecimal("1.125").compareTo(Amounts.parse("1.1250")!!))
    }

    @Test
    fun `whatever the app writes into a field parses back to the same value`() {
        listOf(
            "1.125" to 8,
            "12.345" to 8,
            "0.001" to 8,
            "12.5" to 8,
            "21" to 0,
            "1000" to 0,
            "-1.125" to 8,
        ).forEach { (value, scale) ->
            val input = Amounts.toInput(BigDecimal(value), scale)
            val parsed = Amounts.parse(input)
            assertNotNull("\"$input\" did not parse", parsed)
            assertEquals("\"$input\"", 0, BigDecimal(value).compareTo(parsed!!))
        }
        assertEquals("1.1250", Amounts.toInput(BigDecimal("1.125"), 3))
        assertEquals("12.5", Amounts.toInput(BigDecimal("12.50"), 8))
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

    @Test
    fun `hex is lowercase with two digits per byte`() {
        assertEquals("00010f10ff", byteArrayOf(0, 1, 15, 16, -1).toHex())
        assertEquals("", ByteArray(0).toHex())
    }
}
