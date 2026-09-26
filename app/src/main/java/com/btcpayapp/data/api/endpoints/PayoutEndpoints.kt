package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.dto.ApprovePayoutRequest
import com.btcpayapp.data.api.dto.CreatePayoutRequest
import com.btcpayapp.data.api.dto.CreatePullPaymentRequest
import com.btcpayapp.data.api.dto.LightningPayoutProcessorSettings
import com.btcpayapp.data.api.dto.LnurlData
import com.btcpayapp.data.api.dto.MarkPayoutRequest
import com.btcpayapp.data.api.dto.OnChainPayoutProcessorSettings
import com.btcpayapp.data.api.dto.PaymentRequestData
import com.btcpayapp.data.api.dto.PaymentRequestRequest
import com.btcpayapp.data.api.dto.PayPaymentRequestRequest
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.PayoutProcessorData
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.dto.PullPaymentData
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.UpdateLightningPayoutProcessorSettings
import com.btcpayapp.data.api.dto.UpdateOnChainPayoutProcessorSettings

// ---------------------------------------------------------------------------
// Payment requests
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.paymentRequests(storeId: String): List<PaymentRequestData> =
    get("api/v1/stores/${storeId.pathSegment()}/payment-requests")

internal suspend fun BtcPayApi.paymentRequest(paymentRequestId: String): PaymentRequestData =
    get("api/v1/payment-requests/${paymentRequestId.pathSegment()}")

internal suspend fun BtcPayApi.createPaymentRequest(
    storeId: String,
    request: PaymentRequestRequest,
): PaymentRequestData = post("api/v1/stores/${storeId.pathSegment()}/payment-requests", body(request))

internal suspend fun BtcPayApi.updatePaymentRequest(
    paymentRequestId: String,
    request: PaymentRequestRequest,
): PaymentRequestData =
    put("api/v1/payment-requests/${paymentRequestId.pathSegment()}", body(request))

internal suspend fun BtcPayApi.archivePaymentRequest(paymentRequestId: String) {
    call("DELETE", "api/v1/payment-requests/${paymentRequestId.pathSegment()}")
}

/** Turns a payment request into a payable invoice. Not store-scoped. */
internal suspend fun BtcPayApi.payPaymentRequest(
    paymentRequestId: String,
    request: PayPaymentRequestRequest = PayPaymentRequestRequest(),
): InvoiceData = post("api/v1/payment-requests/${paymentRequestId.pathSegment()}/pay", body(request))

// ---------------------------------------------------------------------------
// Pull payments
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.pullPayments(
    storeId: String,
    includeArchived: Boolean = false,
): List<PullPaymentData> = get(
    "api/v1/stores/${storeId.pathSegment()}/pull-payments",
    listOf("includeArchived" to includeArchived.takeIf { it }),
)

internal suspend fun BtcPayApi.pullPayment(pullPaymentId: String): PullPaymentData =
    get("api/v1/pull-payments/${pullPaymentId.pathSegment()}")

internal suspend fun BtcPayApi.createPullPayment(
    storeId: String,
    request: CreatePullPaymentRequest,
): PullPaymentData = post("api/v1/stores/${storeId.pathSegment()}/pull-payments", body(request))

internal suspend fun BtcPayApi.archivePullPayment(pullPaymentId: String) {
    call("DELETE", "api/v1/pull-payments/${pullPaymentId.pathSegment()}")
}

/** The LNURL-withdraw the claimant scans. */
internal suspend fun BtcPayApi.pullPaymentLnurl(pullPaymentId: String): LnurlData =
    get("api/v1/pull-payments/${pullPaymentId.pathSegment()}/lnurl")

// ---------------------------------------------------------------------------
// Payouts
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.payouts(
    storeId: String,
    includeCancelled: Boolean = false,
): List<PayoutData> = get(
    "api/v1/stores/${storeId.pathSegment()}/payouts",
    listOf("includeCancelled" to includeCancelled.takeIf { it }),
)

internal suspend fun BtcPayApi.pullPaymentPayouts(
    pullPaymentId: String,
    includeCancelled: Boolean = false,
): List<PayoutData> = get(
    "api/v1/pull-payments/${pullPaymentId.pathSegment()}/payouts",
    listOf("includeCancelled" to includeCancelled.takeIf { it }),
)

