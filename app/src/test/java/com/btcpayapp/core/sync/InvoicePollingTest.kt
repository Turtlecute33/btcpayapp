package com.btcpayapp.core.sync

import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import org.junit.Assert.*
import org.junit.Test

class InvoicePollingTest {
    private fun invoice(id: String, created: Long, status: InvoiceStatus) = InvoiceData(id = id, createdTime = created, status = status)

    @Test fun `an old unpaid invoice is tracked until it is paid`() {
        val initial = evaluateInvoices(null, listOf(invoice("old", 1, InvoiceStatus.New)), 100)
        assertTrue(initial.payments.isEmpty())
        assertEquals(listOf("old"), initial.state.pending)
        val next = evaluateInvoices(initial.state, listOf(invoice("old", 1, InvoiceStatus.Settled)), 200)
        assertEquals(listOf("old"), next.payments.map { it.id })
        assertTrue(next.state.pending.isEmpty())
    }

    @Test fun `same-second invoices are neither skipped nor repeated`() {
        val first = evaluateInvoices(InvoicePollState(100), listOf(invoice("one", 200, InvoiceStatus.Settled)), 200)
        val second = evaluateInvoices(first.state, listOf(invoice("one", 200, InvoiceStatus.Settled), invoice("two", 200, InvoiceStatus.Settled)), 201)
        assertEquals(listOf("two"), second.payments.map { it.id })
    }

    @Test fun `processing payments are announced once across settlement`() {
        val detected = evaluateInvoices(InvoicePollState(100), listOf(invoice("one", 100, InvoiceStatus.Processing)), 200)
        assertEquals(1, detected.payments.size)
        assertEquals(listOf("one"), detected.state.pending)
        val settled = evaluateInvoices(detected.state, listOf(invoice("one", 100, InvoiceStatus.Settled)), 300)
        assertTrue(settled.payments.isEmpty())
    }

    @Test fun `expired invoices remain tracked during late payment monitoring`() {
        val late = invoice("late", 1, InvoiceStatus.Expired).copy(monitoringExpiration = 400)
        assertEquals(listOf("late"), evaluateInvoices(InvoicePollState(1), listOf(late), 300).state.pending)
        assertTrue(evaluateInvoices(InvoicePollState(1), listOf(late), 500).state.pending.isEmpty())
    }

    @Test fun `duplicate pages do not duplicate alerts`() {
        val paid = invoice("one", 100, InvoiceStatus.Settled)
        assertEquals(1, evaluateInvoices(InvoicePollState(1), listOf(paid, paid), 200).payments.size)
    }
}
