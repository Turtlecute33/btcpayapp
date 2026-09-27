package com.btcpayapp.ui.screens.lightning

import com.btcpayapp.ui.theme.Motion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.LightningInvoiceData
import com.btcpayapp.data.api.dto.LightningInvoiceStatus
import com.btcpayapp.data.api.dto.LightningPaymentData
import com.btcpayapp.data.api.dto.LightningPaymentStatus
import com.btcpayapp.core.lightning.Bolt11
import com.btcpayapp.data.api.endpoints.lightningInvoices
import com.btcpayapp.data.api.endpoints.lightningPayments
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AnimatedPage
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DirectionIcon
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.components.NoStoreSelectedState
import com.btcpayapp.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAB_INVOICES = 0
private const val TAB_PAYMENTS = 1

/**
 * What the body of the screen is showing, tabs included as a single state.
 *
 * The two tabs are peers with an order, so they move sideways against each
 * other rather than dissolving; everything else here replaces the screen and
 * gets the ordinary swap.
 */
private enum class PaymentsPhase { NoStore, Offline, Tabs }

/** The same idea inside a tab, where a list is either coming, absent or there. */
private enum class PaymentsListPhase { Loading, Error, Empty, Content }

/**
 * One row per payment hash, newest first.
 *
 * Both lists are loaded once and never paged. `offsetIndex` is no page cursor:
 * LND and CLN read it as a createdAt filter in milliseconds for payments and as
 * the node's own add or pay index for invoices, so paging with the list size
 * fetched the whole history again and showed every payment several times.
 * The node also sends its list in its own order (LND oldest first), and
 * CLN can list one hash more than once when a payment was retried. The newest
 * entry per hash is the one that says what happened. A blank hash matches
 * nothing and keeps its row.
 */
internal fun <T> newestPerHash(items: List<T>, hash: (T) -> String, time: (T) -> Long?): List<T> {
    val seen = HashSet<String>()
    return items
        .sortedByDescending { time(it) ?: Long.MIN_VALUE }
        .filter { item -> hash(item).let { it.isBlank() || seen.add(it) } }
}

/**
 * A stable, unique key per row.
 *
 * Stable — the payment hash — so that a pull-to-refresh moves the rows already
 * on screen instead of discarding and re-measuring every visible one, which
 * would make this list jump and lose its place.
 *
 * Unique because a duplicate key in a `LazyColumn` is a crash rather than a
 * cosmetic fault. [newestPerHash] leaves one row per hash, but a backend that
 * leaves the hash blank falls back to the BOLT11 string, which can repeat, and
 * then to the position.
 */
private fun rowKeys(identities: List<String>): List<String> {
    val used = HashSet<String>(identities.size)
    return identities.mapIndexed { index, identity ->
        val base = identity.ifBlank { "row-$index" }
        var key = base
        var repeat = 1
        while (!used.add(key)) {
            key = "$base#$repeat"
            repeat++
        }
        key
    }
}

data class LightningPaymentsState(
    val tab: Int = TAB_INVOICES,

    val invoices: List<LightningInvoiceData> = emptyList(),
    val invoicesLoading: Boolean = false,
    val pendingOnly: Boolean = false,

    val payments: List<LightningPaymentData> = emptyList(),
    val paymentsLoading: Boolean = false,
    val includePending: Boolean = true,

    val refreshing: Boolean = false,
    val nodeOffline: Boolean = false,
    val noStore: Boolean = false,
    val error: ApiException? = null,
)

class LightningPaymentsViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    private val node = LightningBinding(graph.session, serverNode)

    private val _state = MutableStateFlow(LightningPaymentsState(invoicesLoading = true))
    val state = _state.asStateFlow()
    private var invoiceJob: Job? = null
    private var paymentJob: Job? = null

    init {
        loadInvoices()
        node.retryWhenKnown(viewModelScope) {
            if (_state.value.tab == TAB_INVOICES) loadInvoices() else loadPayments()
        }
    }

    fun selectTab(index: Int) {
        if (_state.value.tab == index) return
        _state.update { it.copy(tab = index, error = null) }
        if (index == TAB_PAYMENTS && _state.value.payments.isEmpty()) loadPayments()
        if (index == TAB_INVOICES && _state.value.invoices.isEmpty()) loadInvoices()
    }

    fun setPendingOnly(value: Boolean) {
        _state.update { it.copy(pendingOnly = value) }
        loadInvoices()
    }

    fun setIncludePending(value: Boolean) {
        _state.update { it.copy(includePending = value) }
        loadPayments()
    }

    fun refresh() {
        if (_state.value.tab == TAB_INVOICES) loadInvoices(refreshing = true) else loadPayments(refreshing = true)
    }

    /**
     * Reloads the open tab quietly when the screen comes back, so a payment
     * made meanwhile is listed. Skipped while that tab loads, which also
     * covers the first resume, straight after [init].
     */
    fun onResume() {
        val current = _state.value
        if (current.refreshing) return
        if (current.tab == TAB_INVOICES) {
            if (!current.invoicesLoading) loadInvoices()
        } else {
            if (!current.paymentsLoading) loadPayments()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** The whole list, once; see [newestPerHash] for why there is no paging. */
    private fun loadInvoices(refreshing: Boolean = false) {
        invoiceJob?.cancel()
        val scope = node.scope
        if (scope == null) {
            _state.update { it.copy(invoicesLoading = false, refreshing = false, noStore = true) }
            return
        }
        invoiceJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    invoicesLoading = !refreshing && it.invoices.isEmpty(),
                    refreshing = refreshing,
                    noStore = false,
                    error = null,
                )
            }
            val pendingOnly = _state.value.pendingOnly

            runCatching {
                graph.session.requireApi().lightningInvoices(
                    scope = scope,
                    pendingOnly = pendingOnly.takeIf { it },
                    cryptoCode = cryptoCode,
                )
            }.onSuccess { list ->
                // Greenfield gives an invoice no creation time. Its expiry is
                // the closest stand-in: creation plus the expiry window.
                val invoices = newestPerHash(list, { it.paymentHash }, { it.expiresAt })
                _state.update { current ->
                    current.copy(
                        invoices = invoices,
                        invoicesLoading = false,
                        refreshing = false,
                        nodeOffline = false,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update {
                    it.copy(
                        invoicesLoading = false,
                        refreshing = false,
                        nodeOffline = error.isNodeUnreachable(),
                        error = error.takeIf { e -> !e.isNodeUnreachable() },
                    )
                }
            }
        }
    }

    /** As [loadInvoices]. */
    private fun loadPayments(refreshing: Boolean = false) {
        paymentJob?.cancel()
        val scope = node.scope
        if (scope == null) {
            _state.update { it.copy(paymentsLoading = false, refreshing = false, noStore = true) }
            return
        }
        paymentJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    paymentsLoading = !refreshing && it.payments.isEmpty(),
                    refreshing = refreshing,
                    noStore = false,
                    error = null,
                )
            }
            val includePending = _state.value.includePending

            runCatching {
                graph.session.requireApi().lightningPayments(
                    scope = scope,
                    includePending = includePending,
                    cryptoCode = cryptoCode,
                )
            }.onSuccess { list ->
                val payments = newestPerHash(list, { it.paymentHash }, { it.createdAt })
                _state.update { current ->
                    current.copy(
                        payments = payments,
                        paymentsLoading = false,
                        refreshing = false,
                        nodeOffline = false,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update {
                    it.copy(
                        paymentsLoading = false,
                        refreshing = false,
                        nodeOffline = error.isNodeUnreachable(),
                        error = error.takeIf { e -> !e.isNodeUnreachable() },
                    )
                }
            }
        }
    }
}

