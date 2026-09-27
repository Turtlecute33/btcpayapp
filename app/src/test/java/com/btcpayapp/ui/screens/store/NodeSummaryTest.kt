package com.btcpayapp.ui.screens.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NodeSummaryTest {

    @Test
    fun `two wallets on one shared host give two different lines`() {
        fun blink(key: String, wallet: String) =
            connectionSummary("type=blink;server=https://api.blink.sv/graphql;api-key=$key;wallet-id=$wallet")

        val mine = blink("blink_old", "w-mine")
        assertEquals("blink · api.blink.sv · wallet w-mine", mine)
        assertFalse(mine.contains("blink_old"))
        // A new key for the same wallet reads the same; another wallet does not.
        assertEquals(mine, blink("blink_new", "w-mine"))
        assertNotEquals(mine, blink("blink_new", "w-theirs"))
    }
}
