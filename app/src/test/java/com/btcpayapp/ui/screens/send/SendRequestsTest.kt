package com.btcpayapp.ui.screens.send

import com.btcpayapp.core.lightning.Bolt11
import com.btcpayapp.core.lightning.Bolt11Fixture
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.wallet.SignedTransaction
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.LightningPaymentData
import com.btcpayapp.data.api.dto.LightningPaymentStatus
import com.btcpayapp.data.api.dto.WalletUtxoData
import com.btcpayapp.data.model.BitcoinUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * What the Send screen puts in front of the sender, and what it sends.
 *
 * Both halves are pure on purpose. The Lightning request is built from typed
 * text, where a German "0,5" or a lone thousands separator once reached the
 * server as something else; the on-chain review is computed from the signed
 * transaction, and it is the last thing read before a broadcast that cannot
 * be recalled.
 */
class SendRequestsTest {

    private fun build(
        invoiceMsat: Long? = 150_000L,
        amount: String = "",
        unit: BitcoinUnit = BitcoinUnit.Sat,
        feePercent: String = "3",
        timeout: String = "30",
        flat: String = "",
    ) = buildPayRequest("lnbc1test", invoiceMsat, amount, unit, feePercent, timeout, flat)

    // --- Lightning request -------------------------------------------------

    @Test
    fun `a decimal comma in the fee limit is sent as a plain decimal`() {
        val (request, errors) = build(feePercent = "0,5")
        assertTrue(errors.isEmpty())
        assertEquals("0.5", request!!.maxFeePercent)
    }

    @Test
    fun `a fee limit outside 0 to 100, or not a number, is refused`() {
        for (text in listOf("101", "abc", "-1")) {
            val (request, errors) = build(feePercent = text)
            assertNull(text, request)
            assertTrue(text, FIELD_MAX_FEE_PERCENT in errors)
        }
        assertEquals("100", build(feePercent = "100").first!!.maxFeePercent)
    }

    @Test
    fun `an empty fee limit leaves it to the node`() {
        assertNull(build(feePercent = "").first!!.maxFeePercent)
    }

    @Test
    fun `an amountless invoice sends the typed amount in msat`() {
        assertEquals("1500000", build(invoiceMsat = null, amount = "1500").first!!.amount)
        assertEquals(
            "1000000",
            build(invoiceMsat = null, amount = "0.00001", unit = BitcoinUnit.Btc).first!!.amount,
        )
    }

    @Test
    fun `an amountless invoice needs an amount, read by the one amount rule`() {
        assertTrue(FIELD_AMOUNT in build(invoiceMsat = null, amount = "").second)

        // Ten thousand or ten: refused and asked, never guessed.
        val (request, errors) = build(invoiceMsat = null, amount = "10,000")
        assertNull(request)
        assertEquals(Amounts.parseProblem("10,000"), errors[FIELD_AMOUNT])

        // Half a sat cannot be paid, and nothing is not an amount.
        assertTrue(FIELD_AMOUNT in build(invoiceMsat = null, amount = "1.5").second)
        assertTrue(FIELD_AMOUNT in build(invoiceMsat = null, amount = "0").second)
    }

    @Test
    fun `an invoice with its own amount sends none`() {
        val (request, errors) = build(invoiceMsat = 150_000L, amount = "999")
        assertTrue(errors.isEmpty())
        assertNull(request!!.amount)
        // A stray amount left over from an earlier invoice does not block it.
        assertNotNull(build(invoiceMsat = 150_000L, amount = "abc").first)
    }

    @Test
    fun `the timeout must be 10 to 120 seconds`() {
        for (text in listOf("9", "121", "abc")) {
            val (request, errors) = build(timeout = text)
            assertNull(text, request)
            assertTrue(text, FIELD_SEND_TIMEOUT in errors)
        }
        assertEquals(10, build(timeout = "10").first!!.sendTimeout)
        assertEquals(120, build(timeout = "120").first!!.sendTimeout)
        // Sent even when blank: the pay call's own wait is sized from it.
        assertEquals(30, build(timeout = "").first!!.sendTimeout)
    }

