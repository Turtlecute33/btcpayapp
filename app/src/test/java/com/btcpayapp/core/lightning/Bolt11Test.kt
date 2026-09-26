package com.btcpayapp.core.lightning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The decoder reads an invoice a camera picked up off a stranger's phone, so
 * two things are being asserted here: that a real invoice yields the right
 * numbers, and that a corrupt one yields null rather than an exception or a
 * plausible-looking lie.
 *
 * Field coverage uses invoices built by [Bolt11Fixture] — the spec's own
 * vectors are long enough that a single mistyped character in this file would
 * look like a decoder bug. The two spec vectors at the bottom are there to
 * prove the checksum really is bech32 and not whatever the fixture encoder
 * happens to compute.
 */
class Bolt11Test {

    // --- Amounts -----------------------------------------------------------

    @Test
    fun `milli multiplier is 10^8 msat`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc1m"))
        assertEquals(BigInteger.valueOf(100_000_000L), invoice?.amountMsat)
    }

    @Test
    fun `micro multiplier is 10^5 msat`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc2500u"))
        assertEquals(BigInteger.valueOf(250_000_000L), invoice?.amountMsat)
    }

    @Test
    fun `nano multiplier is 10^2 msat`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc20n"))
        assertEquals(BigInteger.valueOf(2_000L), invoice?.amountMsat)
    }

    @Test
    fun `pico multiplier is a tenth of a msat`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc9678785340p"))
        assertEquals(BigInteger.valueOf(967_878_534L), invoice?.amountMsat)
    }

    @Test
    fun `whole bitcoin with no multiplier`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc2"))
        assertEquals(BigInteger("200000000000"), invoice?.amountMsat)
    }

    @Test
    fun `pico amount that is not a multiple of ten is rejected`() {
        // It would be a fraction of a millisatoshi, which cannot be paid.
        assertNull(Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc1p")))
    }

    @Test
    fun `an invoice with no amount is amountless rather than zero`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc"))
        assertNotNull(invoice)
        assertNull(invoice!!.amountMsat)
        assertTrue(invoice.isAmountless)
    }

    // --- Fields ------------------------------------------------------------

    @Test
    fun `description is read as utf8`() {
        val raw = Bolt11Fixture.invoice(description = "Caffè — 2 espressi ☕")
        assertEquals("Caffè — 2 espressi ☕", Bolt11.decode(raw)?.description)
    }

    @Test
    fun `payment hash round trips as hex`() {
        val hash = "0001020304050607080900010203040506070809000102030405060708090102"
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(paymentHash = hash))
        assertEquals(hash, invoice?.paymentHash)
    }

    @Test
    fun `payee node is read when the optional n field is present`() {
        val payee = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(payee = payee))
        assertEquals(payee, invoice?.payeeNode)
    }

    @Test
    fun `payee node is null when the writer omitted it`() {
        // The common case: most implementations leave `n` out because it is
        // recoverable from the signature, which this app deliberately does not do.
        assertNull(Bolt11.decode(Bolt11Fixture.invoice())?.payeeNode)
    }

    @Test
    fun `missing expiry field defaults to one hour`() {
        val invoice = Bolt11.decode(Bolt11Fixture.invoice(timestamp = 1_700_000_000L))
        assertEquals(3600L, invoice?.expirySeconds)
        assertEquals(1_700_003_600L, invoice?.expiresAt)
    }

    @Test
    fun `expiry field is honoured`() {
        val invoice = Bolt11.decode(
            Bolt11Fixture.invoice(timestamp = 1_700_000_000L, expirySeconds = 60),
        )
        assertEquals(60L, invoice?.expirySeconds)
        assertTrue(invoice!!.isExpired(nowSeconds = 1_700_000_061L))
        assertFalse(invoice.isExpired(nowSeconds = 1_700_000_059L))
    }

    @Test
    fun `description hash invoices report the hash and no description`() {
        val hash = "3925b6f67e2c340036ed12093dd44e0368df1b6ea26c53dbe4811f58fd5db8c1"
        // A real one carries `h` *instead of* `d`; the spec forbids both.
        val invoice = Bolt11.decode(
            Bolt11Fixture.invoice(description = null, descriptionHash = hash),
        )
        assertEquals(hash, invoice?.descriptionHash)
        assertNull(invoice?.description)
    }

    @Test
    fun `unknown tagged fields are skipped by length`() {
        // A payment secret and a feature vector sit between the fields this app
        // reads; neither may derail the ones after them.
        val raw = Bolt11Fixture.invoice(
            description = "After the unknowns",
            includeUnknownFields = true,
        )
        assertEquals("After the unknowns", Bolt11.decode(raw)?.description)
    }

    @Test
    fun `network prefix is reported`() {
        assertEquals("bc", Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbc10u"))?.network)
        assertEquals("tb", Bolt11.decode(Bolt11Fixture.invoice(hrp = "lntb10u"))?.network)
        assertEquals("bcrt", Bolt11.decode(Bolt11Fixture.invoice(hrp = "lnbcrt10u"))?.network)
    }

    // --- Input this app is actually handed ---------------------------------

    @Test
    fun `lightning scheme prefix is stripped`() {
        val bare = Bolt11Fixture.invoice(description = "Coffee")
        assertEquals("Coffee", Bolt11.decode("lightning:$bare")?.description)
        assertEquals("Coffee", Bolt11.decode("LIGHTNING:$bare")?.description)
    }

    @Test
    fun `uppercase invoice from a qr still decodes`() {
        // QR encoders uppercase bech32 to reach alphanumeric mode.
        val raw = Bolt11Fixture.invoice(description = "Coffee")
        assertEquals("Coffee", Bolt11.decode(raw.uppercase())?.description)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val raw = Bolt11Fixture.invoice(description = "Coffee")
        assertEquals("Coffee", Bolt11.decode("  $raw \n")?.description)
    }

    // --- Refusals ----------------------------------------------------------

    @Test
    fun `a single flipped character fails the checksum`() {
        val raw = Bolt11Fixture.invoice(description = "Coffee")
        // 'q' and 'p' are adjacent in the charset, so this is the kind of
        // single-symbol corruption a scanner can produce.
        val index = raw.indexOf('q', startIndex = raw.indexOf('1'))
        val corrupt = raw.substring(0, index) + 'p' + raw.substring(index + 1)
        assertNull(Bolt11.decode(corrupt))
    }

    @Test
    fun `truncated invoice is refused`() {
        val raw = Bolt11Fixture.invoice()
        assertNull(Bolt11.decode(raw.dropLast(10)))
    }

    @Test
    fun `non invoices are refused rather than throwing`() {
        listOf(
            "",
            "   ",
            "not an invoice",
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            "lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexujcmvde6",
            "ln",
            "ln1",
            "lnbc1",
            "lnbc" + "q".repeat(200),
        ).forEach { input ->
            assertNull("expected null for \"$input\"", Bolt11.decode(input))
        }
    }

    @Test
    fun `an absurdly long payload is refused before any work`() {
        assertNull(Bolt11.decode("lnbc1" + "q".repeat(20_000)))
    }

    // --- Spec vectors ------------------------------------------------------

    @Test
    fun `bolt11 spec vector - amountless donation invoice`() {
        val invoice = Bolt11.decode(SPEC_VECTOR_NO_AMOUNT)
        assertNotNull("the spec vector must pass the bech32 checksum", invoice)
        assertEquals("bc", invoice!!.network)
        assertNull(invoice.amountMsat)
        assertEquals(1_496_314_658L, invoice.timestamp)
        assertEquals(SPEC_PAYMENT_HASH, invoice.paymentHash)
        assertEquals("Please consider supporting this project", invoice.description)
    }

    @Test
    fun `bolt11 spec vector - 250 microbitcoin coffee`() {
        val invoice = Bolt11.decode(SPEC_VECTOR_2500U)
        assertNotNull("the spec vector must pass the bech32 checksum", invoice)
        assertEquals(BigInteger.valueOf(250_000_000L), invoice!!.amountMsat)
        assertEquals(1_496_314_658L, invoice.timestamp)
        assertEquals(SPEC_PAYMENT_HASH, invoice.paymentHash)
        assertEquals("1 cup coffee", invoice.description)
        assertEquals(60L, invoice.expirySeconds)
    }

    private companion object {
        const val SPEC_PAYMENT_HASH =
            "0001020304050607080900010203040506070809000102030405060708090102"

        const val SPEC_VECTOR_NO_AMOUNT =
            "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq" +
                "dpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkx" +
                "aq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86" +
                "fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"

        const val SPEC_VECTOR_2500U =
            "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzq" +
                "fqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hd" +
                "q27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24" +
                "cg27h2rspfj9srp"
    }
}
