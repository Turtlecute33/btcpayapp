package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.ServerVersion
import com.btcpayapp.data.api.dto.AppData
import com.btcpayapp.data.api.dto.AppItem
import com.btcpayapp.data.api.dto.AppItemStats
import com.btcpayapp.data.api.dto.AppSalesStats
import com.btcpayapp.data.api.dto.CrowdfundAppData
import com.btcpayapp.data.api.dto.CrowdfundAppRequest
import com.btcpayapp.data.api.dto.PointOfSaleAppData
import com.btcpayapp.data.api.dto.PointOfSaleAppRequest
import com.btcpayapp.data.api.dto.WebhookData
import com.btcpayapp.data.api.dto.WebhookDeliveryData
import com.btcpayapp.data.api.dto.WebhookRequest
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// Apps
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.apps(): List<AppData> = get("api/v1/apps")

internal suspend fun BtcPayApi.storeApps(storeId: String): List<AppData> = get("api/v1/stores/${storeId.pathSegment()}/apps")

internal suspend fun BtcPayApi.app(appId: String): AppData = get("api/v1/apps/${appId.pathSegment()}")

internal suspend fun BtcPayApi.deleteApp(appId: String) {
    call("DELETE", "api/v1/apps/${appId.pathSegment()}")
}

// ---------------------------------------------------------------------------
// Point of Sale
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.pointOfSaleApp(appId: String): PointOfSaleAppData =
    get("api/v1/apps/pos/${appId.pathSegment()}")

/**
 * The same GET, kept as raw JSON. An app update replaces every setting, also
 * the ones this client does not model (per-item extension data, fields added in
 * newer releases). Editing the raw object and sending it back leaves those
 * untouched; a round trip through the typed [PointOfSaleAppData] drops them.
 */
internal suspend fun BtcPayApi.pointOfSaleAppJson(appId: String): JsonObject =
    get("api/v1/apps/pos/${appId.pathSegment()}")

internal suspend fun BtcPayApi.createPointOfSaleApp(
    storeId: String,
    request: PointOfSaleAppRequest,
): PointOfSaleAppData = post("api/v1/stores/${storeId.pathSegment()}/apps/pos", body(request))

/**
 * Sends [body] as it is. The server replaces the whole app from it, so callers
 * start from [pointOfSaleAppJson] and change only the keys the user edited. The
 * items are the exception: they are read as `items` but written as the
 * `template` string ([encodeItemTemplate]).
 */
internal suspend fun BtcPayApi.updatePointOfSaleApp(appId: String, body: JsonObject): PointOfSaleAppData =
    put("api/v1/apps/pos/${appId.pathSegment()}", body.toString())

/**
 * The server reads the item list as a JSON-encoded **string** in `template`
 * but returns it as a real array in `items`. This helper hides that asymmetry
 * so callers only ever deal with `List<AppItem>`.
 */
internal fun BtcPayApi.encodeItemTemplate(items: List<AppItem>): String = body(items)

// ---------------------------------------------------------------------------
// Crowdfund
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.crowdfundApp(appId: String): CrowdfundAppData =
    get("api/v1/apps/crowdfund/${appId.pathSegment()}")

/** The same GET as raw JSON, for the reason given on [pointOfSaleAppJson]. */
internal suspend fun BtcPayApi.crowdfundAppJson(appId: String): JsonObject =
    get("api/v1/apps/crowdfund/${appId.pathSegment()}")

internal suspend fun BtcPayApi.createCrowdfundApp(
    storeId: String,
    request: CrowdfundAppRequest,
): CrowdfundAppData = post("api/v1/stores/${storeId.pathSegment()}/apps/crowdfund", body(request))

/**
 * Sends [body] as it is; see [updatePointOfSaleApp]. The route exists only from
 * 2.3.7 ([ServerVersion.CROWDFUND_EDIT]); older servers
 * can create a crowdfund but not edit one.
 */
internal suspend fun BtcPayApi.updateCrowdfundApp(appId: String, body: JsonObject): CrowdfundAppData =
    put("api/v1/apps/crowdfund/${appId.pathSegment()}", body.toString())

// ---------------------------------------------------------------------------
// App statistics
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.appSales(appId: String, numberOfDays: Int = 7): AppSalesStats =
    get("api/v1/apps/${appId.pathSegment()}/sales", listOf("numberOfDays" to numberOfDays))

internal suspend fun BtcPayApi.appTopItems(appId: String, count: Int = 10, offset: Int = 0): List<AppItemStats> =
    get("api/v1/apps/${appId.pathSegment()}/top-items", listOf("count" to count, "offset" to offset))

// ---------------------------------------------------------------------------
// Webhooks
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.webhooks(storeId: String): List<WebhookData> =
    get("api/v1/stores/${storeId.pathSegment()}/webhooks")

internal suspend fun BtcPayApi.webhook(storeId: String, webhookId: String): WebhookData =
    get("api/v1/stores/${storeId.pathSegment()}/webhooks/${webhookId.pathSegment()}")

/** The response is the only time the signing secret is returned. */
internal suspend fun BtcPayApi.createWebhook(storeId: String, request: WebhookRequest): WebhookData =
    post("api/v1/stores/${storeId.pathSegment()}/webhooks", body(request))

internal suspend fun BtcPayApi.updateWebhook(
    storeId: String,
    webhookId: String,
    request: WebhookRequest,
): WebhookData = put("api/v1/stores/${storeId.pathSegment()}/webhooks/${webhookId.pathSegment()}", body(request))

internal suspend fun BtcPayApi.deleteWebhook(storeId: String, webhookId: String) {
    call("DELETE", "api/v1/stores/${storeId.pathSegment()}/webhooks/${webhookId.pathSegment()}")
}

internal suspend fun BtcPayApi.webhookDeliveries(
    storeId: String,
    webhookId: String,
    count: Int? = null,
): List<WebhookDeliveryData> = get(
    "api/v1/stores/${storeId.pathSegment()}/webhooks/${webhookId.pathSegment()}/deliveries",
    listOf("count" to count),
)

internal suspend fun BtcPayApi.redeliverWebhook(storeId: String, webhookId: String, deliveryId: String) {
    call(
        "POST",
        "api/v1/stores/${storeId.pathSegment()}/webhooks/${webhookId.pathSegment()}" +
            "/deliveries/${deliveryId.pathSegment()}/redeliver",
    )
}
