package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.dto.ConnectToNodeRequest
import com.btcpayapp.data.api.dto.CreateLightningInvoiceRequest
import com.btcpayapp.data.api.dto.HistogramData
import com.btcpayapp.data.api.dto.LightningAddressData
import com.btcpayapp.data.api.dto.LightningBalanceData
import com.btcpayapp.data.api.dto.LightningChannelData
import com.btcpayapp.data.api.dto.LightningInvoiceData
import com.btcpayapp.data.api.dto.LightningNodeInfo
import com.btcpayapp.data.api.dto.LightningPaymentData
import com.btcpayapp.data.api.dto.OpenChannelRequest
import com.btcpayapp.data.api.dto.PayLightningInvoiceRequest

/**
 * The Lightning surface exists twice with identical shapes: once scoped to a
 * store's configured node, and once for the server's internal node. [LightningScope]
 * picks the prefix so every call below is written once.
 *
 * Note `cryptoCode` here is a plain code (`BTC`), not a payment method id.
 */
sealed interface LightningScope {

    fun path(cryptoCode: String): String

    data class Store(val storeId: String) : LightningScope {
        override fun path(cryptoCode: String) = "api/v1/stores/${storeId.pathSegment()}/lightning/${cryptoCode.pathSegment()}"
    }

    data object Server : LightningScope {
        override fun path(cryptoCode: String) = "api/v1/server/lightning/${cryptoCode.pathSegment()}"
    }
}

internal suspend fun BtcPayApi.lightningNodeInfo(scope: LightningScope, cryptoCode: String = "BTC"): LightningNodeInfo =
    get("${scope.path(cryptoCode)}/info")

internal suspend fun BtcPayApi.lightningBalance(scope: LightningScope, cryptoCode: String = "BTC"): LightningBalanceData =
    get("${scope.path(cryptoCode)}/balance")

internal suspend fun BtcPayApi.lightningHistogram(scope: LightningScope, cryptoCode: String = "BTC"): HistogramData =
    get("${scope.path(cryptoCode)}/histogram")

/** Returns a bare JSON string, so it is decoded as one. */
internal suspend fun BtcPayApi.lightningDepositAddress(scope: LightningScope, cryptoCode: String = "BTC"): String =
    post("${scope.path(cryptoCode)}/address")

internal suspend fun BtcPayApi.connectToLightningNode(
    scope: LightningScope,
    nodeUri: String,
    cryptoCode: String = "BTC",
) {
    call("POST", "${scope.path(cryptoCode)}/connect", body(ConnectToNodeRequest(nodeUri)))
}

internal suspend fun BtcPayApi.lightningChannels(
    scope: LightningScope,
    cryptoCode: String = "BTC",
): List<LightningChannelData> = get("${scope.path(cryptoCode)}/channels")

internal suspend fun BtcPayApi.openLightningChannel(
    scope: LightningScope,
    request: OpenChannelRequest,
    cryptoCode: String = "BTC",
) {
    call("POST", "${scope.path(cryptoCode)}/channels", body(request))
}

/**
 * [offsetIndex] is not a page cursor. BTCPay passes it to the node as is: LND
 * reads an `add_index`, CLN a pay index. Without it LND returns its **oldest**
 * invoices and CLN its whole list, so this is no source of recent history.
 */
internal suspend fun BtcPayApi.lightningInvoices(
    scope: LightningScope,
    pendingOnly: Boolean? = null,
    offsetIndex: Long? = null,
    cryptoCode: String = "BTC",
): List<LightningInvoiceData> = get(
    "${scope.path(cryptoCode)}/invoices",
    listOf("pendingOnly" to pendingOnly, "offsetIndex" to offsetIndex),
)

internal suspend fun BtcPayApi.lightningInvoice(
    scope: LightningScope,
    id: String,
    cryptoCode: String = "BTC",
): LightningInvoiceData = get("${scope.path(cryptoCode)}/invoices/${id.pathSegment()}")

internal suspend fun BtcPayApi.createLightningInvoice(
    scope: LightningScope,
    request: CreateLightningInvoiceRequest,
    cryptoCode: String = "BTC",
): LightningInvoiceData = post("${scope.path(cryptoCode)}/invoices", body(request))

/**
 * The route is `/invoices/pay`; there is no bare `/pay`.
 *
 * The server holds its answer for up to `sendTimeout` seconds (30 when unset)
 * while the node tries routes. With the account's usual 30 s read timeout the
 * socket gave up first, and a payment still in flight was shown as failed. So
 * this one call waits longer ([payReadTimeoutMs]).
 */
internal suspend fun BtcPayApi.payLightningInvoice(
    scope: LightningScope,
    request: PayLightningInvoiceRequest,
    cryptoCode: String = "BTC",
): LightningPaymentData = withReadTimeout(payReadTimeoutMs(request.sendTimeout))
    .post("${scope.path(cryptoCode)}/invoices/pay", body(request))

/**
 * `sendTimeout` (BTCPay's default of 30 s when unset) plus 30 s for the server's
 * status lookup and the trip back. Capped at a day, so a typed value cannot
 * overflow the millis.
 */
private fun payReadTimeoutMs(sendTimeoutSeconds: Int?): Int =
    ((sendTimeoutSeconds ?: 30).coerceIn(0, 86_400) + 30) * 1000

/**
 * [offsetIndex] is not a page cursor: LND and CLN read it as a `createdAt`
 * filter in milliseconds (payments at or after that time). A list size sent here
 * matches every payment, so paging with it repeats the whole history.
 */
internal suspend fun BtcPayApi.lightningPayments(
    scope: LightningScope,
    includePending: Boolean? = null,
    offsetIndex: Long? = null,
    cryptoCode: String = "BTC",
): List<LightningPaymentData> = get(
    "${scope.path(cryptoCode)}/payments",
    listOf("includePending" to includePending, "offsetIndex" to offsetIndex),
)

internal suspend fun BtcPayApi.lightningPayment(
    scope: LightningScope,
    paymentHash: String,
    cryptoCode: String = "BTC",
): LightningPaymentData = get("${scope.path(cryptoCode)}/payments/${paymentHash.pathSegment()}")

// ---------------------------------------------------------------------------
// Lightning addresses (store-scoped only)
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.lightningAddresses(storeId: String): List<LightningAddressData> =
    get("api/v1/stores/${storeId.pathSegment()}/lightning-addresses")

internal suspend fun BtcPayApi.upsertLightningAddress(
    storeId: String,
    address: LightningAddressData,
): LightningAddressData = post(
    "api/v1/stores/${storeId.pathSegment()}/lightning-addresses/${address.username.pathSegment()}",
    body(address),
)

internal suspend fun BtcPayApi.deleteLightningAddress(storeId: String, username: String) {
    call("DELETE", "api/v1/stores/${storeId.pathSegment()}/lightning-addresses/${username.pathSegment()}")
}
