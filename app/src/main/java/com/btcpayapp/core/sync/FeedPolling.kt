package com.btcpayapp.core.sync

import com.btcpayapp.data.api.dto.NotificationData
import kotlinx.serialization.Serializable

/**
 * How far the job has read one account's BTCPay notification feed.
 *
 * [since] is the newest creation time seen. BTCPay stamps notifications to the
 * second, so [boundary] keeps the ids from that second: a second notification
 * created in the same second, but after the poll, is still new.
 */
@Serializable
data class FeedPollState(
    val since: Long,
    val boundary: List<String> = emptyList(),
)

internal data class FeedPollResult(val state: FeedPollState, val fresh: List<NotificationData>)

/**
 * The feed entries that are new since [previous], oldest first.
 *
 * The first run adopts what is already in the feed without announcing it, as
 * the invoice poll does: turning on background sync should not replay a
 * backlog into the shade.
 */
internal fun evaluateFeed(previous: FeedPollState?, items: List<NotificationData>): FeedPollResult {
    val unique = items.distinctBy { it.id }
    val fresh = if (previous == null) emptyList() else unique.filter {
        it.createdTime > previous.since || (it.createdTime == previous.since && it.id !in previous.boundary)
    }
    val since = maxOf(previous?.since ?: 0L, unique.maxOfOrNull { it.createdTime } ?: 0L)
    val carried = if (previous?.since == since) previous.boundary else emptyList()
    val boundary = (carried + unique.filter { it.createdTime == since }.map { it.id }).distinct()
    return FeedPollResult(FeedPollState(since, boundary), fresh.sortedBy { it.createdTime })
}

/**
 * Feed entries that the app's own alerts already announce, by BTCPay's
 * notification identifier.
 *
 * The invoice poll reports every payment, including one made after expiry,
 * and the payout poll reports every payout awaiting approval. The same events
 * from the feed would be a second notification for one payment. A payout
 * notification without a status is one awaiting approval: BTCPay renders it
 * with that text.
 */
internal fun coveredByOwnAlerts(identifier: String, payments: Boolean, payouts: Boolean): Boolean =
    when (identifier.lowercase()) {
        "invoice_confirmed", "invoice_paidafterexpiration" -> payments
        "payout", "payout_awaitingapproval" -> payouts
        else -> false
    }
