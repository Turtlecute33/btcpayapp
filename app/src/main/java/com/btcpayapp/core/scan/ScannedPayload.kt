package com.btcpayapp.core.scan

import java.math.BigDecimal
import java.net.URI

/**
 * What a scanned QR code turned out to be.
 *
 * The scanner is used in several places — pairing a server, filling in a payout
 * destination, paying a Lightning invoice — so it classifies once and each
 * screen picks the cases it can act on.
 */
sealed interface ScannedPayload {

    val raw: String

    /** BIP21, with the optional Lightning and payjoin extensions. */
    data class Bip21(
        override val raw: String,
        val address: String,
        val amountBtc: BigDecimal? = null,
        val label: String? = null,
        val message: String? = null,
        val lightning: String? = null,
        val payjoinEndpoint: String? = null,
    ) : ScannedPayload

    /** A bare on-chain address. */
    data class BitcoinAddress(override val raw: String, val address: String) : ScannedPayload

    /** A BOLT11 invoice, with or without a `lightning:` prefix. */
    data class Bolt11(override val raw: String, val invoice: String) : ScannedPayload

    /** LNURL in bech32 (`lnurl1…`) or LUD-17 (`lnurlp://`, `lnurlw://`) form. */
    data class Lnurl(override val raw: String, val value: String) : ScannedPayload

    /** `pubkey@host:port` — used by the "connect to peer" screen. */
    data class NodeUri(override val raw: String, val uri: String) : ScannedPayload

    /** A BTCPay instance URL, possibly carrying a one-time login code. */
    data class ServerUrl(
        override val raw: String,
        val baseUrl: String,
        val loginCode: String? = null,
        val email: String? = null,
    ) : ScannedPayload

    /** A store-user invitation link, `{base}/invite/{userId}/{code}`. */
    data class Invitation(override val raw: String, val url: String) : ScannedPayload

    data class Unknown(override val raw: String) : ScannedPayload
}

/**
 * Classifies a scanned or pasted string.
 *
 * Deliberately permissive about *format* and strict about *claims*: it will
 * recognise a BIP21 URI with unknown parameters, but it will not invent a
 * server URL out of arbitrary text.
 */
object ScanParser {

    private val BECH32_ADDRESS = Regex("^(bc1|tb1|bcrt1)[02-9ac-hj-np-z]{6,87}$", RegexOption.IGNORE_CASE)
    private val BASE58_ADDRESS = Regex("^[13mn2][1-9A-HJ-NP-Za-km-z]{25,39}$")
    private val BOLT11 = Regex("^ln(bc|tb|bcrt)[0-9]*[munp]?1[02-9ac-hj-np-z]{50,}$", RegexOption.IGNORE_CASE)
    private val NODE_URI = Regex("^[0-9a-fA-F]{66}@[^\\s]+$")

    fun parse(input: String): ScannedPayload {
        val value = input.trim()
        if (value.isEmpty()) return ScannedPayload.Unknown(value)

        lowercasePrefix(value, "bitcoin:")?.let { return parseBip21(value) }

        lowercasePrefix(value, "lightning:")?.let { payload ->
            return classifyLightning(value, payload)
        }

        if (value.startsWith("lnurl", ignoreCase = true)) {
            return ScannedPayload.Lnurl(value, value)
        }

        if (BOLT11.matches(value)) return ScannedPayload.Bolt11(value, value.lowercase())
        if (BECH32_ADDRESS.matches(value)) return ScannedPayload.BitcoinAddress(value, value.lowercase())
        if (BASE58_ADDRESS.matches(value)) return ScannedPayload.BitcoinAddress(value, value)
        if (NODE_URI.matches(value)) return ScannedPayload.NodeUri(value, value)

        // `{loginCode};{serverUrl};{email}` — the compact login form some BTCPay
        // screens emit.
        if (value.count { it == ';' } == 2) {
            val (code, server, email) = value.split(';', limit = 3)
            normaliseServerUrl(server)?.let {
                return ScannedPayload.ServerUrl(value, it, loginCode = code.ifBlank { null }, email = email.ifBlank { null })
            }
        }

        // `{encryptionKey}*{serverUrl}*{email}` — device pairing payload. Only
        // the server URL is meaningful to this app.
        if (value.count { it == '*' } == 2) {
            val parts = value.split('*', limit = 3)
            normaliseServerUrl(parts[1])?.let {
                return ScannedPayload.ServerUrl(value, it, email = parts[2].ifBlank { null })
            }
        }

        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return parseHttpUrl(value)
        }

