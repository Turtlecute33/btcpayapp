package com.btcpayapp.core.pairing

import java.net.URLEncoder

/**
 * Builds the URL that asks a BTCPay instance to mint an API key for this app.
 */
object Pairing {

    /**
     * Shown on the server's own authorize page. Equal to the launcher label
     * (`app_name` in `res/values/strings.xml`), so the consent page names the
     * app the user sees on the home screen. Change both together.
     */
    const val APPLICATION_NAME = "BtcPayServer"
    const val APPLICATION_IDENTIFIER = "com.btcpayapp"

    /**
     * What the app asks for by default.
     *
     * Deliberately not `unrestricted`, but still store-admin: it can change
     * store settings and where the store receives funds, sign and broadcast
     * on-chain, and pay from the store's Lightning node. It does not modify
     * server settings or manage users; server administration is an opt-in
     * extra set below. [POINT_OF_SALE_PERMISSIONS] is the least-privilege set.
     *
     * `strict=false` is sent alongside these, so the user can untick any of
     * them on the consent page.
     */
    val DEFAULT_PERMISSIONS: List<String> = listOf(
        "btcpay.store.canviewstoresettings",
        "btcpay.store.canmodifystoresettings",
        "btcpay.store.canviewinvoices",
        "btcpay.store.cancreateinvoice",
        "btcpay.store.canmodifyinvoices",
        "btcpay.store.canviewpaymentrequests",
        "btcpay.store.canmodifypaymentrequests",
        "btcpay.store.canviewpullpayments",
        "btcpay.store.canmanagepullpayments",
        "btcpay.store.canviewpayouts",
        "btcpay.store.canmanagepayouts",
        "btcpay.store.canviewwallet",
        "btcpay.store.canmanagewallettransactions",
        "btcpay.store.cancreatetransactions",
        "btcpay.store.cansigntransactions",
        "btcpay.store.canbroadcasttransactions",
        "btcpay.store.canuselightningnode",
        "btcpay.store.canviewlightninginvoice",
        "btcpay.store.cancreatelightninginvoice",
        "btcpay.store.webhooks.canmodifywebhooks",
        "btcpay.user.canviewprofile",
        "btcpay.user.canviewnotificationsforuser",
        "btcpay.user.canmanagenotificationsforuser",
    )

    /**
     * Enough to take payments and nothing more: view and create invoices
     * (on-chain and Lightning), read the store settings those need, the
     * profile, and the user's notifications, which it may mark as seen or
     * delete (the notifications screen does both, and needs the manage
     * permission for it). No spend, payout, webhook or store configuration
     * power, so a lost till phone cannot move funds or change where the store
     * is paid.
     */
    val POINT_OF_SALE_PERMISSIONS: List<String> = listOf(
        "btcpay.store.canviewinvoices",
        "btcpay.store.cancreateinvoice",
        "btcpay.store.canviewstoresettings",
        "btcpay.store.canviewlightninginvoice",
        "btcpay.store.cancreatelightninginvoice",
        "btcpay.user.canviewprofile",
        "btcpay.user.canviewnotificationsforuser",
        "btcpay.user.canmanagenotificationsforuser",
    )

    /** Added only when the user ticks "manage this server" during pairing. */
    val SERVER_ADMIN_PERMISSIONS: List<String> = listOf(
        "btcpay.server.canmodifyserversettings",
        "btcpay.server.canviewusers",
        "btcpay.server.canmanageusers",
        "btcpay.server.canuseinternallightningnode",
        "btcpay.server.canviewlightninginvoiceinternalnode",
        "btcpay.server.cancreatelightninginvoiceinternalnode",
    )

    /** Read-only subset, for someone who only wants to watch takings. */
    val READ_ONLY_PERMISSIONS: List<String> = listOf(
        "btcpay.store.canviewstoresettings",
        "btcpay.store.canviewinvoices",
        "btcpay.store.canviewpaymentrequests",
        "btcpay.store.canviewpullpayments",
        "btcpay.store.canviewpayouts",
        "btcpay.store.canviewwallet",
        "btcpay.store.canviewlightninginvoice",
        "btcpay.user.canviewprofile",
        "btcpay.user.canviewnotificationsforuser",
    )

    /**
     * `/api-keys/authorize` lives at the site root, not under `/api/v1`.
     *
     * [selectiveStores] adds a store picker to the consent screen, which is what
     * lets someone grant access to one store out of several. The returned
     * permissions then carry a `:storeId` suffix.
     *
     * [strict] = false (the default) lets the user untick permissions on the
     * consent page. BTCPay draws every requested box disabled under
     * `strict=true`, so the user could only take the whole list or nothing.
     */
    fun authorizeUrl(
        baseUrl: String,
        redirectUri: String,
        permissions: List<String>,
        selectiveStores: Boolean = true,
        strict: Boolean = false,
    ): String {
        val query = buildList {
            permissions.forEach { add("permissions" to it) }
            add("applicationName" to APPLICATION_NAME)
            add("applicationIdentifier" to APPLICATION_IDENTIFIER)
            add("strict" to strict.toString())
            add("selectiveStores" to selectiveStores.toString())
            add("redirect" to redirectUri)
        }.joinToString("&") { (key, value) -> "$key=${value.encode()}" }

        return "${baseUrl.trimEnd('/')}/api-keys/authorize?$query"
    }

    /**
     * Wraps [authorizeUrl] in a one-time login so the user does not have to type
     * their password in the browser.
     *
     * A login-code QR from BTCPay is valid for 60 seconds and bypasses 2FA by
     * design, so it is treated as a short-lived bearer token: used immediately,
     * never stored, never logged.
     */
    fun loginThenAuthorizeUrl(baseUrl: String, loginCode: String, authorizeUrl: String): String {
        val returnUrl = authorizeUrl.removePrefix(baseUrl.trimEnd('/'))
        return "${baseUrl.trimEnd('/')}/login?LoginCode=${loginCode.encode()}&returnUrl=${returnUrl.encode()}"
    }

    /**
     * The same consent page as [authorizeUrl], with no redirect.
     *
     * This is the paste-a-key fallback. Without a `redirect` the server has
     * nowhere to POST the grant, so it shows the new key on the page for the
     * user to copy — which is the whole point: the permissions arrive already
     * ticked, so nobody has to reproduce a 23-box list by hand on a phone.
     * Sent with `strict=false`, like [authorizeUrl], so the user can untick.
     */
    fun manualAuthorizeUrl(baseUrl: String, permissions: List<String>): String {
        val query = buildList {
            permissions.forEach { add("permissions" to it) }
            add("applicationName" to APPLICATION_NAME)
            add("applicationIdentifier" to APPLICATION_IDENTIFIER)
            add("strict" to "false")
            add("selectiveStores" to "true")
        }.joinToString("&") { (key, value) -> "$key=${value.encode()}" }

        return "${baseUrl.trimEnd('/')}/api-keys/authorize?$query"
    }

    /** The plain key list, for someone who would rather create one by hand. */
    fun manualApiKeyUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/account/apikeys"

    /** The checkout page for an invoice, for "open in browser" and sharing. */
    fun checkoutUrl(baseUrl: String, invoiceId: String): String =
        "${baseUrl.trimEnd('/')}/i/$invoiceId"

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
