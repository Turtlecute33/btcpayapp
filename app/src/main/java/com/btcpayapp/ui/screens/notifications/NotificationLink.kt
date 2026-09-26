package com.btcpayapp.ui.screens.notifications

import com.btcpayapp.ui.nav.InvoiceDetailRoute
import com.btcpayapp.ui.nav.PayoutsRoute
import com.btcpayapp.ui.nav.PullPaymentDetailRoute
import com.btcpayapp.ui.nav.PullPaymentsRoute
import com.btcpayapp.ui.nav.ServerUsersRoute
import com.btcpayapp.ui.nav.StoreListRoute
import com.btcpayapp.ui.nav.StoreUsersRoute
import java.net.URI

/**
 * Where a notification points, in this app's own terms.
 *
 * Greenfield hands back whatever the server's web UI would have linked to — a
 * path built by ASP.NET's link generator, or an absolute URL for the two cases
 * that genuinely live elsewhere. A client that opens all of them in a browser
 * sends the user out of the app to read something the app already has a screen
 * for, and out of a session the browser is not logged into.
 */
sealed interface NotificationTarget {

    /**
     * A destination in this app.
     *
     * [storeId] is the store the link names, when it names one. Everything
     * behind a store — an invoice, a payout, a pull payment — is fetched
     * through `/api/v1/stores/{storeId}/…`, so opening one of those screens
     * while a different store is active shows the wrong thing, or nothing.
     */
    data class InApp(val route: Any, val storeId: String? = null) : NotificationTarget

    /** Nothing here can show it: a release page, an invitation to accept. */
    data class Web(val url: String) : NotificationTarget

    /** No link, or one this app can neither open nor make absolute. */
    data object None : NotificationTarget
}

// The routes BTCPay's link generator produces for the six notification types it
// defines, plus the two older invoice forms that still turn up. Anchored at the
// start so that a path segment cannot be matched out of the middle of a longer
// one.
private val STORE_INVOICE = Regex("^/stores/([^/]+)/invoices/([^/?#]+)")
private val INVOICE = Regex("^/invoices/([^/?#]+)")
private val CHECKOUT = Regex("^/i/([^/?#]+)")
private val INVOICE_QUERY = Regex("[?&]invoiceId=([^&#]+)", RegexOption.IGNORE_CASE)
private val STORE_PULL_PAYMENT_PAYOUTS = Regex("^/stores/([^/]+)/pull-payments/([^/?#]+)/payouts")
private val STORE_PAYOUTS = Regex("^/stores/([^/]+)/payouts")
private val PULL_PAYMENT_PAYOUTS = Regex("^/pull-payments/([^/?#]+)/payouts")
private val STORE_PULL_PAYMENT = Regex("^/stores/([^/]+)/pull-payments/([^/?#]+)")
private val STORE_PULL_PAYMENTS = Regex("^/stores/([^/]+)/pull-payments/?($|[?#])")
private val PULL_PAYMENT = Regex("^/pull-payments/([^/?#]+)")
private val STORE_USERS = Regex("^/stores/([^/]+)/users")
private val SERVER_USER = Regex("^/server/users/([^/?#]+)")
private val STORES = Regex("^/stores/?($|[?#])")

/** BTCPay links invoices two ways, depending on which notification wrote them. */
internal fun invoiceIdFromLink(link: String): String? =
    CHECKOUT.find(link)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: INVOICE_QUERY.find(link)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

/**
 * Resolves a notification's link against the server it came from.
 *
 * Only a link on that same origin is read as a path into the app. A link
 * somewhere else is a link somewhere else — the release announcement is a
 * GitHub page — and sending one of those to an in-app screen on the strength of
 * a string prefix is how an open redirect is built.
 */