    @Test
    fun `the flat fee cap is whole sats`() {
        assertEquals("21", build(flat = "21").first!!.maxFeeFlat)
        assertEquals("100", build(flat = "100").first!!.maxFeeFlat)
        assertTrue(FIELD_MAX_FEE_FLAT in build(flat = "2.5").second)
        assertNull(build(flat = "").first!!.maxFeeFlat)
    }

    @Test
    fun `the fee limit in the confirmation is an upper bound in sat`() {
        // 3 % of 1,000 sat.
        assertEquals(30L, feeLimitSats(1_000_000L, "3", null))
        // The larger of the two caps, because nodes combine them differently.
        assertEquals(50L, feeLimitSats(1_000_000L, "3", "50"))
        assertEquals(30L, feeLimitSats(1_000_000L, "3", "10"))
        // Rounded up: 0.5 % of 1,001 sat is 5.005 sat.
        assertEquals(6L, feeLimitSats(1_001_000L, "0.5", null))
        // Nothing set, or zero, is the node's own default.
        assertNull(feeLimitSats(1_000_000L, null, null))
        assertNull(feeLimitSats(1_000_000L, "0", "0"))
    }

    @Test
    fun `only an invoice the phone can read is payable`() {
        val invoice = Bolt11Fixture.invoice()
        assertNull(unpayableReason(invoice, Bolt11.decode(invoice)))
        assertNull(unpayableReason("", null))

        assertNotNull(unpayableReason("lnurl1dp68gurn8ghj7cm0d9hxxmmjdejhytnfduq", null))
        assertNotNull(unpayableReason("satoshi@example.com", null))
        assertNotNull(unpayableReason("lnbc1notaninvoice", Bolt11.decode("lnbc1notaninvoice")))
        // No payment hash: a lost answer could not be tracked.
        val noHash = Bolt11Fixture.invoice(paymentHash = null)
        assertNotNull(unpayableReason(noHash, Bolt11.decode(noHash)))
    }

    @Test
    fun `a scheme in any case is stripped and the invoice is sent as it was read`() {
        val invoice = Bolt11Fixture.invoice()
        val state = LightningSendState(bolt11 = "Lightning:${invoice.uppercase()}")
        assertNull(state.unpayable)
        assertFalse(state.needsAmount)
        assertEquals(invoice, state.payRequest(BitcoinUnit.Sat).first!!.BOLT11)
    }

    @Test
    fun `only an amountless invoice asks for an amount`() {
        assertTrue(LightningSendState(bolt11 = Bolt11Fixture.invoice(hrp = "lnbc")).needsAmount)
        // An invoice the phone cannot read is not payable, so it asks for nothing.
        assertFalse(LightningSendState(bolt11 = "lnbc1notaninvoice").needsAmount)
    }

    @Test
    fun `the payee's description cannot break the confirmation into lines`() {
        // A line separator, a right-to-left override and a tab: each could
        // move or reorder the fee limit and the warning after the quote.
        assertEquals("Coffee Routing fee limit: 0 sat", oneLine("Coffee\u2028Routing\u202E fee\tlimit:\n\n0 sat "))
    }

    // --- Amount field ------------------------------------------------------

    @Test
    fun `amount errors say what to change`() {
        assertNull(amountProblem("", BitcoinUnit.Sat))
        assertNull(amountProblem("21000", BitcoinUnit.Sat))
        assertEquals(Amounts.parseProblem("21,000"), amountProblem("21,000", BitcoinUnit.Sat))
        assertEquals("Enter a whole number of sats.", amountProblem("1.5", BitcoinUnit.Sat))
        assertEquals("Use at most 8 decimal places.", amountProblem("0.123456789", BitcoinUnit.Btc))
        assertEquals("Enter an amount greater than zero.", amountProblem("0", BitcoinUnit.Btc))
        assertEquals("Enter an amount greater than zero.", amountProblem("-5", BitcoinUnit.Sat))
    }

    // --- On-chain review ---------------------------------------------------

    private val tx = SignedTransaction(
        hex = "00",
        txid = "t",
        inputs = listOf("a:0", "b:1"),
        outputSats = listOf(50_000L, 45_000L),
        outputScripts = listOf(PAYEE, CHANGE),
        vsize = 200,
    )

