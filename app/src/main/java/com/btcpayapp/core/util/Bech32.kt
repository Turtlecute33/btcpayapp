package com.btcpayapp.core.util

/**
 * The bech32 checksum and bit regrouping (BIP173, and the bech32m constant of
 * BIP350), shared by the BOLT11 and the Bitcoin address decoders so that
 * neither keeps its own copy. Total: bad input gives null or a residue that
 * matches no constant, never an exception.
 */
internal object Bech32 {

    const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** The [residue] of a valid bech32 string. */
    const val BECH32 = 1

    /** The [residue] of a valid bech32m string. */
    const val BECH32M = 0x2bc830a3

    /** The checksum residue of a lowercase [hrp] and its 5-bit [data], checksum included. */
    fun residue(hrp: String, data: List<Int>): Int = polymod(expandHrp(hrp) + data)

    /**
     * 5-bit groups to 8-bit bytes, discarding the trailing padding.
     *
     * Rejects a pad of 5 bits or more, or one that is not zero, which is how
     * a truncated or hand-edited value shows up.
     */
    fun convertBits(values: List<Int>): ByteArray? {
        var accumulator = 0
        var bits = 0
        val out = ArrayList<Byte>(values.size * 5 / 8 + 1)
        for (value in values) {
            if (value < 0 || value > 31) return null
            accumulator = (accumulator shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((accumulator shr bits) and 0xff).toByte())
            }
        }
        if (bits >= 5) return null
        if ((accumulator shl (8 - bits)) and 0xff != 0) return null
        return out.toByteArray()
    }

    private fun expandHrp(hrp: String): List<Int> {
        val high = hrp.map { it.code shr 5 }
        val low = hrp.map { it.code and 31 }
        return high + listOf(0) + low
    }

    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum shr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (bit in 0..4) {
                if ((top shr bit) and 1 == 1) checksum = checksum xor GENERATOR[bit]
            }
        }
        return checksum
    }
}
