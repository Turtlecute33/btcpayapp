package com.btcpayapp.ui.screens.invoice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import com.btcpayapp.data.api.dto.InvoiceAdditionalStatus
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
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusChip
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.continuity
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/**
 * The most rows a return to the list reads again, so one request never asks
 * for thousands of invoices. A list scrolled further is cut back to this.
 */
private const val MAX_RELOAD_ROWS = 10 * PAGE_SIZE

data class InvoiceListState(
    val invoices: List<InvoiceData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: ApiException? = null,
    val query: String = "",
    val statusFilter: InvoiceStatus? = null,
    /** False when the key certainly cannot create invoices here; the New button then goes. */
    val canCreate: Boolean = false,
    /**
     * Counts the resets that landed. The screen asks for the next page again
     * on each: [InvoiceListViewModel.loadMore] refuses during a reset, and a
     * reset that brings back as many rows as before changes nothing else the
     * screen watches. Without this count, a page refused then was never asked
     * for again until the user scrolled.
     */
    val reloads: Int = 0,
) {
    /** A search or a status filter is narrowing the list, so an empty one is not an empty store. */
    val filtered: Boolean get() = query.isNotBlank() || statusFilter != null
}

class InvoiceListViewModel(private val graph: AppGraph) : ViewModel() {

    // Loading from the start, so the first frame is the skeleton and not
    // "No invoices yet" while the store list is still on its way.
    private val _state = MutableStateFlow(InvoiceListState(loading = true))
    val state = _state.asStateFlow()

    /**
     * The store this list was opened in, taken once. The shell closes the tab
     * when the store changes, so nothing here watches for a switch.
     */
    private var storeId: String? = null

    private val searchTrigger = MutableStateFlow("")

    /** The query of the last reset, so the debounce does not load a search that already ran. */
    private var searched = ""

    /**
     * Bumped by every reset. A load started under an older generation drops
     * its result, whatever order the responses come back in.
     */
    private var generation = 0
    private var resetJob: Job? = null
    private var moreJob: Job? = null

    /** False until the first return, which is the screen's first resume and needs no reload. */
    private var resumedBefore = false

