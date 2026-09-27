package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.dto.CreateInvoiceRequest
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoicePaymentMethodData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.MarkInvoiceStatusRequest
import com.btcpayapp.data.api.dto.PullPaymentData
import com.btcpayapp.data.api.dto.RefundInvoiceRequest
import com.btcpayapp.data.api.dto.RefundTriggerData
import com.btcpayapp.data.api.dto.UpdateInvoiceRequest
import kotlinx.serialization.json.JsonObject

/**
 * `orderId` and `status` are repeatable query parameters, and `startDate` /
 * `endDate` are unix seconds.
 */
internal suspend fun BtcPayApi.invoices(
    storeId: String,
    statuses: List<InvoiceStatus>? = null,
    orderIds: List<String>? = null,
    textSearch: String? = null,
    startDate: Long? = null,
    endDate: Long? = null,
    skip: Int? = null,
    take: Int? = null,
    includePaymentMethods: Boolean = false,
): List<InvoiceData> = get(
    "api/v1/stores/${storeId.pathSegment()}/invoices",
    listOf(
        "status" to statuses?.map { it.name },
        "orderId" to orderIds,
        "textSearch" to textSearch?.takeIf { it.isNotBlank() },
        "startDate" to startDate,
        "endDate" to endDate,
        "skip" to skip,
        "take" to take,
        "includePaymentMethods" to includePaymentMethods.takeIf { it },
    ),
)

internal suspend fun BtcPayApi.invoice(
    storeId: String,
    invoiceId: String,
    includePaymentMethods: Boolean = false,
): InvoiceData = get(
    "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}",
    listOf("includePaymentMethods" to includePaymentMethods.takeIf { it }),
)

/**
 * The invoice with [InvoiceData.paymentMethods] always filled, on every 2.x.
 *
 * The single-invoice GET honours `includePaymentMethods` only from 2.4.1; older
 * servers ignore the flag and send no list, or an empty one (2.3.6 to 2.4.0).
 * Without one, checkout has no QR, a refund has no method to pick and the
 * detail screen shows no payments. The `.../payment-methods` route exists in
 * every 2.x, so it fills the gap with a second request only when the first
 * answer has no method. An invoice always has at least one, so an empty list
 * means the server left them out.
 */
internal suspend fun BtcPayApi.invoiceWithPaymentMethods(storeId: String, invoiceId: String): InvoiceData {
    val invoice = invoice(storeId, invoiceId, includePaymentMethods = true)
    if (!invoice.paymentMethods.isNullOrEmpty()) return invoice
    return invoice.copy(paymentMethods = invoicePaymentMethods(storeId, invoiceId))
}

internal suspend fun BtcPayApi.createInvoice(storeId: String, request: CreateInvoiceRequest): InvoiceData =
    post("api/v1/stores/${storeId.pathSegment()}/invoices", body(request))

/** Metadata is replaced wholesale, so callers must merge before sending. */
internal suspend fun BtcPayApi.updateInvoiceMetadata(
    storeId: String,
    invoiceId: String,
    metadata: JsonObject,
): InvoiceData = put(
    "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}",
    body(UpdateInvoiceRequest(metadata)),
)

/** Archives rather than deletes; [unarchiveInvoice] reverses it. */
internal suspend fun BtcPayApi.archiveInvoice(storeId: String, invoiceId: String) {
    call("DELETE", "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}")
}

internal suspend fun BtcPayApi.unarchiveInvoice(storeId: String, invoiceId: String): InvoiceData =
    post("api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}/unarchive")

/**
 * Only `Settled` and `Invalid` are accepted, and only when the target appears
 * in `availableStatusesForManualMarking` on the invoice.
 */
internal suspend fun BtcPayApi.markInvoiceStatus(
    storeId: String,
    invoiceId: String,
    status: InvoiceStatus,
): InvoiceData = post(
    "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}/status",
    body(MarkInvoiceStatusRequest(status)),
)

internal suspend fun BtcPayApi.invoicePaymentMethods(
    storeId: String,
    invoiceId: String,
    includeSensitive: Boolean = false,
    onlyAccountedPayments: Boolean = true,
): List<InvoicePaymentMethodData> = get(
    "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}/payment-methods",
    listOf(
        "includeSensitive" to includeSensitive.takeIf { it },
        "onlyAccountedPayments" to onlyAccountedPayments,
    ),
)

/** Lazy payment methods only produce an address once activated. */
internal suspend fun BtcPayApi.activatePaymentMethod(
    storeId: String,
    invoiceId: String,
    paymentMethodId: String,
) {
    call(
        "POST",
        "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}" +
            "/payment-methods/${paymentMethodId.pathSegment()}/activate",
    )
}

/**
 * Refunds are issued as a pull payment the customer claims, which is why the
 * response is a [PullPaymentData] rather than a transaction.
 */
internal suspend fun BtcPayApi.refundInvoice(
    storeId: String,
    invoiceId: String,
    request: RefundInvoiceRequest,
): PullPaymentData = post(
    "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}/refund",
    body(request),
)

/** Tells the refund screen what each variant would actually pay out. */
internal suspend fun BtcPayApi.refundTrigger(
    storeId: String,
    invoiceId: String,
    paymentMethodId: String,
): RefundTriggerData = get(
    "api/v1/stores/${storeId.pathSegment()}/invoices/${invoiceId.pathSegment()}/refund/${paymentMethodId.pathSegment()}",
)