    // As Greenfield sends them: NBitcoin writes an outpoint as "txid-vout".
    private val coins = coinValues(
        listOf(
            WalletUtxoData(amount = BigDecimal("0.0006"), outpoint = "a-0"),
            WalletUtxoData(amount = BigDecimal("0.0004"), outpoint = "B-1"),
        ),
    )

    @Test
    fun `server outpoints are keyed the way the transaction names its inputs`() {
        assertEquals(mapOf("a:0" to 60_000L, "b:1" to 40_000L), coins)
        // A colon form, should a server ever send one, is kept as it is.
        val colon = WalletUtxoData(amount = BigDecimal("0.00000001"), outpoint = "a:0")
        assertEquals(mapOf("a:0" to 1L), coinValues(listOf(colon)))
    }

    @Test
    fun `the fee is what the inputs hold minus what the outputs pay`() {
        assertEquals(5_000L, tx.feeSats(coins))
        val numbers = reviewOf(
            tx, feeSats = 5_000, paidSats = 50_000, amountSats = 50_000, subtractFee = false, nextBlockRate = 20.0,
        )
        assertEquals(25.0, numbers.feeRate, 1e-9)
        assertEquals(55_000L, numbers.totalSats)
        // 25 sat/vB is under twice 20, and 5,000 sat is exactly a tenth.
        assertNull(numbers.warning)
    }

    @Test
    fun `the total is the signed payment plus the fee, not the typed amount`() {
        // Fee taken from 55,000: the output is 50,000, so 55,000 leaves.
        val taken = paidToDestination(tx, PAYEE, amountSats = 55_000, feeSats = 5_000, subtractFee = true)!!
        val fromAmount = reviewOf(tx, 5_000, taken, 55_000, subtractFee = true, nextBlockRate = 20.0)
        assertEquals(55_000L, fromAmount.totalSats)
        assertTrue(fromAmount.feeFromAmount)
        assertNull(fromAmount.warning)
        // Asked to take the fee from 50,000, the server paid all of it: the fee comes on top, and the total says so.
        val notTaken = paidToDestination(tx, PAYEE, amountSats = 50_000, feeSats = 5_000, subtractFee = true)!!
        val onTop = reviewOf(tx, 5_000, notTaken, 50_000, subtractFee = true, nextBlockRate = 20.0)
        assertEquals(55_000L, onTop.totalSats)
        // The review must not say the fee comes out of the amount, and it warns.
        assertFalse(onTop.feeFromAmount)
        assertEquals("Not all of the fee is taken from the amount. Check the total.", onTop.warning)
        // Not asked for, the fee on top is what the sender expects.
        assertFalse(reviewOf(tx, 5_000, 50_000, 50_000, subtractFee = false, nextBlockRate = 20.0).feeFromAmount)
    }

    @Test
    fun `a coin missing from the wallet list leaves the fee unknown`() {
        assertNull(tx.feeSats(mapOf("a:0" to 60_000L)))
        assertNull(tx.feeSats(emptyMap()))
    }

    @Test
    fun `a rate above twice the next-block estimate warns`() {
        assertEquals(
            "This fee rate is more than twice the next-block estimate.",
            reviewOf(tx, 5_000, 50_000, 50_000, subtractFee = false, nextBlockRate = 12.0).warning,
        )
        // No estimate, nothing to compare with.
        assertNull(reviewOf(tx, 5_000, 50_000, 50_000, subtractFee = false, nextBlockRate = null).warning)
    }

    @Test
    fun `a fee above a tenth of the amount warns`() {
        assertEquals(
            "The fee is more than 10% of the amount.",
            reviewOf(tx, 5_000, 49_999, 49_999, subtractFee = false, nextBlockRate = 20.0).warning,
        )
    }

    @Test
    fun `one warning does not hide another`() {
        // Asked to take the fee from 45,000, the server paid all of it, and the fee is also over a tenth.
        assertEquals(
            "Not all of the fee is taken from the amount. Check the total.\nThe fee is more than 10% of the amount.",
            reviewOf(tx, 5_000, 45_000, 45_000, subtractFee = true, nextBlockRate = 20.0).warning,
        )
    }

