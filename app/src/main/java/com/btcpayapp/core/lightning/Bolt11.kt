package com.btcpayapp.core.lightning

import com.btcpayapp.core.util.Bech32
import com.btcpayapp.core.util.toHex
import java.math.BigInteger
import java.util.Locale

/**
 * What a BOLT11 invoice says, read on the device before anything is paid.
 *
 * [payeeNode] is only present when the writer included the optional `n` field.
 * Most implementations leave it out because it is recoverable from the
 * signature — and recovering it means a secp256k1 point recovery, which this
 * app will not hand-roll. Getting that subtly wrong would put the *wrong*
 * counterparty name in front of someone about to spend money, which is worse
 * than leaving the field blank. When it is absent, [description] is what the
 * payer actually recognises anyway.
 */
data class Bolt11Invoice(
    /** `bc`, `tb`, `tbs`, `bcrt` — the chain the invoice is for. */
    val network: String,
    /** Null for a zero-amount ("any amount") invoice. */
    val amountMsat: BigInteger?,
    /** Unix seconds. */
    val timestamp: Long,
    val paymentHash: String?,
    val description: String?,
    /** Set instead of [description] when the writer used a description hash. */
    val descriptionHash: String?,
    val payeeNode: String?,
    /**
     * Seconds after [timestamp], as the invoice states it (capped at a
     * century). BOLT11 says 3600 when the field is absent.
     */
    val expirySeconds: Long,
) {
    val expiresAt: Long get() = timestamp + expirySeconds

    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        nowSeconds >= expiresAt

    /** True for an invoice that lets the payer choose the amount. */
    val isAmountless: Boolean get() = amountMsat == null
}

/**
 * A read-only BOLT11 decoder: bech32 plus the tagged fields this app displays.
 *
 * Decoding happens entirely on the device. The alternative — posting the
 * invoice to the server to be told what is in it — would leak the payee and
 * amount before the operator has decided whether to pay, and would leave the
 * screen blank whenever the node is unreachable.
 *
 * The signature is *not* verified. Nothing here authorises a payment: the node
 * validates the invoice when it is asked to pay it, and this output only ever
 * reaches a preview card. Every failure returns null; a scanned QR is untrusted
 * input and must not be able to throw out of a parser.
 */
object Bolt11 {

    /** Base32 values of the tagged-field types that carry something to show. */
    private const val TAG_PAYMENT_HASH = 1 // 'p'
    private const val TAG_EXPIRY = 6 // 'x'
    private const val TAG_DESCRIPTION = 13 // 'd'
    private const val TAG_PAYEE = 19 // 'n'
    private const val TAG_DESCRIPTION_HASH = 23 // 'h'

    /** 520 signature bits over 5 bits per character. */
    private const val SIGNATURE_LENGTH = 104

    /** 35 bits of unix seconds. */
    private const val TIMESTAMP_LENGTH = 7

    private const val DEFAULT_EXPIRY_SECONDS = 3600L

    /**
     * The only limit on a stated expiry. A long one is legitimate (a donation
     * or subscription invoice), and replacing it with the one-hour default
     * showed a valid invoice as expired and blocked Pay. The node refuses a
     * truly expired invoice anyway. A century keeps [Bolt11Invoice.expiresAt]
     * far from overflow and inside the years a date can be printed for.
     */
    private const val MAX_EXPIRY_SECONDS = 100L * 365 * 86_400

    /** No BOLT11 invoice is anywhere near this long; a QR payload might be. */
    private const val MAX_LENGTH = 7089

    private val MSAT_PER_BTC: BigInteger = BigInteger.TEN.pow(11)

