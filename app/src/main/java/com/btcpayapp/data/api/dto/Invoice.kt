@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

@Serializable
data class InvoiceData(
    val id: String = "",
    val storeId: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val paidAmount: BigDecimal = BigDecimal.ZERO,
    val currency: String = "",
    val type: InvoiceType = InvoiceType.Standard,
    val checkoutLink: String? = null,
    val createdTime: Long = 0,
    val expirationTime: Long = 0,
    val monitoringExpiration: Long = 0,
    val status: InvoiceStatus = InvoiceStatus.New,
    val additionalStatus: InvoiceAdditionalStatus = InvoiceAdditionalStatus.None,
    /** Which manual transitions the server will currently accept. */
    val availableStatusesForManualMarking: List<InvoiceStatus> = emptyList(),
    val archived: Boolean = false,
    val metadata: JsonObject? = null,
    val checkout: CheckoutOptions? = null,
    val receipt: ReceiptOptions? = null,
    /**
     * Null or empty unless the request set `includePaymentMethods=true` on a
     * server that honours it (the single-invoice GET does only from 2.4.1;
     * 2.3.6 to 2.4.0 send an empty list). Use `invoiceWithPaymentMethods`,
     * which fills it on every 2.x.
     */
    val paymentMethods: List<InvoicePaymentMethodData>? = null,
) {
    val isSettled: Boolean get() = status == InvoiceStatus.Settled
    val isOpen: Boolean get() = status == InvoiceStatus.New || status == InvoiceStatus.Processing
    val dueAmount: BigDecimal get() = (amount - paidAmount).coerceAtLeast(BigDecimal.ZERO)
}

@Serializable(with = InvoiceStatusSerializer::class)
enum class InvoiceStatus { New, Processing, Expired, Invalid, Settled, Unknown }

internal object InvoiceStatusSerializer : FallbackEnumSerializer<InvoiceStatus>(
    "InvoiceStatus",
    InvoiceStatus.entries.toTypedArray(),
    InvoiceStatus.Unknown,
)

@Serializable(with = InvoiceAdditionalStatusSerializer::class)
enum class InvoiceAdditionalStatus { None, PaidPartial, PaidOver, PaidLate, Marked, Invalid, Unknown }

internal object InvoiceAdditionalStatusSerializer : FallbackEnumSerializer<InvoiceAdditionalStatus>(
    "InvoiceAdditionalStatus",
    InvoiceAdditionalStatus.entries.toTypedArray(),
    InvoiceAdditionalStatus.Unknown,
)

@Serializable(with = InvoiceTypeSerializer::class)
enum class InvoiceType { Standard, TopUp, Unknown }

internal object InvoiceTypeSerializer : FallbackEnumSerializer<InvoiceType>(
    "InvoiceType",
    InvoiceType.entries.toTypedArray(),
    InvoiceType.Unknown,
)

@Serializable
data class CreateInvoiceRequest(
    /** Null creates a top-up invoice that accepts any amount. */
    val amount: BigDecimal? = null,
    /** Null falls back to the store's default currency. */
    val currency: String? = null,
    val metadata: JsonObject? = null,
    val checkout: CheckoutOptions? = null,
    val receipt: ReceiptOptions? = null,
    val additionalSearchTerms: List<String>? = null,
)

@Serializable
data class UpdateInvoiceRequest(
    val metadata: JsonObject,
)

@Serializable
data class MarkInvoiceStatusRequest(
    /** The server accepts only `Settled` or `Invalid` here. */
    val status: InvoiceStatus,
)

@Serializable
data class CheckoutOptions(
    val speedPolicy: SpeedPolicy? = null,
    /** Payment method ids, e.g. `["BTC-CHAIN", "BTC-LN"]`. */
    val paymentMethods: List<String>? = null,
    val defaultPaymentMethod: String? = null,
    val lazyPaymentMethods: Boolean? = null,
    /** Minutes — the store-level equivalent is in seconds. */
    val expirationMinutes: Int? = null,
    /** Minutes. */
    val monitoringMinutes: Int? = null,
    val paymentTolerance: Double? = null,
    /** Uppercase `URL` on purpose; that is the wire name. */
    val redirectURL: String? = null,
    val redirectAutomatically: Boolean? = null,
    val defaultLanguage: String? = null,
)

@Serializable
data class InvoicePaymentMethodData(
    val paymentMethodId: String = "",
    val currency: String = "",
    /** On-chain address, BOLT11 invoice, or LNURL depending on the method. */
    val destination: String = "",
    /** BIP21 / `lightning:` URI suitable for a QR code. */
    val paymentLink: String? = null,
    val rate: BigDecimal = BigDecimal.ZERO,
    val paymentMethodPaid: BigDecimal = BigDecimal.ZERO,
    val totalPaid: BigDecimal = BigDecimal.ZERO,
    val due: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
    val paymentMethodFee: BigDecimal = BigDecimal.ZERO,
    /** Lazy payment methods are only usable once activated. */
    val activated: Boolean = true,
    val payments: List<PaymentData> = emptyList(),
    val additionalData: JsonObject? = null,
)

@Serializable
data class PaymentData(
    val id: String = "",
    val receivedDate: Long = 0,
    val value: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val status: PaymentStatus = PaymentStatus.Processing,
    val destination: String = "",
)

@Serializable(with = PaymentStatusSerializer::class)
enum class PaymentStatus { Invalid, Processing, Settled, Unknown }

internal object PaymentStatusSerializer : FallbackEnumSerializer<PaymentStatus>(
    "PaymentStatus",
    PaymentStatus.entries.toTypedArray(),
    PaymentStatus.Unknown,
)

// ---------------------------------------------------------------------------
// Refunds
// ---------------------------------------------------------------------------

@Serializable
data class RefundInvoiceRequest(
    val name: String? = null,
    val description: String? = null,
    val payoutMethods: List<String>? = null,
    /**
     * The legacy single-method field. Servers before 2.4.1 read only this one
     * and refund with the invoice's default method when it is absent; newer
     * servers prefer [payoutMethods]. Callers set both to the same id.
     */
    val payoutMethodId: String? = null,
    val refundVariant: RefundVariant = RefundVariant.CurrentRate,
    val subtractPercentage: BigDecimal? = null,
    /** Required when [refundVariant] is [RefundVariant.Custom]. */
    val customAmount: BigDecimal? = null,
    val customCurrency: String? = null,
)

@Serializable(with = RefundVariantSerializer::class)
enum class RefundVariant {
    /** Refund the fiat value using the rate at the time of payment. */
    RateThen,

    /** Refund the fiat value using today's rate. */
    CurrentRate,

    /** Refund only the amount paid above the invoice total. */
    OverpaidAmount,

    /** Refund the invoice's fiat amount. */
    Fiat,

    /** Refund an amount the operator types in. */
    Custom,

    Unknown,
}

internal object RefundVariantSerializer : FallbackEnumSerializer<RefundVariant>(
    "RefundVariant",
    RefundVariant.entries.toTypedArray(),
    RefundVariant.Unknown,
)

/** Drives the refund screen: how much each variant would actually pay out. */
@Serializable
data class RefundTriggerData(
    val paymentAmountThen: BigDecimal = BigDecimal.ZERO,
    val paymentAmountNow: BigDecimal = BigDecimal.ZERO,
    val invoiceAmount: BigDecimal = BigDecimal.ZERO,
    val paymentCurrency: String = "",
    val paymentCurrencyDivisibility: Int = 8,
    val invoiceCurrency: String = "",
    val invoiceCurrencyDivisibility: Int = 2,
    val overpaidPaymentAmount: BigDecimal? = null,
)
