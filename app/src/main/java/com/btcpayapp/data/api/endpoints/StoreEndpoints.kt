package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.ServerVersion
import com.btcpayapp.data.api.dto.AddStoreUserResult
import com.btcpayapp.data.api.dto.CreateStoreRequest
import com.btcpayapp.data.api.dto.EmailSettingsData
import com.btcpayapp.data.api.dto.GenerateWalletRequest
import com.btcpayapp.data.api.dto.GenerateWalletResponse
import com.btcpayapp.data.api.dto.PaymentMethodData
import com.btcpayapp.data.api.dto.RoleData
import com.btcpayapp.data.api.dto.SendEmailRequest
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.dto.StoreInvitationData
import com.btcpayapp.data.api.dto.StoreRateConfiguration
import com.btcpayapp.data.api.dto.StoreRateResult
import com.btcpayapp.data.api.dto.StoreUserData
import com.btcpayapp.data.api.dto.StoreUserRequest
import com.btcpayapp.data.api.dto.UpdateEmailSettingsRequest
import com.btcpayapp.data.api.dto.UpdatePaymentMethodRequest
import com.btcpayapp.data.api.dto.WalletPreviewResponse
import com.btcpayapp.data.api.dto.forWrite
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// Stores
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.stores(): List<StoreData> = get("api/v1/stores")

internal suspend fun BtcPayApi.store(storeId: String): StoreData = get("api/v1/stores/${storeId.pathSegment()}")

/** Sends only name and currency, so the admin's default store template fills the rest. */
internal suspend fun BtcPayApi.createStore(request: CreateStoreRequest): StoreData =
    post("api/v1/stores", body(request))

internal suspend fun BtcPayApi.updateStore(storeId: String, store: StoreData): StoreData =
    put("api/v1/stores/${storeId.pathSegment()}", body(store))

internal suspend fun BtcPayApi.deleteStore(storeId: String) {
    call("DELETE", "api/v1/stores/${storeId.pathSegment()}")
}

// ---------------------------------------------------------------------------
// Payment methods
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.paymentMethods(
    storeId: String,
    onlyEnabled: Boolean? = null,
    includeConfig: Boolean = false,
): List<PaymentMethodData> = get(
    "api/v1/stores/${storeId.pathSegment()}/payment-methods",
    listOf("onlyEnabled" to onlyEnabled, "includeConfig" to includeConfig.takeIf { it }),
)

internal suspend fun BtcPayApi.paymentMethod(
    storeId: String,
    paymentMethodId: String,
    includeConfig: Boolean = false,
): PaymentMethodData = get(
    "api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}",
    listOf("includeConfig" to includeConfig.takeIf { it }),
)

/**
 * There is no separate enable/disable route — both are a partial update. A null
 * field means "leave unchanged", which is why the request type is all-nullable.
 */
internal suspend fun BtcPayApi.updatePaymentMethod(
    storeId: String,
    paymentMethodId: String,
    request: UpdatePaymentMethodRequest,
): PaymentMethodData =
    put("api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}", body(request))

internal suspend fun BtcPayApi.deletePaymentMethod(storeId: String, paymentMethodId: String) {
    call("DELETE", "api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}")
}

/**
 * Generates a new on-chain wallet server-side. The mnemonic comes back exactly
 * once and this app shows it without storing it — writing someone's seed into
 * app storage would defeat the point of a hardware-backed vault.
 */
internal suspend fun BtcPayApi.generateWallet(
    storeId: String,
    paymentMethodId: String,
    request: GenerateWalletRequest,
): GenerateWalletResponse = post(
    "api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}/wallet/generate",
    body(request),
)

/** The first addresses of the **saved** wallet. */
internal suspend fun BtcPayApi.previewWallet(
    storeId: String,
    paymentMethodId: String,
    offset: Int = 0,
    count: Int = 10,
): WalletPreviewResponse = get(
    "api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}/wallet/preview",
    listOf("offset" to offset, "count" to count),
)

