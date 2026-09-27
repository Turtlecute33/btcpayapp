package com.btcpayapp.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.math.BigDecimal
import org.junit.Test

/**
 * How many minor units each currency has.
 *
 * The terminal keypad accumulates an integer of minor units and only divides
 * at the end, so the scale it divides by *is* the price. The currency chips
 * offer SATS and the store's own default, so a scale hard-coded to 2 for every
 * currency except BTC would bill a 1000 sat sale as 10 sat. The keypad's own
 * arithmetic is tested against the real code in TerminalStateTest.
 */
class CurrencyScaleTest {

    @Test
    fun `satoshi has no minor units`() {
        assertEquals(0, Amounts.scaleFor("SATS"))
        assertEquals(0, Amounts.scaleFor("SAT"))
        assertEquals(0, Amounts.scaleFor("sats"))
    }

    @Test
    fun `bitcoin has eight`() {
        assertEquals(8, Amounts.scaleFor("BTC"))
        assertEquals(8, Amounts.scaleFor("btc"))
    }

    @Test
    fun `zero-decimal fiat is not assumed to have two`() {
        assertEquals(0, Amounts.scaleFor("JPY"))
        assertEquals(0, Amounts.scaleFor("KRW"))
    }

    @Test
    fun `three-decimal fiat is not assumed to have two`() {
        assertEquals(3, Amounts.scaleFor("KWD"))
        assertEquals(3, Amounts.scaleFor("BHD"))
    }

    @Test
    fun `ordinary fiat has two`() {
        assertEquals(2, Amounts.scaleFor("USD"))
        assertEquals(2, Amounts.scaleFor("EUR"))
    }

    @Test
    fun `an unknown code falls back to two rather than throwing`() {
        assertEquals(2, Amounts.scaleFor("NOTACURRENCY"))
    }
}

/**
 * The decimal separator shown by `KeyboardType.Decimal` is the locale's, and
 * `toBigDecimalOrNull` accepts only '.'. With it, a German or Czech merchant
 * typing "12,50" would get null — and a call site would grey out the submit
 * button with no message, save a point-of-sale item with no price, or default
 * an unattended-payout threshold to zero.
 */
class AmountParseTest {

    @Test
    fun `accepts a full stop`() {
        assertEquals(BigDecimal("12.50"), Amounts.parse("12.50"))
    }

    @Test
    fun `accepts a comma as the decimal separator`() {
        assertEquals(BigDecimal("12.50"), Amounts.parse("12,50"))
        assertEquals(BigDecimal("2.5"), Amounts.parse("2,5"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        // `BigDecimal(" 10")` throws, so untrimmed, a leading space from a paste
        // would make the Create button inert with no explanation.
        assertEquals(BigDecimal("10"), Amounts.parse(" 10 "))
    }

    @Test
    fun `rejects blank input`() {
        assertNull(Amounts.parse(""))
        assertNull(Amounts.parse("   "))
    }

    @Test
    fun `rejects nonsense rather than defaulting`() {
        assertNull(Amounts.parse("abc"))
        assertNull(Amounts.parse("1.2.3"))
    }

    @Test
    fun `refuses to guess at an ambiguous group separator`() {
        // "1,234" could be 1234 or 1.234. Guessing wrong about money is worse
        // than asking the user to retype it.
        assertNull(Amounts.parse("1,234.56"))
        assertNull(Amounts.parse("1,234"))
        assertNull(Amounts.parse("1.234"))
        assertNull(Amounts.parse("21,000"))
    }
}

class RelativeDateTest {

    @Test
    fun `never renders zero minutes ago`() {
        // 45-59 seconds must not fall through to "${seconds / 60} min", which
        // would show "0 min ago" on the invoice list right after a payment.
        val now = 1_000_000L
        for (age in 0..59L) {
            assertEquals("just now", Dates.relative(now - age, now))
        }
        assertEquals("1 min ago", Dates.relative(now - 60, now))
    }
}
