@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.DecimalTextSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Lightning amounts are **millisatoshi**, and the server sends them as JSON
 * strings. They are kept as `String` here and converted at the edge by
 * `Msat.kt`, which is deliberate: a msat value can exceed what a Double holds
 * without loss, and silently rounding someone's balance is not acceptable.
 */
@Serializable
data class LightningNodeInfo(
    val nodeURIs: List<String> = emptyList(),
    val blockHeight: Int = 0,
    val alias: String? = null,
    val color: String? = null,
    val version: String? = null,
    val peersCount: Int? = null,
    val activeChannelsCount: Int? = null,
    val inactiveChannelsCount: Int? = null,
    val pendingChannelsCount: Int? = null,
)

@Serializable
data class LightningBalanceData(
    val onchain: LightningOnchainBalance? = null,
    val offchain: LightningOffchainBalance? = null,
)

/** Satoshi strings. */
@Serializable
data class LightningOnchainBalance(
    val confirmed: String? = null,
    val unconfirmed: String? = null,
    val reserved: String? = null,
)

/** Millisatoshi strings. */
@Serializable
data class LightningOffchainBalance(
    val opening: String? = null,
    val local: String? = null,
    val remote: String? = null,
    val closing: String? = null,
)

@Serializable
data class LightningChannelData(
    val remoteNode: String = "",
    val isPublic: Boolean = false,
    val isActive: Boolean = false,
    /** msat. */
    val capacity: String = "0",
    /** msat. */
    val localBalance: String = "0",
    val channelPoint: String? = null,
)

@Serializable
data class ConnectToNodeRequest(
    /** `pubkey@host:port` */
    val nodeURI: String,
)

@Serializable
data class OpenChannelRequest(
    val nodeURI: String,
    /** Satoshi. */
    val channelAmount: String,
    /** sat/vB. */
    val feeRate: Double,
)

@Serializable
data class LightningInvoiceData(
    val id: String = "",
    val status: LightningInvoiceStatus = LightningInvoiceStatus.Unpaid,
    /** Uppercase on the wire. */
    val BOLT11: String = "",
    val paymentHash: String = "",
    val preimage: String? = null,
    val paidAt: Long? = null,
    val expiresAt: Long = 0,
    /** msat. */
    val amount: String = "0",
    /** msat. */
    val amountReceived: String? = null,
    val customRecords: Map<String, String>? = null,
)

@Serializable(with = LightningInvoiceStatusSerializer::class)
enum class LightningInvoiceStatus { Unpaid, Paid, Expired, Unknown }

internal object LightningInvoiceStatusSerializer : FallbackEnumSerializer<LightningInvoiceStatus>(
    "LightningInvoiceStatus",
    LightningInvoiceStatus.entries.toTypedArray(),
    LightningInvoiceStatus.Unknown,
)

@Serializable
data class CreateLightningInvoiceRequest(
    /** Millisatoshi, as a string. */
    val amount: String,
    val description: String? = null,
    val descriptionHashOnly: Boolean = false,
    /** Seconds. */
    val expiry: Int = 3600,
    val privateRouteHints: Boolean = false,
)

@Serializable
data class LightningPaymentData(
    val id: String = "",
    val status: LightningPaymentStatus = LightningPaymentStatus.Unknown,
    val BOLT11: String = "",
    val paymentHash: String = "",
    val preimage: String? = null,
    val createdAt: Long? = null,
    /** msat. */
    val totalAmount: String? = null,
    /** msat. */
    val feeAmount: String? = null,
)

@Serializable(with = LightningPaymentStatusSerializer::class)
enum class LightningPaymentStatus { Unknown, Pending, Complete, Failed }

internal object LightningPaymentStatusSerializer : FallbackEnumSerializer<LightningPaymentStatus>(
    "LightningPaymentStatus",
    LightningPaymentStatus.entries.toTypedArray(),
    LightningPaymentStatus.Unknown,
)

@Serializable
data class PayLightningInvoiceRequest(
    val BOLT11: String,
    /** msat. Only meaningful for a zero-amount invoice. */
    val amount: String? = null,
    /** e.g. `"6.15"`. */
    val maxFeePercent: String? = null,
    /** Satoshi, e.g. `"21"`. */
    val maxFeeFlat: String? = null,
    /** Seconds. */
    val sendTimeout: Int? = null,
)

@Serializable
data class LightningAddressData(
    val username: String = "",
    val currencyCode: String? = null,
    /**
     * Satoshi, as plain decimal text. The server sends JSON numbers here (a bare
     * C# `decimal?`, whatever the swagger says), which a plain `String` refuses
     * under `isLenient = false`, so one address with limits failed the whole list.
     */
    @Serializable(with = DecimalTextSerializer::class)
    val min: String? = null,
    @Serializable(with = DecimalTextSerializer::class)
    val max: String? = null,
    val invoiceMetadata: kotlinx.serialization.json.JsonObject? = null,
)
