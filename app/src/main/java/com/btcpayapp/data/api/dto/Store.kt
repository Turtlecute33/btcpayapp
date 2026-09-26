@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

@Serializable
data class StoreData(
    val id: String = "",
    val name: String = "",
    val website: String? = null,
    val supportUrl: String? = null,
    /** Absolute URL, or `fileid:{id}` referring to an uploaded file. */
    val logoUrl: String? = null,
    val cssUrl: String? = null,
    val paymentSoundUrl: String? = null,
    val brandColor: String? = null,
    val applyBrandColorToBackend: Boolean = false,

    val defaultCurrency: String = "USD",
    val additionalTrackedRates: List<String> = emptyList(),

    /** Seconds. Not to be confused with the invoice-level `expirationMinutes`. */
    val invoiceExpiration: Int = 900,
    /** Days. The server spells this with an uppercase BOLT11. */
    val refundBOLT11Expiration: Int = 30,
    /** Seconds. */
    val displayExpirationTimer: Int = 300,
    /** Seconds. */
    val monitoringExpiration: Int = 86_400,

    val speedPolicy: SpeedPolicy = SpeedPolicy.MediumSpeed,
    val lightningDescriptionTemplate: String? = null,
    /** A plain JSON number here, unlike most amounts. */
    val paymentTolerance: Double = 0.0,
    val archived: Boolean = false,
    val anyoneCanCreateInvoice: Boolean = false,
    val receipt: ReceiptOptions? = null,

    val lightningAmountInSatoshi: Boolean = false,
    val lightningPrivateRouteHints: Boolean = false,
    val onChainWithLnInvoiceFallback: Boolean = false,
    val allowZeroAmountInvoices: Boolean = false,

    val redirectAutomatically: Boolean = false,
    val showRecommendedFee: Boolean = true,
    val recommendedFeeBlockTarget: Int = 1,
    val defaultLang: String = "en",
    val htmlTitle: String? = null,
    val networkFeeMode: NetworkFeeMode = NetworkFeeMode.MultiplePaymentsOnly,
    val payJoinEnabled: Boolean = false,
    val autoDetectLanguage: Boolean = false,
    val showPayInWalletButton: Boolean = true,
    val showStoreHeader: Boolean = true,
    val celebratePayment: Boolean = true,
    val playSoundOnPayment: Boolean = false,
    val lazyPaymentMethods: Boolean = false,
    val defaultPaymentMethod: String? = null,
    /**
     * The swagger declares this as an object; the wire form is an array. Trust
     * the wire.
     */
    val paymentMethodCriteria: List<PaymentMethodCriteria> = emptyList(),
)

@Serializable
data class ReceiptOptions(
    val enabled: Boolean? = null,
    val showQR: Boolean? = null,
    val showPayments: Boolean? = null,
)

@Serializable
data class PaymentMethodCriteria(
    val paymentMethodId: String = "",
    val currencyCode: String = "USD",
    val amount: BigDecimal = BigDecimal.ZERO,
    val above: Boolean = false,
)

@Serializable(with = SpeedPolicySerializer::class)
enum class SpeedPolicy {
    /** 0 confirmations. */
    HighSpeed,

    /** 1 confirmation. */
    MediumSpeed,

    /** 6 confirmations. */
    LowSpeed,

    /** 2 confirmations. */
    LowMediumSpeed,

    Unknown,
    ;

    val confirmations: Int
        get() = when (this) {
            HighSpeed -> 0
            MediumSpeed -> 1
            LowMediumSpeed -> 2
            LowSpeed -> 6
            Unknown -> 1
        }
}

internal object SpeedPolicySerializer : FallbackEnumSerializer<SpeedPolicy>(
    "SpeedPolicy",
    SpeedPolicy.entries.toTypedArray(),
    SpeedPolicy.Unknown,
)

