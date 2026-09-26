package com.btcpayapp.core.sync

import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import kotlinx.serialization.Serializable

@Serializable
data class InvoicePollState(
    val since: Long,
    val pending: List<String> = emptyList(),
    val announced: List<String> = emptyList(),
)

internal data class InvoicePollResult(val state: InvoicePollState, val payments: List<InvoiceData>)

/** Creation time discovers invoices; pending IDs track subsequent payment changes. */
internal fun evaluateInvoices(previous: InvoicePollState?, invoices: List<InvoiceData>, now: Long): InvoicePollResult {
    val unique = invoices.distinctBy { it.id }
    val notified = previous?.announced.orEmpty().toSet()
    val paid = unique.filter { it.status == InvoiceStatus.Processing || it.status == InvoiceStatus.Settled }
    val payments = if (previous == null) emptyList() else paid.filterNot { it.id in notified }
    val pending = unique.filter {
        it.isOpen || (it.status == InvoiceStatus.Expired && it.monitoringExpiration >= now)
    }.map { it.id }
    // Include the boundary second again on the next poll, and remember which
    // payments from that second were announced. No fixed-size tail can do this.
    val retained = pending.toSet() + unique.filter { it.createdTime >= now }.map { it.id }
    return InvoicePollResult(
        InvoicePollState(now, pending, (notified + paid.map { it.id }).filter { it in retained }),
        payments,
    )
}
