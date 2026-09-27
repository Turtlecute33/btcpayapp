package com.btcpayapp.ui.screens.payout

import com.btcpayapp.core.lightning.Bolt11Fixture
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.mayHaveGoneThrough
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.RefundTriggerData
import com.btcpayapp.data.api.dto.RefundVariant
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.components.reviewDestination
import com.btcpayapp.ui.screens.invoice.RefundState
import com.btcpayapp.ui.screens.invoice.figureFor
import com.btcpayapp.ui.screens.invoice.refundAfterWithholding
import com.btcpayapp.ui.screens.invoice.refundAutoApproved
import com.btcpayapp.ui.screens.invoice.withheldPercent
import com.btcpayapp.ui.screens.server.ProcessorKind
import com.btcpayapp.ui.screens.server.ProcessorValues
import com.btcpayapp.ui.screens.server.feeTargetToSend
import com.btcpayapp.ui.screens.server.processorSummary
import com.btcpayapp.ui.screens.server.thresholdToSend
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers the payout, refund and processor screens send. Each of these
 * decides how much money leaves the store, or when, so they are tested as the
 * functions the screens call, not as copies of them.
 */
class PayoutRequestsTest {

    /** Compared by value: 0.00050 and 0.0005 are the same amount. */
    private fun assertAmount(expected: String, actual: BigDecimal?) {
        assertNotNull("expected $expected, got null", actual)
        assertEquals("expected $expected, got $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    // --- payoutAmount ------------------------------------------------------

    @Test
    fun `a sat amount is sent as BTC`() {
        // The case that sent 50,000 BTC: the field was read as BTC whatever
        // the user's unit.
        assertAmount("0.0005", payoutAmount("50000", BitcoinUnit.Sat, pullPaymentCurrency = null))
    }

    @Test
    fun `a BTC amount passes through`() {
        assertAmount("0.001", payoutAmount("0.001", BitcoinUnit.Btc, pullPaymentCurrency = null))
    }

    @Test
    fun `a pull payment amount stays in its currency whatever the unit`() {
        assertAmount("12.50", payoutAmount("12.50", BitcoinUnit.Sat, pullPaymentCurrency = "USD"))
        assertAmount("0.001", payoutAmount("0.001", BitcoinUnit.Sat, pullPaymentCurrency = "BTC"))
    }

    @Test
    fun `an altcoin method keeps its own unit`() {
        assertAmount("1.5", payoutAmount("1.5", BitcoinUnit.Sat, pullPaymentCurrency = null, cryptoCode = "LTC"))
    }

    @Test
    fun `the ambiguous thousands shape is refused`() {
        assertNull(payoutAmount("1,000", BitcoinUnit.Sat, pullPaymentCurrency = null))
        assertNull(payoutAmount("1,000", BitcoinUnit.Btc, pullPaymentCurrency = "USD"))
    }

    @Test
    fun `zero, negative and part-sat amounts are refused`() {
        assertNull(payoutAmount("0", BitcoinUnit.Btc, pullPaymentCurrency = null))
        assertNull(payoutAmount("-1", BitcoinUnit.Btc, pullPaymentCurrency = "USD"))
        assertNull(payoutAmount("0.5", BitcoinUnit.Sat, pullPaymentCurrency = null))
    }

    @Test
    fun `a pull payment amount takes no more decimals than the review shows`() {
        // The review showed $10.01 and the request carried 10.0051.
        assertNull(payoutAmount("10.0051", BitcoinUnit.Btc, pullPaymentCurrency = "USD"))
        assertNull(payoutAmount("0.123456789", BitcoinUnit.Sat, pullPaymentCurrency = "BTC"))
    }

    @Test
    fun `a pull payment with no currency is in BTC`() {
        assertEquals("BTC", PullPaymentCreateState(currency = " ").amountCurrency)
        assertEquals("USD", PullPaymentCreateState(currency = "USD").amountCurrency)
    }

    // --- A blank amount ----------------------------------------------------

    @Test
    fun `a blank amount takes what the invoice asks for, so the review can show it`() {
        val invoice = Bolt11Fixture.invoice(hrp = "lnbc2500u")
        assertAmount("0.0025", impliedInvoiceAmount(invoice, "BTC", pullPaymentCurrency = null))
        assertAmount("0.0025", impliedInvoiceAmount("lightning:$invoice", "BTC", pullPaymentCurrency = "BTC"))
    }

    @Test
    fun `a blank amount must be typed when the server would not take it from an invoice`() {
        // In another currency the server claims the rest of the pull payment.
        assertNull(impliedInvoiceAmount(Bolt11Fixture.invoice(hrp = "lnbc2500u"), "BTC", pullPaymentCurrency = "USD"))
        // An invoice that leaves the amount to the payer, and no invoice at all.
        assertNull(impliedInvoiceAmount(Bolt11Fixture.invoice(hrp = "lnbc"), "BTC", pullPaymentCurrency = null))
        assertNull(impliedInvoiceAmount("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", "BTC", pullPaymentCurrency = null))
    }

    // --- A create that failed ----------------------------------------------

    @Test
    fun `a create with no clear answer or a server error may have been made`() {
        // A 500 after the record was saved left Create on, and a retry made a second one.
        assertTrue(ApiException.OutcomeUnknown().mayHaveGoneThrough())
        assertTrue(ApiException.Server(500, "server-error", "").mayHaveGoneThrough())
        assertTrue(ApiException.Server(503, "server-error", "").mayHaveGoneThrough())
    }

    @Test
    fun `a create refused before anything was made can be tried again`() {
        assertFalse(ApiException.Server(400, "generic-error", "").mayHaveGoneThrough())
        assertFalse(ApiException.Server(409, "duplicate-destination", "").mayHaveGoneThrough())
        assertFalse(ApiException.Forbidden(null).mayHaveGoneThrough())
        assertFalse(ApiException.NotFound().mayHaveGoneThrough())
        assertFalse(ApiException.Transport("down").mayHaveGoneThrough())
    }

    // --- Refunds -----------------------------------------------------------

    @Test
    fun `nothing withheld refunds the whole amount`() {
        assertAmount("0.001", refundAfterWithholding(BigDecimal("0.001"), BigDecimal.ZERO))
    }

    @Test
    fun `ten percent withheld refunds nine tenths`() {
        assertAmount("0.0009", refundAfterWithholding(BigDecimal("0.001"), BigDecimal.TEN))
        assertAmount("45", refundAfterWithholding(BigDecimal("50"), BigDecimal.TEN))
    }

    @Test
    fun `everything withheld refunds nothing`() {
        assertAmount("0", refundAfterWithholding(BigDecimal("0.001"), BigDecimal(100)))
    }

    @Test
    fun `withholding must be a percentage`() {
        assertAmount("0", withheldPercent(""))
        assertAmount("10", withheldPercent("10"))
        assertAmount("2.5", withheldPercent("2,5"))
        // Each of these used to be dropped, so the customer could claim it all.
        assertNull(withheldPercent("10%"))
        assertNull(withheldPercent("101"))
        assertNull(withheldPercent("-1"))
    }

    @Test
    fun `the fiat option is in the invoice currency`() {
        val trigger = RefundTriggerData(
            paymentAmountThen = BigDecimal("0.001"),
            paymentAmountNow = BigDecimal("0.0011"),
            invoiceAmount = BigDecimal("50"),
            paymentCurrency = "BTC",
            invoiceCurrency = "USD",
        )
        assertEquals("USD", trigger.figureFor(RefundVariant.Fiat)?.currency)
        assertEquals("BTC", trigger.figureFor(RefundVariant.CurrentRate)?.currency)
        // Not overpaid: no figure, so the option cannot be chosen or submitted.
        assertNull(trigger.figureFor(RefundVariant.OverpaidAmount))
    }

    @Test
    fun `the server approves claims for the rate variants and custom in the payment currency`() {
        assertTrue(refundAutoApproved(RefundVariant.CurrentRate, customCurrency = "USD", paymentCurrency = "BTC"))
        assertTrue(refundAutoApproved(RefundVariant.RateThen, customCurrency = "USD", paymentCurrency = "BTC"))
        assertTrue(refundAutoApproved(RefundVariant.OverpaidAmount, customCurrency = "USD", paymentCurrency = "BTC"))
        assertTrue(refundAutoApproved(RefundVariant.Custom, customCurrency = "BTC", paymentCurrency = "BTC"))
        assertTrue(refundAutoApproved(RefundVariant.Custom, customCurrency = "btc ", paymentCurrency = "BTC"))
    }

    @Test
    fun `a custom refund is reviewed only when it is exactly what is sent`() {
        val custom = RefundState(variant = RefundVariant.Custom, customCurrency = "USD")
        assertAmount("10.01", custom.copy(customAmount = "10.01").refundFigure?.amount)
        // Shown as $10.01, it was sent as 10.0051.
        assertNull(custom.copy(customAmount = "10.0051").refundFigure)
        assertNull(custom.copy(customAmount = "10", customCurrency = " ").refundFigure)
    }

    @Test
    fun `claims wait for approval for fiat and custom in another currency`() {
        assertFalse(refundAutoApproved(RefundVariant.Custom, customCurrency = "USD", paymentCurrency = "BTC"))
        assertFalse(refundAutoApproved(RefundVariant.Fiat, customCurrency = "BTC", paymentCurrency = "BTC"))
    }

    // --- Payout processors -------------------------------------------------

    @Test
    fun `a blank threshold keeps the loaded value`() {
        // Omitted, the server would set it to 0 and sweep on every run.
        assertAmount("0.001", thresholdToSend("", BigDecimal("0.001")))
        assertAmount("0", thresholdToSend(" ", BigDecimal.ZERO))
    }

    @Test
    fun `a typed threshold replaces the loaded one`() {
        assertAmount("0.5", thresholdToSend("0,5", BigDecimal("0.001")))
        assertAmount("0", thresholdToSend("0", BigDecimal("0.001")))
    }

    @Test
    fun `a threshold that is not a BTC amount is refused`() {
        assertNull(thresholdToSend("abc", BigDecimal.ZERO))
        assertNull(thresholdToSend("-1", BigDecimal.ZERO))
        assertNull(thresholdToSend("0.000000001", BigDecimal.ZERO))
    }

    @Test
    fun `a blank fee target keeps the loaded value`() {
        assertEquals(6, feeTargetToSend("", 6))
        assertEquals(3, feeTargetToSend("3", 6))
        assertNull(feeTargetToSend("0", 6))
    }

    @Test
    fun `the processor confirmation lists the settings it turns on`() {
        val values = ProcessorValues(seconds = 3600, instant = false, feeTarget = 6, threshold = BigDecimal("0.001"))
        assertEquals(
            "Runs every 1 h.\nNew payouts wait for the next run.\nFee target: 6 blocks.\nThreshold: 0.001 BTC.",
            processorSummary(ProcessorKind.OnChain, "BTC-CHAIN", values),
        )
        // Lightning has no fee target or threshold, so none is claimed.
        assertEquals(
            "Runs every 1 h.\nNew payouts are sent at once.",
            processorSummary(ProcessorKind.Lightning, "BTC-LN", values.copy(instant = true)),
        )
    }

    @Test
    fun `the processor confirmation shows the interval exactly`() {
        fun interval(seconds: Int) = processorSummary(
            ProcessorKind.Lightning,
            "BTC-LN",
            ProcessorValues(seconds = seconds, instant = false, feeTarget = 1, threshold = BigDecimal.ZERO),
        ).lineSequence().first()
        // 1.5 minutes read "1 min", which is not what the server runs.
        assertEquals("Runs every 90 s.", interval(90))
        assertEquals("Runs every 30 s.", interval(30))
        assertEquals("Runs every 1 min.", interval(60))
        assertEquals("Runs every 90 min.", interval(5400))
        assertEquals("Runs every 2 h.", interval(7200))
    }

    // --- Review ------------------------------------------------------------

    @Test
    fun `an address is shown in groups of four and whole`() {
        assertEquals(
            "bc1q ar0s rrr7 xfkv y5l6 43ly dnw9 re59 gtzz wf5m dq",
            reviewDestination("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"),
        )
    }

    @Test
    fun `anything that is not an address is shown as it is`() {
        val lnurl = "lnurl1dp68gurn8ghj7cm0d9hxxmmjdejhytnfduq"
        assertEquals(lnurl, reviewDestination(lnurl))
        assertEquals("satoshi@example.com", reviewDestination(" satoshi@example.com "))
    }

    @Test
    fun `approving a payout priced in fiat needs the store's rate for its coin`() {
        assertEquals("BTC_USD", approveRatePair(PayoutData(originalCurrency = "usd", payoutMethodId = "BTC-LN")))
        assertEquals("LTC_EUR", approveRatePair(PayoutData(originalCurrency = "EUR", payoutMethodId = "LTC-CHAIN")))
    }

    @Test
    fun `approving a payout priced in its own coin needs no rate`() {
        assertNull(approveRatePair(PayoutData(originalCurrency = "BTC", payoutMethodId = "BTC-CHAIN")))
        assertNull(approveRatePair(PayoutData(originalCurrency = "USD", payoutMethodId = "")))
    }
}