@Serializable(with = NetworkFeeModeSerializer::class)
enum class NetworkFeeMode { MultiplePaymentsOnly, Always, Never, Unknown }

internal object NetworkFeeModeSerializer : FallbackEnumSerializer<NetworkFeeMode>(
    "NetworkFeeMode",
    NetworkFeeMode.entries.toTypedArray(),
    NetworkFeeMode.Unknown,
)

// ---------------------------------------------------------------------------
// Payment methods
// ---------------------------------------------------------------------------

@Serializable
data class PaymentMethodData(
    val enabled: Boolean = false,
    val paymentMethodId: String = "",
    /** Only present when the request asked for `includeConfig=true`. */
    val config: JsonObject? = null,
)

@Serializable
data class UpdatePaymentMethodRequest(
    /** Null means "leave unchanged". */
    val enabled: Boolean? = null,
    val config: JsonObject? = null,
)

@Serializable
data class OnChainPaymentMethodConfig(
    val derivationScheme: String = "",
    val label: String? = null,
    val accountKeyPath: String? = null,
)

@Serializable
data class LightningPaymentMethodConfig(
    /** `"Internal Node"` selects the server's own node. */
    val connectionString: String = "",
)

@Serializable
data class LnurlPaymentMethodConfig(
    val useBech32Scheme: Boolean = true,
    val lud12Enabled: Boolean = false,
    val lud21Enabled: Boolean = false,
)

@Serializable
data class GenerateWalletRequest(
    val label: String? = null,
    val existingMnemonic: String? = null,
    val passphrase: String? = null,
    val accountNumber: Int = 0,
    /** Hot wallet. Required for in-app spending; keep false for watch-only. */
    val savePrivateKeys: Boolean = false,
    val wordList: String = "English",
    /** Serialised as a number, not a string. */
    val wordCount: Int = 12,
    val scriptPubKeyType: String = "Segwit",
)

@Serializable
data class GenerateWalletResponse(
    val enabled: Boolean = false,
    val paymentMethodId: String = "",
    /** Shown once, never persisted by this app. */
    val mnemonic: String? = null,
    val config: OnChainPaymentMethodConfig? = null,
)

@Serializable
data class WalletPreviewResponse(
    val addresses: List<WalletPreviewAddress> = emptyList(),
)

@Serializable
data class WalletPreviewAddress(
    val keyPath: String = "",
    val address: String = "",
)

// ---------------------------------------------------------------------------
// Store users, invitations, rates
// ---------------------------------------------------------------------------

@Serializable
data class StoreUserData(
    val id: String = "",
    val email: String = "",
    val roleId: String = "",
)

@Serializable
data class StoreUserRequest(
    /** User id or email. */
    val id: String,
    /** `Owner`, `Manager`, `Employee`, `Guest`, or a custom role id. */
    val storeRole: String,
    val requireInvitation: Boolean? = null,
)

@Serializable
data class AddStoreUserResult(
    val storeInvitation: StoreInvitationResult? = null,
)

@Serializable
data class StoreInvitationResult(
    val token: String = "",
    val link: String = "",
)

@Serializable
data class StoreInvitationData(
    val storeId: String = "",
    val storeName: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val roleId: String = "",
    val invitedByUserId: String? = null,
    /** ISO-8601 here, unlike the unix seconds used almost everywhere else. */
    val created: String? = null,
    val expiresAt: String? = null,
    val isExpired: Boolean = false,
    val isForCurrentUser: Boolean = false,
)

@Serializable
data class StoreRateResult(
    /** `"BTC_USD"`. */
    val currencyPair: String = "",
    val rate: BigDecimal = BigDecimal.ZERO,
    val errors: List<String> = emptyList(),
)

@Serializable
data class StoreRateConfiguration(
    /** Percent, 0..100. */
    val spread: String = "0",
    val preferredSource: String? = null,
    val isCustomScript: Boolean = false,
    val effectiveScript: String = "",
)
