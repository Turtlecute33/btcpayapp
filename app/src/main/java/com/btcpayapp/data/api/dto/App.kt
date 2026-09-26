@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonArray
import java.math.BigDecimal

@Serializable
data class AppData(
    val id: String = "",
    val appName: String = "",
    val storeId: String = "",
    val created: Long = 0,
    /** `PointOfSale` or `Crowdfund`, plus whatever plugins register. */
    val appType: String = "",
    val archived: Boolean = false,
)

// ---------------------------------------------------------------------------
// Point of Sale
// ---------------------------------------------------------------------------

@Serializable
data class PointOfSaleAppData(
    val id: String = "",
    val appName: String = "",
    val storeId: String = "",
    val created: Long = 0,
    val appType: String = "PointOfSale",
    val archived: Boolean = false,

    val title: String? = null,
    val description: String? = null,
    val defaultView: PosView = PosView.Static,
    val showItems: Boolean = false,
    val showCustomAmount: Boolean = false,
    val showDiscount: Boolean = false,
    val showSearch: Boolean = true,
    val showCategories: Boolean = true,
    val enableTips: Boolean = false,
    val currency: String? = null,
    val fixedAmountPayButtonText: String? = null,
    val customAmountPayButtonText: String? = null,
    val tipText: String? = null,
    val customTipPercentages: List<Int> = emptyList(),
    val notificationUrl: String? = null,
    val redirectUrl: String? = null,
    val redirectAutomatically: Boolean = false,
    val htmlLang: String? = null,
    val htmlMetaTags: String? = null,
    val formId: String? = null,

    /** Read side: a real array. The write side uses [PointOfSaleAppRequest.template]. */
    val items: List<AppItem> = emptyList(),
)

/**
 * Write side of a PoS app. Note the asymmetry with [PointOfSaleAppData]: the
 * server accepts the item list as `template`, a **string** containing serialised
 * JSON, but returns it as `items`, a real array.
 */
@Serializable
data class PointOfSaleAppRequest(
    val appName: String? = null,
    val title: String? = null,
    val description: String? = null,
    val defaultView: PosView? = null,
    val showItems: Boolean? = null,
    val showCustomAmount: Boolean? = null,
    val showDiscount: Boolean? = null,
    val showSearch: Boolean? = null,
    val showCategories: Boolean? = null,
    val enableTips: Boolean? = null,
    val currency: String? = null,
    val fixedAmountPayButtonText: String? = null,
    val customAmountPayButtonText: String? = null,
    val tipText: String? = null,
    val customTipPercentages: List<Int>? = null,
    val notificationUrl: String? = null,
    val redirectUrl: String? = null,
    val redirectAutomatically: Boolean? = null,
    val htmlLang: String? = null,
    val htmlMetaTags: String? = null,
    val formId: String? = null,
    /** JSON-encoded `List<AppItem>`. */
    val template: String? = null,
)

@Serializable(with = PosViewSerializer::class)
enum class PosView { Static, Cart, Light, Print, Unknown }

internal object PosViewSerializer : FallbackEnumSerializer<PosView>(
    "PosView",
    PosView.entries.toTypedArray(),
    PosView.Unknown,
)

@Serializable
data class AppItem(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    val image: String? = null,
    val price: BigDecimal? = null,
    val priceType: AppItemPriceType = AppItemPriceType.Fixed,
    val buyButtonText: String? = null,
    val inventory: Int? = null,
    val disabled: Boolean = false,
    /** Present on the wire, absent from the published schema. */
    val categories: List<String> = emptyList(),
    val taxRate: BigDecimal? = null,
)

@Serializable(with = AppItemPriceTypeSerializer::class)
enum class AppItemPriceType {
    Fixed,

    /** Customer may pay more than [AppItem.price], never less. */
    Minimum,

    /** Customer names the amount. */
    Topup,

    Unknown,
}

internal object AppItemPriceTypeSerializer : FallbackEnumSerializer<AppItemPriceType>(
    "AppItemPriceType",
    AppItemPriceType.entries.toTypedArray(),
    AppItemPriceType.Unknown,
)

// ---------------------------------------------------------------------------
// Crowdfund
// ---------------------------------------------------------------------------

@Serializable
data class CrowdfundAppData(
    val id: String = "",
    val appName: String = "",
    val storeId: String = "",
    val created: Long = 0,
    val appType: String = "Crowdfund",
    val archived: Boolean = false,

    val title: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
    val enforceTargetAmount: Boolean = false,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val targetCurrency: String? = null,
    val targetAmount: BigDecimal? = null,
    val mainImageUrl: String? = null,
    val notificationUrl: String? = null,
    val tagline: String? = null,
    val soundsEnabled: Boolean = false,
    val animationsEnabled: Boolean = false,
    val resetEveryAmount: Int? = null,
    val resetEvery: String? = null,
    val displayPerksValue: Boolean = false,
    val displayPerksRanking: Boolean = false,
    val sortPerksByPopularity: Boolean = true,
    val sounds: List<String> = emptyList(),
    val animationColors: List<String> = emptyList(),
    val htmlLang: String? = null,
    val htmlMetaTags: String? = null,
    val formId: String? = null,

    val perks: JsonArray? = null,
)

@Serializable
data class CrowdfundAppRequest(
    val appName: String? = null,
    val title: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val enforceTargetAmount: Boolean? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val targetCurrency: String? = null,
    val targetAmount: BigDecimal? = null,
    val mainImageUrl: String? = null,
    val notificationUrl: String? = null,
    val tagline: String? = null,
    val soundsEnabled: Boolean? = null,
    val animationsEnabled: Boolean? = null,
    val resetEveryAmount: Int? = null,
    val resetEvery: String? = null,
    val displayPerksValue: Boolean? = null,
    val displayPerksRanking: Boolean? = null,
    val sortPerksByPopularity: Boolean? = null,
    val sounds: List<String>? = null,
    val animationColors: List<String>? = null,
    val htmlLang: String? = null,
    val htmlMetaTags: String? = null,
    val formId: String? = null,
    /** JSON-encoded perk array. */
    val perksTemplate: String? = null,
)

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

@Serializable
data class AppSalesStats(
    val salesCount: Int = 0,
    val series: List<AppSalesSeriesPoint> = emptyList(),
)

@Serializable
data class AppSalesSeriesPoint(
    val date: Long = 0,
    val label: String = "",
    val salesCount: Int = 0,
)

@Serializable
data class AppItemStats(
    val itemCode: String = "",
    val title: String = "",
    val salesCount: Int = 0,
    val total: BigDecimal = BigDecimal.ZERO,
    val totalFormatted: String = "",
)
