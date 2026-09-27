package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.SpeedPolicy
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.ui.screens.store.StoreField
import com.btcpayapp.ui.screens.store.StoreSettingsDraft
import com.btcpayapp.ui.screens.store.currencyProblem
import org.junit.Assert.*
import org.junit.Test

class StoreSettingsDraftTest {
    private val draft = StoreSettingsDraft.of(StoreData())

    @Test fun `unrelated edits preserve exact stored durations`() {
        val store = StoreData(invoiceExpiration = 90, displayExpirationTimer = 60, monitoringExpiration = 3601)
        val loaded = StoreSettingsDraft.of(store)
        assertEquals("1.5", loaded.invoiceExpirationMinutes)
        assertEquals(store, loaded.toStore())
    }
    @Test fun `fractional minutes convert exactly`() {
        assertEquals(150, draft.copy(invoiceExpirationMinutes = "2.5", displayExpirationSeconds = "60").toStore().invoiceExpiration)
    }
    @Test fun `a decimal comma in a duration is a decimal point`() {
        assertEquals(150, draft.copy(invoiceExpirationMinutes = "2,5", displayExpirationSeconds = "60").toStore().invoiceExpiration)
    }
    @Test fun `cleared and overflowing numeric fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { draft.copy(displayExpirationSeconds = "").toStore() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(invoiceExpirationMinutes = "999999999999").toStore() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(paymentTolerancePercent = "NaN").toStore() }
    }
    @Test fun `0 minutes expiration rejected`() {
        assertTrue(StoreField.InvoiceExpiration in draft.copy(invoiceExpirationMinutes = "0").errors())
        assertTrue(StoreField.InvoiceExpiration !in draft.copy(invoiceExpirationMinutes = "1", displayExpirationSeconds = "60").errors())
    }
    @Test fun `9 minutes monitoring rejected`() {
        // 0.15 hours is 540 seconds; the server means 10 minutes at least.
        assertTrue(StoreField.Monitoring in draft.copy(monitoringExpirationHours = "0.15").errors())
        assertTrue(StoreField.Monitoring !in draft.copy(monitoringExpirationHours = "0.2").errors())
    }
    @Test fun `display timer above expiration rejected`() {
        val fiveMinutes = draft.copy(invoiceExpirationMinutes = "5")
        assertTrue(StoreField.DisplayTimer in fiveMinutes.copy(displayExpirationSeconds = "301").errors())
        assertTrue(fiveMinutes.copy(displayExpirationSeconds = "300").errors().isEmpty())
    }
    @Test fun `tolerance 0 comma 5 accepted`() {
        assertEquals(0.5, draft.copy(paymentTolerancePercent = "0,5").toStore().paymentTolerance, 0.0)
    }
    @Test fun `tolerance 1 comma 000 rejected`() {
        // Ambiguous: a thousand, or one. Neither guess is safe, so it asks.
        assertTrue(StoreField.Tolerance in draft.copy(paymentTolerancePercent = "1,000").errors())
        assertTrue(StoreField.Tolerance in draft.copy(paymentTolerancePercent = "101").errors())
    }
    @Test fun `a cleared website becomes an empty string`() {
        val withSite = StoreSettingsDraft.of(StoreData(website = "https://shop.example"))
        assertEquals("", withSite.copy(website = "").toStore().website)
        // Untouched, an unset field stays unset.
        assertNull(StoreSettingsDraft.of(StoreData()).toStore().website)
    }
    @Test fun `a changed default currency must be a real code`() {
        fun typed(code: String) = draft.copy(store = draft.store.copy(defaultCurrency = code))
        assertTrue(StoreField.DefaultCurrency in typed("EUT").errors())
        assertEquals("EUR", typed(" eur ").toStore().defaultCurrency)
        assertNull(currencyProblem("SATS"))
        assertNotNull(currencyProblem(""))
    }
    @Test fun `only a change that accepts less as paid is named for review`() {
        val server = StoreData(speedPolicy = SpeedPolicy.MediumSpeed, paymentTolerance = 1.0)
        val loaded = StoreSettingsDraft.of(server)
        assertTrue(loaded.loosenedChecks(server).isEmpty())
        val zeroConf = loaded.copy(store = loaded.store.copy(speedPolicy = SpeedPolicy.HighSpeed))
        assertEquals(
            listOf("Paid after: Medium speed · 1 confirmation → High speed · 0 confirmations"),
            zeroConf.loosenedChecks(server),
        )
        assertEquals(listOf("Payment tolerance: 1 % → 100 %"), loaded.copy(paymentTolerancePercent = "100").loosenedChecks(server))
        // Stricter needs no review: more confirmations, a smaller margin.
        val stricter = loaded.copy(store = loaded.store.copy(speedPolicy = SpeedPolicy.LowSpeed), paymentTolerancePercent = "0")
        assertTrue(stricter.loosenedChecks(server).isEmpty())
        // A field error stops the save first, so nothing is named.
        assertTrue(loaded.copy(paymentTolerancePercent = "101").loosenedChecks(server).isEmpty())
    }
    @Test fun `a stored currency this app does not list still saves as it was`() {
        // USDT (a Liquid asset) is not in the app's list, nor in an old phone's ISO list.
        val stored = StoreSettingsDraft.of(StoreData(defaultCurrency = "USDt"))
        assertTrue(stored.errors().isEmpty())
        assertEquals("USDt", stored.copy(website = "https://shop.example").toStore().defaultCurrency)
        // Only the case differs: the form upper-cases what is typed, so this is no change.
        assertEquals("USDt", stored.copy(store = stored.store.copy(defaultCurrency = "USDT")).toStore().defaultCurrency)
    }
}
