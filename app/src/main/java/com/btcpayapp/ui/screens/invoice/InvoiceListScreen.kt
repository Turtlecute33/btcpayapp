package com.btcpayapp.ui.screens.invoice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.endpoints.invoices
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusChip
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.continuity
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reference implementation for a list screen.
 *
 * The pattern used across the app:
 *   - a `ViewModel` holds one immutable `State` data class in a `StateFlow`,
 *   - it talks to the API through `graph.session.requireApi()` and the endpoint
 *     extension functions, never to HTTP directly,
 *   - `ApiException` is caught and stored, never thrown at the composable,
 *   - the composable is a pure function of state plus navigation lambdas.
 */
private const val PAGE_SIZE = 25

data class InvoiceListState(
    val invoices: List<InvoiceData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: ApiException? = null,
    val query: String = "",
    val statusFilter: InvoiceStatus? = null,
)

class InvoiceListViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(InvoiceListState())
    val state = _state.asStateFlow()

    private val storeId get() = graph.session.activeStore.value?.id

    @OptIn(FlowPreview::class)
    private val searchTrigger = MutableStateFlow("")

    init {
        viewModelScope.launch {
            // Reload when the user switches store or account.
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest { if (it != null) load(reset = true) }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            searchTrigger
                // Without this the initial "" is debounced through and fires a
                // second full page-1 load 350 ms after the activeStore
                // collector has already done one — two requests per tab open,
                // with a window for them to interleave.
                .drop(1)
                .debounce(350)
                .distinctUntilChanged()
                .collectLatest { load(reset = true) }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        searchTrigger.value = value
    }

    fun setStatus(status: InvoiceStatus?) {
        _state.update { it.copy(statusFilter = status) }
        load(reset = true)
    }

    fun refresh() = load(reset = true, refreshing = true)

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || current.endReached) return
        load(reset = false)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private var loadJob: Job? = null

    private fun load(reset: Boolean, refreshing: Boolean = false) {
        val store = storeId ?: return
        // A reset supersedes whatever is in flight. Merely clearing
        // `loadingMore` would release the guard in `loadMore()` while that
        // request is still running, and the stale page would then be appended
        // onto the freshly reset list.
        if (reset) loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = reset && !refreshing && it.invoices.isEmpty(),
                    refreshing = refreshing,
                    loadingMore = !reset,
                    error = null,
                )
            }

            val snapshot = _state.value
            val skip = if (reset) 0 else snapshot.invoices.size

            runCatching {
                graph.session.requireApi().invoices(
                    storeId = store,
                    statuses = snapshot.statusFilter?.let(::listOf),
                    textSearch = snapshot.query.takeIf { it.isNotBlank() },
                    skip = skip,
                    take = PAGE_SIZE,
                )
            }.onSuccess { page ->
                _state.update { current ->
                    current.copy(
                        // `distinctBy` is load-bearing, not defensive. Paging by
                        // `skip` against a list the server returns newest-first
                        // means an invoice created while the user scrolls shifts
                        // every later page by one, so the boundary row comes back
                        // twice. With `key = { it.id }` on the LazyColumn a
                        // repeated id is a hard IllegalArgumentException, which
                        // would crash the Invoices tab on any busy store.
                        invoices = if (reset) page else (current.invoices + page).distinctBy { it.id },
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        endReached = page.size < PAGE_SIZE,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                // Cancellation is not a failure: navigating away or superseding
                // a load would otherwise paint a "StandaloneCoroutine was
                // cancelled" error banner over a perfectly healthy screen.
                if (failure is CancellationException) throw failure
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        error = failure as? ApiException
                            ?: ApiException.Transport(failure.message ?: "Unexpected failure"),
                    )
                }
            }
        }
    }
}

/**
 * Which of the four mutually exclusive bodies the list is showing.
 *
 * The swap animates on this and not on the state, so a refresh that returns the
 * same twenty-five invoices leaves the list alone instead of dissolving it and
 * building it again.
 */
private enum class InvoiceListPhase { Loading, Error, Empty, Content }

