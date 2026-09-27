package com.btcpayapp.ui.components

import com.btcpayapp.core.net.CertificateProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one check that stands between a typed fingerprint and a pinned key.
 *
 * Both directions matter. Too strict, and an operator who pasted the openssl
 * output with its colons or in capitals cannot connect to their own box. Too
 * loose, and a short or wrong fingerprint pins an interceptor's key for good.
 */
class FingerprintMatchTest {

    // Letters as well as digits, so the case tests test something.
    private val spkiHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val certHex = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val pin = java.util.Base64.getEncoder().encodeToString(bytes(spkiHex))

    private val probe = CertificateProbe(
        pin = pin,
        subject = "CN=btcpay.lan",
        notBefore = 0L,
        notAfter = Long.MAX_VALUE,
        subjectAlternativeNames = listOf("btcpay.lan"),
        certificateSha256Hex = certHex,
    )

    private fun matches(typed: String, against: CertificateProbe = probe) = fingerprintMatches(typed, against)

    @Test
    fun `the first 32 hex characters of the key hash match`() {
        assertTrue(matches(spkiHex.take(32)))
    }

    @Test
    fun `colons and spaces are ignored`() {
        // How `openssl x509 -fingerprint` prints it, and how people copy it.
        assertTrue(matches(spkiHex.take(32).uppercase().chunked(2).joinToString(":")))
        assertTrue(matches(spkiHex.take(32).chunked(4).joinToString(" ")))
    }

    @Test
    fun `either case matches`() {
        assertTrue(matches(spkiHex.take(32).uppercase()))
        assertTrue(matches(spkiHex.take(32).mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c }.joinToString("")))
    }

    @Test
    fun `the full hex matches`() {
        assertTrue(matches(spkiHex))
        assertTrue(matches(spkiHex.uppercase().chunked(2).joinToString(":")))
    }

    @Test
    fun `the base64 pin pasted whole matches`() {
        assertTrue(matches(pin))
        assertTrue(matches("  $pin\n"))
    }

    @Test
    fun `a label in front of the value is ignored`() {
        assertTrue(matches("SHA256 Fingerprint=${certHex.take(32).uppercase()}"))
        assertTrue(matches("SHA2-256(stdin)= ${spkiHex.take(32)}"))
    }

    @Test
    fun `the whole line the dialog's own command prints matches`() {
        // `openssl dgst -sha256 -r` puts the hash first and " *stdin" after it.
        assertTrue(matches("$spkiHex *stdin"))
        assertTrue(matches("${certHex.uppercase().chunked(2).joinToString(":")}\n"))
    }

    @Test
    fun `the start of the certificate hash matches`() {
        assertTrue(matches(certHex.take(32)))
    }

    @Test
    fun `31 characters are not enough`() {
        // 64 bits used to pass; an attacker can grind a certificate hash that long.
        assertFalse(matches(spkiHex.take(16)))
        assertFalse(matches(spkiHex.take(31)))
        assertFalse(matches(certHex.take(31)))
        assertFalse(matches("${spkiHex.take(31)} *stdin"))
    }

    @Test
    fun `one wrong character refuses`() {
        val wrong = spkiHex.take(31) + "1"
        assertFalse(spkiHex.startsWith(wrong))
        assertFalse(matches(wrong))
        assertFalse(matches(spkiHex.dropLast(1) + "1"))
        assertFalse(matches("${spkiHex.dropLast(1)}1 *stdin"))
    }

    @Test
    fun `a match must be at the start`() {
        assertFalse(matches(spkiHex.drop(2).take(32)))
    }

    @Test
    fun `empty and non-hex input refuse`() {
        assertFalse(matches(""))
        assertFalse(matches("   "))
        assertFalse(matches("ghijklmnopqrstuv"))
    }

    @Test
    fun `a probe without a certificate hash still refuses a wrong value`() {
        val keyOnly = probe.copy(certificateSha256Hex = "")
        assertTrue(matches(spkiHex.take(32), keyOnly))
        assertFalse(matches(certHex.take(32), keyOnly))
    }

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
