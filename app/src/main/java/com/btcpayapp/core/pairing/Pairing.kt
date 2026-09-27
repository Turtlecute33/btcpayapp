package com.btcpayapp.core.pairing

import java.net.URLEncoder

/**
 * Builds the URL where a BTCPay instance makes an API key for this app.
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

    /** Added only when the user ticks "manage this server". */
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
     * The server's consent page, with [permissions] already ticked.
     *
     * `/api-keys/authorize` lives at the site root, not under `/api/v1`. The
     * link has no `redirect`, so the server has nowhere to send the new key
     * and shows it on its API keys page for the user to copy. Nothing on the
     * phone listens for a reply.
     *
     * `selectiveStores=true` adds a store picker, which lets someone grant
     * access to one store out of several; the key's permissions then carry a
     * `:storeId` suffix. `strict=false` lets the user untick permissions:
     * under `strict=true` BTCPay draws every requested box disabled, so the
     * user could only take the whole list or nothing.
     */
    fun authorizeUrl(baseUrl: String, permissions: List<String>): String {
        val query = buildList {
            permissions.forEach { add("permissions" to it) }
            add("applicationName" to APPLICATION_NAME)
            add("applicationIdentifier" to APPLICATION_IDENTIFIER)
            add("strict" to "false")
            add("selectiveStores" to "true")
        }.joinToString("&") { (key, value) -> "$key=${value.encode()}" }

        return "${baseUrl.trimEnd('/')}/api-keys/authorize?$query"
    }

    /** The checkout page for an invoice, for "open in browser" and sharing. */
    fun checkoutUrl(baseUrl: String, invoiceId: String): String =
        "${baseUrl.trimEnd('/')}/i/$invoiceId"

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
