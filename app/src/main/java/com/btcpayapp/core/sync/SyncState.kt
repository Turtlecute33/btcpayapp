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
    val accountFailures: Map<String, Int> = emptyMap(),
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

    private fun key(accountId: String, storeId: String) = "$accountId|$storeId"
}

// SyncEngine retains only payouts that are still awaiting approval. A fixed
// tail cap would repeatedly announce older payouts whenever more than 50 wait.