internal fun notificationTarget(link: String?, baseUrl: String?): NotificationTarget {
    val raw = link?.trim().orEmpty()
    if (raw.isEmpty()) return NotificationTarget.None

    val path = samePathOrNull(raw, baseUrl)
        ?: return absoluteLink(baseUrl, raw)?.let(NotificationTarget::Web) ?: NotificationTarget.None

    STORE_INVOICE.find(path)?.let { match ->
        return NotificationTarget.InApp(
            route = InvoiceDetailRoute(match.groupValues[2]),
            storeId = match.groupValues[1],
        )
    }
    INVOICE.find(path)?.let {
        return NotificationTarget.InApp(InvoiceDetailRoute(it.groupValues[1]))
    }
    invoiceIdFromLink(path)?.let {
        return NotificationTarget.InApp(InvoiceDetailRoute(it))
    }

    // The payout screens the app has are per store and unfiltered, so every
    // shape of payout link lands on the same list. Better a list holding the
    // payout than a browser holding the right filter.
    STORE_PULL_PAYMENT_PAYOUTS.find(path)?.let {
        return NotificationTarget.InApp(PayoutsRoute, storeId = it.groupValues[1])
    }
    STORE_PAYOUTS.find(path)?.let {
        return NotificationTarget.InApp(PayoutsRoute, storeId = it.groupValues[1])
    }
    PULL_PAYMENT_PAYOUTS.find(path)?.let { return NotificationTarget.InApp(PayoutsRoute) }

    STORE_PULL_PAYMENT.find(path)?.let { match ->
        return NotificationTarget.InApp(
            route = PullPaymentDetailRoute(match.groupValues[2]),
            storeId = match.groupValues[1],
        )
    }
    STORE_PULL_PAYMENTS.find(path)?.let {
        return NotificationTarget.InApp(PullPaymentsRoute, storeId = it.groupValues[1])
    }
    PULL_PAYMENT.find(path)?.let {
        return NotificationTarget.InApp(PullPaymentDetailRoute(it.groupValues[1]))
    }

    STORE_USERS.find(path)?.let {
        return NotificationTarget.InApp(StoreUsersRoute, storeId = it.groupValues[1])
    }
    // The app has the user list, not a page per user, and the list is where
    // approving one happens — which is the only reason this link is ever sent.
    SERVER_USER.find(path)?.let { return NotificationTarget.InApp(ServerUsersRoute) }
    STORES.find(path)?.let { return NotificationTarget.InApp(StoreListRoute) }

    return absoluteLink(baseUrl, raw)?.let(NotificationTarget::Web) ?: NotificationTarget.None
}

/**
 * The link as a path under [baseUrl], or null when it points somewhere else.
 *
 * Compared by origin rather than by string prefix: `https://pay.example.com.evil
 * .test/` starts with `https://pay.example.com` and is not the same server.
 * BTCPay can also be installed under a root path, so the base's own path is
 * taken off the front — `https://host/btcpay/invoices/x` has to match the same
 * rules as `https://host/invoices/x`.
 */
private fun samePathOrNull(link: String, baseUrl: String?): String? {
    val relative = !link.startsWith("http://", true) && !link.startsWith("https://", true)
    if (relative) return "/" + link.trimStart('/')

    val base = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val linkUri = runCatching { URI(link) }.getOrNull() ?: return null
    val baseUri = runCatching { URI(base) }.getOrNull() ?: return null
    if (!linkUri.scheme.equals(baseUri.scheme, ignoreCase = true)) return null
    if (!linkUri.host.equals(baseUri.host, ignoreCase = true)) return null
    if (linkUri.port != baseUri.port) return null

    val root = baseUri.rawPath.orEmpty().trimEnd('/')
    var path = linkUri.rawPath.orEmpty()
    if (root.isNotEmpty()) {
        if (!path.startsWith(root)) return null
        path = path.substring(root.length)
    }
    val query = linkUri.rawQuery?.let { "?$it" }.orEmpty()
    return "/" + path.trimStart('/') + query
}

/** A relative link made absolute against the server it came from. */
internal fun absoluteLink(baseUrl: String?, link: String): String? = when {
    link.startsWith("http://", true) || link.startsWith("https://", true) -> link
    baseUrl.isNullOrBlank() -> null
    else -> "${baseUrl.trimEnd('/')}/${link.trimStart('/')}"
}
