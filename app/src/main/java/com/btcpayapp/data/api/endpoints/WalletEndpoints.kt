package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.dto.BroadcastTransactionRequest
import com.btcpayapp.data.api.dto.CreateTransactionPsbtResponse
import com.btcpayapp.data.api.dto.CreateTransactionRequest
import com.btcpayapp.data.api.dto.HistogramData
import com.btcpayapp.data.api.dto.PatchTransactionRequest
import com.btcpayapp.data.api.dto.WalletAddressData
import com.btcpayapp.data.api.dto.WalletFeeRateData
import com.btcpayapp.data.api.dto.WalletOverviewData
import com.btcpayapp.data.api.dto.WalletTransactionData
import com.btcpayapp.data.api.dto.WalletTransactionStatus
import com.btcpayapp.data.api.dto.WalletUtxoData

/**
 * The on-chain wallet hangs off the generic payment-method route, so
 * [paymentMethodId] is `BTC-CHAIN` (or `LTC-CHAIN`, …). The old
 * `/payment-methods/onchain/{cryptoCode}/wallet` path was removed in BTCPay 2.0
 * and now answers 410.
 */
private fun walletPath(storeId: String, paymentMethodId: String) =
    "api/v1/stores/${storeId.pathSegment()}/payment-methods/${paymentMethodId.pathSegment()}/wallet"

internal suspend fun BtcPayApi.walletOverview(storeId: String, paymentMethodId: String): WalletOverviewData =
    get(walletPath(storeId, paymentMethodId))

internal suspend fun BtcPayApi.walletHistogram(storeId: String, paymentMethodId: String): HistogramData =
    get("${walletPath(storeId, paymentMethodId)}/histogram")

internal suspend fun BtcPayApi.walletFeeRate(
    storeId: String,
    paymentMethodId: String,
    blockTarget: Int? = null,
): WalletFeeRateData = get(
    "${walletPath(storeId, paymentMethodId)}/feerate",
    listOf("blockTarget" to blockTarget),
)

/**
 * Reserves the next unused receive address. [forceGenerate] skips the reuse of
 * an already-reserved-but-unpaid address.
 */
internal suspend fun BtcPayApi.walletAddress(
    storeId: String,
    paymentMethodId: String,
    forceGenerate: Boolean = false,
): WalletAddressData = get(
    "${walletPath(storeId, paymentMethodId)}/address",
    listOf("forceGenerate" to forceGenerate.takeIf { it }),
)

/** Releases the last reserved address back into the pool. */
internal suspend fun BtcPayApi.unreserveWalletAddress(storeId: String, paymentMethodId: String) {
    call("DELETE", "${walletPath(storeId, paymentMethodId)}/address")
}

internal suspend fun BtcPayApi.walletUtxos(storeId: String, paymentMethodId: String): List<WalletUtxoData> =
    get("${walletPath(storeId, paymentMethodId)}/utxos")

internal suspend fun BtcPayApi.walletTransactions(
    storeId: String,
    paymentMethodId: String,
    statuses: List<WalletTransactionStatus>? = null,
    labelFilter: String? = null,
    skip: Int? = null,
    limit: Int? = null,
): List<WalletTransactionData> = get(
    "${walletPath(storeId, paymentMethodId)}/transactions",
    listOf(
        "statusFilter" to statuses?.map { it.name },
        "labelFilter" to labelFilter?.takeIf { it.isNotBlank() },
        "skip" to skip,
        "limit" to limit,
    ),
)

internal suspend fun BtcPayApi.walletTransaction(
    storeId: String,
    paymentMethodId: String,
    transactionId: String,
): WalletTransactionData =
    get("${walletPath(storeId, paymentMethodId)}/transactions/${transactionId.pathSegment()}")

/**
 * Creates, signs and broadcasts in one call. Requires a hot wallet on the
 * server; a watch-only store will reject it.
 */
internal suspend fun BtcPayApi.createTransaction(
    storeId: String,
    paymentMethodId: String,
    request: CreateTransactionRequest,
): WalletTransactionData = post(
    "${walletPath(storeId, paymentMethodId)}/transactions",
    body(request),
)

/**
 * Same endpoint with `signWithSeed = false`, which returns an unsigned PSBT for
 * an external signer instead of moving any money.
 */
internal suspend fun BtcPayApi.createUnsignedPsbt(
    storeId: String,
    paymentMethodId: String,
    request: CreateTransactionRequest,
): CreateTransactionPsbtResponse = post(
    "${walletPath(storeId, paymentMethodId)}/transactions",
    body(request.copy(signWithSeed = false, proceedWithBroadcast = false)),
)

internal suspend fun BtcPayApi.broadcastTransaction(
    storeId: String,
    paymentMethodId: String,
    transaction: String,
): WalletTransactionData = post(
    "${walletPath(storeId, paymentMethodId)}/transactions/broadcast",
    body(BroadcastTransactionRequest(transaction)),
)

/** Attaches a comment and labels to a transaction. */
internal suspend fun BtcPayApi.patchTransaction(
    storeId: String,
    paymentMethodId: String,
    transactionId: String,
    request: PatchTransactionRequest,
    force: Boolean = false,
): WalletTransactionData = patch(
    "${walletPath(storeId, paymentMethodId)}/transactions/${transactionId.pathSegment()}",
    body(request),
    listOf("force" to force.takeIf { it }),
)
