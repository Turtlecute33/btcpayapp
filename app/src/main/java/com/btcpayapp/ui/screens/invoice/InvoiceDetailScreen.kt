package com.btcpayapp.ui.screens.invoice
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoicePaymentMethodData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.endpoints.archiveInvoice
import com.btcpayapp.data.api.endpoints.invoice
import com.btcpayapp.data.api.endpoints.markInvoiceStatus
import com.btcpayapp.data.api.endpoints.unarchiveInvoice
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.StatusChip
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.continuity
import com.btcpayapp.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class InvoiceDetailState(
    val invoice: InvoiceData? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: ApiException? = null,
)

class InvoiceDetailViewModel(
    private val graph: AppGraph,
    private val invoiceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(InvoiceDetailState())
    val state = _state.asStateFlow()

    val baseUrl: String? get() = graph.session.activeAccount.value?.baseUrl

    init {
        load()
    }

    /**
     * Waits for the store instead of silently giving up.
     *
     * `activeStore` starts null and only becomes non-null once `/stores`
     * returns. Opening this screen from a payment notification or a launcher
     * shortcut on a cold start would otherwise find no store, return from
     * `load()` without clearing `loading`, and spin forever with no retry.
     */
    private suspend fun awaitStoreId(): String =
        graph.session.activeStore.filterNotNull().first().id

    fun load() {
        viewModelScope.launch {
            val storeId = awaitStoreId()
            runCatching {
                graph.session.requireApi().invoice(storeId, invoiceId, includePaymentMethods = true)
            }.onSuccess { data ->
                _state.update { it.copy(invoice = data, loading = false, error = null) }
            }.onFailure { failure ->
                _state.update { it.copy(loading = false, error = failure.asApiException()) }
            }
        }
    }

    fun mark(status: InvoiceStatus) = mutate { api, storeId ->
        api.markInvoiceStatus(storeId, invoiceId, status)
    }

    fun archive() = mutate { api, storeId ->
        api.archiveInvoice(storeId, invoiceId)
        api.invoice(storeId, invoiceId, includePaymentMethods = true)
    }

    fun unarchive() = mutate { api, storeId ->
        api.unarchiveInvoice(storeId, invoiceId)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun mutate(block: suspend (com.btcpayapp.data.api.BtcPayApi, String) -> Any?) {
        val storeId = graph.session.activeStore.value?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            runCatching { block(graph.session.requireApi(), storeId) }
                .onFailure { failure -> _state.update { it.copy(error = failure.asApiException()) } }
            _state.update { it.copy(working = false) }
            load()
        }
    }
}


/**
 * Which body the screen is showing.
 *
 * A discriminator rather than the state, so that marking an invoice — which
 * reloads it — does not dissolve and rebuild a page the user is reading.
 */
private enum class InvoiceDetailPhase { Loading, Error, Content }