        // A bare host is the most common thing someone types during onboarding.
        normaliseServerUrl(value)?.let { return ScannedPayload.ServerUrl(value, it) }

        return ScannedPayload.Unknown(value)
    }

    private fun classifyLightning(raw: String, payload: String): ScannedPayload = when {
        payload.startsWith("lnurl", ignoreCase = true) -> ScannedPayload.Lnurl(raw, payload)
        BOLT11.matches(payload) -> ScannedPayload.Bolt11(raw, payload.lowercase())
        else -> ScannedPayload.Unknown(raw)
    }

    private fun parseBip21(raw: String): ScannedPayload {
        val withoutScheme = raw.substring("bitcoin:".length)
        val address = withoutScheme.substringBefore('?').trim()
        val params = withoutScheme.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val encoded = pair.substringAfter('=', "")
                key.lowercase() to runCatching {
                    java.net.URLDecoder.decode(encoded, "UTF-8")
                }.getOrDefault(encoded)
            }

        // BIP21 requires rejecting payment requests with unknown required fields.
        if (params.keys.any { it.startsWith("req-") }) return ScannedPayload.Unknown(raw)
        val amount = params["amount"]?.takeIf { it.length <= 128 }?.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 && it.scale() in -32..32 && it.precision() <= 100 && it.stripTrailingZeros().scale() <= 8 }
        if (params.containsKey("amount") && amount == null) return ScannedPayload.Unknown(raw)

        if (address.isEmpty() && params["lightning"].isNullOrEmpty()) {
            return ScannedPayload.Unknown(raw)
        }

        return ScannedPayload.Bip21(
            raw = raw,
            address = address.lowercase().takeIf { BECH32_ADDRESS.matches(it) } ?: address,
            amountBtc = amount,
            label = params["label"],
            message = params["message"],
            lightning = params["lightning"]?.takeIf { it.isNotBlank() },
            payjoinEndpoint = params["pj"],
        )
    }

    private fun parseHttpUrl(value: String): ScannedPayload {
        val uri = runCatching { URI(value) }.getOrNull() ?: return ScannedPayload.Unknown(value)
        val normalised = normaliseServerUrl(value) ?: return ScannedPayload.Unknown(value)

        if (uri.path?.contains("/invite/") == true) {
            return ScannedPayload.Invitation(value, value)
        }

        val query = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }

        val loginCode = query["logincode"]?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }
        val base = if (uri.path.orEmpty().trimEnd('/').endsWith("/login", true)) {
            normalised.dropLast("/login".length)
        } else normalised

        return ScannedPayload.ServerUrl(
            raw = value,
            baseUrl = base,
            loginCode = loginCode?.takeIf { it.isNotBlank() },
        )
    }

    private fun lowercasePrefix(value: String, prefix: String): String? =
        if (value.startsWith(prefix, ignoreCase = true)) value.substring(prefix.length) else null

    /**
     * Turns what someone typed into a base URL.
     *
     * Defaults to `https://` — except for `.onion`, which is plain HTTP by
     * design (Tor already authenticates and encrypts, and no CA can issue for
     * an onion name).
     */
    fun normaliseServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            trimmed.substringBefore('/').substringBefore(':').endsWith(".onion", true) -> "http://$trimmed"
            else -> "https://$trimmed"
        }

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.rawUserInfo != null || uri.port == 0 || uri.port > 65535) return null
        if (!host.contains('.') && !host.contains(':') && !host.equals("localhost", true)) return null
        if (host.startsWith('.') || host.endsWith('.')) return null

        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "${uri.scheme.lowercase()}://${host.lowercase()}$port$path"
    }
}