/**
 * The first addresses of a wallet that is **not saved yet**. [config] is the
 * write JSON the update would send, so the operator can compare these addresses
 * with the signing device before receive payments are sent to a new wallet.
 */
internal suspend fun BtcPayApi.previewProposedWallet(
    storeId: String,
    paymentMethodId: String,
    config: JsonObject,
    count: Int = 5,
): WalletPreviewResponse = post(
    "api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}/wallet/preview",
    body(UpdatePaymentMethodRequest(config = config)),
    listOf("offset" to 0, "count" to count),
)

// ---------------------------------------------------------------------------
// Store users, roles, invitations
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.storeUsers(storeId: String): List<StoreUserData> =
    get("api/v1/stores/${storeId.pathSegment()}/users")

internal suspend fun BtcPayApi.addStoreUser(storeId: String, request: StoreUserRequest): AddStoreUserResult =
    post("api/v1/stores/${storeId.pathSegment()}/users", body(request))

internal suspend fun BtcPayApi.updateStoreUser(storeId: String, request: StoreUserRequest) {
    call("PUT", "api/v1/stores/${storeId.pathSegment()}/users", body(request))
}

internal suspend fun BtcPayApi.removeStoreUser(storeId: String, idOrEmail: String) {
    call("DELETE", "api/v1/stores/${storeId.pathSegment()}/users/${idOrEmail.pathSegment()}")
}

/** From 2.4.4 only ([ServerVersion.STORE_INVITATIONS]); older servers answer 404. */
internal suspend fun BtcPayApi.storeInvitations(storeId: String): List<StoreInvitationData> =
    get("api/v1/stores/${storeId.pathSegment()}/users/invitations")

internal suspend fun BtcPayApi.storeRoles(storeId: String): List<RoleData> =
    get("api/v1/stores/${storeId.pathSegment()}/roles")

// ---------------------------------------------------------------------------
// Rates
// ---------------------------------------------------------------------------

/** [currencyPairs] look like `BTC_USD`. */
internal suspend fun BtcPayApi.rates(storeId: String, currencyPairs: List<String>): List<StoreRateResult> =
    get("api/v1/stores/${storeId.pathSegment()}/rates", listOf("currencyPair" to currencyPairs))

/**
 * [rateSource] is `primary` or `fallback`. Both paths exist from 2.2.0
 * ([ServerVersion.MINIMUM]); before that only the unsuffixed route did.
 */
internal suspend fun BtcPayApi.rateConfiguration(storeId: String, rateSource: String = "primary"): StoreRateConfiguration =
    get("api/v1/stores/${storeId.pathSegment()}/rates/configuration/$rateSource")

/** Sends [StoreRateConfiguration.forWrite], never the loaded object as it is. */
internal suspend fun BtcPayApi.updateRateConfiguration(
    storeId: String,
    configuration: StoreRateConfiguration,
    rateSource: String = "primary",
): StoreRateConfiguration =
    put("api/v1/stores/${storeId.pathSegment()}/rates/configuration/$rateSource", body(configuration.forWrite()))

/** Validated like an update, so the body is [StoreRateConfiguration.forWrite] too. */
internal suspend fun BtcPayApi.previewRateConfiguration(
    storeId: String,
    configuration: StoreRateConfiguration,
    currencyPairs: List<String>,
): List<StoreRateResult> = post(
    "api/v1/stores/${storeId.pathSegment()}/rates/configuration/preview",
    body(configuration.forWrite()),
    listOf("currencyPair" to currencyPairs),
)

// ---------------------------------------------------------------------------
// Store email
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.storeEmailSettings(storeId: String): EmailSettingsData =
    get("api/v1/stores/${storeId.pathSegment()}/email")

internal suspend fun BtcPayApi.updateStoreEmailSettings(
    storeId: String,
    request: UpdateEmailSettingsRequest,
): EmailSettingsData = put("api/v1/stores/${storeId.pathSegment()}/email", body(request))

internal suspend fun BtcPayApi.sendStoreEmail(storeId: String, request: SendEmailRequest) {
    call("POST", "api/v1/stores/${storeId.pathSegment()}/email/send", body(request))
}
