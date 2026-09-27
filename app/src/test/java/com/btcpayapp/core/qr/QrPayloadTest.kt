package com.btcpayapp.core.qr

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals("LIGHTNING:LNBC1PVJLUEZPP5", QrEncoder.optimiseCase("lightning:lnbc1pvjluezpp5"))
    }

    @Test
    fun `lightning scheme payloads are uppercased too`() {
        // Checkout links and Lightning receive both show `lightning:lnbc…`,
        // the longest payloads in the app.
        assertEquals("LIGHTNING:LNBC1PVJLUEZPP5", QrEncoder.optimiseCase("lightning:lnbc1pvjluezpp5"))
        assertEquals("LIGHTNING:LNTBS1PVJLUEZPP5", QrEncoder.optimiseCase("lightning:lntbs1pvjluezpp5"))
        assertEquals("LIGHTNING:LNURL1DP68GURN8GHJ7", QrEncoder.optimiseCase("lightning:lnurl1dp68gurn8ghj7"))
    }

    @Test
    fun `case-sensitive lightning payloads are left alone`() {
        // A LUD-17 URL has a path and query; a Lightning address has a user.
        val url = "lightning:lnurlp://pay.example.com/.well-known/lnurlp/Alice"
        assertEquals(url, QrEncoder.optimiseCase(url))
        val address = "lightning:Alice@pay.example.com"
        assertEquals(address, QrEncoder.optimiseCase(address))
    }

    @Test
    fun `ascii payloads are encoded without an eci header`() {
        // With a character-set hint zxing prefixes every byte-mode code with a
        // UTF-8 ECI segment; without one it adds none.
        val payload = QrEncoder.optimiseCase("lightning:lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq")
        val hints = QrEncoder.hints(payload, 2)
        assertFalse(hints.containsKey(EncodeHintType.CHARACTER_SET))
        assertNotNull(QrEncoder.matrix(payload))
        assertEquals(Mode.ALPHANUMERIC, Encoder.encode(payload, ErrorCorrectionLevel.M, hints).mode)

        val link = "https://pay.example.com/apps/abc/pos"
        assertFalse(QrEncoder.hints(link, 2).containsKey(EncodeHintType.CHARACTER_SET))
        assertNotNull(QrEncoder.matrix(link))
    }

    @Test
    fun `text that is not ascii keeps its utf-8 bytes`() {
        // zxing's default byte encoding is ISO-8859-1, which would print '?'.
        assertEquals("UTF-8", QrEncoder.hints("Caffè ☕", 2)[EncodeHintType.CHARACTER_SET])
        assertNotNull(QrEncoder.matrix("Caffè ☕"))
    }

    @Test
    fun `an empty payload has no code`() {
        assertNull(QrEncoder.matrix(""))
    }
}
