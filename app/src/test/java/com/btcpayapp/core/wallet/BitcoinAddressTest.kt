package com.btcpayapp.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The send review requires an output that pays the script of the typed
 * address, so a wrong script would refuse a good payment or pass a swapped
 * one. Vectors from BIP173 and BIP350, and two well-known base58 addresses.
 */
class BitcoinAddressTest {

    @Test
    fun `segwit addresses give their witness scripts`() {
        mapOf(
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4" to "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7" to
                "00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262",
            "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080" to "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            "tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy" to
                "0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
            // bech32m, BIP350.
            "bc1pw508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kt5nd6y" to
                "5128751e76e8199196d454941c45d1b3a323f1433bd6751e76e8199196d454941c45d1b3a323f1433bd6",
            "BC1SW50QGDZ25J" to "6002751e",
            "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs" to "5210751e76e8199196d454941c45d1b3a323",
            "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c" to
                "5120000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0" to
                "512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
        ).forEach { (address, script) -> assertEquals(address, script, scriptPubKeyOf(address)) }
    }

    @Test
    fun `base58 addresses give their P2PKH and P2SH scripts`() {
        assertEquals("76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac", scriptPubKeyOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertEquals("a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87", scriptPubKeyOf("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"))
        assertEquals("76a914243f1394f44554f4ce3fd68649c19adc483ce92488ac", scriptPubKeyOf("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"))
        assertEquals("a9144e9f39ca4688ff102128ea4ccda34105324305b087", scriptPubKeyOf("2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc"))
    }

    @Test
    fun `anything else gives null`() {
        listOf(
            // BIP350 invalid addresses, each for the reason given.
            "tc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq5zuyut", // unknown hrp
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd", // v1 with a bech32 checksum
            "tb1z0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqglt7rf", // v2 with a bech32 checksum
            "BC1S0XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ54WELL", // v16 with a bech32 checksum
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh", // v0 with a bech32m checksum
            "tb1q0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq24jc47", // v0 with a bech32m checksum
            "bc1p38j9r5y49hruaue7wxjce0updqjuyyx0kh56v8s25huc6995vvpql3jow4", // 'o' is not in the charset
            "BC130XLXVLHEMJA6C4DQV22UAPCTQUPFHLXM9H8Z3K2E72Q4K9HCZ7VQ7ZWS8R", // version 17
            "bc1pw5dgrnzv", // a 1-byte program
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v8n0nx0muaewav253zgeav", // 41 bytes
            "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P", // v0 with a 16-byte program
            "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vq47Zagq", // mixed case
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7v07qwwzcrf", // more than 4 bits of padding
            "tb1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vpggkg4j", // padding that is not zero
            "bc1gmk9yu", // no data
            // Base58: a changed last character breaks the checksum.
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb",
            // A BIP21 link is not an address.
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.001",
            "",
            "0",
            "1",
            "bc1",
            "1".repeat(35),
            "x".repeat(200),
        ).forEach { assertNull(it, scriptPubKeyOf(it)) }
    }
}
