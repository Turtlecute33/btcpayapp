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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.InvoiceAdditionalStatus
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoicePaymentMethodData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.PaymentData
import com.btcpayapp.data.api.dto.PaymentStatus
import com.btcpayapp.data.api.endpoints.archiveInvoice
import com.btcpayapp.data.api.endpoints.invoiceWithPaymentMethods
import com.btcpayapp.data.api.endpoints.markInvoiceStatus
import com.btcpayapp.data.api.endpoints.unarchiveInvoice
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
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
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.continuity
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.theme.AppTheme
import java.math.BigDecimal
import kotlinx.coroutines.async
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
    /** A pull to refresh is running; before, the indicator snapped back at once. */
    val refreshing: Boolean = false,
    val working: Boolean = false,
    val error: ApiException? = null,
)

class InvoiceDetailViewModel(
    private val graph: AppGraph,
    private val invoiceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(InvoiceDetailState())
    val state = _state.asStateFlow()

    /**
     * The store this screen was opened in, taken once.
     *
     * Waited for instead of silently given up on: `activeStore` starts null
     * and only becomes non-null once `/stores` returns. Opening this screen
     * from a payment notification or a launcher shortcut on a cold start would
     * otherwise find no store, return from `load()` without clearing
     * `loading`, and spin forever with no retry. Taken once, because the shell
     * closes this screen when the store changes: a later store is one this
     * invoice is not in.
     */
    private val storeId = viewModelScope.async { graph.session.activeStore.filterNotNull().first().id }

    init {
        load()
    }

    /** Pull to refresh: the same read, with the indicator up until it lands. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching {
                // With the payment methods on every 2.x; before 2.4.1 the
                // flag alone brings none, and the payments below stay empty.
                graph.session.requireApi().invoiceWithPaymentMethods(storeId.await(), invoiceId)
            }.onSuccess { data ->
                _state.update { it.copy(invoice = data, loading = false, refreshing = false, error = null) }
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update { it.copy(loading = false, refreshing = false, error = error) }
            }
        }
    }

    fun mark(status: InvoiceStatus) = mutate { api, storeId ->
        api.markInvoiceStatus(storeId, invoiceId, status)
    }

    fun archive() = mutate { api, storeId ->
        api.archiveInvoice(storeId, invoiceId)
    }

    fun unarchive() = mutate { api, storeId ->
        api.unarchiveInvoice(storeId, invoiceId)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * The name of the store [invoice] is in, for a confirmation that must say
     * whose invoice it changes. Found by the invoice's own store id: the
     * invoice was loaded from the store every change goes to.
     */
    fun storeName(invoice: InvoiceData): String =
        graph.session.stores.value.firstOrNull { it.id == invoice.storeId }?.name?.takeIf(String::isNotBlank)
            ?: invoice.storeId.ifBlank { "this store" }

    private fun mutate(block: suspend (BtcPayApi, String) -> Any?) {
        // The menu stays open to taps while a change runs, so a second change
        // is dropped here instead of being sent next to the first.
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            runCatching { block(graph.session.requireApi(), storeId.await()) }
                .onFailure { failure ->
                    val error = failure.asApiException()
                    _state.update { it.copy(error = error) }
                }
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
    val unit = LocalSettings.current.bitcoinUnit
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<InvoiceStatus?>(null) }

    AppScreen(
        title = "Invoice",
        subtitle = TextUtil.middleEllipsis(invoiceId, 8, 6),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        refreshing = state.refreshing || state.working,
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
                if (invoice?.offersRefund == true) {
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
                        if (invoice.additionalStatus != InvoiceAdditionalStatus.None) {
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

                    // Under their method, each in its unit. Flattened into one
                    // list, "0.00012" could be BTC on-chain, BTC over Lightning
                    // or another chain, and a refund is decided on exactly
                    // that.
                    val paidMethods = invoice.paymentMethods.orEmpty().filter { it.payments.isNotEmpty() }
                    if (paidMethods.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().arrive(3)) {
                            SectionHeader("Payments")
                            paidMethods.forEach { method ->
                                Text(
                                    text = method.paymentMethodId,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                method.payments.forEach { payment -> PaymentCard(payment, method.currency) }
                            }
                        }
                    }

                    if (invoice.offersRefund) {
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

    // The menu offers a mark only once the invoice has loaded, and it is never
    // unloaded, so both are known here.
    val status = confirming
    val invoice = state.invoice
    if (status != null && invoice != null) {
        // Settled makes the invoice count as paid, and a shop or a webhook may
        // then hand over the goods. So it is checked as a payment is: the
        // invoice, amount and store in full, not masked in privacy mode, then
        // the spend prompt.
        val settles = status == InvoiceStatus.Settled
        val amount = methodAmountText(invoice.amount, invoice.currency, unit)
        val store = viewModel.storeName(invoice)
        ConfirmDialog(
            title = "Mark as ${status.name.lowercase()}?",
            message = if (settles) {
                "Invoice: ${invoice.id}\nAmount: $amount\nStore: $store\n\n" +
                    "It then counts as paid, whatever the server has seen. It cannot be undone from here."
            } else {
                "This overrides what the server observed on chain. It cannot be undone from here."
            },
            confirmLabel = "Mark",
            destructive = status == InvoiceStatus.Invalid,
            onConfirm = {
                // Two taps in one frame both land here. Only the first finds
                // the mark still open, so one review never asks or sends twice.
                if (confirming != null) {
                    confirming = null
                    if (settles) {
                        val subtitle = "$amount, invoice ${TextUtil.middleEllipsis(invoice.id, 8, 6)}, $store"
                        scope.afterSpendGate(gate, "Confirm settled invoice", subtitle, { snackbarHostState.showSnackbar(it) }) {
                            viewModel.mark(status)
                        }
                    } else {
                        viewModel.mark(status)
                    }
                }
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
        //
        // Drawn by AmountText, like the row, so privacy mode masks it and the
        // row's figure and this one are the same text.
        AmountText(
            amount = invoice.amount,
            currency = invoice.currency,
            modifier = Modifier.continuity("invoice-amount-${invoice.id}"),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        StatusChip(
            status = invoice.status,
            modifier = Modifier.continuity("invoice-status-${invoice.id}"),
            detail = invoice.statusDetail(),
        )
        if (invoice.paidAmount.signum() > 0 && invoice.paidAmount.compareTo(invoice.amount) != 0) {
            Spacer(Modifier.height(8.dp))
            AmountPaid(
                amount = invoice.paidAmount,
                currency = invoice.currency,
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

    // The tip is the one amount this app writes into metadata (the Terminal
    // does), so privacy mode masks it with the others.
    val privacy = LocalSettings.current.privacyMode
    val entries = metadata.entries
        .mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { if (privacy && key == "tipAmount") Amounts.MASK else it }
                ?.let { (known[key] ?: TextUtil.sentenceCase(key)) to it }
        }

    if (entries.isEmpty()) return

    SectionHeader("Order")
    entries.forEach { (label, value) -> DetailRow(label, value) }
}

@Composable
private fun PaymentMethodCard(method: InvoicePaymentMethodData, invoiceCurrency: String) {
    val unit = LocalSettings.current.bitcoinUnit
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
            DetailRow("Due", maskedIfPrivate(methodAmountText(method.due, method.currency, unit)))
            DetailRow("Paid", maskedIfPrivate(methodAmountText(method.totalPaid, method.currency, unit)))
            if (method.paymentMethodFee.signum() > 0) {
                val fee = methodAmountText(method.paymentMethodFee, method.currency, unit)
                DetailRow("Network fee", maskedIfPrivate(fee))
            }
            if (method.rate.signum() > 0) {
                val rate = maskedIfPrivate(Amounts.format(method.rate, invoiceCurrency))
                DetailRow("Rate", "$rate / ${method.currency}")
            }
            if (method.destination.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                CopyableField(label = "Destination", value = method.destination, truncate = true)
            }
        }
    }
}

@Composable
private fun PaymentCard(payment: PaymentData, currency: String) {
    val unit = LocalSettings.current.bitcoinUnit
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = maskedIfPrivate(methodAmountText(payment.value, currency, unit)),
                    style = MaterialTheme.typography.titleMedium,
                )
                StatusPill(
                    label = payment.status.name,
                    container = when (payment.status) {
                        PaymentStatus.Settled -> AppTheme.statusColors.settled
                        PaymentStatus.Processing -> AppTheme.statusColors.pending
                        else -> AppTheme.statusColors.invalid
                    },
                    content = when (payment.status) {
                        PaymentStatus.Settled -> AppTheme.statusColors.onSettled
                        PaymentStatus.Processing -> AppTheme.statusColors.onPending
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

/**
 * A payment method's figure in its own unit: BTC in the unit the user chose
 * (sat by default, as on Home and in the wallet), any other chain with its
 * code. Shared with the checkout, so one payment does not read "12,345 sat"
 * on one screen and "0.00012345 BTC" on the next.
 */
internal fun methodAmountText(value: BigDecimal, cryptoCode: String, unit: BitcoinUnit): String =
    if (cryptoCode.equals("BTC", ignoreCase = true)) {
        Amounts.formatBitcoin(value, unit)
    } else {
        Amounts.format(value, cryptoCode)
    }

/**
 * Whether the server will refund this invoice: its `InvoiceState.CanRefund()`
 * allows settled and invalid invoices, and expired ones that still took money.
 * When only settled invoices offered a refund, an underpayment (a wallet that
 * took its fee out of the amount) could not be returned from the counter.
 */
internal val InvoiceData.offersRefund: Boolean
    get() = when (status) {
        InvoiceStatus.Settled, InvoiceStatus.Invalid -> true
        InvoiceStatus.Expired -> additionalStatus == InvoiceAdditionalStatus.PaidPartial ||
            additionalStatus == InvoiceAdditionalStatus.PaidLate ||
            additionalStatus == InvoiceAdditionalStatus.PaidOver
        InvoiceStatus.New, InvoiceStatus.Processing, InvoiceStatus.Unknown -> false
    }
