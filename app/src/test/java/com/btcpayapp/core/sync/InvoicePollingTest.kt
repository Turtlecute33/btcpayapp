package com.btcpayapp.core.sync

import com.btcpayapp.data.api.dto.InvoiceAdditionalStatus
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

    @Test fun `a detected payment is announced once, then again when it settles`() {
        val detected = evaluateInvoices(InvoicePollState(100), listOf(invoice("one", 100, InvoiceStatus.Processing)), 200)
        assertEquals(listOf("Payment detected"), detected.payments.map(::paymentTitle))
        assertEquals(listOf("one"), detected.state.pending)
        val waiting = evaluateInvoices(detected.state, listOf(invoice("one", 100, InvoiceStatus.Processing)), 300)
        assertTrue(waiting.payments.isEmpty())
        val settled = evaluateInvoices(waiting.state, listOf(invoice("one", 100, InvoiceStatus.Settled)), 400)
        assertEquals(listOf("Payment received"), settled.payments.map(::paymentTitle))
        assertTrue(settled.state.pending.isEmpty())
        assertTrue(settled.state.announced.isEmpty())
    }

    @Test fun `a detected payment that settles in the boundary second is not announced twice`() {
        val detected = evaluateInvoices(InvoicePollState(100), listOf(invoice("one", 200, InvoiceStatus.Processing)), 200)
        // A second poll in the same second, so the next poll reads the invoice again by its creation time.
        val settled = evaluateInvoices(detected.state, listOf(invoice("one", 200, InvoiceStatus.Settled)), 200)
        assertEquals(listOf("one"), settled.payments.map { it.id })
        val again = evaluateInvoices(settled.state, listOf(invoice("one", 200, InvoiceStatus.Settled)), 201)
        assertTrue(again.payments.isEmpty())
    }

    @Test fun `a detected payment that does not confirm is announced again`() {
        val detected = evaluateInvoices(InvoicePollState(100), listOf(invoice("one", 100, InvoiceStatus.Processing)), 200)
        val failed = evaluateInvoices(detected.state, listOf(invoice("one", 100, InvoiceStatus.Invalid)), 300)
        assertEquals(listOf("Payment not confirmed"), failed.payments.map(::paymentTitle))
        val marked = invoice("one", 100, InvoiceStatus.Invalid).copy(additionalStatus = InvoiceAdditionalStatus.Marked)
        assertEquals(listOf("Marked as invalid"), evaluateInvoices(detected.state, listOf(marked), 300).payments.map(::paymentTitle))
    }

    @Test fun `a payment adopted as Processing by the first run is announced when it settles`() {
        val adopted = evaluateInvoices(null, listOf(invoice("one", 1, InvoiceStatus.Processing)), 100)
        assertTrue(adopted.payments.isEmpty())
        assertTrue(evaluateInvoices(adopted.state, listOf(invoice("one", 1, InvoiceStatus.Processing)), 200).payments.isEmpty())
        val settled = evaluateInvoices(adopted.state, listOf(invoice("one", 1, InvoiceStatus.Settled)), 200)
        assertEquals(listOf("Payment received"), settled.payments.map(::paymentTitle))
    }

    @Test fun `an unpaid invoice marked invalid is not a payment`() {
        val marked = invoice("x", 1, InvoiceStatus.Invalid).copy(additionalStatus = InvoiceAdditionalStatus.Marked)
        val pending = evaluateInvoices(null, listOf(invoice("x", 1, InvoiceStatus.New)), 100)
        assertTrue(evaluateInvoices(pending.state, listOf(marked), 200).payments.isEmpty())
    }

    @Test fun `an expired invoice is not kept pending, even while the server still monitors it`() {
        val expired = invoice("gone", 1, InvoiceStatus.Expired).copy(monitoringExpiration = 400)
        val next = evaluateInvoices(InvoicePollState(1, pending = listOf("gone")), listOf(expired), 300)
        assertTrue(next.payments.isEmpty())
        assertTrue(next.state.pending.isEmpty())
    }

    @Test fun `a late payment on a pending invoice is announced once, then dropped`() {
        val late = invoice("late", 1, InvoiceStatus.Expired).copy(additionalStatus = InvoiceAdditionalStatus.PaidLate)
        val next = evaluateInvoices(InvoicePollState(1, pending = listOf("late")), listOf(late), 300)
        assertEquals(listOf("late"), next.payments.map { it.id })
        assertEquals("Late payment detected", paymentTitle(next.payments.single()))
        // Neither pending nor created since the watermark: the next poll does not read it again.
        assertTrue(next.state.pending.isEmpty())
        assertEquals(300L, next.state.since)
    }

    @Test fun `a partial payment on an expired invoice is announced, then dropped`() {
        val partial = invoice("part", 1, InvoiceStatus.Expired).copy(additionalStatus = InvoiceAdditionalStatus.PaidPartial)
        val next = evaluateInvoices(InvoicePollState(1, pending = listOf("part")), listOf(partial), 300)
        assertEquals("Partial payment detected", paymentTitle(next.payments.single()))
        assertTrue(next.state.pending.isEmpty())
    }

    @Test fun `a late payment in the boundary second is not announced twice`() {
        val late = invoice("late", 300, InvoiceStatus.Expired).copy(additionalStatus = InvoiceAdditionalStatus.PaidLate)
        val first = evaluateInvoices(InvoicePollState(1), listOf(late), 300)
        assertEquals(1, first.payments.size)
        assertTrue(evaluateInvoices(first.state, listOf(late), 301).payments.isEmpty())
    }

    @Test fun `an expired invoice without money is not a payment`() {
        listOf(InvoiceAdditionalStatus.None, InvoiceAdditionalStatus.Marked).forEach { status ->
            val expired = invoice("x", 1, InvoiceStatus.Expired).copy(additionalStatus = status)
            assertTrue(evaluateInvoices(InvoicePollState(1, pending = listOf("x")), listOf(expired), 300).payments.isEmpty())
        }
    }

    @Test fun `titles follow the status, then the payment kind`() {
        fun title(status: InvoiceStatus, extra: InvoiceAdditionalStatus = InvoiceAdditionalStatus.None) =
            paymentTitle(invoice("a", 1, status).copy(additionalStatus = extra))
        assertEquals("Payment received", title(InvoiceStatus.Settled))
        assertEquals("Payment received", title(InvoiceStatus.Settled, InvoiceAdditionalStatus.PaidOver))
        assertEquals("Marked as paid", title(InvoiceStatus.Settled, InvoiceAdditionalStatus.Marked))
        assertEquals("Payment detected", title(InvoiceStatus.Processing))
        // Only Settled says "received": these payments may never confirm.
        assertEquals("Payment detected", title(InvoiceStatus.Expired, InvoiceAdditionalStatus.PaidOver))
        assertEquals("Payment not confirmed", title(InvoiceStatus.Invalid, InvoiceAdditionalStatus.PaidOver))
        assertEquals("Payment not confirmed", title(InvoiceStatus.Invalid, InvoiceAdditionalStatus.PaidLate))
        assertEquals("Payment not confirmed", title(InvoiceStatus.Invalid))
        assertEquals("Marked as invalid", title(InvoiceStatus.Invalid, InvoiceAdditionalStatus.Marked))
    }

    @Test fun `duplicate pages do not duplicate alerts`() {
        val paid = invoice("one", 100, InvoiceStatus.Settled)
        assertEquals(1, evaluateInvoices(InvoicePollState(1), listOf(paid, paid), 200).payments.size)
    }
}
