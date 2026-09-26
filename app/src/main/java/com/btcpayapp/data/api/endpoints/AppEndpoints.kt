package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
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

internal suspend fun BtcPayApi.createPointOfSaleApp(
    storeId: String,
    request: PointOfSaleAppRequest,
): PointOfSaleAppData = post("api/v1/stores/${storeId.pathSegment()}/apps/pos", body(request))

internal suspend fun BtcPayApi.updatePointOfSaleApp(
    appId: String,
    request: PointOfSaleAppRequest,
): PointOfSaleAppData = put("api/v1/apps/pos/${appId.pathSegment()}", body(request))

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

internal suspend fun BtcPayApi.createCrowdfundApp(
    storeId: String,
    request: CrowdfundAppRequest,
): CrowdfundAppData = post("api/v1/stores/${storeId.pathSegment()}/apps/crowdfund", body(request))

internal suspend fun BtcPayApi.updateCrowdfundApp(
    appId: String,
    request: CrowdfundAppRequest,
): CrowdfundAppData = put("api/v1/apps/crowdfund/${appId.pathSegment()}", body(request))

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
