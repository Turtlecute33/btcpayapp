package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.ui.screens.store.StoreSettingsDraft
import org.junit.Assert.*
import org.junit.Test

class StoreSettingsDraftTest {
    @Test fun `unrelated edits preserve exact stored durations`() {
        val store = StoreData(invoiceExpiration = 90, monitoringExpiration = 3601)
        val draft = StoreSettingsDraft.of(store)
        assertEquals("1.5", draft.invoiceExpirationMinutes)
        assertEquals(store, draft.toStore())
    }
    @Test fun `fractional minutes convert exactly`() {
        assertEquals(150, StoreSettingsDraft.of(StoreData()).copy(invoiceExpirationMinutes = "2.5").toStore().invoiceExpiration)
    }
    @Test fun `cleared and overflowing numeric fields are rejected`() {
        val draft = StoreSettingsDraft.of(StoreData())
        assertThrows(IllegalArgumentException::class.java) { draft.copy(displayExpirationSeconds = "").toStore() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(invoiceExpirationMinutes = "999999999999").toStore() }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(paymentTolerancePercent = "NaN").toStore() }
    }
}
