package com.btcpayapp.ui.screens.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The arithmetic the terminal bills with, run through [TerminalState] itself.
 *
 * The keypad collects minor-unit digits and divides once, by the currency's
 * scale, so that scale is the price. These tests call the real state rather
 * than a copy of its formula: a copy keeps passing while the code it copied
 * changes. Amounts are compared with their scale, because "12.34"
 * and "12.340" are the same number but not the same charge request.
 */
class TerminalStateTest {

    private fun typed(currency: String, keys: String): TerminalState =
        keys.fold(TerminalState(currency = currency)) { state, key -> state.typed(key) }

    @Test
    fun `dollar digits are cents`() {
        assertEquals(BigDecimal("12.34"), typed("USD", "1234").baseAmount)
    }

    @Test
    fun `yen has no minor unit`() {
        assertEquals(BigDecimal("1234"), typed("JPY", "1234").baseAmount)
    }

    @Test
    fun `bahraini dinar has three`() {
        assertEquals(BigDecimal("1.234"), typed("BHD", "1234").baseAmount)
    }

    @Test
    fun `bitcoin has eight`() {
        assertEquals(BigDecimal("0.00001234"), typed("BTC", "1234").baseAmount)
    }

    @Test
    fun `sats are whole, so a 1000 sat sale is not billed as 10`() {
        assertEquals(BigDecimal("1000"), typed("SATS", "1000").baseAmount)
    }

    @Test
    fun `an empty entry is zero and cannot be charged`() {
        val state = TerminalState(currency = "USD")
        assertEquals(0, state.total.signum())
        assertFalse(state.canCharge)
    }

    @Test
    fun `leading zeros are dropped`() {
        val state = typed("USD", "0005")
        assertEquals("5", state.digits)
        assertEquals(BigDecimal("0.05"), state.baseAmount)
    }

    @Test
    fun `entry stops at twelve digits`() {
        val state = typed("USD", "1234567890123456")
        assertEquals(TerminalState.MAX_DIGITS, state.digits.length)
        assertEquals("123456789012", state.digits)
        assertEquals(BigDecimal("1234567890.12"), state.baseAmount)
    }

    @Test
    fun `a tip rounds half up at the currency's scale`() {
        // 15% of 10.05 is 1.5075.
        val state = typed("USD", "1005").copy(tipPercent = 15)
        assertEquals(BigDecimal("1.51"), state.tipAmount)
        assertEquals(BigDecimal("11.56"), state.total)
    }

    @Test
    fun `a tip in yen rounds to a whole yen`() {
        // 15% of 999 is 149.85.
        val state = typed("JPY", "999").copy(tipPercent = 15)
        assertEquals(BigDecimal("150"), state.tipAmount)
        assertEquals(BigDecimal("1149"), state.total)
    }

    @Test
    fun `no tip adds nothing`() {
        val state = typed("USD", "1005")
        assertEquals(0, state.tipAmount.signum())
        assertEquals(state.baseAmount, state.total)
    }

    @Test
    fun `a currency of another scale clears the entry`() {
        // "1234" is 12.34 USD but 0.00001234 BTC: carried over, the operator
        // would charge a number they never typed.
        val state = typed("USD", "1234").copy(tipPercent = 10, askingForTip = true).withCurrency("BTC")
        assertEquals("BTC", state.currency)
        assertEquals("", state.digits)
        assertNull(state.tipPercent)
        assertFalse(state.askingForTip)
    }

    @Test
    fun `a currency of the same scale keeps the entry`() {
        val state = typed("USD", "1234").copy(tipPercent = 10).withCurrency("EUR")
        assertEquals(BigDecimal("12.34"), state.baseAmount)
        assertEquals(10, state.tipPercent)
    }

    @Test
    fun `cannot charge while an invoice is being created`() {
        val state = typed("USD", "100")
        assertTrue(state.canCharge)
        assertFalse(state.copy(creating = true).canCharge)
    }

    @Test
    fun `the next sale keeps the store and the currency`() {
        val state = TerminalState(
            digits = "500",
            currency = "EUR",
            tipPercent = 10,
            creating = true,
            storeId = "store",
            currencyKey = "account|store",
        ).cleared()
        assertEquals("", state.digits)
        assertNull(state.tipPercent)
        assertFalse(state.creating)
        assertEquals("EUR", state.currency)
        assertEquals("store", state.storeId)
        assertEquals("account|store", state.currencyKey)
    }
}