@Composable
fun InvoiceListScreen(
    onOpenInvoice: (String) -> Unit,
    onCreateInvoice: () -> Unit,
) {
    val viewModel = appViewModel { InvoiceListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var searching by rememberSaveable { mutableStateOf(false) }

    // Infinite scroll: fetch the next page once the tail comes into view.
    LaunchedEffect(listState, state.invoices.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filter { it != null && it >= state.invoices.size - 5 }
            .collectLatest { viewModel.loadMore() }
    }

    AppScreen(
        title = "Invoices",
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { searching = !searching }) {
                Icon(Icons.Rounded.Search, contentDescription = "Search")
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateInvoice,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (searching) {
                com.btcpayapp.ui.components.FormField(
                    label = "Search",
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Order id, buyer email, item…",
                )
            }

            StatusFilterRow(
                selected = state.statusFilter,
                onSelect = viewModel::setStatus,
            )

            // Only when there is already a list underneath: with nothing to show
            // the failure belongs in the body, as a full ErrorState.
            if (state.invoices.isNotEmpty()) {
                ErrorBanner(state.error, onDismiss = viewModel::dismissError, onRetry = viewModel::refresh)
            }

            val phase = when {
                state.loading -> InvoiceListPhase.Loading
                state.error != null && state.invoices.isEmpty() -> InvoiceListPhase.Error
                state.invoices.isEmpty() -> InvoiceListPhase.Empty
                else -> InvoiceListPhase.Content
            }

            AnimatedSwap(phase, label = "invoices") { shown ->
                when (shown) {
                    // Rows in outline rather than a spinner, so the invoices
                    // land into a page that is already their shape.
                    InvoiceListPhase.Loading -> SkeletonList()

                    InvoiceListPhase.Error ->
                        ErrorState(error = state.error, onRetry = viewModel::refresh)

                    InvoiceListPhase.Empty -> EmptyState(
                        title = "No invoices yet",
                        description = "Invoices created in this store will appear here.",
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        actionLabel = "Create one",
                        onAction = onCreateInvoice,
                    )

                    InvoiceListPhase.Content ->
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(state.invoices, key = { it.id }) { invoice ->
                                // On the item rather than on the row, because
                                // the row and its divider move together and a
                                // rule left behind by a reordering row is worse
                                // than no animation at all.
                                Column(Modifier.animateItem()) {
                                    InvoiceRow(invoice = invoice, onClick = { onOpenInvoice(invoice.id) })
                                    ThinDivider()
                                }
                            }
                            // Kept in the list rather than added to it, so the
                            // footer can collapse when the page lands instead of
                            // vanishing out from under the row above it.
                            item(key = "load-more") {
                                AnimatedVisibility(
                                    visible = state.loadingMore,
                                    enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                                    exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(24.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun StatusFilterRow(selected: InvoiceStatus?, onSelect: (InvoiceStatus?) -> Unit) {
    val options = remember {
        listOf(
            null to "All",
            InvoiceStatus.New to "New",
            InvoiceStatus.Processing to "Processing",
            InvoiceStatus.Settled to "Settled",
            InvoiceStatus.Expired to "Expired",
            InvoiceStatus.Invalid to "Invalid",
        )
    }
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { (status, label) ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(status) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun InvoiceRow(invoice: InvoiceData, onClick: () -> Unit) {
    val orderId = remember(invoice) {
        (invoice.metadata?.get("orderId") as? JsonPrimitive)?.content
    }
    val itemDesc = remember(invoice) {
        (invoice.metadata?.get("itemDesc") as? JsonPrimitive)?.content
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = itemDesc ?: orderId ?: invoice.id,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    status = invoice.status,
                    modifier = Modifier.continuity("invoice-status-${invoice.id}"),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Dates.relative(invoice.createdTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // The amount and the status are the same two facts the detail
            // screen leads with, so they travel there rather than being
            // dismissed and redrawn. Keyed by role as well as by invoice: the
            // row shows two amounts, and only this one is the invoice total.
            AmountText(
                amount = invoice.amount,
                currency = invoice.currency,
                modifier = Modifier.continuity("invoice-amount-${invoice.id}"),
                style = MaterialTheme.typography.titleMedium,
            )
            if (invoice.paidAmount.signum() > 0 && invoice.status != InvoiceStatus.Settled) {
                AmountText(
                    amount = invoice.paidAmount,
                    currency = invoice.currency,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
