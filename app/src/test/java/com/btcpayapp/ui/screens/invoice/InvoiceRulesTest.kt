package com.btcpayapp.ui.screens.invoice

import com.btcpayapp.data.api.dto.InvoiceAdditionalStatus
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what the checkout shows, how often it asks, when a
 * refund is offered, what the status chip says and which amounts the new
 * invoice form takes. They run the real functions: a checkout that maps
 * Processing back to Paying puts a payable code in front of a customer who
 * has already paid.
 */
class InvoiceRulesTest {

    private fun invoice(
        status: InvoiceStatus,
        additional: InvoiceAdditionalStatus = InvoiceAdditionalStatus.None,
        monitoringExpiration: Long = 0,
    ) = InvoiceData(status = status, additionalStatus = additional, monitoringExpiration = monitoringExpiration)

    private fun phase(status: InvoiceStatus) = phaseOf(CheckoutState(invoice = invoice(status), loading = false))

    // --- Checkout phase, title and status line ------------------------------

    @Test
    fun `no invoice yet is loading, then an error once the read failed`() {
        assertEquals(CheckoutPhase.Loading, phaseOf(CheckoutState()))
        assertEquals(CheckoutPhase.Error, phaseOf(CheckoutState(loading = false)))
    }

    @Test
    fun `only a new invoice shows the code`() {
        assertEquals(CheckoutPhase.Paying, phase(InvoiceStatus.New))
        assertEquals(CheckoutPhase.Received, phase(InvoiceStatus.Processing))
        assertEquals(CheckoutPhase.Paid, phase(InvoiceStatus.Settled))
        assertEquals(CheckoutPhase.Closed, phase(InvoiceStatus.Expired))
        assertEquals(CheckoutPhase.Closed, phase(InvoiceStatus.Invalid))
        assertEquals(CheckoutPhase.Closed, phase(InvoiceStatus.Unknown))
    }

    @Test
    fun `the title follows the phase`() {
        assertEquals("Awaiting payment", titleOf(CheckoutPhase.Paying, invoice(InvoiceStatus.New)))
        assertEquals("Payment detected", titleOf(CheckoutPhase.Received, invoice(InvoiceStatus.Processing)))
        assertEquals("Paid", titleOf(CheckoutPhase.Paid, invoice(InvoiceStatus.Settled)))
        assertEquals("Invoice expired", titleOf(CheckoutPhase.Closed, invoice(InvoiceStatus.Expired)))
        assertEquals("Invoice invalid", titleOf(CheckoutPhase.Closed, invoice(InvoiceStatus.Invalid)))
    }

    @Test
    fun `a received payment waits for confirmation and a marked one says so`() {
        assertEquals("Waiting for confirmation", statusOf(CheckoutPhase.Received, invoice(InvoiceStatus.Processing)))
        val marked = invoice(InvoiceStatus.Settled, InvoiceAdditionalStatus.Marked)
        assertEquals("Marked as paid", statusOf(CheckoutPhase.Paid, marked))
        assertEquals("Payment received", statusOf(CheckoutPhase.Paid, invoice(InvoiceStatus.Settled)))
    }

    // --- Poll interval ------------------------------------------------------

    @Test
    fun `new invoices are read often and processing ones less`() {
        assertEquals(3_000L, pollIntervalMs(null, nowSeconds = 0))
        assertEquals(3_000L, pollIntervalMs(invoice(InvoiceStatus.New), nowSeconds = 0))
        assertEquals(15_000L, pollIntervalMs(invoice(InvoiceStatus.Processing), nowSeconds = 0))
    }

    @Test
    fun `an expired invoice is read until the server stops watching it`() {
        val expired = invoice(InvoiceStatus.Expired, monitoringExpiration = 1_000)
        assertEquals(60_000L, pollIntervalMs(expired, nowSeconds = 999))
        assertNull(pollIntervalMs(expired, nowSeconds = 1_000))
    }