    @Test
    fun `the signed outputs must pay the amount on the review`() {
        assertEquals(50_000L, paidToDestination(tx, PAYEE, amountSats = 50_000, feeSats = 5_000, subtractFee = false))
        assertNull(paidToDestination(tx, PAYEE, amountSats = 49_000, feeSats = 5_000, subtractFee = false))
        // Fee taken from 55,000: the output is 50,000.
        assertEquals(50_000L, paidToDestination(tx, PAYEE, amountSats = 55_000, feeSats = 5_000, subtractFee = true))
        // The fee taken twice from 60,000 would leave 50,000: less than the amount less the fee.
        assertNull(paidToDestination(tx, PAYEE, amountSats = 60_000, feeSats = 5_000, subtractFee = true))
    }

    @Test
    fun `the amount must go to the typed address, not to another output`() {
        // 45,000 is paid, but to the change script, not to the address on the review.
        assertNull(paidToDestination(tx, PAYEE, amountSats = 45_000, feeSats = 5_000, subtractFee = false))
        // A destination the server swapped: the right value, to another script.
        assertNull(paidToDestination(tx, OTHER, amountSats = 50_000, feeSats = 5_000, subtractFee = false))
        // With no script (a coin other than BTC), only the value is checked.
        assertEquals(45_000L, paidToDestination(tx, null, amountSats = 45_000, feeSats = 5_000, subtractFee = false))
    }

    @Test
    fun `only the payment and at most one change output may be signed`() {
        // A third output takes money the total would not show.
        val extra = tx.copy(outputSats = listOf(50_000L, 40_000L, 5_000L), outputScripts = listOf(PAYEE, CHANGE, OTHER))
        assertNull(paidToDestination(extra, PAYEE, amountSats = 50_000, feeSats = 5_000, subtractFee = false))
        assertNull(paidToDestination(extra, null, amountSats = 50_000, feeSats = 5_000, subtractFee = false))
        // A second output to the typed address is not change.
        val twice = tx.copy(outputScripts = listOf(PAYEE, PAYEE))
        assertNull(paidToDestination(twice, PAYEE, amountSats = 50_000, feeSats = 5_000, subtractFee = false))
        // No change at all is fine.
        val alone = tx.copy(outputSats = listOf(50_000L), outputScripts = listOf(PAYEE))
        assertEquals(50_000L, paidToDestination(alone, PAYEE, amountSats = 50_000, feeSats = 5_000, subtractFee = false))
    }

    @Test
    fun `with no script to tell them apart, the larger matching output counts`() {
        // Both could be the payment of 50,000 less part of the fee; the larger keeps the total from being too low.
        assertEquals(50_000L, paidToDestination(tx, null, amountSats = 50_000, feeSats = 5_000, subtractFee = true))
    }

    @Test
    fun `a custom fee rate that cannot be used blocks the send`() {
        assertNull(WalletSendState().feeRateError)
        assertNull(WalletSendState(customFeeRate = "1.125").feeRateError)
        assertEquals(1.125, WalletSendState(customFeeRate = "1.125").effectiveFeeRate!!, 0.0)
        for (text in listOf("0", "1.2.3")) {
            val state = WalletSendState(customFeeRate = text)
            assertEquals(text, "Enter a fee rate greater than zero, in sat/vB.", state.feeRateError)
            assertNull(text, state.effectiveFeeRate)
        }
    }

    // --- On-chain settlement -----------------------------------------------

    private val nodeRefusal = ApiException.Server(400, "broadcast-error", "min relay fee not met")
    private val notIndexed = ApiException.NotFound(code = "transaction-not-found")

    @Test
    fun `a refusal before anything may be out is not sent, and the payment can change`() {
        val refusal = refusalOf(nodeRefusal, mayHaveReachedNetwork = false, versionUnknown = false)!!
        assertTrue(refusal.canChange)
        assertEquals("The payment was not sent: min relay fee not met. Nothing left the wallet.", refusal.message)
        assertNotNull(refusalOf(ApiException.Validation(emptyList()), false, false))
        assertNotNull(refusalOf(ApiException.Forbidden(null), false, false))
    }

