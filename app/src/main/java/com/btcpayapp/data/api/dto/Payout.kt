@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
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

@Serializable
data class LightningPayoutProcessorSettings(
    val payoutMethodId: String = "",
    val intervalSeconds: Int = 3600,
    val cancelPayoutAfterFailures: Int? = null,
    val processNewPayoutsInstantly: Boolean = false,
)

@Serializable
data class UpdateLightningPayoutProcessorSettings(
    val intervalSeconds: Int,
    val cancelPayoutAfterFailures: Int? = null,
    val processNewPayoutsInstantly: Boolean = false,
)

@Serializable
data class OnChainPayoutProcessorSettings(
    val payoutMethodId: String = "",
    val feeTargetBlock: Int = 1,
    val intervalSeconds: Int = 3600,
    val threshold: BigDecimal = BigDecimal.ZERO,
    val processNewPayoutsInstantly: Boolean = false,
)

@Serializable
data class UpdateOnChainPayoutProcessorSettings(
    val feeTargetBlock: Int? = null,
    val intervalSeconds: Int,
    // Nullable, and null by default. `ApiJson` sets `encodeDefaults = true`, so
    // a non-null default would be transmitted on every write: editing only the
    // interval would send `threshold: "0"` and the processor would sweep every
    // payout regardless of the minimum the merchant had configured. With
    // `explicitNulls = false` a null is simply omitted, which is "leave it".
    val threshold: BigDecimal? = null,
    val processNewPayoutsInstantly: Boolean = false,
)