    @Test
    fun `final invoices are not read again`() {
        assertNull(pollIntervalMs(invoice(InvoiceStatus.Settled), nowSeconds = 0))
        assertNull(pollIntervalMs(invoice(InvoiceStatus.Invalid), nowSeconds = 0))
        assertNull(pollIntervalMs(invoice(InvoiceStatus.Unknown), nowSeconds = 0))
    }

    // --- Refund -------------------------------------------------------------

    @Test
    fun `settled and invalid invoices can be refunded`() {
        assertTrue(invoice(InvoiceStatus.Settled).offersRefund)
        assertTrue(invoice(InvoiceStatus.Invalid).offersRefund)
    }

    @Test
    fun `an expired invoice can be refunded only when it took money`() {
        assertFalse(invoice(InvoiceStatus.Expired).offersRefund)
        assertTrue(invoice(InvoiceStatus.Expired, InvoiceAdditionalStatus.PaidPartial).offersRefund)
        assertTrue(invoice(InvoiceStatus.Expired, InvoiceAdditionalStatus.PaidLate).offersRefund)
        assertTrue(invoice(InvoiceStatus.Expired, InvoiceAdditionalStatus.PaidOver).offersRefund)
    }

    @Test
    fun `an open invoice cannot be refunded`() {
        assertFalse(invoice(InvoiceStatus.New).offersRefund)
        assertFalse(invoice(InvoiceStatus.Processing, InvoiceAdditionalStatus.PaidOver).offersRefund)
        assertFalse(invoice(InvoiceStatus.Unknown).offersRefund)
    }

    // --- Status chip --------------------------------------------------------

    @Test
    fun `the chip keeps the status next to the exception`() {
        assertEquals(
            "Processing · paid over",
            invoice(InvoiceStatus.Processing, InvoiceAdditionalStatus.PaidOver).statusDetail(),
        )
        assertEquals(
            "Expired · paid partial",
            invoice(InvoiceStatus.Expired, InvoiceAdditionalStatus.PaidPartial).statusDetail(),
        )
        assertEquals("Settled · marked", invoice(InvoiceStatus.Settled, InvoiceAdditionalStatus.Marked).statusDetail())
        assertEquals("Invalid · marked", invoice(InvoiceStatus.Invalid, InvoiceAdditionalStatus.Marked).statusDetail())
    }

    @Test
    fun `no exception, or one that repeats the status, leaves the plain status`() {
        assertNull(invoice(InvoiceStatus.Settled).statusDetail())
        assertNull(invoice(InvoiceStatus.New, InvoiceAdditionalStatus.Unknown).statusDetail())
        assertNull(invoice(InvoiceStatus.Invalid, InvoiceAdditionalStatus.Invalid).statusDetail())
    }

    // --- New invoice amount -------------------------------------------------

    private fun problem(amount: String, currency: String) =
        CreateInvoiceState(amount = amount, currency = currency).amountProblem

    @Test
    fun `an amount may have as many decimals as its currency`() {
        assertNull(problem("10.12", "USD"))
        assertNull(problem("10.10", "USD"))
        assertNull(problem("10.1250", "BHD"))
        assertNull(problem("0.00000001", "BTC"))
        assertEquals("USD takes at most 2 decimals.", problem("10.1234", "USD"))
    }

    @Test
    fun `whole-unit currencies take no decimals`() {
        assertNull(problem("100", "JPY"))
        assertEquals("JPY takes whole amounts only.", problem("100.5", "JPY"))
        assertEquals("SATS takes whole amounts only.", problem("1.5", "SATS"))
    }

    @Test
    fun `an amount that reads two ways is refused`() {
        val text = problem("1,000", "USD")
        assertNotNull(text)
        assertTrue(text!!, text.contains("1000"))
    }

    @Test
    fun `no currency, no submit`() {
        assertNull(problem("10.1234", ""))
        assertFalse(CreateInvoiceState(amount = "10", currency = "").canSubmit)
        assertTrue(CreateInvoiceState(amount = "10", currency = "USD").canSubmit)
    }
}
