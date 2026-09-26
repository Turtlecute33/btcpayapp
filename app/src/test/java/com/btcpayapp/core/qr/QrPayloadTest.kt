package com.btcpayapp.core.qr

import org.junit.Assert.assertEquals
import java.util.Locale
import org.junit.After
import org.junit.Test

/**
 * `optimiseCase` uppercases a payload so the QR encoder can use its compact
 * alphanumeric mode. That is only safe when the payload is genuinely
 * case-insensitive, and not every `bitcoin:` URI is.
 */
class QrPayloadTest {

    private val original = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun `legacy base58 addresses are left alone`() {
        // Base58 is case-SENSITIVE. Uppercasing this produces an address the
        // customer's wallet rejects, or silently reads as something else.
        val p2pkh = "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        assertEquals(p2pkh, QrEncoder.optimiseCase(p2pkh))

        val p2sh = "bitcoin:3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"
        assertEquals(p2sh, QrEncoder.optimiseCase(p2sh))
    }

    @Test
    fun `bech32 addresses are uppercased`() {
        assertEquals(
            "BITCOIN:BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4",
            QrEncoder.optimiseCase("bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"),
        )
    }

    @Test
    fun `testnet and regtest bech32 prefixes are recognised`() {
        assertEquals(
            "BITCOIN:TB1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KXPJZSX",
            QrEncoder.optimiseCase("bitcoin:tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"),
        )
        assertEquals(
            "BITCOIN:BCRT1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KYGT080",
            QrEncoder.optimiseCase("bitcoin:bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"),
        )
    }

    @Test
    fun `bolt11 invoices are uppercased`() {
        assertEquals("LNBC1PVJLUEZPP5", QrEncoder.optimiseCase("lnbc1pvjluezpp5"))
    }

    @Test
    fun `payloads with a query string are left alone`() {
        val withAmount = "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4?amount=0.01"
        assertEquals(withAmount, QrEncoder.optimiseCase(withAmount))
    }

    @Test
    fun `the scheme survives a Turkish locale`() {
        // In tr/az, "i".uppercase() is "İ" (U+0130), so a default-locale
        // uppercase turns "bitcoin:" into "BİTCOİN:" — a scheme no wallet
        // parses. The conversion must use Locale.ROOT.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        val result = QrEncoder.optimiseCase("bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertEquals("BITCOIN:BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", result)
    }

    @Test
    fun `lightning payloads survive a Turkish locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertEquals("LNBC1PVJLUEZPP5", QrEncoder.optimiseCase("lnbc1pvjluezpp5"))
    }
}