@Composable
fun LightningPaymentsScreen(
    cryptoCode: String,
    serverNode: Boolean,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "ln-payments:$cryptoCode:$serverNode") {
        LightningPaymentsViewModel(it, cryptoCode, serverNode)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit = LocalSettings.current.bitcoinUnit
    val invoiceListState = rememberLazyListState()
    val paymentListState = rememberLazyListState()

    val activeCount = if (state.tab == TAB_INVOICES) state.invoices.size else state.payments.size

    // Which way the tabs are travelling. The state carries only where the
    // selection ended up, and a page that always slid in from the same side
    // would say the two tabs were a sequence rather than a pair. Recorded in a
    // `SideEffect` so it is updated after the transition has been built from
    // it, not before.
    var previousTab by remember { mutableIntStateOf(state.tab) }
    val forward = state.tab >= previousTab
    SideEffect { previousTab = state.tab }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    AppScreen(
        title = "Invoices and payments",
        subtitle = chainSubtitle(cryptoCode, prefix = "Server node".takeIf { serverNode }),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == TAB_INVOICES,
                    onClick = { viewModel.selectTab(TAB_INVOICES) },
                    text = { Text("Invoices") },
                )
                Tab(
                    selected = state.tab == TAB_PAYMENTS,
                    onClick = { viewModel.selectTab(TAB_PAYMENTS) },
                    text = { Text("Payments") },
                )
            }

            // Only a banner when there is still a list underneath; an empty
            // list gets the full-screen treatment inside the tab instead.
            if (activeCount > 0) {
                ErrorBanner(
                    error = state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                )
            }

            val phase = when {
                state.noStore -> PaymentsPhase.NoStore
                state.nodeOffline -> PaymentsPhase.Offline
                else -> PaymentsPhase.Tabs
            }

            AnimatedSwap(phase, Modifier.fillMaxSize(), label = "payments") { shown ->
                when (shown) {
                    PaymentsPhase.NoStore -> NoStoreSelectedState()

                    PaymentsPhase.Offline -> NodeUnreachableState(onRetry = viewModel::refresh)

                    PaymentsPhase.Tabs -> AnimatedPage(
                        targetState = state.tab,
                        forward = forward,
                        modifier = Modifier.fillMaxSize(),
                        label = "tab",
                    ) { tab ->
                        if (tab == TAB_INVOICES) {
                            InvoicesTab(
                                state = state,
                                unit = unit,
                                listState = invoiceListState,
                                onRetry = viewModel::refresh,
                                onPendingOnly = viewModel::setPendingOnly,
                            )
                        } else {
                            PaymentsTab(
                                state = state,
                                unit = unit,
                                listState = paymentListState,
                                onRetry = viewModel::refresh,
                                onIncludePending = viewModel::setIncludePending,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoicesTab(
    state: LightningPaymentsState,
    unit: BitcoinUnit,
    listState: LazyListState,
    onRetry: () -> Unit,
    onPendingOnly: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    // Keyed off the list itself, so the pass over it happens when the list
    // lands rather than on every recomposition of a screen with a switch on it.
    val keys = remember(state.invoices) {
        rowKeys(state.invoices.map { it.paymentHash.ifBlank { it.BOLT11 } })
    }

    Column(Modifier.fillMaxSize()) {
        FormSwitch(
            title = "Unpaid only",
            checked = state.pendingOnly,
            onCheckedChange = onPendingOnly,
        )
        // Said plainly, because the list cannot be made complete from here:
        // without an index LND sends its oldest invoices, and there is no
        // cursor that pages back to the newest.
        Text(
            text = "Listed by your Lightning node. Some nodes send only their oldest invoices, " +
                "so recent ones can be missing. The store's Invoices screen lists every store payment.",
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThinDivider()

        val phase = when {
            state.invoicesLoading -> PaymentsListPhase.Loading
            state.error != null && state.invoices.isEmpty() -> PaymentsListPhase.Error
            state.invoices.isEmpty() -> PaymentsListPhase.Empty
            else -> PaymentsListPhase.Content
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "invoices") { shown ->
            when (shown) {
                PaymentsListPhase.Loading -> SkeletonList()

                // Read through `?.let` rather than `requireNotNull`. The branch
                // on its way out of a swap stays composed while it fades, and
                // by then the error it was built from has usually been cleared.
                PaymentsListPhase.Error -> state.error?.let { error ->
                    ErrorState(error = error, onRetry = onRetry)
                }

                PaymentsListPhase.Empty -> EmptyState(
                    title = "No invoices",
                    description = "Invoices this node has issued will appear here.",
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                )

                PaymentsListPhase.Content -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.invoices, key = { index, _ -> keys[index] }) { index, invoice ->
                        // Expansion is tracked by the same key the list is
                        // keyed on, not by `id`: a blank `id` would otherwise
                        // open every row on the screen at once.
                        val rowKey = keys[index]
                        InvoiceRow(
                            invoice = invoice,
                            unit = unit,
                            expanded = expanded == rowKey,
                            modifier = Modifier.animateItem(),
                            onToggle = { expanded = if (expanded == rowKey) null else rowKey },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PaymentsTab(
    state: LightningPaymentsState,
    unit: BitcoinUnit,
    listState: LazyListState,
    onRetry: () -> Unit,
    onIncludePending: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    val keys = remember(state.payments) {
        rowKeys(state.payments.map { it.paymentHash.ifBlank { it.BOLT11 } })
    }

    Column(Modifier.fillMaxSize()) {
        FormSwitch(
            title = "Include pending",
            checked = state.includePending,
            onCheckedChange = onIncludePending,
        )
        ThinDivider()

        val phase = when {
            state.paymentsLoading -> PaymentsListPhase.Loading
            state.error != null && state.payments.isEmpty() -> PaymentsListPhase.Error
            state.payments.isEmpty() -> PaymentsListPhase.Empty
            else -> PaymentsListPhase.Content
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "payments-list") { shown ->
            when (shown) {
                PaymentsListPhase.Loading -> SkeletonList()

                PaymentsListPhase.Error -> state.error?.let { error ->
                    ErrorState(error = error, onRetry = onRetry)
                }

                PaymentsListPhase.Empty -> EmptyState(
                    title = "No payments",
                    description = "Payments this node has sent will appear here.",
                    icon = Icons.Rounded.Bolt,
                )

                PaymentsListPhase.Content -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.payments, key = { index, _ -> keys[index] }) { index, payment ->
                        val rowKey = keys[index]
                        PaymentRow(
                            payment = payment,
                            unit = unit,
                            expanded = expanded == rowKey,
                            modifier = Modifier.animateItem(),
                            onToggle = { expanded = if (expanded == rowKey) null else rowKey },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * One row per invoice, not one card with four labelled rows.
 *
 * What an operator scans a list like this for is "did that payment arrive, and
 * for how much" — which is a description, a status and an amount. The payment
 * hash, the BOLT11 string and the QR are reached by tapping the row, because
 * they matter once, when something has gone wrong.
 */
@Composable
private fun InvoiceRow(
    invoice: LightningInvoiceData,
    unit: BitcoinUnit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    // The description lives inside the BOLT11 string; BTCPay does not break it
    // out into its own field, so the only way to show it is to read the invoice.
    val title = remember(invoice.BOLT11, invoice.paymentHash) {
        Bolt11.decode(invoice.BOLT11)?.description?.takeIf { it.isNotBlank() }
            ?: TextUtil.middleEllipsis(invoice.paymentHash, 10, 8)
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectionIcon(incoming = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InvoiceStatusPill(invoice.status)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = invoiceTiming(invoice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                // What arrived beats what was asked for, when they differ.
                text = maskedIfPrivate(msatLabel(invoice.amountReceived ?: invoice.amount, unit)),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    CopyableField(label = "Payment hash", value = invoice.paymentHash, truncate = true)
                    Spacer(Modifier.height(12.dp))
                    CopyableField(label = "BOLT11", value = invoice.BOLT11, truncate = true)
                }
                // Only worth a QR while it can still be paid.
                if (invoice.status == LightningInvoiceStatus.Unpaid) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        QrCode(
                            content = "lightning:${invoice.BOLT11}",
                            modifier = Modifier.fillMaxWidth(0.7f),
                            contentDescription = "Invoice as a QR code",
                        )
                    }
                }
            }
        }
        ThinDivider()
    }
}

/** "paid 2 h ago", "expires in 12 min", "expired 3 d ago" — one line, whichever applies. */
private fun invoiceTiming(invoice: LightningInvoiceData): String = when (val paidAt = invoice.paidAt) {
    null -> if (invoice.status == LightningInvoiceStatus.Unpaid) {
        "expires ${Dates.relative(invoice.expiresAt)}"
    } else {
        Dates.relative(invoice.expiresAt)
    }

    else -> "paid ${Dates.relative(paidAt)}"
}

@Composable
private fun PaymentRow(
    payment: LightningPaymentData,
    unit: BitcoinUnit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val title = remember(payment.BOLT11, payment.paymentHash) {
        Bolt11.decode(payment.BOLT11)?.description?.takeIf { it.isNotBlank() }
            ?: TextUtil.middleEllipsis(payment.paymentHash, 10, 8)
    }
    val fee = remember(payment.feeAmount, unit) {
        payment.feeAmount.takeIfPositive()?.let { msatLabel(it, unit) }
    }?.let { "fee ${maskedIfPrivate(it)}" }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectionIcon(incoming = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaymentStatusPill(payment.status)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = listOfNotNull(fee, payment.createdAt?.let(Dates::relative))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = maskedIfPrivate(msatLabel(payment.totalAmount, unit)),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                CopyableField(label = "Payment hash", value = payment.paymentHash, truncate = true)
                payment.preimage?.takeIf { it.isNotBlank() }?.let { preimage ->
                    Spacer(Modifier.height(12.dp))
                    // The receipt for this payment. Not a credential, but it
                    // proves the payment happened, so it is kept off the
                    // clipboard preview.
                    CopyableField(label = "Preimage", value = preimage, truncate = true, sensitive = true)
                }
            }
        }
        ThinDivider()
    }
}

@Composable
internal fun InvoiceStatusPill(status: LightningInvoiceStatus) {
    val colors = AppTheme.statusColors
    val (container, content) = when (status) {
        LightningInvoiceStatus.Paid -> colors.settled to colors.onSettled
        LightningInvoiceStatus.Unpaid -> colors.pending to colors.onPending
        LightningInvoiceStatus.Expired -> colors.expired to colors.onExpired
        LightningInvoiceStatus.Unknown -> colors.invalid to colors.onInvalid
    }
    StatusPill(label = status.name, container = container, content = content)
}

@Composable
internal fun PaymentStatusPill(status: LightningPaymentStatus) {
    val colors = AppTheme.statusColors
    val (container, content) = when (status) {
        LightningPaymentStatus.Complete -> colors.settled to colors.onSettled
        LightningPaymentStatus.Pending -> colors.pending to colors.onPending
        LightningPaymentStatus.Failed -> colors.invalid to colors.onInvalid
        LightningPaymentStatus.Unknown -> colors.expired to colors.onExpired
    }
    StatusPill(label = status.name, container = container, content = content)
}
