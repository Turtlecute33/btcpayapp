@file:UseSerializers(BigDecimalSerializer::class)

package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.BigDecimalSerializer
import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

@Serializable
data class PaymentRequestData(
    val id: String = "",
    val storeId: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val title: String = "",
    val currency: String? = null,
    val email: String? = null,
    /** HTML. */
    val description: String? = null,
    val expiryDate: Long? = null,
    val referenceId: String? = null,
    val allowCustomPaymentAmounts: Boolean = false,
    val formId: String? = null,
    val formResponse: JsonObject? = null,
    val createdTime: Long = 0,
    val status: PaymentRequestStatus = PaymentRequestStatus.Pending,
    /** Sent by the server even though the swagger schema omits it. */
    val archived: Boolean = false,
)

@Serializable
data class PaymentRequestRequest(
    val amount: BigDecimal,
    val title: String,
    val currency: String? = null,
    val email: String? = null,
    val description: String? = null,
    val expiryDate: Long? = null,
    /** Unique per store; a duplicate is rejected with `duplicate-reference-id`. */
    val referenceId: String? = null,
    val allowCustomPaymentAmounts: Boolean? = null,
    val formId: String? = null,
)

@Serializable(with = PaymentRequestStatusSerializer::class)
enum class PaymentRequestStatus {
    /** Not enough has been paid yet. */
    Pending,

    /** Paid in full, waiting for settlement. */
    Processing,

    Completed,
    Expired,
    Unknown,
}

internal object PaymentRequestStatusSerializer : FallbackEnumSerializer<PaymentRequestStatus>(
    "PaymentRequestStatus",
    PaymentRequestStatus.entries.toTypedArray(),
    PaymentRequestStatus.Unknown,
)

@Serializable
data class PayPaymentRequestRequest(
    /** Null uses the payment request's own amount. */
    val amount: BigDecimal? = null,
    val allowPendingInvoiceReuse: Boolean = true,
)
