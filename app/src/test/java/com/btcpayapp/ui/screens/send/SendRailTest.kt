package com.btcpayapp.ui.screens.send

import com.btcpayapp.core.scan.ScanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One Send screen serves both rails, so this decides which form the sender is
 * looking at. Getting it wrong is not cosmetic: an address filed under Lightning
 * is a payment that cannot be made, and an invoice filed on-chain is worse — the
 * server would be asked to broadcast to a destination that is not an address.
 *
 * The addresses and invoices below are real shapes, not placeholders; a regex
 * that only matches a fixture is a regex that only works in this file.
 */
class SendRailTest {

    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val invoice = "lnbc150n1p3w0tkypp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq" +
        "5vdhkven9v5sxyetpdees9qrsgqtqyx5vggfcsll4wu246hz02kp85x4katwsk9639we5n5yngc3yhqkm35jnj" +
        "w504jsxqsxs3dqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    private fun rail(raw: String, preferOnChain: Boolean = true) =
        railFor(ScanParser.parse(raw), preferOnChain)

    @Test
    fun `a bare address is on-chain`() {
        assertEquals(SendRail.OnChain, rail(address))
    }

    @Test
    fun `a bolt11 invoice is lightning`() {
        assertEquals(SendRail.Lightning, rail(invoice))
    }

    @Test
    fun `a lightning uri is lightning`() {
        assertEquals(SendRail.Lightning, rail("lightning:$invoice"))
    }

    @Test
    fun `an lnurl is lightning`() {
        // Not payable from this screen, but it is unambiguously not on-chain,
        // and the Lightning form is where the explanation lives.
        assertEquals(SendRail.Lightning, rail("lnurl1dp68gurn8ghj7cm0d9hxxmmjdejhytnfduq"))
    }

    @Test
    fun `a plain bip21 is on-chain`() {
        assertEquals(SendRail.OnChain, rail("bitcoin:$address?amount=0.00015"))
    }

    @Test
    fun `a bip21 carrying both follows the store`() {
        val raw = "bitcoin:$address?amount=0.00015&lightning=$invoice"

        // With an on-chain wallet the address half wins; the invoice is still
        // there to be switched to.
        assertEquals(SendRail.OnChain, rail(raw, preferOnChain = true))
        // Without one, the invoice is the only half that can be paid.
        assertEquals(SendRail.Lightning, rail(raw, preferOnChain = false))
    }

    @Test
    fun `a bip21 with no address is lightning whatever the store has`() {
        val raw = "bitcoin:?lightning=$invoice"
        assertEquals(SendRail.Lightning, rail(raw, preferOnChain = true))
        assertEquals(SendRail.Lightning, rail(raw, preferOnChain = false))
    }

    @Test
    fun `a destination that says nothing yet does not move the sender`() {
        // Null rather than a guess. Switching the form out from under someone
        // mid-keystroke is worse than waiting for the character that settles it.
        assertNull(rail(""))
        assertNull(rail("bc1"))
        assertNull(rail("lnb"))
    }

    @Test
    fun `a bech32 prefix long enough to read as an address counts as one`() {
        // The parser does not verify the checksum, so a truncated address still
        // reads as on-chain. That is the right answer for choosing a rail — the
        // form it opens is the only one that could ever pay it — and the address
        // itself is validated by the server, which is the only place it can be.
        assertEquals(SendRail.OnChain, rail("bc1qar0srrr"))
    }

    @Test
    fun `a server url is not a destination`() {
        assertNull(rail("https://btcpay.example.com"))
    }
}
