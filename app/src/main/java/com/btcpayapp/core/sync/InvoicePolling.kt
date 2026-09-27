package com.btcpayapp.core.sync

import com.btcpayapp.data.api.dto.InvoiceAdditionalStatus
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import kotlinx.serialization.Serializable

/**
 * How far one store's invoices have been polled.
 *
 * [pending] holds only open invoices (New or Processing). Each is read again on
 * every run, so the cost of a run follows the invoices opened in the last
 * expiry window, not the store's abandoned checkouts. An invoice that closes
 * unpaid is dropped; a late payment after that comes from the server's
 * notification feed.
 *
 * [announced] holds the payments already announced (or adopted by a first
 * run) that a run can read again: the open ones and those of the boundary
 * second. One that is in both lists was Processing, because a New invoice is
 * never a payment.
 */
@Serializable
data class InvoicePollState(
    val since: Long,
    val pending: List<String> = emptyList(),
    val announced: List<String> = emptyList(),
)

internal data class InvoicePollResult(val state: InvoicePollState, val payments: List<InvoiceData>)

/**
 * Creation time discovers invoices; pending IDs track subsequent payment changes.
 *
 * A payment is an invoice that is Processing or Settled, or one that closed
 * with money on it (see [closedWithPayment]). Each is announced once. But an
 * invoice that was Processing at the last run ("Payment detected") is
 * announced again when it becomes Settled or Invalid: the merchant must know
 * whether a detected payment became final or failed. The alert keeps its id,
 * so it replaces "Payment detected" in the shade.
 */
internal fun evaluateInvoices(previous: InvoicePollState?, invoices: List<InvoiceData>, now: Long): InvoicePollResult {
    val unique = invoices.distinctBy { it.id }
    val notified = previous?.announced.orEmpty().toSet()
    // Processing at the last run: see InvoicePollState.announced.
    val detected = previous?.pending.orEmpty().filter { it in notified }.toSet()
    val paid = unique.filter {
        it.status == InvoiceStatus.Processing || it.status == InvoiceStatus.Settled || it.closedWithPayment
    }
    val outcomes = unique.filter {
        it.id in detected && (it.status == InvoiceStatus.Settled || it.status == InvoiceStatus.Invalid)
    }
    val payments = if (previous == null) emptyList() else paid.filterNot { it.id in notified } + outcomes
    val pending = unique.filter { it.isOpen }.map { it.id }
    // Include the boundary second again on the next poll, and remember which
    // payments from that second were announced. No fixed-size tail can do this.
    val retained = pending.toSet() + unique.filter { it.createdTime >= now }.map { it.id }
    return InvoicePollResult(
        InvoicePollState(now, pending, (notified + paid.map { it.id }).filter { it in retained }),
        payments,
    )
}

/**
 * The notification title for a payment from [evaluateInvoices]. It must name
 * no amount or store: the lock screen shows it too ([Notifier.paymentReceived]).
 * Only Settled says "received". An expired invoice gets its late or partial
 * flag when the payment is seen, which can be before it confirms, and BTCPay
 * does not settle it later. Invalid means the payment did not confirm in time,
 * unless someone marked the invoice invalid.
 */
internal fun paymentTitle(invoice: InvoiceData): String = when {
    invoice.status == InvoiceStatus.Settled ->
        if (invoice.additionalStatus == InvoiceAdditionalStatus.Marked) "Marked as paid" else "Payment received"
    invoice.status == InvoiceStatus.Invalid ->
        if (invoice.additionalStatus == InvoiceAdditionalStatus.Marked) "Marked as invalid" else "Payment not confirmed"
    invoice.status == InvoiceStatus.Processing -> "Payment detected"
    invoice.additionalStatus == InvoiceAdditionalStatus.PaidPartial -> "Partial payment detected"
    invoice.additionalStatus == InvoiceAdditionalStatus.PaidLate -> "Late payment detected"
    else -> "Payment detected"
}

/**
 * Closed, but money came in: paid after expiry, paid in part, or paid too much.
 * BTCPay keeps a late payment's status at Expired and sets only
 * additionalStatus, so the status alone would miss real money.
 */
private val InvoiceData.closedWithPayment: Boolean
    get() = (status == InvoiceStatus.Expired || status == InvoiceStatus.Invalid) &&
        additionalStatus in PAID_WHILE_CLOSED

private val PAID_WHILE_CLOSED = setOf(
    InvoiceAdditionalStatus.PaidPartial,
    InvoiceAdditionalStatus.PaidLate,
    InvoiceAdditionalStatus.PaidOver,
)
