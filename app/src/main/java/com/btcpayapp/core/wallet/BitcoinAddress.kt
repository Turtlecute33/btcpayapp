package com.btcpayapp.core.wallet

import com.btcpayapp.core.util.Bech32
import com.btcpayapp.core.util.toHex
import java.math.BigInteger
import java.util.Locale

/**
 * The output script (scriptPubKey) that [address] pays, as lowercase hex, or
 * null when it is not a Bitcoin address this app can read.
 *
 * Read: segwit v0 in bech32 (BIP173) and v1 to v16 in bech32m (BIP350), with
 * the hrp bc, tb or bcrt, and base58check P2PKH and P2SH for mainnet (versions
 * 0x00 and 0x05) and testnet (0x6f and 0xc4). The send review uses it to
 * require an output that pays this exact script, so the address on the review
 * is the one the signed bytes pay. The address is typed or scanned, so this is
 * total: anything else gives null, never an exception.
 */
internal fun scriptPubKeyOf(address: String): String? = segwitScript(address) ?: base58Script(address)

private val SEGWIT_HRPS = setOf("bc", "tb", "bcrt")

private fun segwitScript(address: String): String? {
    if (address.length > 90) return null
    val value = address.lowercase(Locale.ROOT)
    // One case only: BIP173 refuses a mixed-case string.
    if (address != value && address != address.uppercase(Locale.ROOT)) return null
    val separator = value.lastIndexOf('1')
    if (separator < 1) return null
    val hrp = value.substring(0, separator)
    if (hrp !in SEGWIT_HRPS) return null
    val data = value.substring(separator + 1).map { character ->
        Bech32.CHARSET.indexOf(character).takeIf { it >= 0 } ?: return null
    }
    // The version, then the checksum's six characters.
    if (data.size < 7) return null
    val version = data[0]
    if (version > 16) return null
    // v0 uses bech32 and every later version bech32m (BIP350).
    if (Bech32.residue(hrp, data) != if (version == 0) Bech32.BECH32 else Bech32.BECH32M) return null
    val program = Bech32.convertBits(data.subList(1, data.size - 6)) ?: return null
    if (program.size !in 2..40) return null
    if (version == 0 && program.size != 20 && program.size != 32) return null
    // OP_0, or OP_1 to OP_16, then a push of the program.
    val opcode = if (version == 0) 0 else 0x50 + version
    return byteArrayOf(opcode.toByte(), program.size.toByte()).toHex() + program.toHex()
}

private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

private val FIFTY_EIGHT = BigInteger.valueOf(58)

private fun base58Script(address: String): String? {
    // A version byte, a 20-byte hash and a 4-byte checksum: 25 bytes, which
    // are at most 35 characters.
    if (address.isEmpty() || address.length > 35) return null
    var number = BigInteger.ZERO
    for (character in address) {
        val digit = BASE58.indexOf(character).takeIf { it >= 0 } ?: return null
        number = number.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit.toLong()))
    }
    // Each leading '1' is a leading zero byte, which the number cannot hold.
    val zeros = address.takeWhile { it == '1' }.length
    val bytes = ByteArray(zeros) + number.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    if (bytes.size != 25) return null
    val payload = bytes.copyOfRange(0, 21)
    if (!sha256d(payload).copyOfRange(0, 4).contentEquals(bytes.copyOfRange(21, 25))) return null
    val hash = payload.copyOfRange(1, 21).toHex()
    return when (payload[0].toInt() and 0xff) {
        0x00, 0x6f -> "76a914${hash}88ac" // OP_DUP OP_HASH160 <hash> OP_EQUALVERIFY OP_CHECKSIG
        0x05, 0xc4 -> "a914${hash}87" // OP_HASH160 <hash> OP_EQUAL
        else -> null
    }
}
