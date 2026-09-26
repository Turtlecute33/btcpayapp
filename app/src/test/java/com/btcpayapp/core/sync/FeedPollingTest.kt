package com.btcpayapp.core.sync

import com.btcpayapp.data.api.dto.NotificationData
import org.junit.Assert.*
import org.junit.Test

class FeedPollingTest {
    private fun item(id: String, created: Long) = NotificationData(id = id, createdTime = created)

    @Test fun `the first run adopts the backlog without announcing it`() {
        val first = evaluateFeed(null, listOf(item("a", 100), item("b", 90)))
        assertTrue(first.fresh.isEmpty())
        assertEquals(FeedPollState(100, listOf("a")), first.state)
    }

    @Test fun `newer entries are announced once, oldest first`() {
        val next = evaluateFeed(FeedPollState(100, listOf("a")), listOf(item("c", 120), item("b", 110), item("a", 100)))
        assertEquals(listOf("b", "c"), next.fresh.map { it.id })
        val again = evaluateFeed(next.state, listOf(item("c", 120), item("b", 110)))
        assertTrue(again.fresh.isEmpty())
    }

    @Test fun `same-second entries are neither skipped nor repeated`() {
        val first = evaluateFeed(FeedPollState(50), listOf(item("one", 200)))
        assertEquals(listOf("one"), first.fresh.map { it.id })
        val second = evaluateFeed(first.state, listOf(item("two", 200), item("one", 200)))
        assertEquals(listOf("two"), second.fresh.map { it.id })
        assertEquals(setOf("one", "two"), second.state.boundary.toSet())
    }

    @Test fun `an empty feed keeps the watermark`() {
        val state = FeedPollState(100, listOf("a"))
        assertEquals(state, evaluateFeed(state, emptyList()).state)
    }

    @Test fun `duplicate entries do not duplicate alerts`() {
        val next = evaluateFeed(FeedPollState(0), listOf(item("x", 10), item("x", 10)))
        assertEquals(1, next.fresh.size)
    }

    @Test fun `settlements and waiting payouts are left to the app's own alerts`() {
        assertTrue(coveredByOwnAlerts("invoice_confirmed", payments = true, payouts = true))
        assertTrue(coveredByOwnAlerts("invoice_paidAfterExpiration", payments = true, payouts = false))
        assertTrue(coveredByOwnAlerts("payout_awaitingapproval", payments = false, payouts = true))
        assertTrue(coveredByOwnAlerts("payout", payments = false, payouts = true))
    }

    @Test fun `a switched-off alert leaves its events to the feed`() {
        assertFalse(coveredByOwnAlerts("invoice_confirmed", payments = false, payouts = true))
        assertFalse(coveredByOwnAlerts("payout_awaitingapproval", payments = true, payouts = false))
    }

    @Test fun `events the app does not poll for always come from the feed`() {
        listOf("invoice_expiredpaidpartial", "invoice_failedtoconfirm", "payout_awaitingpayment",
            "newversion", "newuserrequiresapproval", "storeinvitation", "external-payout-transaction").forEach {
            assertFalse(it, coveredByOwnAlerts(it, payments = true, payouts = true))
        }
    }
}
