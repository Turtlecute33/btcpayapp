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

    private fun populated(accountId: String) = SyncState()
        .withInvoicePoll(accountId, "s", InvoicePollState(since = 1, pending = listOf("i")))
        .withAnnounced(accountId, "s", listOf("p"))
        .withWatermark(accountId, "s", 5)
        .withFeedPoll(accountId, FeedPollState(since = 7))
        .withAccountFailure(accountId, now = 10)
        .withServerIssueAlerted(accountId)

    private fun SyncState.plus(other: SyncState) = copy(
        invoiceWatermarks = invoiceWatermarks + other.invoiceWatermarks,
        announcedPayouts = announcedPayouts + other.announcedPayouts,
        invoicePolls = invoicePolls + other.invoicePolls,
        accountFailureSince = accountFailureSince + other.accountFailureSince,
        alertedAccounts = alertedAccounts + other.alertedAccounts,
        feedPolls = feedPolls + other.feedPolls,
    )

    @Test fun `forgetting an account removes only its entries`() {
        // "ab" shares the prefix "a" but is another account.
        val state = populated("a").plus(populated("ab")).plus(populated("b"))
        assertEquals(populated("ab").plus(populated("b")), state.forgetAccount("a"))
        assertEquals(SyncState(), populated("a").forgetAccount("a"))
    }

    @Test fun `forgetting poll positions keeps what was announced and the outage clock`() {
        val state = populated("a")
        val forgotten = state.withoutPollPositions()
        assertNull(forgotten.invoicePoll("a", "s"))
        assertNull(forgotten.feedPoll("a"))
        assertEquals(listOf("p"), forgotten.announced("a", "s"))
        assertEquals(state.accountFailureSince, forgotten.accountFailureSince)
        assertEquals(state.alertedAccounts, forgotten.alertedAccounts)
    }

    @Test fun `retaining accounts drops every other account`() {
        val state = populated("a").plus(populated("b"))
        assertEquals(populated("b"), state.retainAccounts(setOf("b", "unknown")))
    }

    @Test fun `the server issue is due once, after an hour of failures`() {
        val hour = SyncState.SERVER_ISSUE_AFTER_MS
        val failing = SyncState().withAccountFailure("a", now = 1_000)
        assertFalse(failing.serverIssueDue("a", now = 1_000 + hour - 1))
        assertTrue(failing.serverIssueDue("a", now = 1_000 + hour))
        // A later failure does not restart the clock.
        assertEquals(failing, failing.withAccountFailure("a", now = 5_000))
        assertFalse(failing.withServerIssueAlerted("a").serverIssueDue("a", now = 1_000 + 2 * hour))
        assertFalse(failing.serverIssueDue("b", now = 1_000 + hour))
    }

    @Test fun `a success clears the failure clock and the alert flag of that account only`() {
        val state = SyncState()
            .withAccountFailure("a", now = 1).withServerIssueAlerted("a")
            .withAccountFailure("b", now = 2).withServerIssueAlerted("b")
        val reached = state.withAccountReached("a")
        assertEquals(mapOf("b" to 2L), reached.accountFailureSince)
        assertEquals(listOf("b"), reached.alertedAccounts)
    }
}
