package com.btcpayapp.core.util

import com.btcpayapp.data.model.BitcoinUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Amount and date formatting.
 *
 * Every amount in this app is a [BigDecimal] from first parse to final string.
 * `Double` is never used for money — a merchant's takings should not depend on
 * binary floating point.
 */
object Amounts {

    private const val SATS_PER_BTC = 100_000_000L
    private val SATS_PER_BTC_DECIMAL = BigDecimal(SATS_PER_BTC)
    private val MSAT_PER_SAT = BigDecimal(1000)

    /** Currency codes BTCPay uses that are not ISO 4217 and have no locale symbol. */
    private val CRYPTO_CODES = setOf("BTC", "SATS", "SAT", "LTC", "DOGE", "DASH", "XMR", "LBTC", "BTG", "GRS", "MONA", "ZEC")

    fun isCrypto(currency: String): Boolean = currency.uppercase(Locale.ROOT) in CRYPTO_CODES

    /**
     * `Currency.getInstance` throws for unknown codes, and building the
     * exception (with its stack trace) for every crypto code, on every row, on
     * every frame, is exception-driven control flow in the scroll path. The
     * negative result is cached too, which is the whole point.
     */
    private val isoCurrencies = ConcurrentHashMap<String, Optional<Currency>>()

    private fun isoCurrency(code: String): Currency? = isoCurrencies
        .getOrPut(code) { Optional.ofNullable(runCatching { Currency.getInstance(code) }.getOrNull()) }
        .orElse(null)

    /**
     * `NumberFormat.getCurrencyInstance` is an ICU lookup costing hundreds of
     * microseconds, too slow to rebuild for every amount drawn. Cached per
     * (locale, code); `NumberFormat` is not thread-safe, so each use is
     * synchronised on the instance itself.
     */
    private val currencyFormats = ConcurrentHashMap<String, NumberFormat>()

    /**
     * How many minor-unit digits [currency] has: 0 for satoshi and for JPY-like
     * currencies, 8 for BTC, 3 for KWD/BHD/JOD, 2 for most of the rest.
     */
    fun scaleFor(currency: String): Int {
        val code = currency.uppercase(Locale.ROOT)
        if (isCrypto(code)) return cryptoScale(code)
        return isoCurrency(code)?.defaultFractionDigits?.coerceAtLeast(0) ?: 2
    }

    /**
     * Formats an amount in whatever currency the invoice is denominated in.
     * Falls back to `12.34 XYZ` for codes the platform does not know, which is
     * every crypto code and a few regional ones.
     */
    fun format(amount: BigDecimal, currency: String, locale: Locale = Locale.getDefault()): String {
        val code = currency.uppercase(Locale.ROOT)
        if (isCrypto(code)) return "${trim(amount, cryptoScale(code))} $code"

        val iso = isoCurrency(code) ?: return "${trim(amount, 2)} $code"

        val format = currencyFormats.getOrPut("${locale.toLanguageTag()}|$code") {
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = iso
                maximumFractionDigits = iso.defaultFractionDigits.coerceAtLeast(0)
                minimumFractionDigits = iso.defaultFractionDigits.coerceAtLeast(0)
                // Match `trim`, which uses HALF_UP. Without this the JDK default
                // of HALF_EVEN applies and the same amount can be shown two
                // different ways on two screens.
                roundingMode = RoundingMode.HALF_UP
            }
        }
        return synchronized(format) { format.format(amount) }
    }

    private fun cryptoScale(code: String) = if (code == "SATS" || code == "SAT") 0 else 8

    /** Formats a BTC-denominated value in the user's chosen unit. */
    fun formatBitcoin(
        btc: BigDecimal,
        unit: BitcoinUnit,
        locale: Locale = Locale.getDefault(),
    ): String = when (unit) {
        BitcoinUnit.Btc -> "${trim(btc, 8)} BTC"
        BitcoinUnit.Sat -> {
            val sats = btc.multiply(SATS_PER_BTC_DECIMAL).setScale(0, RoundingMode.HALF_UP)
            "${NumberFormat.getIntegerInstance(locale).format(sats)} sat"
        }
    }

    fun btcToSats(btc: BigDecimal): BigDecimal =
        btc.multiply(SATS_PER_BTC_DECIMAL).setScale(0, RoundingMode.HALF_UP)

    fun satsToBtc(sats: BigDecimal): BigDecimal =
        sats.divide(SATS_PER_BTC_DECIMAL, 8, RoundingMode.HALF_UP)

    /** Lightning amounts arrive as millisatoshi strings. */
    fun msatToSats(msat: String?): BigDecimal =
        (msat?.toBigDecimalOrNull() ?: BigDecimal.ZERO).divide(MSAT_PER_SAT, 3, RoundingMode.DOWN)

    fun msatToBtc(msat: String?): BigDecimal = satsToBtc(msatToSats(msat))

    fun satsToMsat(sats: BigDecimal): String =
        sats.multiply(MSAT_PER_SAT).setScale(0, RoundingMode.HALF_UP).toPlainString()

    fun formatMsat(msat: String?, unit: BitcoinUnit, locale: Locale = Locale.getDefault()): String =
        formatBitcoin(msatToBtc(msat), unit, locale)

    /** Drops trailing zeros without switching to scientific notation. */
    fun trim(value: BigDecimal, maxScale: Int): String {
        val scaled = value.setScale(maxScale, RoundingMode.HALF_UP)
        val stripped = scaled.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
    }

    /** Masks an amount when privacy mode is on, keeping the layout stable. */
    fun masked(text: String): String = "•".repeat(text.length.coerceIn(3, 8))

    /**
     * Parses an amount the user typed, or null if it is not a number.
     *
     * `KeyboardType.Decimal` shows the *locale's* separator, so a German,
     * French, Czech or Italian merchant types "12,50" — and `toBigDecimalOrNull`
     * accepts only '.', so it would return null. A screen would then either
     * grey out the submit button with no message or, worse, fall back to a
     * default: a point-of-sale item saved with no price, or a payout
     * processor's minimum threshold of zero, which makes it sweep on every run.
     *
     * Group separators are rejected rather than stripped: "1,234" is ambiguous
     * between one-thousand-something and 1.234, and guessing wrong about money
     * is worse than asking the user to retype it.
     */
    fun parse(input: String): BigDecimal? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 128) return null
        if (trimmed.count { it == ',' || it == '.' } > 1) return null
        return trimmed.replace(',', '.').toBigDecimalOrNull()
            ?.takeIf { it.scale() in -32..32 && it.precision() <= 100 }
    }
}

