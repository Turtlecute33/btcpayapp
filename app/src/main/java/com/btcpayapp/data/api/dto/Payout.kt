@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

// ---------------------------------------------------------------------------
// Pull payments
// ---------------------------------------------------------------------------

@Serializable
data class PullPaymentData(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val currency: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    /** Days. Uppercase BOLT11 is the wire name. */
    val BOLT11Expiration: Int = 30,
    val autoApproveClaims: Boolean = false,
    val archived: Boolean = false,
    val viewLink: String? = null,
    /** Sent by the server even though the swagger schema omits these two. */
    val startsAt: Long? = null,
    val expiresAt: Long? = null,
)

@Serializable
data class CreatePullPaymentRequest(
    val name: String,
    val description: String? = null,
    val amount: BigDecimal,
    val currency: String,
    val BOLT11Expiration: Int? = null,
    val startsAt: Long? = null,
    val expiresAt: Long? = null,
    /** Payout method ids, e.g. `["BTC-CHAIN", "BTC-LN"]`. */
    val payoutMethods: List<String> = emptyList(),
    val autoApproveClaims: Boolean = false,
)

@Serializable
data class LnurlData(
    val lnurlBech32: String = "",
    val lnurlUri: String = "",
)

@Serializable
data class RegisterBoltcardRequest(
    val UID: String,
    val onExisting: String = "UpdateVersion",
)

@Serializable
data class BoltcardData(
    val LNURLW: String = "",
    val version: Int = 0,
    val K0: String = "",
    val K1: String = "",
    val K2: String = "",
    val K3: String = "",
    val K4: String = "",
)

// ---------------------------------------------------------------------------
// Payouts
// ---------------------------------------------------------------------------

@Serializable
data class PayoutData(
    val id: String = "",
    /** Guards approval against a concurrent edit; send it back verbatim. */
    val revision: Int = 0,
    val pullPaymentId: String? = null,
    val date: Long = 0,
    val destination: String = "",
    val originalCurrency: String = "",
    val originalAmount: BigDecimal = BigDecimal.ZERO,
    val payoutCurrency: String? = null,
    /** Only set once the payout has been approved and a rate locked in. */
    val payoutAmount: BigDecimal? = null,
    val payoutMethodId: String = "",
    val state: PayoutState = PayoutState.AwaitingApproval,
    val paymentProof: JsonObject? = null,
    val metadata: JsonObject? = null,
)

@Serializable(with = PayoutStateSerializer::class)
enum class PayoutState {
    AwaitingApproval,
    AwaitingPayment,
    InProgress,
    Completed,
    Cancelled,
    Unknown,
}

internal object PayoutStateSerializer : FallbackEnumSerializer<PayoutState>(
    "PayoutState",
    PayoutState.entries.toTypedArray(),
    PayoutState.Unknown,
)

@Serializable
data class CreatePayoutRequest(
    /** Address, BIP21 URI, BOLT11 invoice, or LNURL. */
    val destination: String,
    val amount: BigDecimal? = null,
    val payoutMethodId: String,
    val pullPaymentId: String? = null,
    val approved: Boolean? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class ApprovePayoutRequest(
    val revision: Int,
    val rateRule: String? = null,
)

@Serializable
data class MarkPayoutRequest(
    val state: PayoutState,
    val paymentProof: JsonObject? = null,
)

// ---------------------------------------------------------------------------
// Payout processors
// ---------------------------------------------------------------------------

@Serializable
data class PayoutProcessorData(
    val name: String = "",
    val friendlyName: String = "",
    val payoutMethods: List<String> = emptyList(),
)

/**
 * BTCPay's Lightning processor has only an interval and the instant flag
 * (LightningAutomatedPayoutSettings, v2.4.4). `cancelPayoutAfterFailures` is in
 * the swagger alone: the server drops it, so it is not modelled at all.
 */
@Serializable
data class LightningPayoutProcessorSettings(
    val payoutMethodId: String = "",
    val intervalSeconds: Int = 3600,
    val processNewPayoutsInstantly: Boolean = false,
)

@Serializable
data class UpdateLightningPayoutProcessorSettings(
    val intervalSeconds: Int,
    val processNewPayoutsInstantly: Boolean = false,
)

/**
 * The wire key is `feeBlockTarget` (the C# `FeeBlockTarget`); the swagger's
 * `feeTargetBlock` is a documentation error. Read under the wrong key, every
 * processor showed 1 block. The swagger spelling is still accepted on read in
 * case a server ever follows its own documentation.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OnChainPayoutProcessorSettings(
    val payoutMethodId: String = "",
    @SerialName("feeBlockTarget")
    @JsonNames("feeTargetBlock")
    val feeTargetBlock: Int = 1,
    val intervalSeconds: Int = 3600,
    val threshold: BigDecimal = BigDecimal.ZERO,
    val processNewPayoutsInstantly: Boolean = false,
)

/**
 * Not a partial update: the server replaces the whole settings blob. An omitted
 * [threshold] becomes 0 (every payout is swept, whatever minimum was set) and an
 * omitted [feeTargetBlock] becomes 1 (next-block fees on every batch). So both
 * are required here: callers send the loaded values when the user did not
 * change them.
 */
@Serializable
data class UpdateOnChainPayoutProcessorSettings(
    @SerialName("feeBlockTarget")
    val feeTargetBlock: Int,
    val intervalSeconds: Int,
    val threshold: BigDecimal,
    val processNewPayoutsInstantly: Boolean = false,
)
