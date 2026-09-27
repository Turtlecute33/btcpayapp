package com.btcpayapp.data.session

/**
 * Decides whether an API key's grant covers a permission, the way the server does.
 *
 * A BTCPay policy also grants every policy below it, so an exact name match is
 * wrong in both directions. The most visible case: BTCPay 2.2 and 2.3
 * do not know the 2.4 wallet policies (`canviewwallet`, `cansigntransactions`,
 * …) and silently drop them from the grant, but guard the wallet routes with
 * `canmodifystoresettings`. The tree below maps that policy onto the 2.4 wallet
 * policies, so a store-admin key on 2.3 can send, as the server allows.
 *
 * The table is copied from BTCPay Server: `PolicyMap` in
 * `BTCPayServer.Client/Permissions.cs` (v2.2.0), plus the policy definitions in
 * `Hosting/BTCPayServerServices.cs` and `Plugins/Wallets/WalletsPlugin.cs`
 * (v2.4.4). Keep it in step with those files.
 *
 * This never grants anything; it only decides what the UI shows. When in doubt
 * it answers "covered": the worst case is a control the server then refuses,
 * which is better than hiding one that works.
 */
internal object Permissions {

    private const val UNRESTRICTED = "unrestricted"
    private const val STORE = "btcpay.store."
    private const val SERVER = "btcpay.server."
    private const val USER = "btcpay.user."

    /** Each policy and the policies it grants directly. */
    private val CHILDREN: Map<String, List<String>> = mapOf(
        "${STORE}canmodifystoresettings" to listOf(
            "${STORE}canmanagepullpayments",
            "${STORE}canmodifyinvoices",
            "${STORE}canviewstoresettings",
            "${STORE}webhooks.canmodifywebhooks",
            "${STORE}canmodifypaymentrequests",
            "${STORE}canmanagepayouts",
            "${STORE}canuselightningnode",
            "${STORE}cansendstoreemails",
            "${STORE}canmanagewallets",
        ),
        "${STORE}canmanagewallets" to listOf(
            "${STORE}canmanagewalletsettings",
            "${STORE}canmanagewallettransactions",
        ),
        "${STORE}canmanagewalletsettings" to listOf("${STORE}canviewwallet"),
        "${STORE}canmanagewallettransactions" to listOf(
            "${STORE}cancreatetransactions",
            "${STORE}cansigntransactions",
            "${STORE}canbroadcasttransactions",
            "${STORE}cancanceltransactions",
        ),
        "${STORE}cancreatetransactions" to listOf("${STORE}canviewwallet"),
        "${STORE}cansigntransactions" to listOf("${STORE}canviewwallet"),
        "${STORE}canbroadcasttransactions" to listOf("${STORE}canviewwallet"),
        "${STORE}cancanceltransactions" to listOf("${STORE}canviewwallet"),
        // Not canviewwallet: on 2.4 viewing the wallet is its own policy.
        "${STORE}canviewstoresettings" to listOf(
            "${STORE}canviewinvoices",
            "${STORE}canviewpaymentrequests",
            "${STORE}canviewreports",
            "${STORE}canviewpullpayments",
            "${STORE}canviewpayouts",
        ),
        "${STORE}canmodifyinvoices" to listOf(
            "${STORE}canviewinvoices",
            "${STORE}cancreateinvoice",
            "${STORE}cancreatelightninginvoice",
        ),
        "${STORE}canmodifypaymentrequests" to listOf("${STORE}canviewpaymentrequests"),
        "${STORE}canmanagepullpayments" to listOf(
            "${STORE}cancreatepullpayments",
            "${STORE}canarchivepullpayments",
        ),
        "${STORE}cancreatepullpayments" to listOf("${STORE}cancreatenonapprovedpullpayments"),
        "${STORE}cancreatenonapprovedpullpayments" to listOf("${STORE}canviewpullpayments"),
        "${STORE}canmanagepayouts" to listOf("${STORE}canviewpayouts"),
        "${STORE}canuselightningnode" to listOf(
            "${STORE}canviewlightninginvoice",
            "${STORE}cancreatelightninginvoice",
        ),
        "${STORE}cancreatelightninginvoice" to listOf("${STORE}canviewlightninginvoice"),
        "${SERVER}canmodifyserversettings" to listOf(
            "${SERVER}canuseinternallightningnode",
            "${SERVER}canmanageusers",
        ),
        "${SERVER}canuseinternallightningnode" to listOf(
            "${SERVER}canviewlightninginvoiceinternalnode",
            "${SERVER}cancreatelightninginvoiceinternalnode",
        ),
        // Not canviewusers: the server keeps that one separate.
        "${SERVER}canmanageusers" to listOf("${SERVER}cancreateuser"),
        "${USER}canmanagenotificationsforuser" to listOf("${USER}canviewnotificationsforuser"),
        "${USER}canmodifyprofile" to listOf("${USER}canviewprofile"),
    )

    /** Each policy with everything below it, itself included. Built once. */
    private val COVERED: Map<String, Set<String>> = CHILDREN.keys.associateWith { root ->
        buildSet {
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val policy = pending.removeLast()
                if (add(policy)) pending.addAll(CHILDREN[policy].orEmpty())
            }
        }
    }

    /**
     * True when [granted] covers [permission] for [storeId].
     *
     * Each entry is `policy` or `policy:scope`; an empty scope (`policy:`) is
     * unscoped. A scope must equal [storeId] when both are set. Null or empty
     * [granted] (never reported) and a grant with no `btcpay.` name at all (a
     * format this table does not know) both answer true: the server decides.
     */
    fun covers(granted: List<String>?, permission: String, storeId: String? = null): Boolean {
        if (granted.isNullOrEmpty()) return true
        val grants = granted.map { entry ->
            entry.substringBefore(':') to entry.substringAfter(':', "").takeIf(String::isNotBlank)
        }
        if (grants.any { (policy, _) -> policy == UNRESTRICTED }) return true
        if (grants.none { (policy, _) -> policy.startsWith("btcpay.") }) return true
        return grants.any { (policy, scope) ->
            (scope == null || storeId == null || scope == storeId) &&
                permission in (COVERED[policy] ?: setOf(policy))
        }
    }
}