    fun decode(input: String): Bolt11Invoice? {
        val raw = input.trim()
            .removePrefix("lightning:")
            .removePrefix("LIGHTNING:")
            .trim()
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return null

        val value = raw.lowercase(Locale.ROOT)
        if (!value.startsWith("ln")) return null

        val separator = value.lastIndexOf('1')
        if (separator < 3 || separator + 7 > value.length) return null

        val hrp = value.substring(0, separator)
        val data = value.substring(separator + 1).map { character ->
            Bech32.CHARSET.indexOf(character).takeIf { it >= 0 } ?: return null
        }

        if (!checksumValid(hrp, data)) return null

        // Drop the 6 checksum characters; what remains is timestamp, tagged
        // fields and signature.
        val payload = data.subList(0, data.size - 6)
        if (payload.size < TIMESTAMP_LENGTH + SIGNATURE_LENGTH) return null

        val (network, amountMsat) = parseHrp(hrp) ?: return null

        val timestamp = readNumber(payload.subList(0, TIMESTAMP_LENGTH)) ?: return null
        val fields = payload.subList(TIMESTAMP_LENGTH, payload.size - SIGNATURE_LENGTH)

        var paymentHash: String? = null
        var description: String? = null
        var descriptionHash: String? = null
        var payee: String? = null
        var expiry: Long? = null

        var index = 0
        while (index + 3 <= fields.size) {
            val type = fields[index]
            val length = fields[index + 1] * 32 + fields[index + 2]
            val start = index + 3
            val end = start + length
            // A length that runs past the signature means the invoice is
            // malformed. Stop rather than read into the signature bytes.
            if (end > fields.size) return null
            val field = fields.subList(start, end)

            when (type) {
                TAG_PAYMENT_HASH -> if (length == 52 && paymentHash == null) {
                    paymentHash = (Bech32.convertBits(field) ?: return null).toHex()
                }

                TAG_DESCRIPTION -> if (description == null) {
                    val bytes = Bech32.convertBits(field) ?: return null
                    description = String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
                }

                TAG_DESCRIPTION_HASH -> if (length == 52 && descriptionHash == null) {
                    descriptionHash = (Bech32.convertBits(field) ?: return null).toHex()
                }

                TAG_PAYEE -> if (length == 53 && payee == null) {
                    payee = (Bech32.convertBits(field) ?: return null).toHex()
                }

                TAG_EXPIRY -> if (expiry == null) {
                    expiry = readNumber(field)
                }

                // Payment secret, feature bits, routing hints, fallback
                // addresses and anything a later BOLT adds: skipped by length,
                // which is exactly what the spec asks an unknown-field reader
                // to do.
                else -> Unit
            }
            index = end
        }

        // The payment hash is the one field BOLT11 makes mandatory, and
        // requiring it is also what keeps an LNURL out of here: `lnurl1…` is
        // bech32 too, so it passes the checksum and its hrp starts with "ln",
        // but it carries no tagged fields at all. Without this check a long
        // enough LNURL would decode into a confident-looking invoice card.
        if (paymentHash == null) return null

        return Bolt11Invoice(
            network = network,
            amountMsat = amountMsat,
            timestamp = timestamp,
            paymentHash = paymentHash,
            description = description,
            descriptionHash = descriptionHash,
            payeeNode = payee?.takeIf { NodeDirectory.isPubkey(it) },
            expirySeconds = expiry?.coerceAtMost(MAX_EXPIRY_SECONDS) ?: DEFAULT_EXPIRY_SECONDS,
        )
    }

    /**
     * `lnbc2500u` → `bc` and 250 000 000 msat.
     *
     * The currency is the letters between `ln` and the first digit; everything
     * after is the amount and its multiplier. An hrp with no digits is a
     * perfectly valid amountless invoice.
     */
    private fun parseHrp(hrp: String): Pair<String, BigInteger?>? {
        val body = hrp.substring(2)
        val firstDigit = body.indexOfFirst { it.isDigit() }
        val network = if (firstDigit < 0) body else body.substring(0, firstDigit)
        if (network.isEmpty() || !network.all { it in 'a'..'z' }) return null
        if (firstDigit < 0) return network to null

        val amount = body.substring(firstDigit)
        val multiplier = amount.last()
        val digits = if (multiplier.isDigit()) amount else amount.dropLast(1)
        if (digits.isEmpty() || digits.length > 20 || !digits.all { it.isDigit() }) return null
        val value = BigInteger(digits)

        val msat = when {
            multiplier.isDigit() -> value.multiply(MSAT_PER_BTC)
            multiplier == 'm' -> value.multiply(BigInteger.TEN.pow(8))
            multiplier == 'u' -> value.multiply(BigInteger.TEN.pow(5))
            multiplier == 'n' -> value.multiply(BigInteger.TEN.pow(2))
            // Pico-bitcoin is a tenth of a millisatoshi, so the spec requires
            // the value to be a multiple of 10. Anything else is not payable.
            multiplier == 'p' ->
                if (value.mod(BigInteger.TEN).signum() == 0) value.divide(BigInteger.TEN) else return null

            else -> return null
        }
        // A zero amount is spelled by omitting the field, not by writing 0.
        return network to msat.takeIf { it.signum() > 0 }
    }

    /** Big-endian base32 to a Long, refusing anything that would overflow. */
    private fun readNumber(values: List<Int>): Long? {
        if (values.isEmpty() || values.size > 12) return null
        var result = 0L
        for (value in values) {
            result = result * 32 + value
            if (result < 0) return null
        }
        return result
    }

    // --- bech32 ------------------------------------------------------------

    private fun checksumValid(hrp: String, data: List<Int>): Boolean {
        if (data.size < 6) return false
        // BOLT11 uses plain bech32, not bech32m, and explicitly lifts BIP173's
        // 90-character cap — so length is not checked here.
        return Bech32.residue(hrp, data) == Bech32.BECH32
    }
}
