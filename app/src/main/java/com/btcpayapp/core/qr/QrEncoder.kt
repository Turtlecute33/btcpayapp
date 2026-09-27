package com.btcpayapp.core.qr

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.graphics.Bitmap
import java.util.Locale

/**
 * QR generation.
 *
 * The bitmap is produced at exactly one pixel per module and drawn scaled with
 * nearest-neighbour filtering, so the result is pin-sharp at any size and the
 * allocation stays at a few kilobytes instead of a few megabytes.
 *
 * Error correction is set to M, not the usual L. Payment QRs get scanned off
 * a scratched phone screen in bad light, and the extra redundancy costs only a
 * slightly denser code.
 */
object QrEncoder {

    private val writer = QRCodeWriter()

    fun encode(
        contents: String,
        foreground: Color = Color.Black,
        background: Color = Color.White,
        quietZoneModules: Int = 2,
    ): ImageBitmap? {
        val matrix = matrix(contents, quietZoneModules) ?: return null

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        val on = foreground.toArgb()
        val off = background.toArgb()

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x, y]) on else off
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * The module grid, or null when [contents] is empty or too long for a QR
     * code. Split from [encode] so a JVM test can run it without a Bitmap.
     */
    internal fun matrix(contents: String, quietZoneModules: Int = 2): BitMatrix? {
        if (contents.isEmpty()) return null
        return runCatching {
            writer.encode(contents, BarcodeFormat.QR_CODE, 0, 0, hints(contents, quietZoneModules))
        }.getOrNull()
    }

    /**
     * No character-set hint for ASCII, which is every payment payload. With
     * the hint set, zxing writes a UTF-8 ECI header in front of every
     * byte-mode code: more modules, and a header some older wallet scanners
     * read badly. Only text that is not ASCII gets it, because zxing's default
     * byte encoding is ISO-8859-1 and would turn such characters into '?'.
     */
    internal fun hints(contents: String, quietZoneModules: Int): Map<EncodeHintType, Any> = buildMap {
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        put(EncodeHintType.MARGIN, quietZoneModules)
        if (contents.any { it.code > 0x7F }) put(EncodeHintType.CHARACTER_SET, "UTF-8")
    }

    /**
     * Bech32 and BOLT11 payloads encode far more densely in uppercase, because
     * that unlocks QR's alphanumeric mode. Wallets treat them case-insensitively,
     * so this is free. Anything containing a lowercase-significant part (a URL
     * path, an LNURL with a query string) is left alone.
     */
    fun optimiseCase(payload: String): String {
        // Locale.ROOT, never the default locale. In Turkish and Azerbaijani
        // `"bitcoin:".uppercase()` yields "BİTCOİN:" (U+0130, dotted capital I)
        // and no wallet can parse the scheme.
        val upper = payload.uppercase(Locale.ROOT)
        return when {
            payload.isLightningBech32() -> upper

            // Only bech32 addresses are case-insensitive. Legacy base58
            // addresses (1…, 3…, and testnet m/n/2…) are case-SENSITIVE, so
            // uppercasing one produces an address the customer's wallet either
            // rejects or, worse, reads as a different string. Restricted to an
            // explicit bech32 human-readable prefix.
            payload.startsWith("bitcoin:", true) &&
                !payload.contains('?') &&
                payload.isBech32Address() -> upper

            else -> payload
        }
    }

    /**
     * A BOLT11 invoice or a bech32 LNURL, bare or behind `lightning:`. The
     * scheme matters: checkout links and Lightning receive both show
     * `lightning:lnbc…`, and without it here the densest payloads in the app
     * never reached alphanumeric mode. Letters and digits only, so nothing
     * case-sensitive (a LUD-17 URL, a Lightning address) is ever uppercased.
     */
    private fun String.isLightningBech32(): Boolean {
        val body = if (startsWith(LIGHTNING_SCHEME, true)) substring(LIGHTNING_SCHEME.length) else this
        return LIGHTNING_PREFIXES.any { body.startsWith(it, true) } &&
            body.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    }

    private const val LIGHTNING_SCHEME = "lightning:"

    /** `lnbc` also covers regtest `lnbcrt`, and `lntb` covers signet `lntbs`. */
    private val LIGHTNING_PREFIXES = listOf("lnbc", "lntb", "lnurl1")

    /** True when the address part of a BIP21 URI is bech32/bech32m. */
    private fun String.isBech32Address(): Boolean {
        val address = substringAfter(':').substringBefore('?')
        return BECH32_PREFIXES.any { address.startsWith(it, ignoreCase = true) }
    }

    /** mainnet, testnet/signet, regtest. */
    private val BECH32_PREFIXES = listOf("bc1", "tb1", "bcrt1")
}
