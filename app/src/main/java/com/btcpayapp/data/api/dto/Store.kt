@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.DecimalTextSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonNames
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

/**
 * Only the two values the user chooses. The server merges the request over the
 * admin's default store template and every value sent wins, so a whole
 * [StoreData] of Kotlin defaults would override the template's speed policy,
 * expiry and tolerance.
 */
@Serializable
data class CreateStoreRequest(
    val name: String,
    val defaultCurrency: String,
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

/**
 * The **write** shape of an on-chain config (BTCPay's alternative config). The
 * server builds a new wallet from it, so it never reads back the same way; the
 * GET shape is [OnChainWalletConfig].
 */
@Serializable
data class OnChainPaymentMethodConfig(
    val derivationScheme: String = "",
    val label: String? = null,
    val accountKeyPath: String? = null,
)

/**
 * The **read** shape of an on-chain config: BTCPay's `DerivationSchemeSettings`,
 * as returned by `GET .../payment-methods/{id}?includeConfig=true`. It has no
 * `derivationScheme` key, so reading it as [OnChainPaymentMethodConfig] shows a
 * configured wallet as empty. One [AccountKeySettings] per signer; more than one
 * is a multisig.
 */
@Serializable
data class OnChainWalletConfig(
    val accountDerivation: String = "",
    val label: String? = null,
    /** The server holds the keys and can sign (in-app send, automated payouts). */
    val isHotWallet: Boolean = false,
    /** How the wallet was set up, e.g. `NBXplorer`. */
    val source: String? = null,
    val accountKeySettings: List<AccountKeySettings> = emptyList(),
)

@Serializable
data class AccountKeySettings(
    val rootFingerprint: String? = null,
    val accountKeyPath: String? = null,
    val accountKey: String? = null,
)

@Serializable
data class LightningPaymentMethodConfig(
    /** `"Internal Node"` selects the server's own node. */
    val connectionString: String = "",
    /** Set by the GET, instead of [connectionString], when the store uses the server's internal node. */
    val internalNodeRef: String? = null,
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

/**
 * The role key changed: `storeRole` up to 2.4.3, `roleId` from 2.4.4. Both are
 * read, or every row on an older server shows no role.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StoreUserData(
    val id: String = "",
    val email: String = "",
    @JsonNames("storeRole")
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
    /**
     * Percent, 0..100, as plain decimal text. The server sends a JSON number
     * (a bare C# `decimal`, whatever the swagger says), which a plain `String`
     * refuses under `isLenient = false`, so the whole screen failed to load.
     */
    @Serializable(with = DecimalTextSerializer::class)
    val spread: String = "0",
    val preferredSource: String? = null,
    val isCustomScript: Boolean = false,
    val effectiveScript: String = "",
)

/**
 * The body for a rates update or preview. The GET fills `effectiveScript` even
 * when no custom script is in use, and the server's
 * `ValidateAndSanitizeConfiguration` rejects a non-empty `effectiveScript`
 * without custom scripting and a `preferredSource` with it. So echoing the
 * loaded object back failed every save; this keeps only what the chosen mode
 * reads.
 */
internal fun StoreRateConfiguration.forWrite(): StoreRateConfiguration = copy(
    effectiveScript = if (isCustomScript) effectiveScript else "",
    preferredSource = if (isCustomScript) null else preferredSource,
)
