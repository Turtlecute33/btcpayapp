package com.btcpayapp.core.util

import com.btcpayapp.data.model.BitcoinUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
     * The locale's integer format and decimal separator, looked up once per
     * locale for the same reason as [currencyFormats]: sat labels are drawn
     * per row, in composition, while a list scrolls. The format is not
     * thread-safe, so each use is synchronised on it.
     */
    private class LocaleNumbers(val integer: NumberFormat, val decimalSeparator: Char)

    private val localeNumbers = ConcurrentHashMap<Locale, LocaleNumbers>()

    private fun numbersFor(locale: Locale): LocaleNumbers = localeNumbers.getOrPut(locale) {
        LocaleNumbers(NumberFormat.getIntegerInstance(locale), DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }

    /**
     * [trim] with the locale's decimal separator, for display only. BTC and
     * the other codes without a platform format use it, so they follow the
     * same convention as fiat and sats. With a fixed '.', a German screen
     * showed "25.000 sat" (twenty-five thousand) next to "1.234 BTC" (one and
     * a bit): the same mark meant two things. [parse] reads every separator
     * this can show ('.', ',' and the Arabic '٫'). A copied value with the
     * ambiguous shape is refused, not misread.
     */
    private fun display(value: BigDecimal, maxScale: Int, locale: Locale): String =
        trim(value, maxScale).replace('.', numbersFor(locale).decimalSeparator)

    /**
     * Formats an amount in whatever currency the invoice is denominated in.
     * Falls back to `12.34 XYZ` for codes the platform does not know, which is
     * every crypto code and a few regional ones.
     */
    fun format(amount: BigDecimal, currency: String, locale: Locale = Locale.getDefault()): String {
        val code = currency.uppercase(Locale.ROOT)
        if (isCrypto(code)) return "${display(amount, cryptoScale(code), locale)} $code"

        val iso = isoCurrency(code) ?: return "${display(amount, 2, locale)} $code"

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
        BitcoinUnit.Btc -> "${display(btc, 8, locale)} BTC"
        BitcoinUnit.Sat -> {
            val sats = btc.multiply(SATS_PER_BTC_DECIMAL).setScale(0, RoundingMode.HALF_UP)
            val integer = numbersFor(locale).integer
            "${synchronized(integer) { integer.format(sats) }} sat"
        }
    }

    /**
     * The same value in the unit the user did not choose, for the echo under
     * an amount field and on confirmations: "21,000 sat" under "0.00021", so a
     * slip of three zeros shows before anything is sent.
     */
    fun inOtherUnit(btc: BigDecimal, unit: BitcoinUnit, locale: Locale = Locale.getDefault()): String =
        formatBitcoin(btc, if (unit == BitcoinUnit.Btc) BitcoinUnit.Sat else BitcoinUnit.Btc, locale)

    fun btcToSats(btc: BigDecimal): BigDecimal =
        btc.multiply(SATS_PER_BTC_DECIMAL).setScale(0, RoundingMode.HALF_UP)

    fun satsToBtc(sats: BigDecimal): BigDecimal =
        sats.divide(SATS_PER_BTC_DECIMAL, 8, RoundingMode.HALF_UP)

    /**
     * Lightning amounts arrive as millisatoshi strings. A missing or unreadable
     * one counts as zero; see [serverDecimal] for why unreadable includes
     * "1e2147483647".
     */
    fun msatToSats(msat: String?): BigDecimal =
        (serverDecimal(msat) ?: BigDecimal.ZERO).divide(MSAT_PER_SAT, 3, RoundingMode.DOWN)

    /**
     * A number the server sent as text (Lightning msat and sat strings, rate
     * spreads), or null when it is not one this app can work with.
     *
     * The bounds are the ones [parse] and the JSON decimal serializer use.
     * Without them one bad value is enough to take down a screen: "1e2147483647"
     * makes the msat-to-sat divide throw, and "1e999999999" makes it build a
     * billion-digit number, both inside composition. Home draws the Lightning
     * balance, so that would crash every launch.
     */
    fun serverDecimal(text: String?): BigDecimal? =
        text?.trim()?.takeIf { it.length <= 128 }?.toBigDecimalOrNull()
            ?.takeIf { it.scale() in -32..32 && it.precision() <= 100 }

    fun msatToBtc(msat: String?): BigDecimal = satsToBtc(msatToSats(msat))

    fun satsToMsat(sats: BigDecimal): String =
        sats.multiply(MSAT_PER_SAT).setScale(0, RoundingMode.HALF_UP).toPlainString()

    fun formatMsat(msat: String?, unit: BitcoinUnit, locale: Locale = Locale.getDefault()): String =
        formatBitcoin(msatToBtc(msat), unit, locale)

    /**
     * Drops trailing zeros without switching to scientific notation.
     *
     * [maxScale] is held to 0..32, the bounds of [serverDecimal]: the refund
     * screen passes the server's divisibility, and a scale of millions makes
     * this build a number of millions of digits inside composition.
     */
    fun trim(value: BigDecimal, maxScale: Int): String {
        val scaled = value.setScale(maxScale.coerceIn(0, 32), RoundingMode.HALF_UP)
        val stripped = scaled.stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
    }

    /**
     * What privacy mode draws in place of an amount. Fixed width on purpose:
     * a mask as long as the text it hides tells a shoulder-surfer whether the
     * day's takings have three digits or five.
     */
    const val MASK: String = "••••••"

    /**
     * One separator with exactly three digits after it and one to three before,
     * no leading zero: "1,000", "21.000", "500,000", "1.125". Half the world
     * reads that as a thousand-something and the other half as a decimal.
     */
    private val AMBIGUOUS = Regex("[-+]?[1-9][0-9]{0,2}[.,][0-9]{3}")

    /**
     * U+066B, the decimal mark of Persian and many Arabic locales, which
     * [display] shows there. It is never a thousands mark (that is U+066C,
     * which stays refused), so it is never ambiguous.
     */
    private const val ARABIC_DECIMAL = '\u066B'

    /**
     * [input] trimmed, with every decimal digit written in ASCII.
     * `toBigDecimalOrNull` also reads Arabic-Indic and other Unicode digits,
     * so without this "٢١,٠٠٠" would slip past [AMBIGUOUS] and become 21.
     */
    private fun normalised(input: String): String =
        input.trim().map { if (it.isDigit()) '0' + it.digitToInt() else it }.joinToString("")

    /**
     * Parses an amount the user typed, or null if it is not a number.
     *
     * `KeyboardType.Decimal` shows the *locale's* separator, so a German,
     * French, Czech or Italian merchant types "12,50" — and `toBigDecimalOrNull`
     * accepts only '.', so it would return null. A screen would then either
     * grey out the submit button with no message or, worse, fall back to a
     * default: a point-of-sale item saved with no price, or a payout
     * processor's minimum threshold of zero, which makes it sweep on every run.
     * So ',' and '.' both mean the decimal point, as does [ARABIC_DECIMAL],
     * and only one may appear.
     *
     * The one shape that stays refused is [AMBIGUOUS]. Read as a decimal,
     * "21,000" sat became 21 sat and "1,500" USD became $1.50, a 1000x
     * underbill with nothing on screen to show it. Read as a thousands group,
     * "1.125" BTC would become 1125 BTC. Guessing wrong about money is worse
     * than asking, so [parseProblem] asks. "0.125", ".125", "1234.567",
     * "12,50" and "1.1250" are not ambiguous and parse as decimals.
     */
    fun parse(input: String): BigDecimal? {
        val trimmed = normalised(input)
        if (trimmed.isEmpty() || trimmed.length > 128) return null
        if (trimmed.count { it == ',' || it == '.' || it == ARABIC_DECIMAL } > 1) return null
        if (AMBIGUOUS.matches(trimmed)) return null
        return trimmed.replace(',', '.').replace(ARABIC_DECIMAL, '.').toBigDecimalOrNull()
            ?.takeIf { it.scale() in -32..32 && it.precision() <= 100 }
    }

    /**
     * The field error for [input]: null when it is blank or [parse] accepts
     * it. For the ambiguous shape the text is built from what was typed and
     * says how to write each reading, so the fix is one edit away.
     */
    fun parseProblem(input: String): String? {
        val trimmed = normalised(input)
        if (trimmed.isEmpty() || parse(trimmed) != null) return null
        if (!AMBIGUOUS.matches(trimmed)) return "Enter a number, for example 12.50."
        val whole = trimmed.filter { it != ',' && it != '.' }
        return "$trimmed can mean $whole or a decimal. Type $whole, or ${trimmed}0 for the decimal."
    }

    /**
     * The text to put in an amount field for [value]: [trim] to [maxScale],
     * plus one '0' when that would have the ambiguous shape ("1.125" becomes
     * "1.1250"). Every prefill from a loaded or scanned value goes through
     * here, so [parse] always accepts what the app wrote itself.
     */
    fun toInput(value: BigDecimal, maxScale: Int): String {
        val text = trim(value, maxScale)
        return if (AMBIGUOUS.matches(text)) "${text}0" else text
    }

    /**
     * [text] as a positive amount in [currency], or null. More decimals than
     * [currency] shows are refused, because every review rounds to that scale:
     * "10.0051" USD was confirmed as $10.01 and then sent as typed.
     */
    fun inCurrency(text: String, currency: String): BigDecimal? =
        parse(text)?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= scaleFor(currency) }

    /**
     * The field error for [text] in [currency]: not a number, or more decimals
     * than [inCurrency] takes. Null for a blank, and for zero or less, which
     * each caller words for itself. A blank [currency] has no scale to check yet.
     */
    fun inCurrencyProblem(text: String, currency: String): String? {
        parseProblem(text)?.let { return it }
        val value = parse(text) ?: return null
        if (currency.isBlank()) return null
        val scale = scaleFor(currency)
        return when {
            value.stripTrailingZeros().scale() <= scale -> null
            scale == 0 -> "Enter a whole number of $currency."
            else -> "Use at most $scale decimal places for $currency."
        }
    }

    /** Digits, one ',' or '.', then exactly three digits: "1.125", "2,500". */
    private val THREE_DECIMALS = Regex("\\p{Nd}*[.,]\\p{Nd}{3}")

    /**
     * A typed fee rate in sat/vB, or null when it is not a number above zero.
     *
     * Here ',' and '.' always mark the decimal. [parse] refuses "1.125"
     * because, as an amount, it can also mean 1125. A fee rate never has a
     * thousands mark, so here "1.125" is read as "1.1250": the same value, with
     * only one reading. To ask instead would offer 1125 sat/vB, a rate 1000
     * times too high.
     */
    fun feeRate(text: String): BigDecimal? {
        val typed = text.trim()
        return parse(if (THREE_DECIMALS.matches(typed)) "${typed}0" else typed)?.takeIf { it.signum() > 0 }
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

    /** 9999-12-31T23:59:59Z, the last second with a four-digit year. */
    private const val MAX_EPOCH_SECONDS = 253_402_300_799L

    /**
     * Timestamps come off the server as plain longs, and `Instant.ofEpochSecond`
     * throws for Long.MAX_VALUE. One bad `createdTime` would then crash the
     * invoice list in composition, so every conversion clamps to years
     * 1970..9999 first. A wrong date on screen is the safe failure.
     */
    private fun clamp(epochSeconds: Long): Long = epochSeconds.coerceIn(0, MAX_EPOCH_SECONDS)

    private fun instant(epochSeconds: Long): Instant = Instant.ofEpochSecond(clamp(epochSeconds))

    /** [epochSeconds] — BTCPay sends unix seconds, not milliseconds. */
    fun full(epochSeconds: Long): String = dateTime.withZone(ZoneId.systemDefault())
        .format(instant(epochSeconds))

    fun date(epochSeconds: Long): String = dateOnly.withZone(ZoneId.systemDefault())
        .format(instant(epochSeconds))

    fun time(epochSeconds: Long): String = timeOnly.withZone(ZoneId.systemDefault())
        .format(instant(epochSeconds))

    // The Material date picker speaks UTC midnight: selectedDateMillis is
    // 00:00Z of the chosen day, and initialSelectedDateMillis is read the same
    // way. Stored as is, an expiry picked for 10 Oct fell on 9 Oct 20:00 in New
    // York and at 09:00 on the 10th in Tokyo, so requests and crowdfunds closed
    // before the day the merchant chose. These convert between that and the
    // local calendar day, in the zone the device is in now.

    private fun pickerDate(pickerUtcMillis: Long): LocalDate =
        Instant.ofEpochMilli(pickerUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    /** Epoch seconds of local 00:00:00 on the picked day, for start dates. */
    fun pickerStartOfDay(pickerUtcMillis: Long): Long =
        pickerDate(pickerUtcMillis).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    /**
     * Epoch seconds of local 23:59:59 on the picked day, for expiry and end
     * dates: next local midnight minus one second, which stays right on a day
     * that DST makes 23 or 25 hours long.
     */
    fun pickerEndOfDay(pickerUtcMillis: Long): Long =
        pickerDate(pickerUtcMillis).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1

    /** The picker's value for the local calendar day that holds [epochSeconds]. */
    fun pickerMillis(epochSeconds: Long): Long =
        instant(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** "just now", "12 min ago", "3 h ago", then an absolute date. */
    fun relative(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val delta = clamp(nowSeconds) - clamp(epochSeconds)
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

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex, two digits per byte. The one encoder for txids, hashes, keys and fingerprints. */
internal fun ByteArray.toHex(): String {
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        builder.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return builder.toString()
}
