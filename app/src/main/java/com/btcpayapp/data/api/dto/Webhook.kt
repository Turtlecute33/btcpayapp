package com.btcpayapp.data.api.dto

import com.btcpayapp.data.api.FallbackEnumSerializer
import kotlinx.serialization.Serializable

@Serializable
data class WebhookData(
    val id: String = "",
    val enabled: Boolean = true,
    val automaticRedelivery: Boolean = true,
    val url: String = "",
    val authorizedEvents: WebhookAuthorizedEvents = WebhookAuthorizedEvents(),
    /** Returned only by the create call. */
    val secret: String? = null,
)

@Serializable
data class WebhookAuthorizedEvents(
    val everything: Boolean = true,
    val specificEvents: List<String> = emptyList(),
)

@Serializable
data class WebhookRequest(
    val url: String,
    val enabled: Boolean = true,
    val automaticRedelivery: Boolean = true,
    val authorizedEvents: WebhookAuthorizedEvents = WebhookAuthorizedEvents(),
    /** Null on update leaves the existing secret in place. */
    val secret: String? = null,
)

@Serializable
data class WebhookDeliveryData(
    val id: String = "",
    val timestamp: Long = 0,
    val deliveryTime: Long = 0,
    val httpCode: Int? = null,
    val errorMessage: String? = null,
    val status: WebhookDeliveryStatus = WebhookDeliveryStatus.Failed,
)

@Serializable(with = WebhookDeliveryStatusSerializer::class)
enum class WebhookDeliveryStatus { Failed, HttpError, HttpSuccess, Unknown }

internal object WebhookDeliveryStatusSerializer : FallbackEnumSerializer<WebhookDeliveryStatus>(
    "WebhookDeliveryStatus",
    WebhookDeliveryStatus.entries.toTypedArray(),
    WebhookDeliveryStatus.Unknown,
)

/**
 * Every event type the server can deliver. Kept as a list rather than an enum
 * because the set grows with plugins, and the picker should show whatever the
 * operator's instance actually supports without an app update.
 */
object WebhookEventTypes {

    val invoice = listOf(
        "InvoiceCreated",
        "InvoiceReceivedPayment",
        "InvoicePaymentSettled",
        "InvoiceProcessing",
        "InvoiceExpired",
        "InvoiceSettled",
        "InvoiceInvalid",
        "InvoiceExpiredPaidPartial",
        "InvoicePaidAfterExpiration",
        "InvoiceRefund",
    )

    val payout = listOf(
        "PayoutCreated",
        "PayoutApproved",
        "PayoutUpdated",
    )

    val paymentRequest = listOf(
        "PaymentRequestCreated",
        "PaymentRequestUpdated",
        "PaymentRequestArchived",
        "PaymentRequestStatusChanged",
        "PaymentRequestCompleted",
    )

    val all: List<String> = invoice + payout + paymentRequest

    fun groupOf(event: String): String = when (event) {
        in invoice -> "Invoices"
        in payout -> "Payouts"
        in paymentRequest -> "Payment requests"
        else -> "Other"
    }
}
