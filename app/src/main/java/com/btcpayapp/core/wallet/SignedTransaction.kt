package com.btcpayapp.core.wallet

import com.btcpayapp.core.util.toHex
import java.security.MessageDigest

/**
 * A signed Bitcoin transaction as the server returned it, read on the device
 * so the review screen can show what will really go out: the coins it spends,
 * what it pays, and so the absolute fee, not just a rate.
 *
 * Display and lookup only. Nothing here signs, checks a signature or rebuilds
 * the transaction: the server's [hex] is broadcast unchanged, and [txid] is
 * what the app looks for afterwards to tell "sent" from "not sent".
 *
 * The hex comes off the network, so [parse] is strict and total: any shape it
 * does not expect gives null, never an exception.
 */
data class SignedTransaction(
    /** The server's hex, exactly as received. */
    val hex: String,
    /** Display order (byte-reversed), as explorers and Greenfield write it. */
    val txid: String,
    /**
     * Spent coins as `txid:vout`. Greenfield writes `WalletUtxoData.outpoint`
     * as `txid-vout`; the send screen converts it (coinValues).
     */
    val inputs: List<String>,
    /** Output values in satoshi, in transaction order. */
    val outputSats: List<Long>,
    /**
     * Output scripts (scriptPubKey) as lowercase hex, in the order of
     * [outputSats], so the review can check what each value pays.
     */
    val outputScripts: List<String>,
    /** Virtual size in vbytes, the size fee rates are quoted against. */
    val vsize: Int,
) {

    /**
     * Inputs minus outputs, given the value of each spent coin. Null when a
     * coin is missing from [inputSats] (a fee that cannot be known is not
     * guessed), when a value is outside what Bitcoin allows, or when the
     * outputs are more than the inputs.
     */
    fun feeSats(inputSats: Map<String, Long>): Long? {
        var total = 0L
        for (input in inputs) {
            total += inputSats[input]?.takeIf { it in 0L..MAX_MONEY } ?: return null
            if (total > MAX_MONEY) return null
        }
        return (total - outputSats.sum()).takeIf { it >= 0 }
    }

    companion object {

        /**
         * Legacy or BIP144 segwit serialisation, which must end exactly after
         * the lock time.
         *
         * The txid hashes the stripped form (version, inputs, outputs, lock
         * time), without the marker, flag and witness, which is what makes it
         * stable under witness changes. vsize is the weight over four, rounded
         * up, with weight = stripped size x 3 + full size.
         */
        fun parse(hex: String): SignedTransaction? {
            if (hex.isEmpty() || hex.length % 2 != 0 || hex.length > MAX_HEX_LENGTH) return null
            val bytes = decodeHex(hex) ?: return null
            val reader = Reader(bytes)

            if (!reader.skip(4)) return null // version
            // A zero where the input count belongs, then flag 01, marks the
            // witness form. A real transaction never has zero inputs.
            val segwit = reader.peek(0) == 0 && reader.peek(1) == 1
            if (segwit) reader.skip(2)
            val bodyStart = reader.position

            val inputCount = reader.count(min = 1) ?: return null
            val inputs = ArrayList<String>(inputCount)
            repeat(inputCount) {
                val previous = reader.take(32) ?: return null
                val vout = reader.uintLE(4) ?: return null
                if (!reader.skipPrefixed()) return null // script
                if (!reader.skip(4)) return null // sequence
                inputs += "${previous.reversedArray().toHex()}:$vout"
            }

            val outputCount = reader.count(min = 1) ?: return null
            val outputs = ArrayList<Long>(outputCount)
            val scripts = ArrayList<String>(outputCount)
            var total = 0L
            repeat(outputCount) {
                val value = reader.uintLE(8)?.takeIf { it in 0L..MAX_MONEY } ?: return null
                total += value
                if (total > MAX_MONEY) return null
                scripts += (reader.takePrefixed() ?: return null).toHex()
                outputs += value
            }
            val bodyEnd = reader.position

            if (segwit) repeat(inputCount) {
                val items = reader.count(min = 0) ?: return null
                repeat(items) { if (!reader.skipPrefixed()) return null }
            }
            val witnessEnd = reader.position

            if (!reader.skip(4) || !reader.atEnd) return null // lock time, then nothing

            val stripped = bytes.copyOfRange(0, 4) +
                bytes.copyOfRange(bodyStart, bodyEnd) +
                bytes.copyOfRange(witnessEnd, bytes.size)
            val weight = stripped.size * 3 + bytes.size
            return SignedTransaction(
                hex = hex,
                txid = sha256d(stripped).reversedArray().toHex(),
                inputs = inputs,
                outputSats = outputs,
                outputScripts = scripts,
                vsize = (weight + 3) / 4,
            )
        }
    }
}

/** 400 kB, the standard size limit, witness included. */
private const val MAX_HEX_LENGTH = 800_000

/** Far above any standard transaction, low enough to bound the loops. */
private const val MAX_COUNT = 10_000

/** 21 million BTC in satoshi: no value, and no sum of values, can be larger. */
private const val MAX_MONEY = 2_100_000_000_000_000L

/** Forward-only reads over the raw bytes; every read fails soft at the end. */
private class Reader(private val bytes: ByteArray) {

    var position = 0
        private set

    val atEnd: Boolean get() = position == bytes.size

    private val remaining: Int get() = bytes.size - position

    fun peek(offset: Int): Int? = bytes.getOrNull(position + offset)?.toInt()?.and(0xff)

    /** Moves past [count] bytes; false when fewer are left. */
    fun skip(count: Long): Boolean {
        if (count < 0 || count > remaining) return false
        position += count.toInt()
        return true
    }

    fun take(count: Int): ByteArray? {
        if (count > remaining) return null
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    /** A little-endian unsigned integer of [size] bytes, at most 8. */
    fun uintLE(size: Int): Long? {
        if (size > remaining) return null
        var value = 0L
        for (i in size - 1 downTo 0) value = (value shl 8) or (bytes[position + i].toLong() and 0xff)
        position += size
        return value
    }

    /** Bitcoin's CompactSize, refusing the non-minimal forms consensus refuses. */
    fun varint(): Long? = when (val first = uintLE(1) ?: return null) {
        0xfdL -> uintLE(2)?.takeIf { it >= 0xfd }
        0xfeL -> uintLE(4)?.takeIf { it > 0xffff }
        0xffL -> uintLE(8)?.takeIf { it > 0xffff_ffffL }
        else -> first
    }

    fun count(min: Int): Int? = varint()?.takeIf { it in min.toLong()..MAX_COUNT.toLong() }?.toInt()

    /** Moves past a CompactSize length and that many bytes. */
    fun skipPrefixed(): Boolean = skip(varint() ?: return false)

    /** A CompactSize length and that many bytes, or null when fewer are left. */
    fun takePrefixed(): ByteArray? = take(varint()?.takeIf { it in 0..remaining }?.toInt() ?: return null)
}

private fun decodeHex(hex: String): ByteArray? {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val high = nibble(hex[i * 2])
        val low = nibble(hex[i * 2 + 1])
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

/** ASCII hex only: `Character.digit` would also accept Arabic-Indic digits. */
private fun nibble(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> -1
}

internal fun sha256d(bytes: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(digest.digest(bytes))
}
