package com.btcpayapp.ui.screens.home

import com.btcpayapp.data.api.dto.InvoiceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * "0.00 today" after a timeout states a fact nobody measured, so
 * a failed today-query keeps the last totals and says they are old.
 */
class HomeTodayTest {

    private fun paid(currency: String, amount: String) =
        InvoiceData(currency = currency, paidAmount = BigDecimal(amount))

    @Test
    fun `settled invoices are summed per currency`() {
        val state = HomeState().withToday(listOf(paid("USD", "10"), paid("EUR", "5"), paid("USD", "2.5")))
        assertEquals(3, state.settledTodayCount)
        assertEquals(0, BigDecimal("12.5").compareTo(state.todayTotals.getValue("USD").first))
        assertEquals(2, state.todayTotals.getValue("USD").second)
        assertEquals(1, state.todayTotals.getValue("EUR").second)
        assertFalse(state.todayStale)
    }

    @Test
    fun `a failed query keeps the previous totals and marks them stale`() {
        val before = HomeState().withToday(listOf(paid("USD", "10")))
        val after = before.withToday(null)
        assertEquals(before.todayTotals, after.todayTotals)
        assertEquals(1, after.settledTodayCount)
        assertTrue(after.todayStale)
    }

    @Test
    fun `nothing settled yet is one card at zero in the store currency`() {
        val cards = todayCards(emptyMap(), stale = false, defaultCurrency = "EUR")
        assertEquals(setOf("EUR"), cards.keys)
        assertEquals(0, BigDecimal.ZERO.compareTo(cards.getValue("EUR")!!.first))
    }

    @Test
    fun `nothing known is a card with no figure`() {
        val cards = todayCards(emptyMap(), stale = true, defaultCurrency = "EUR")
        assertEquals(setOf("EUR"), cards.keys)
        assertNull(cards.getValue("EUR"))
    }

    @Test
    fun `known totals are shown even when stale`() {
        val totals = mapOf("USD" to (BigDecimal.TEN to 1))
        assertEquals(totals, todayCards(totals, stale = true, defaultCurrency = "EUR"))
    }
}