    @Test
    fun `anything but a clear refusal may have gone out`() {
        val failures = listOf(
            ApiException.OutcomeUnknown(),
            ApiException.Timeout(),
            ApiException.Transport("Connection reset"),
            ApiException.Decoding(),
            ApiException.Server(500, "unknown", "Internal error"),
            ApiException.NotFound(code = "store-not-found"),
        )
        for (failure in failures) assertNull(failure.javaClass.simpleName, refusalOf(failure, false, false))
    }

    @Test
    fun `after a lost answer a refusal of the same bytes is not trusted`() {
        // The first attempt got no answer, so the view model marks the bytes
        // as maybe out. A node that already has them can refuse them next time.
        assertNull(refusalOf(ApiException.OutcomeUnknown(), mayHaveReachedNetwork = false, versionUnknown = false))
        val second = refusalOf(nodeRefusal, mayHaveReachedNetwork = true, versionUnknown = false)
        assertNull(second)
        assertTrue(settled("t", notIndexed, second) is OnChainPhase.Unknown)
    }

    @Test
    fun `an empty 404 is a missing route only on a server of unknown version`() {
        val missing = refusalOf(ApiException.NotFound(), mayHaveReachedNetwork = false, versionUnknown = true)!!
        assertFalse(missing.canChange)
        // A known version that got this far is 2.3.3 or later, so the route exists.
        assertNull(refusalOf(ApiException.NotFound(), mayHaveReachedNetwork = false, versionUnknown = false))
    }

    @Test
    fun `the lookup decides, and only a refusal makes not found mean not sent`() {
        val refusal = OnChainPhase.NotSent("Refused.", canChange = true)
        assertEquals(OnChainPhase.Sent("t"), settled("t", null, refusal))
        assertEquals(refusal, settled("t", notIndexed, refusal))
        assertTrue(settled("t", notIndexed, null) is OnChainPhase.Unknown)
        // A lookup that failed says nothing either way.
        assertTrue(settled("t", ApiException.Timeout(), refusal) is OnChainPhase.Unknown)
    }

    // --- Lightning tracking ------------------------------------------------

    private val noSuchPayment = ApiException.NotFound(code = "payment-not-found")

    private fun LightningTracking.afterNotFound(times: Int): LightningTracking {
        var tracking = this
        repeat(times) { tracking = tracking.after(null, noSuchPayment) }
        return tracking
    }

    @Test
    fun `three answers in a row that the node has no such payment mean not sent`() {
        val tracking = LightningTracking("h", inFlight = false, seen = false)
        assertFalse(tracking.afterNotFound(2).notSent)
        assertTrue(tracking.afterNotFound(3).notSent)
    }

    @Test
    fun `an error in between, or an empty 404, starts the row again`() {
        val start = LightningTracking("h", inFlight = false, seen = false)
        assertEquals(0, start.afterNotFound(2).after(null, ApiException.Timeout()).notFound)
        assertFalse(start.afterNotFound(2).after(null, ApiException.NotFound()).afterNotFound(1).notSent)
    }

    @Test
    fun `a payment the node once showed is never called not sent`() {
        // The pay call answered "pending", so the node had it.
        val pending = LightningTracking("h", inFlight = true, seen = true).afterNotFound(5)
        assertFalse(pending.notSent)
        assertFalse(pending.inFlight)

        // The same when a lookup showed it first.
        val shown = LightningTracking("h", inFlight = false, seen = false)
            .after(LightningPaymentData(status = LightningPaymentStatus.Pending), null)
        assertTrue(shown.inFlight)
        assertFalse(shown.afterNotFound(3).notSent)
    }

    private companion object {
        /** P2WPKH, P2WSH and P2PKH output scripts, as SignedTransaction writes them. */
        const val PAYEE = "0014751e76e8199196d454941c45d1b3a323f1433bd6"
        const val CHANGE = "00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262"
        const val OTHER = "76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac"
    }
}
