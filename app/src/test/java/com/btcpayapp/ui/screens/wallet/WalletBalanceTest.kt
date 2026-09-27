package com.btcpayapp.ui.screens.wallet

import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.dto.LightningInvoiceData
import com.btcpayapp.data.api.dto.LightningInvoiceStatus
import com.btcpayapp.data.model.BitcoinUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The wallet headline and the Lightning list decide what a merchant believes
 * the store holds and received. A null rail read as zero, or a cut
 * made before the paid filter, shows a wrong figure as a fact.
 */
class WalletBalanceTest {

    private fun headline(
        onChain: String?,
        lightning: String?,
        hasOnChain: Boolean = true,
        hasLightning: Boolean = true,
        bitcoin: Boolean = true,
    ) = balanceHeadline(onChain?.toBigDecimal(), lightning?.toBigDecimal(), hasOnChain, hasLightning, bitcoin)

    @Test
    fun `both rails known is the whole balance`() {
        val result = headline("0.5", "0.25")
        assertEquals(0, BigDecimal("0.75").compareTo(result.amount!!))
        assertTrue(result.complete)
    }

    @Test
    fun `a rail that did not load is left out and the sum is partial`() {
        val onChainFailed = headline(null, "0.25")
        assertEquals(0, BigDecimal("0.25").compareTo(onChainFailed.amount!!))
        assertFalse(onChainFailed.complete)

        val nodeUnknown = headline("0.5", null)
        assertEquals(0, BigDecimal("0.5").compareTo(nodeUnknown.amount!!))
        assertFalse(nodeUnknown.complete)
    }

    @Test
    fun `nothing known has no figure rather than zero`() {
        val result = headline(null, null)
        assertNull(result.amount)
        assertFalse(result.complete)
    }

    @Test
    fun `a rail the store does not have is not missing`() {
        assertTrue(headline("0.5", null, hasLightning = false).complete)
        assertTrue(headline(null, "0.25", hasOnChain = false).complete)
    }

    @Test
    fun `an altcoin wallet is never added to the node`() {
        val result = headline("2.5", "0.25", bitcoin = false)
        assertEquals(0, BigDecimal("2.5").compareTo(result.amount!!))
        assertTrue(result.complete)
    }

    @Test
    fun `an altcoin amount keeps its own code whatever the bitcoin unit`() {
        val amount = BigDecimal("2.5")
        assertEquals(Amounts.format(amount, "LTC"), formatOnChain(amount, "LTC", BitcoinUnit.Sat))
        assertEquals(Amounts.formatBitcoin(amount, BitcoinUnit.Sat), formatOnChain(amount, "BTC", BitcoinUnit.Sat))
    }

    private fun invoice(hash: String, status: LightningInvoiceStatus, paidAt: Long?, expiresAt: Long) =
        LightningInvoiceData(paymentHash = hash, status = status, paidAt = paidAt, expiresAt = expiresAt)

    @Test
    fun `open checkouts do not push out the payments that landed`() {
        // Every open checkout makes a node invoice that expires in the future,
        // so it sorts above every paid one.
        val open = (1..RECENT_LIGHTNING_LIMIT + 5).map { invoice("open-$it", LightningInvoiceStatus.Unpaid, null, 2_000L + it) }
        val paid = listOf(invoice("paid-a", LightningInvoiceStatus.Paid, 100L, 900L), invoice("paid-b", LightningInvoiceStatus.Paid, 200L, 900L))
        assertEquals(listOf("paid-b", "paid-a"), recentPaid(open + paid).map { it.paymentHash })
    }

    @Test
    fun `only the newest paid invoices are kept`() {
        val paid = (1..RECENT_LIGHTNING_LIMIT + 5).map { invoice("p$it", LightningInvoiceStatus.Paid, it.toLong(), 0L) }
        val result = recentPaid(paid.shuffled())
        assertEquals(RECENT_LIGHTNING_LIMIT, result.size)
        assertEquals("p${RECENT_LIGHTNING_LIMIT + 5}", result.first().paymentHash)
        assertEquals("p6", result.last().paymentHash)
    }
}
