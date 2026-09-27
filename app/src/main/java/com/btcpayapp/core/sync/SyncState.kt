package com.btcpayapp.core.sync

import kotlinx.serialization.Serializable

/**
 * What the background job remembers between runs, so it can tell a genuinely
 * new payment from one it has already announced.
 *
 * Kept in its own encrypted document rather than in settings: it changes far
 * more often, and a corrupted watermark should never take the credential store
 * down with it.
 */
@Serializable
data class SyncState(
    /** `accountId|storeId` → the newest invoice creation time already seen. */
    val invoiceWatermarks: Map<String, Long> = emptyMap(),
    /** `accountId|storeId` → payout ids already announced as awaiting approval. */
    val announcedPayouts: Map<String, List<String>> = emptyMap(),
    val lastRunAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val invoicePolls: Map<String, InvoicePollState> = emptyMap(),
    /**
     * `accountId` → when (epoch ms) its server stopped answering. A time, not a
     * count: JobScheduler's backoff retries a failed run within seconds, so four
     * failures can be four minutes of a routine server restart.
     */
    val accountFailureSince: Map<String, Long> = emptyMap(),
    /** Accounts whose server-issue alert was posted for the current outage, so it is posted once. */
    val alertedAccounts: List<String> = emptyList(),
    /** `accountId` → how far that account's BTCPay notification feed has been read. */
    val feedPolls: Map<String, FeedPollState> = emptyMap(),
) {
    fun feedPoll(accountId: String): FeedPollState? = feedPolls[accountId]

    fun withFeedPoll(accountId: String, poll: FeedPollState): SyncState =
        copy(feedPolls = feedPolls + (accountId to poll))

    fun invoicePoll(accountId: String, storeId: String): InvoicePollState? = invoicePolls[key(accountId, storeId)]

    fun withInvoicePoll(accountId: String, storeId: String, poll: InvoicePollState): SyncState =
        copy(invoicePolls = invoicePolls + (key(accountId, storeId) to poll))

    fun watermark(accountId: String, storeId: String): Long =
        invoiceWatermarks[key(accountId, storeId)] ?: 0L

    fun withWatermark(accountId: String, storeId: String, value: Long): SyncState =
        copy(invoiceWatermarks = invoiceWatermarks + (key(accountId, storeId) to value))

    fun announced(accountId: String, storeId: String): List<String> =
        announcedPayouts[key(accountId, storeId)].orEmpty()

    fun withAnnounced(accountId: String, storeId: String, ids: List<String>): SyncState =
        copy(announcedPayouts = announcedPayouts + (key(accountId, storeId) to ids.distinct()))

    /** Starts the outage clock of [accountId] at [now], unless it already runs. */
    fun withAccountFailure(accountId: String, now: Long): SyncState =
        if (accountId in accountFailureSince) this
        else copy(accountFailureSince = accountFailureSince + (accountId to now))

    /** True when [accountId] has failed for [SERVER_ISSUE_AFTER_MS] and was not alerted yet. */
    fun serverIssueDue(accountId: String, now: Long): Boolean {
        val since = accountFailureSince[accountId] ?: return false
        return now - since >= SERVER_ISSUE_AFTER_MS && accountId !in alertedAccounts
    }

    fun withServerIssueAlerted(accountId: String): SyncState =
        copy(alertedAccounts = (alertedAccounts + accountId).distinct())

    /** The server answered: the outage clock and the alert flag go. */
    fun withAccountReached(accountId: String): SyncState =
        copy(accountFailureSince = accountFailureSince - accountId, alertedAccounts = alertedAccounts - accountId)

    /**
     * Every invoice poll and feed position, so each is adopted again as on a
     * first run, without announcing what it finds. Announced payouts stay,
     * so none is told twice. A payout that started to wait meanwhile still
     * waits for approval, so it is still told.
     */
    fun withoutPollPositions(): SyncState = copy(invoicePolls = emptyMap(), feedPolls = emptyMap())

    /**
     * Everything kept for [accountId]: its store polls, announced payouts,
     * feed position and outage entries. For account removal, so no invoice or
     * payout id of a removed server stays on the device.
     */
    fun forgetAccount(accountId: String): SyncState = keepAccounts { it != accountId }

    /** Drops the entries of every account not in [accountIds], e.g. one removed while a run was busy. */
    internal fun retainAccounts(accountIds: Set<String>): SyncState = keepAccounts { it in accountIds }

    private fun keepAccounts(keep: (accountId: String) -> Boolean): SyncState {
        // Account ids are UUIDs, so the account part of a store key is all
        // before its first `|`.
        fun <V> Map<String, V>.scoped() = filterKeys { keep(it.substringBefore('|')) }
        return copy(
            invoiceWatermarks = invoiceWatermarks.scoped(),
            announcedPayouts = announcedPayouts.scoped(),
            invoicePolls = invoicePolls.scoped(),
            accountFailureSince = accountFailureSince.filterKeys(keep),
            alertedAccounts = alertedAccounts.filter(keep),
            feedPolls = feedPolls.filterKeys(keep),
        )
    }

    private fun key(accountId: String, storeId: String) = "$accountId|$storeId"

    companion object {
        /** How long a server must stay unreachable before the user is told. */
        const val SERVER_ISSUE_AFTER_MS: Long = 60 * 60 * 1000L
    }
}

// SyncEngine retains only payouts that are still awaiting approval. A fixed
// tail cap would repeatedly announce older payouts whenever more than 50 wait.