    init {
        viewModelScope.launch {
            val session = graph.session
            // A store, or the news that the key sees none. `""` stands for
            // the second, which leaves the list empty instead of loading.
            val id = combine(session.activeStore, session.storesLoaded, session.stores) { store, loaded, all ->
                store?.id ?: "".takeIf { loaded && all.isEmpty() }
            }.filterNotNull().first()
            if (id.isEmpty()) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            storeId = id
            _state.update { it.copy(canCreate = session.canCreateInvoice(id)) }
            reset()
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            searchTrigger
                // Without this the initial "" is debounced through and fires a
                // second full page-1 load 350 ms after the first one — two
                // requests per tab open, with a window for them to interleave.
                .drop(1)
                .debounce(350)
                .distinctUntilChanged()
                .collectLatest { if (it != searched) reset() }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        searchTrigger.value = value
    }

    /** The keyboard's Search key: the typed search now, not after the pause. */
    fun searchNow() {
        if (_state.value.query != searched) reset()
    }

    fun setStatus(status: InvoiceStatus?) {
        _state.update { it.copy(statusFilter = status) }
        reset()
    }

    fun clearFilters() {
        _state.update { it.copy(query = "", statusFilter = null) }
        searchTrigger.value = ""
        reset()
    }

    fun refresh() = reset(refreshing = true)

    /**
     * Each return to the list reads the rows it shows again, in one request,
     * so a status changed in the detail screen or on the server is not left
     * stale, and the scroll position survives.
     */
    fun resume() {
        if (!resumedBefore) {
            resumedBefore = true
            return
        }
        val shown = _state.value.invoices.size
        val pages = ((shown + PAGE_SIZE - 1) / PAGE_SIZE).coerceIn(1, MAX_RELOAD_ROWS / PAGE_SIZE)
        reset(take = pages * PAGE_SIZE)
    }

    fun loadMore() {
        val current = _state.value
        // Not during a reset: skip would come from the old list and the page
        // from the new filter.
        if (resetJob?.isActive == true || current.loading || current.loadingMore || current.endReached) return
        _state.update { it.copy(loadingMore = true, error = null) }
        moreJob = load(ticket = generation, skip = current.invoices.size, take = PAGE_SIZE)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Starts the list again from the top, [take] rows of it.
     *
     * A reset supersedes everything in flight: it cancels the running loads
     * and moves to a new [generation]. Without that a page asked for at
     * skip=100 under the old filter could land after the reset and be added
     * to the new list, with invoices 25-99 silently missing and the order
     * wrong.
     */
    private fun reset(refreshing: Boolean = false, take: Int = PAGE_SIZE) {
        if (storeId == null) return
        resetJob?.cancel()
        moreJob?.cancel()
        val ticket = ++generation
        searched = _state.value.query
        _state.update {
            it.copy(
                loading = !refreshing && it.invoices.isEmpty(),
                refreshing = refreshing,
                loadingMore = false,
                error = null,
            )
        }
        resetJob = load(ticket = ticket, skip = 0, take = take)
    }

    private fun load(ticket: Int, skip: Int, take: Int): Job = viewModelScope.launch {
        val store = storeId ?: return@launch
        val snapshot = _state.value
        val result = runCatching {
            graph.session.requireApi().invoices(
                storeId = store,
                statuses = snapshot.statusFilter?.let(::listOf),
                textSearch = snapshot.query.takeIf { it.isNotBlank() },
                skip = skip,
                take = take,
            )
        }
        // Superseded by a reset: this result belongs to a list that is gone.
        if (ticket != generation) return@launch
        result.onSuccess { page ->
            _state.update { current ->
                current.copy(
                    // `distinctBy` is load-bearing, not defensive. Paging by
                    // `skip` against a list the server returns newest-first
                    // means an invoice created while the user scrolls shifts
                    // every later page by one, so the boundary row comes back
                    // twice. With `key = { it.id }` on the LazyColumn a
                    // repeated id is a hard IllegalArgumentException, which
                    // would crash the Invoices tab on any busy store.
                    invoices = if (skip == 0) page else (current.invoices + page).distinctBy { it.id },
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    endReached = page.size < take,
                    error = null,
                    reloads = if (skip == 0) current.reloads + 1 else current.reloads,
                )
            }
        }.onFailure { failure ->
            // Cancellation is not a failure: navigating away or superseding
            // a load would otherwise paint a "StandaloneCoroutine was
            // cancelled" error banner over a perfectly healthy screen.
            val error = failure.asApiException()
            _state.update {
                it.copy(loading = false, refreshing = false, loadingMore = false, error = error)
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

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    // Infinite scroll: fetch the next page once the tail comes into view, and
    // check again after each reset (see `reloads`).
    LaunchedEffect(listState, state.invoices.size, state.reloads) {
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
            IconButton(
                onClick = {
                    // Closing the field ends the search. A query left running
                    // behind a hidden field keeps the list filtered with
                    // nothing on screen to say so.
                    if (searching) viewModel.setQuery("")
                    searching = !searching
                },
            ) {
                Icon(
                    imageVector = if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (searching) "Close search" else "Search",
                )
            }
        },
        floatingActionButton = {
            if (state.canCreate) {
                ExtendedFloatingActionButton(
                    onClick = onCreateInvoice,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (searching) {
                FormField(
                    label = "Search",
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Order id, buyer email, item…",
                    imeAction = ImeAction.Search,
                    onImeAction = viewModel::searchNow,
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

                    // With a search or a filter on, an empty list says nothing
                    // about the store, and "No invoices yet" with "Create one"
                    // would say the wrong thing.
                    InvoiceListPhase.Empty -> if (state.filtered) {
                        EmptyState(
                            title = "No matches",
                            description = "No invoice matches this search or filter.",
                            icon = Icons.Rounded.SearchOff,
                            actionLabel = "Clear filters",
                            onAction = {
                                searching = false
                                viewModel.clearFilters()
                            },
                        )
                    } else {
                        EmptyState(
                            title = "No invoices yet",
                            description = "Invoices created in this store will appear here.",
                            icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                            actionLabel = "Create one".takeIf { state.canCreate },
                            onAction = onCreateInvoice,
                        )
                    }

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

@OptIn(ExperimentalLayoutApi::class)
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
            // A flow, not a row: "Expired · paid partial" with a BTC amount or
            // a large font leaves no room, and the date then goes under the
            // chip instead of wrapping in a sliver beside it.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    status = invoice.status,
                    modifier = Modifier.continuity("invoice-status-${invoice.id}"),
                    detail = invoice.statusDetail(),
                )
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
                AmountPaid(
                    amount = invoice.paidAmount,
                    currency = invoice.currency,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The chip label when the invoice has a payment exception: the status and the
 * exception together ("Expired · paid partial"), else null for the plain
 * status. Both, because each says something the other does not: an expired
 * invoice that took $60 needs a refund, which "Expired" alone hides, and
 * "Paid over" alone hides that a Processing payment is not confirmed yet.
 * An exception that only repeats the status (Invalid, invalid) adds nothing.
 */
internal fun InvoiceData.statusDetail(): String? = additionalStatus
    .takeIf {
        it != InvoiceAdditionalStatus.None && it != InvoiceAdditionalStatus.Unknown && it.name != status.name
    }
    ?.let { "${status.name} · ${TextUtil.sentenceCase(it.name).lowercase()}" }

/**
 * "<amount> paid", masked like any other amount. Says which of two figures
 * is the money that came in, not the money still due.
 */
@Composable
internal fun AmountPaid(amount: BigDecimal, currency: String, style: TextStyle, color: Color) {
    Row {
        AmountText(
            amount = amount,
            currency = currency,
            modifier = Modifier.alignByBaseline(),
            style = style,
            color = color,
        )
        Text(text = " paid", modifier = Modifier.alignByBaseline(), style = style, color = color)
    }
}
