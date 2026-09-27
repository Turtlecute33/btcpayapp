package com.btcpayapp.ui.screens.store

import com.btcpayapp.data.api.dto.StoreRateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class StoreReviewTest {

    @Test
    fun `the rates review shows each pair now and under the new rules`() {
        val now = listOf(
            StoreRateResult("BTC_USD", BigDecimal("60000.50")),
            StoreRateResult("BTC_EUR", BigDecimal("55000")),
        )
        val next = listOf(
            StoreRateResult("btc_usd", BigDecimal("100000000")),
            StoreRateResult("BTC_EUR", errors = listOf("Unknown exchange")),
        )
        assertEquals(
            listOf(
                "BTC_USD: 60000.5 → 100000000",
                "BTC_EUR: 55000 → no rate",
                "BTC_CZK: not known → not known",
            ),
            rateChanges(listOf("BTC_USD", "BTC_EUR", "BTC_CZK"), now, next),
        )
    }

    @Test
    fun `a node line with no host and no wallet names no account`() {
        // Key-only connectors read the same for every account.
        assertFalse(connectionNamesAccount("type=strike;api-key=secret"))
        assertFalse(connectionNamesAccount("type=nwc;key=nostr+walletconnect://abc?relay=wss://r.example&secret=s"))
        assertFalse(connectionNamesAccount("garbage"))
        // A host or a wallet id tells one account from another.
        assertTrue(connectionNamesAccount("type=lnd-rest;server=https://node.example:8080/;macaroon=abcd"))
        assertTrue(connectionNamesAccount("type=blink;api-key=k;wallet-id=w-mine"))
        assertTrue(connectionNamesAccount(" internal node "))
    }
}
