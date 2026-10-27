package com.btcpayapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.btcpayapp.core.net.isLocalNetworkHost
import java.util.Locale

/**
 * The host of [url], read by hand: `java.net.URI` gives no host for a name
 * with '_', and Docker service names, a common webhook target, often have one.
 * A '\' is read as '/', as the server's .NET `Uri` does for http, so
 * `http://shop.example\@10.0.0.1/` is shop.example here too.
 */
internal fun hostOf(url: String): String? {
    val authority = url.replace('\\', '/').substringAfter("://", "")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
    val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.takeIf { it.isNotBlank() && it != "[]" }
}

/**
 * True when an http:// webhook to [host] needs no "Send unencrypted?".
 *
 * [isLocalNetworkHost] calls a dotless name local, but the server reads a
 * host made of numbers the way `inet_aton` does: "134744072", "0x08080808"
 * and "010.0.0.1" are public addresses to it. So such a host passes only as a
 * plain dotted quad, and a host with a '%' escape, which the server may
 * decode, always asks.
 */
internal fun isLocalWebhookHost(host: String): Boolean {
    val name = host.trimEnd('.')
    if ('%' in name) return false
    val parts = name.split('.')
    val numeric = parts.all { part ->
        part.isNotEmpty() && (part.all { it in '0'..'9' } || part.startsWith("0x", ignoreCase = true))
    }
    val plainQuad = parts.size == 4 && parts.all { part ->
        part.all { it in '0'..'9' } && (part == "0" || !part.startsWith('0'))
    }
    return (!numeric || plainQuad) && isLocalNetworkHost(host)
}

/** True when [url] is http:// to a host on the internet, so what goes there is not encrypted. */
internal fun isPublicCleartext(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return url.trim().substringBefore("://", "").lowercase(Locale.ROOT) == "http" && !isLocalWebhookHost(host)
}

/**
 * The new targets in [fields], each a URL as typed to the URL as loaded: the
 * ones that are not blank and changed. The server posts store data to them or
 * sends buyers there, so a save asks about them and then runs the spend gate,
 * as for a webhook.
 */
internal fun changedTargets(vararg fields: Pair<String, String>): List<String> =
    fields.mapNotNull { (typed, loaded) -> typed.trim().takeIf { it.isNotEmpty() && it != loaded.trim() } }

/** The hosts of [targets], for the review and the spend prompt. */
internal fun targetHosts(targets: List<String>): String =
    targets.map { hostOf(it) ?: it }.distinct().joinToString(", ")

/**
 * The review of [targets] from [changedTargets]: their hosts, with a warning
 * for http:// to the internet. The dialog closes before the spend gate, so
 * [onConfirm] must run the gate from the screen's scope.
 */
@Composable
fun TargetReviewDialog(
    targets: List<String>,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hosts = remember(targets) { targetHosts(targets) }
    val cleartext = remember(targets) { targets.any(::isPublicCleartext) }
    ConfirmDialog(
        title = "Send to $hosts?",
        message = message + if (cleartext) " An http:// address gets the data over the internet without encryption." else "",
        confirmLabel = if (cleartext) "Save anyway" else "Save",
        destructive = cleartext,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
