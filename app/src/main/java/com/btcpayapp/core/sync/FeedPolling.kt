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
 *
 * An entry stamped more than a day after [now] (seconds) is left out until
 * its time comes. Taken in, it would move [FeedPollState.since] past every
 * later entry and stop the feed for good: a server whose clock was wrong for
 * a moment, or one that wants the phone quiet. A watermark saved that far
 * ahead is moved back.
 */
internal fun evaluateFeed(previous: FeedPollState?, items: List<NotificationData>, now: Long): FeedPollResult {
    val latest = now + MAX_AHEAD_SECONDS
    val last = previous?.let { if (it.since > latest) FeedPollState(latest) else it }
    val unique = items.distinctBy { it.id }.filter { it.createdTime <= latest }
    val fresh = if (last == null) emptyList() else unique.filter {
        it.createdTime > last.since || (it.createdTime == last.since && it.id !in last.boundary)
    }
    val since = maxOf(last?.since ?: 0L, unique.maxOfOrNull { it.createdTime } ?: 0L)
    val carried = if (last?.since == since) last.boundary else emptyList()
    val boundary = (carried + unique.filter { it.createdTime == since }.map { it.id }).distinct()
    return FeedPollResult(FeedPollState(since, boundary), fresh.sortedBy { it.createdTime })
}

private const val MAX_AHEAD_SECONDS = 24 * 60 * 60L

/**
 * Feed entries that the app's own alerts already announce, by BTCPay's
 * notification identifier.
 *
 * The invoice poll reports every settlement in the stores it reads
 * ([invoiceStores]), also of a payment it announced as detected before, and
 * the payout poll every payout awaiting approval in its stores
 * ([payoutStores]). The same events from the feed would be a second
 * notification for one payment. A payout notification without a status is one
 * awaiting approval: BTCPay renders it with that text. A store that a poll
 * does not read (its alert is off, or the key may not view it) has only the
 * feed.
 *
 * A late or partial payment, or a payment that did not confirm, is covered
 * only when the poll announced that invoice in this run
 * ([announcedInvoiceIds]; the entry's link names the invoice). The poll
 * follows open invoices only, so an invoice that expired unpaid and was paid
 * later reaches the phone through the feed alone.
 */
internal fun coveredByOwnAlerts(
    item: NotificationData,
    invoiceStores: Set<String>,
    payoutStores: Set<String>,
    announcedInvoiceIds: Set<String>,
): Boolean = when (item.identifier.lowercase()) {
    "invoice_confirmed" -> invoiceStores.reads(item.storeId)
    "invoice_paidafterexpiration", "invoice_expiredpaidpartial", "invoice_failedtoconfirm" ->
        announcedInvoiceIds.any { it.isNotEmpty() && item.link?.contains(it) == true }
    "payout", "payout_awaitingapproval" -> payoutStores.reads(item.storeId)
    else -> false
}

/** An entry that names no store counts as read when any store is, so it does not come twice. */
private fun Set<String>.reads(storeId: String?): Boolean = if (storeId == null) isNotEmpty() else storeId in this
