@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import com.btcpayapp.data.api.LabelListSerializer
import com.btcpayapp.data.api.TolerantLongSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

@Serializable
data class WalletOverviewData(
    val balance: BigDecimal = BigDecimal.ZERO,
    val unconfirmedBalance: BigDecimal = BigDecimal.ZERO,
    val confirmedBalance: BigDecimal = BigDecimal.ZERO,
)

@Serializable
data class WalletFeeRateData(
    /** sat/vB. A plain JSON number here, not a string. */
    val feeRate: Double = 1.0,
)

@Serializable
data class WalletAddressData(
    val address: String = "",
    val keyPath: String = "",
    /** BIP21 URI, ready to render as a QR code. */
    val paymentLink: String? = null,
)

@Serializable
data class WalletTransactionData(
    val transactionHash: String? = null,
    val comment: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val blockHash: String? = null,
    /** Sent as a JSON string by the server. */
    @Serializable(with = TolerantLongSerializer::class)
    val blockHeight: Long = 0,
    @Serializable(with = TolerantLongSerializer::class)
    val confirmations: Long = 0,
    val timestamp: Long = 0,
    val status: WalletTransactionStatus = WalletTransactionStatus.Unconfirmed,
    /** Sent as an object keyed by label type, not the array the swagger claims. */
    @Serializable(with = LabelListSerializer::class)
    val labels: List<LabelData> = emptyList(),
) {
    val isIncoming: Boolean get() = amount.signum() >= 0
}

@Serializable(with = WalletTransactionStatusSerializer::class)
enum class WalletTransactionStatus { Confirmed, Unconfirmed, Unknown }

internal object WalletTransactionStatusSerializer : FallbackEnumSerializer<WalletTransactionStatus>(
    "WalletTransactionStatus",
    WalletTransactionStatus.entries.toTypedArray(),
    WalletTransactionStatus.Unknown,
)

@Serializable
data class WalletUtxoData(
    val comment: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val link: String? = null,
    /** `{txid}:{vout}` — the form the coin selector expects back. */
    val outpoint: String = "",
    val timestamp: Long = 0,
    val keyPath: String = "",
    val address: String = "",
    val confirmations: Int = 0,
    /** As [WalletTransactionData.labels] — an object, not an array. */
    @Serializable(with = LabelListSerializer::class)
    val labels: List<LabelData> = emptyList(),
)

@Serializable
data class CreateTransactionRequest(
    val destinations: List<TransactionDestination>,
    /** sat/vB. */
    val feerate: Double? = null,
    val proceedWithPayjoin: Boolean = true,
    /**
     * False returns a signed-but-unbroadcast transaction, which is what the
     * review-before-send flow uses so the operator sees the real fee first.
     */
    val proceedWithBroadcast: Boolean = true,
    /** False returns an unsigned PSBT for an external signer. */
    val signWithSeed: Boolean = true,
    val noChange: Boolean = false,
    val rbf: Boolean? = null,
    val excludeUnconfirmed: Boolean = false,
    /** Outpoints to spend, for manual coin control. */
    val selectedInputs: List<String>? = null,
)

@Serializable
data class TransactionDestination(
    /** Address or BIP21 URI. */
    val destination: String,
    val amount: BigDecimal? = null,
    /** Take the fee out of this output rather than adding it on top. */
    val subtractFromAmount: Boolean = false,
)

@Serializable
data class CreateTransactionPsbtResponse(
    val psbt: String = "",
)

@Serializable
data class BroadcastTransactionRequest(
    /** A finalised PSBT (base64) or a raw transaction (hex). */
    val transaction: String,
)

@Serializable
data class PatchTransactionRequest(
    val comment: String? = null,
    val labels: List<String>? = null,
)

// ---------------------------------------------------------------------------
// Wallet objects (labels, attachments and the links between them)
// ---------------------------------------------------------------------------

@Serializable
data class WalletObjectData(
    val type: String = "",
    val id: String = "",
    val data: JsonObject? = null,
    val links: List<WalletObjectLink> = emptyList(),
)

@Serializable
data class WalletObjectLink(
    val type: String = "",
    val id: String = "",
    val linkData: JsonObject? = null,
    val objectData: JsonObject? = null,
)

@Serializable
data class AddWalletObjectLinkRequest(
    val type: String,
    val id: String,
    val data: JsonObject? = null,
)