@Composable
fun InvoiceDetailScreen(
    invoiceId: String,
    onBack: () -> Unit,
    onRefund: () -> Unit,
    onCheckout: () -> Unit,
) {
    val viewModel = appViewModel(key = "invoice-$invoiceId") { InvoiceDetailViewModel(it, invoiceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<InvoiceStatus?>(null) }

    AppScreen(
        title = "Invoice",
        subtitle = TextUtil.middleEllipsis(invoiceId, 8, 6),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::load,
        refreshing = state.working,
        actions = {
            val invoice = state.invoice
            if (invoice?.isOpen == true) {
                IconButton(onClick = onCheckout) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = "Show the payment code")
                }
            }
            invoice?.checkoutLink?.let { link ->
                IconButton(onClick = { context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in a browser")
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // The server tells us which manual transitions it will accept;
                // offering any others would just produce a 400.
                invoice?.availableStatusesForManualMarking?.forEach { status ->
                    DropdownMenuItem(
                        text = { Text("Mark as ${status.name.lowercase()}") },
                        onClick = {
                            menuOpen = false
                            confirming = status
                        },
                    )
                }
                if (invoice?.isSettled == true) {
                    DropdownMenuItem(
                        text = { Text("Refund") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRefund()
                        },
                    )
                }
                if (invoice != null) {
                    DropdownMenuItem(
                        text = { Text(if (invoice.archived) "Unarchive" else "Archive") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (invoice.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            if (invoice.archived) viewModel.unarchive() else viewModel.archive()
                        },
                    )
                }
            }
        },
    ) { padding ->
        val invoice = state.invoice

        val phase = when {
            state.loading && invoice == null -> InvoiceDetailPhase.Loading
            invoice == null -> InvoiceDetailPhase.Error
            else -> InvoiceDetailPhase.Content
        }

        AnimatedSwap(phase, label = "invoice") { shown ->
            // Branched on `shown` rather than on `phase`, so the outgoing body
            // keeps drawing itself while it leaves. The `invoice == null` arm is
            // unreachable in practice — the invoice is never unloaded — and is
            // here so the content arm has something the compiler trusts.
            when {
                shown == InvoiceDetailPhase.Loading -> LoadingState(Modifier.padding(padding))

                shown == InvoiceDetailPhase.Error || invoice == null -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::load,
                )

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    // Deliberately not given an `arrive`, unlike everything
                    // below it. The amount and the status arrive from the row
                    // that was tapped — see `continuity` in `Header` — and a
                    // shared element that also fades up from nothing is an
                    // object the eye is following that goes briefly missing.
                    Header(invoice)
                    ThinDivider()

                    // The rest of the page assembles behind that, one section at
                    // a time. Grouped rather than staggered row by row: the
                    // reader should see sections settle, not count nine
                    // individual lines in.
                    Column(Modifier.fillMaxWidth().arrive(0)) {
                        SectionHeader("Details")
                        DetailRow("Created", Dates.full(invoice.createdTime))
                        DetailRow("Expires", Dates.full(invoice.expirationTime))
                        DetailRow("Monitoring until", Dates.full(invoice.monitoringExpiration))
                        DetailRow("Type", invoice.type.name)
                        if (invoice.additionalStatus != com.btcpayapp.data.api.dto.InvoiceAdditionalStatus.None) {
                            DetailRow("Detail", TextUtil.sentenceCase(invoice.additionalStatus.name))
                        }
                        if (invoice.archived) DetailRow("Archived", "Yes")

                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            CopyableField(label = "Invoice id", value = invoice.id)
                        }
                    }

                    invoice.metadata?.let {
                        Column(Modifier.fillMaxWidth().arrive(1)) { MetadataSection(it) }
                    }

                    if (invoice.paymentMethods?.isNotEmpty() == true) {
                        Column(Modifier.fillMaxWidth().arrive(2)) {
                            SectionHeader("Payment methods")
                            invoice.paymentMethods.orEmpty().forEach { method ->
                                PaymentMethodCard(method, invoice.currency)
                            }
                        }
                    }

                    val payments = invoice.paymentMethods.orEmpty().flatMap { it.payments }
                    if (payments.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().arrive(3)) {
                            SectionHeader("Payments")
                            payments.forEach { payment ->
                                AppCard {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = Amounts.trim(payment.value, 8),
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            StatusPill(
                                                label = payment.status.name,
                                                container = when (payment.status) {
                                                    com.btcpayapp.data.api.dto.PaymentStatus.Settled -> AppTheme.statusColors.settled
                                                    com.btcpayapp.data.api.dto.PaymentStatus.Processing -> AppTheme.statusColors.pending
                                                    else -> AppTheme.statusColors.invalid
                                                },
                                                content = when (payment.status) {
                                                    com.btcpayapp.data.api.dto.PaymentStatus.Settled -> AppTheme.statusColors.onSettled
                                                    com.btcpayapp.data.api.dto.PaymentStatus.Processing -> AppTheme.statusColors.onPending
                                                    else -> AppTheme.statusColors.onInvalid
                                                },
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = Dates.full(payment.receivedDate),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (payment.destination.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            CopyableField(
                                                label = "Destination",
                                                value = payment.destination,
                                                truncate = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (invoice.isSettled) {
                        Column(Modifier.fillMaxWidth().arrive(4)) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = onRefund,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                                Spacer(Modifier.height(0.dp))
                                Text("  Issue a refund")
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    confirming?.let { status ->
        ConfirmDialog(
            title = "Mark as ${status.name.lowercase()}?",
            message = "This overrides what the server observed on chain. It cannot be undone from here.",
            confirmLabel = "Mark",
            destructive = status == InvoiceStatus.Invalid,
            onConfirm = {
                viewModel.mark(status)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun Header(invoice: InvoiceData) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The receiving half of the list row's shared elements. The keys are
        // built from the invoice id and the element's role, and must match the
        // row's exactly; a mismatch is not an error, it is a transition that
        // silently stops happening.
        Text(
            text = Amounts.format(invoice.amount, invoice.currency),
            modifier = Modifier.continuity("invoice-amount-${invoice.id}"),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        StatusChip(
            status = invoice.status,
            modifier = Modifier.continuity("invoice-status-${invoice.id}"),
        )
        if (invoice.paidAmount.signum() > 0 && invoice.paidAmount.compareTo(invoice.amount) != 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${Amounts.format(invoice.paidAmount, invoice.currency)} paid",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetadataSection(metadata: JsonObject) {
    // Metadata is free-form, so only the keys BTCPay documents are given a
    // friendly label; anything else is shown verbatim rather than dropped.
    val known = remember {
        mapOf(
            "orderId" to "Order id",
            "itemDesc" to "Item",
            "itemCode" to "Item code",
            "buyerName" to "Buyer",
            "buyerEmail" to "Buyer email",
            "buyerCountry" to "Country",
            "buyerCity" to "City",
            "buyerAddress1" to "Address",
            "buyerPhone" to "Phone",
            "orderUrl" to "Order URL",
            "paymentRequestId" to "Payment request",
            "tipAmount" to "Tip",
            "tipPercent" to "Tip %",
        )
    }

    val entries = metadata.entries
        .mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { (known[key] ?: TextUtil.sentenceCase(key)) to it }
        }

    if (entries.isEmpty()) return

    SectionHeader("Order")
    entries.forEach { (label, value) -> DetailRow(label, value) }
}

@Composable
private fun PaymentMethodCard(method: InvoicePaymentMethodData, invoiceCurrency: String) {
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(method.paymentMethodId, style = MaterialTheme.typography.titleSmall)
                if (!method.activated) {
                    StatusPill(
                        label = "Not activated",
                        container = AppTheme.statusColors.expired,
                        content = AppTheme.statusColors.onExpired,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DetailRow("Due", "${Amounts.trim(method.due, 8)} ${method.currency}")
            DetailRow("Paid", "${Amounts.trim(method.totalPaid, 8)} ${method.currency}")
            if (method.paymentMethodFee.signum() > 0) {
                DetailRow("Network fee", "${Amounts.trim(method.paymentMethodFee, 8)} ${method.currency}")
            }
            if (method.rate.signum() > 0) {
                DetailRow("Rate", "${Amounts.format(method.rate, invoiceCurrency)} / ${method.currency}")
            }
            if (method.destination.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CopyableField(label = "Destination", value = method.destination, truncate = true)
            }
        }
    }
}
