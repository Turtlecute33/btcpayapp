package com.btcpayapp.core.lightning

/**
 * Builds syntactically valid BOLT11 invoices for [Bolt11Test].
 *
 * The signature is 104 zero symbols. Nothing in the app verifies it — the node
 * does that when it is asked to pay — so a real one would only make these
 * fixtures harder to read without testing anything extra.
 *
 * This encoder shares the bech32 polymod with the decoder by construction, so
 * on its own it could not catch a wrong generator constant. That is what the
 * two published spec vectors in [Bolt11Test] are for.
 */
object Bolt11Fixture {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private const val DEFAULT_PAYMENT_HASH =
        "0001020304050607080900010203040506070809000102030405060708090102"

    fun invoice(
        hrp: String = "lnbc2500u",
        timestamp: Long = 1_496_314_658L,
        paymentHash: String? = DEFAULT_PAYMENT_HASH,
        description: String? = "Test invoice",
        descriptionHash: String? = null,
        payee: String? = null,
        expirySeconds: Long? = null,
        includeUnknownFields: Boolean = false,
    ): String {
        val data = mutableListOf<Int>()
        data += number(timestamp, width = 7)

        if (includeUnknownFields) {
            // 's' — payment secret, 52 symbols of it.
            data += field('s', bytesToFive(ByteArray(32) { 0x2a }))
            // '9' — feature bits, a short odd-length field.
            data += field('9', listOf(1, 0, 0, 0, 2))
        }

        paymentHash?.let { data += field('p', bytesToFive(hexToBytes(it))) }
        descriptionHash?.let { data += field('h', bytesToFive(hexToBytes(it))) }
        description?.let { data += field('d', bytesToFive(it.toByteArray(Charsets.UTF_8))) }
        payee?.let { data += field('n', bytesToFive(hexToBytes(it))) }
        expirySeconds?.let { data += field('x', minimalNumber(it)) }

        data += List(104) { 0 }

        return hrp + "1" + (data + checksum(hrp, data)).map(CHARSET::get).joinToString("")
    }

    /** `type` + two symbols of length + payload. */
    private fun field(type: Char, payload: List<Int>): List<Int> {
        require(payload.size < 1024) { "field too long" }
        return listOf(CHARSET.indexOf(type), payload.size / 32, payload.size % 32) + payload
    }

    /** Big-endian base32, fixed width. */
    private fun number(value: Long, width: Int): List<Int> =
        (width - 1 downTo 0).map { position -> ((value shr (position * 5)) and 31).toInt() }

    /** Big-endian base32, no leading zero symbols. */
    private fun minimalNumber(value: Long): List<Int> {
        if (value == 0L) return listOf(0)
        val digits = mutableListOf<Int>()
        var remaining = value
        while (remaining > 0) {
            digits.add(0, (remaining and 31).toInt())
            remaining = remaining shr 5
        }
        return digits
    }

    /** 8-bit bytes to 5-bit symbols, zero-padded up to a symbol boundary. */
    private fun bytesToFive(bytes: ByteArray): List<Int> {
        var accumulator = 0
        var bits = 0
        val out = mutableListOf<Int>()
        for (byte in bytes) {
            accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out += (accumulator shr bits) and 31
            }
        }
        if (bits > 0) out += (accumulator shl (5 - bits)) and 31
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun checksum(hrp: String, data: List<Int>): List<Int> {
        val values = expandHrp(hrp) + data + List(6) { 0 }
        val polymod = polymod(values) xor 1
        return (0..5).map { (polymod shr (5 * (5 - it))) and 31 }
    }

    private fun expandHrp(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

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
