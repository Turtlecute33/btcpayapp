package com.btcpayapp.core.sync

import org.junit.Assert.*
import org.junit.Test

class SyncStateTest {
    @Test fun `all pending payouts are remembered beyond fifty`() {
        val ids = (1..75).map { it.toString() }
        assertEquals(ids, SyncState().withAnnounced("a", "s", ids).announced("a", "s"))
    }
    @Test fun `completed payouts can be removed without changing another store`() {
        val state = SyncState().withAnnounced("a", "s", listOf("1", "2"))
            .withAnnounced("a", "other", listOf("3"))
            .withAnnounced("a", "s", listOf("2"))
        assertEquals(listOf("2"), state.announced("a", "s"))
        assertEquals(listOf("3"), state.announced("a", "other"))
    }
}
