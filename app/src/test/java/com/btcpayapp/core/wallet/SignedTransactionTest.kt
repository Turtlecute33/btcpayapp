package com.btcpayapp.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The review screen shows the fee this parser computes, and the app looks up
 * the txid after a broadcast to tell "sent" from "not sent". A wrong txid
 * would call a sent payment unsent; a wrong input or output list would show
 * the wrong fee. The hex comes off the network, so anything malformed must
 * give null rather than throw.
 */
class SignedTransactionTest {

    @Test
    fun `the genesis coinbase gives its well known txid`() {
        val tx = SignedTransaction.parse(GENESIS)
        assertNotNull(tx)
        tx!!
        assertEquals("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b", tx.txid)
        assertEquals(listOf("0".repeat(64) + ":4294967295"), tx.inputs)
        assertEquals(listOf(5_000_000_000L), tx.outputSats)
        // The P2PK script of the genesis output, as every explorer shows it.
        assertEquals(
            listOf(
                "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c" +
                    "384df7ba0b8d578a4c702b6bf11d5fac",
            ),
            tx.outputScripts,
        )
        // Legacy: no witness discount, so vsize is the byte count.
        assertEquals(204, tx.vsize)
        assertEquals(GENESIS, tx.hex)
    }

    @Test
    fun `a segwit transaction hashes without its witness`() {
        val tx = SignedTransaction.parse(segwit(withWitness = true).toHex())
        assertNotNull(tx)
        tx!!

        // The txid is displayed byte-reversed, and so is the spent txid.
        val stripped = segwit(withWitness = false)
        assertEquals(sha256d(stripped).reversedArray().toHex(), tx.txid)
        assertEquals(listOf("${PREVIOUS.reversedArray().toHex()}:1"), tx.inputs)
        assertEquals(listOf(50_000L, 1_234_567L), tx.outputSats)
        assertEquals(listOf("0014" + "00".repeat(20), "0020" + "ab".repeat(32)), tx.outputScripts)
        // 125 stripped bytes, 234 in full: weight 125 x 3 + 234 = 609, and
        // 609 / 4 rounded up is 153.
        assertEquals(125, stripped.size)
        assertEquals(153, tx.vsize)
    }

    @Test
    fun `the fee is inputs minus outputs`() {
        val tx = SignedTransaction.parse(segwit(withWitness = true).toHex())!!
        val outpoint = tx.inputs.single()
        assertEquals(10_000L, tx.feeSats(mapOf(outpoint to 1_294_567L)))
    }

    @Test
    fun `a fee is not guessed when a spent coin is unknown`() {
        val tx = SignedTransaction.parse(segwit(withWitness = true).toHex())!!
        assertNull(tx.feeSats(emptyMap()))
        assertNull(tx.feeSats(mapOf("${"ab".repeat(32)}:0" to 5_000_000L)))
    }

    @Test
    fun `outputs larger than inputs give no fee`() {
        val tx = SignedTransaction.parse(segwit(withWitness = true).toHex())!!
        assertNull(tx.feeSats(mapOf(tx.inputs.single() to 1_000L)))
    }

    @Test
    fun `truncated or padded hex is refused`() {
        val hex = segwit(withWitness = true).toHex()
        assertNull(SignedTransaction.parse(hex.dropLast(2)))
        assertNull(SignedTransaction.parse(hex + "00"))
        assertNull(SignedTransaction.parse(GENESIS.dropLast(2)))
        assertNull(SignedTransaction.parse(GENESIS + "00"))
    }

    @Test
    fun `anything that is not transaction hex gives null`() {
        listOf(
            "",
            "0",
            "zz",
            "0100000000",
            GENESIS.replaceFirst('0', 'g'),
            // Arabic-Indic digits pass `Character.digit` but are not hex.
            "٠١" + GENESIS.drop(2),
            "00".repeat(400_001),
        ).forEach { assertNull(it.take(40), SignedTransaction.parse(it)) }
    }

    /**
     * Version 2, one input spending [PREVIOUS]:1 with an empty script, two
     * outputs (P2WPKH and P2WSH), a two-item witness, lock time 0.
     */
    private fun segwit(withWitness: Boolean): ByteArray = ByteArrayOutputStream().apply {
        write(le(2, 4))
        if (withWitness) write(byteArrayOf(0x00, 0x01))
        write(byteArrayOf(1))
        write(PREVIOUS)
        write(le(1, 4))
        write(byteArrayOf(0)) // empty script
        write(byteArrayOf(0xfd.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        write(byteArrayOf(2))
        write(le(50_000, 8))
        write(byteArrayOf(22, 0x00, 0x14))
        write(ByteArray(20))
        write(le(1_234_567, 8))
        write(byteArrayOf(34, 0x00, 0x20))
        write(ByteArray(32) { 0xab.toByte() })
        if (withWitness) {
            write(byteArrayOf(2))
            write(byteArrayOf(71))
            write(ByteArray(71) { 0x30 })
            write(byteArrayOf(33))
            write(ByteArray(33) { 0x02 })
        }
        write(le(0, 4))
    }.toByteArray()

    private fun le(value: Long, size: Int): ByteArray =
        ByteArray(size) { index -> (value shr (8 * index)).toByte() }

    private fun sha256d(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(bytes))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val PREVIOUS = ByteArray(32) { it.toByte() }

        const val GENESIS =
            "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04" +
                "ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e" +
                "206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff0100f205" +
                "2a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6" +
                "bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000"
    }
}