object Dates {

    // The pattern is fixed, but the zone is NOT baked in. `withZone` at class
    // init captures the zone once for the whole process lifetime; Android
    // recreates activities on a timezone or locale change but does not restart
    // the process, so every timestamp in the app would stay wrong after a DST
    // transition or a flight until the app was force-stopped.
    private val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

    private val dateOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

    private val timeOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** [epochSeconds] — BTCPay sends unix seconds, not milliseconds. */
    fun full(epochSeconds: Long): String = dateTime.withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

    fun date(epochSeconds: Long): String = dateOnly.withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

    fun time(epochSeconds: Long): String = timeOnly.withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

    /** "just now", "12 min ago", "3 h ago", then an absolute date. */
    fun relative(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val delta = nowSeconds - epochSeconds
        val future = delta < 0
        val seconds = abs(delta)
        return when {
            // 60, not 45: below that the next branch renders "0 min ago" for
            // anything 45-59 seconds old, which is visible on the invoice list
            // moments after a payment lands.
            seconds < 60 -> if (future) "in a moment" else "just now"
            seconds < 3600 -> "${seconds / 60} min".suffix(future)
            seconds < 86_400 -> "${seconds / 3600} h".suffix(future)
            seconds < 7 * 86_400 -> "${seconds / 86_400} d".suffix(future)
            else -> date(epochSeconds)
        }
    }

    private fun String.suffix(future: Boolean) = if (future) "in $this" else "$this ago"

    /** Countdown for an invoice that has not expired yet. */
    fun countdown(untilEpochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String? {
        val remaining = untilEpochSeconds - nowSeconds
        if (remaining <= 0) return null
        val minutes = remaining / 60
        val seconds = remaining % 60
        return if (minutes >= 60) {
            "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}

object Text {

    // Hoisted out of the functions below. These are called once per status chip
    // and once per notification body — i.e. several times per row, per frame,
    // while a list scrolls — and compiling the pattern each time would dominate
    // the actual work by an order of magnitude. Patterns are immutable and
    // thread-safe.
    private val BR_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val P_CLOSE = Regex("</p\\s*>", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("<[^>]*>")
    private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /**
     * BTCPay notification bodies are HTML fragments. Rather than pull in a
     * parser or — worse — render them in a WebView, tags are stripped and the
     * five XML entities decoded. Anything else stays literal, which is the safe
     * failure direction for text coming off a server.
     */
    fun stripHtml(html: String): String = html
        .replace(BR_TAG, "\n")
        .replace(P_CLOSE, "\n")
        .replace(ANY_TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    /** `bc1qxy…k5mdfg` — enough to compare visually, short enough to fit. */
    fun middleEllipsis(value: String, head: Int = 10, tail: Int = 8): String =
        if (value.length <= head + tail + 1) value else "${value.take(head)}…${value.takeLast(tail)}"

    /**
     * `AwaitingApproval` → `Awaiting approval`.
     *
     * Sentence case, not title case: the API's PascalCase enum names appear as
     * chips and row labels, and the rest of the interface is sentence case.
     * Acronyms are left alone so `BOLT11` and `LNURL` survive.
     */
    fun sentenceCase(value: String): String = value
        .replace(CAMEL_BOUNDARY, " ")
        .split(' ')
        .mapIndexed { index, word ->
            when {
                index == 0 -> word.replaceFirstChar { it.uppercase() }
                word.all { it.isUpperCase() || it.isDigit() } -> word
                else -> word.replaceFirstChar { it.lowercase() }
            }
        }
        .joinToString(" ")
}