internal suspend fun BtcPayApi.payout(payoutId: String): PayoutData =
    get("api/v1/payouts/${payoutId.pathSegment()}")

internal suspend fun BtcPayApi.createPayout(storeId: String, request: CreatePayoutRequest): PayoutData =
    post("api/v1/stores/${storeId.pathSegment()}/payouts", body(request))

/**
 * Approving locks in a rate and moves the payout to `AwaitingPayment`. The
 * [PayoutData.revision] must be sent back unchanged; a stale one is rejected
 * with `old-revision` rather than silently overwriting a concurrent edit.
 */
internal suspend fun BtcPayApi.approvePayout(
    payoutId: String,
    revision: Int,
    rateRule: String? = null,
): PayoutData = post(
    "api/v1/payouts/${payoutId.pathSegment()}",
    body(ApprovePayoutRequest(revision, rateRule)),
)

internal suspend fun BtcPayApi.cancelPayout(payoutId: String) {
    call("DELETE", "api/v1/payouts/${payoutId.pathSegment()}")
}

internal suspend fun BtcPayApi.markPayoutPaid(payoutId: String) {
    call("POST", "api/v1/payouts/${payoutId.pathSegment()}/mark-paid")
}

internal suspend fun BtcPayApi.markPayout(payoutId: String, state: PayoutState) {
    call("POST", "api/v1/payouts/${payoutId.pathSegment()}/mark", body(MarkPayoutRequest(state)))
}

// ---------------------------------------------------------------------------
// Payout processors (automated sending)
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.payoutProcessors(storeId: String): List<PayoutProcessorData> =
    get("api/v1/stores/${storeId.pathSegment()}/payout-processors")

internal suspend fun BtcPayApi.lightningPayoutProcessors(storeId: String): List<LightningPayoutProcessorSettings> =
    get("api/v1/stores/${storeId.pathSegment()}/payout-processors/LightningAutomatedPayoutSenderFactory")

internal suspend fun BtcPayApi.updateLightningPayoutProcessor(
    storeId: String,
    payoutMethodId: String,
    settings: UpdateLightningPayoutProcessorSettings,
): LightningPayoutProcessorSettings = put(
    "api/v1/stores/${storeId.pathSegment()}/payout-processors/LightningAutomatedPayoutSenderFactory/${payoutMethodId.pathSegment()}",
    body(settings),
)

/**
 * Greenfield's processor factory names.
 *
 * Shared constants so the read and write paths cannot drift apart. The on-chain
 * factory is `OnChainAutomatedPayoutSenderFactory`, not the plausible-looking
 * `OnChainAutomatedTransferSenderFactory`; a read under the wrong name would
 * never show a saved processor, and "delete" would be a no-op against a
 * processor that is not there.
 */
internal const val LIGHTNING_PAYOUT_PROCESSOR = "LightningAutomatedPayoutSenderFactory"
internal const val ONCHAIN_PAYOUT_PROCESSOR = "OnChainAutomatedPayoutSenderFactory"

internal suspend fun BtcPayApi.onChainPayoutProcessors(storeId: String): List<OnChainPayoutProcessorSettings> =
    get("api/v1/stores/${storeId.pathSegment()}/payout-processors/$ONCHAIN_PAYOUT_PROCESSOR")

internal suspend fun BtcPayApi.updateOnChainPayoutProcessor(
    storeId: String,
    paymentMethodId: String,
    settings: UpdateOnChainPayoutProcessorSettings,
): OnChainPayoutProcessorSettings = put(
    "api/v1/stores/${storeId.pathSegment()}/payout-processors/$ONCHAIN_PAYOUT_PROCESSOR/${paymentMethodId.pathSegment()}",
    body(settings),
)

internal suspend fun BtcPayApi.deletePayoutProcessor(
    storeId: String,
    processor: String,
    paymentMethodId: String,
) {
    call(
        "DELETE",
        "api/v1/stores/${storeId.pathSegment()}/payout-processors/${processor.pathSegment()}/${paymentMethodId.pathSegment()}",
    )
}
