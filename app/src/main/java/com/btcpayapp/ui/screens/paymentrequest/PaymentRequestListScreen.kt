package com.btcpayapp.ui.screens.paymentrequest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.btcpayapp.data.api.dto.PaymentRequestData
import com.btcpayapp.data.api.dto.PaymentRequestStatus
import com.btcpayapp.data.api.endpoints.archivePaymentRequest
import com.btcpayapp.data.api.endpoints.paymentRequests
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.copyToClipboard
import com.btcpayapp.ui.screens.apps.publicLinkWarning
import com.btcpayapp.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the body of the screen is showing. A discriminator rather than the
 * state, so a refresh returning the same requests does not replay the list.
 */
private enum class PaymentRequestsPhase { NoStore, Loading, Error, Empty, Content }

enum class PaymentRequestFilter(val label: String) {
    All("All"),
    Open("Open"),
    Completed("Completed"),
    Archived("Archived"),
}

data class PaymentRequestListState(
    /** The store [requests] belong to. Rows and actions never outlive it. */
    val storeId: String? = null,
    val requests: List<PaymentRequestData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val filter: PaymentRequestFilter = PaymentRequestFilter.All,
    val storeMissing: Boolean = false,
    val baseUrl: String? = null,
    /** Set when [baseUrl] is an address customers may not reach; see [publicLinkWarning]. */
    val linkWarning: String? = null,
    val fallbackCurrency: String = "USD",
    val pendingArchive: PaymentRequestData? = null,
    val message: String? = null,
) {
    /**
     * "All" means everything still live. An archived request is a deliberate
     * choice to stop showing it, so it gets its own bucket rather than sitting
     * in the default view greyed out.
     */
    val visible: List<PaymentRequestData>
        get() = when (filter) {
            PaymentRequestFilter.All -> requests.filterNot { it.archived }
            PaymentRequestFilter.Open -> requests.filter {
                !it.archived &&
                    (it.status == PaymentRequestStatus.Pending || it.status == PaymentRequestStatus.Processing)
            }
            PaymentRequestFilter.Completed ->
                requests.filter { !it.archived && it.status == PaymentRequestStatus.Completed }
            PaymentRequestFilter.Archived -> requests.filter { it.archived }
        }

    /** The page a customer opens. Null until the active account is known. */
    fun publicLink(id: String): String? = baseUrl?.let { "$it/payment-requests/$id" }
}

class PaymentRequestListViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PaymentRequestListState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest { id ->
                    // The old store's rows go at once, not when the reload
                    // lands: until then they would sit under the new store's
                    // name, with Archive one tap away.
                    _state.update {
                        it.copy(
                            storeId = id,
                            requests = emptyList(),
                            loading = false,
                            refreshing = false,
                            error = null,
                            pendingArchive = null,
                            storeMissing = id == null,
                            fallbackCurrency = graph.session.activeStore.value?.defaultCurrency
                                ?: it.fallbackCurrency,
                        )
                    }
                    if (id != null) load()
                }
        }
        viewModelScope.launch {
            graph.session.activeAccount.collectLatest { account ->
                _state.update {
                    it.copy(baseUrl = account?.baseUrl, linkWarning = account?.let { a -> publicLinkWarning(a.host) })
                }
            }
        }
    }

    fun setFilter(filter: PaymentRequestFilter) = _state.update { it.copy(filter = filter) }

    fun refresh() = load(refreshing = true)

    /**
     * A quiet reload when the screen comes back, from the edit screen or
     * another app. Without it a request just created was missing from the
     * list, and could be created a second time. Ignored while a load runs.
     */
    fun reload() {
        val current = _state.value
        if (!current.loading && !current.refreshing) load()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun message(text: String) = _state.update { it.copy(message = text) }

    fun requestArchive(request: PaymentRequestData) = _state.update { it.copy(pendingArchive = request) }

    fun dismissArchive() = _state.update { it.copy(pendingArchive = null) }

    fun confirmArchive() {
        val target = _state.value.pendingArchive ?: return
        val store = _state.value.storeId ?: return
        _state.update { it.copy(pendingArchive = null) }
        viewModelScope.launch {
            runCatching { graph.session.requireApi().archivePaymentRequest(store, target.id) }
                .onSuccess {
                    _state.update { it.copy(message = "“${target.title}” archived") }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.asApiException().userMessage) }
                }
        }
    }

    private fun load(refreshing: Boolean = false) {
        val store = _state.value.storeId ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refreshing && it.requests.isEmpty(),
                    refreshing = refreshing,
                    error = null,
                )
            }
            // Archived ones too: the server leaves them out by default, and
            // the Archived filter below was always empty. The split is here.
            runCatching { graph.session.requireApi().paymentRequests(store, includeArchived = true) }
                .onSuccess { list ->
                    // A late answer for a store the user has left is dropped.
                    _state.update {
                        if (it.storeId != store) {
                            it
                        } else {
                            it.copy(
                                requests = list.sortedByDescending(PaymentRequestData::createdTime),
                                loading = false,
                                refreshing = false,
                                error = null,
                            )
                        }
                    }
                }
                .onFailure { failure ->
                    val error = failure.asApiException()
                    _state.update {
                        if (it.storeId != store) it else it.copy(loading = false, refreshing = false, error = error)
                    }
                }
        }
    }
}

@Composable
fun PaymentRequestListScreen(
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel { PaymentRequestListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var qrLink by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = "Payment requests",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A fixed list that never reorders, so there is nothing for
                // `animateItem` to animate and no need for a key.
                items(PaymentRequestFilter.entries.toList()) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }

            if (state.requests.isNotEmpty()) {
                ErrorBanner(
                    state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                )
            }

            val phase = when {
                state.storeMissing -> PaymentRequestsPhase.NoStore
                state.loading -> PaymentRequestsPhase.Loading
                state.error != null && state.requests.isEmpty() -> PaymentRequestsPhase.Error
                state.visible.isEmpty() -> PaymentRequestsPhase.Empty
                else -> PaymentRequestsPhase.Content
            }

            AnimatedSwap(phase, label = "paymentRequests") { shown ->
                when (shown) {
                    PaymentRequestsPhase.NoStore -> EmptyState(
                        title = "No store selected",
                        description = "Choose a store to see its payment requests.",
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    )

                    PaymentRequestsPhase.Loading -> SkeletonList()

                    // Read through the nullable: a retry clears the error on the
                    // frame this branch starts leaving, and it is still composed.
                    PaymentRequestsPhase.Error -> state.error?.let {
                        ErrorState(error = it, onRetry = viewModel::refresh)
                    }

                    PaymentRequestsPhase.Empty -> EmptyState(
                        title = if (state.filter == PaymentRequestFilter.All) {
                            "No payment requests yet"
                        } else {
                            "Nothing under this filter"
                        },
                        description = "A payment request is a reusable page a customer can pay from.",
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        actionLabel = "Create one".takeIf { state.filter == PaymentRequestFilter.All },
                        onAction = onCreate.takeIf { state.filter == PaymentRequestFilter.All },
                    )

                    PaymentRequestsPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.visible, key = { it.id }) { request ->
                            // Row and rule travel together, so archiving one
                            // closes the gap instead of stranding a divider.
                            Column(Modifier.animateItem()) {
                                PaymentRequestRow(
                                    request = request,
                                    fallbackCurrency = state.fallbackCurrency,
                                    onClick = { onOpen(request.id) },
                                    onCopyLink = {
                                        val link = state.publicLink(request.id)
                                        if (link == null) {
                                            viewModel.message("The account URL is not known yet.")
                                        } else {
                                            copyToClipboard(context, "Payment request link", link)
                                            viewModel.message(state.linkWarning?.let { "Link copied. $it" } ?: "Link copied")
                                        }
                                    },
                                    onShowQr = {
                                        val link = state.publicLink(request.id)
                                        if (link == null) {
                                            viewModel.message("The account URL is not known yet.")
                                        } else {
                                            qrLink = link
                                        }
                                    },
                                    onArchive = { viewModel.requestArchive(request) },
                                )
                                ThinDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingArchive?.let { target ->
        ConfirmDialog(
            title = "Archive payment request?",
            message = "“${target.title}” will stop accepting payments and drop out of the default list.",
            confirmLabel = "Archive",
            onConfirm = viewModel::confirmArchive,
            onDismiss = viewModel::dismissArchive,
            destructive = true,
        )
    }

    qrLink?.let { link ->
        ModalBottomSheet(onDismissRequest = { qrLink = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text(
                    text = "Share this payment request",
                    modifier = Modifier.arrive(0),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                state.linkWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                // `QrCode` fades its own bitmap in once the encoder has run, so
                // the card is not given a second entrance on top of that.
                QrCode(content = link, contentDescription = "Payment request link")
                Spacer(Modifier.height(16.dp))
                CopyableField(label = "Link", value = link, modifier = Modifier.arrive(2))
            }
        }
    }
}

@Composable
private fun PaymentRequestRow(
    request: PaymentRequestData,
    fallbackCurrency: String,
    onClick: () -> Unit,
    onCopyLink: () -> Unit,
    onShowQr: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = request.title.ifBlank { request.id },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaymentRequestStatusPill(request.status)
                if (request.archived) {
                    Spacer(Modifier.width(6.dp))
                    ArchivedPill()
                }
                request.expiryDate?.let { expiry ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Expires ${Dates.date(expiry)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        AmountText(
            amount = request.amount,
            currency = request.currency ?: fallbackCurrency,
            style = MaterialTheme.typography.titleMedium,
        )

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy link") },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopyLink()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Show QR") },
                    leadingIcon = { Icon(Icons.Rounded.QrCode2, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onShowQr()
                    },
                )
                if (!request.archived) {
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onArchive()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentRequestStatusPill(status: PaymentRequestStatus) {
    val colors = AppTheme.statusColors
    val (container, content) = when (status) {
        PaymentRequestStatus.Completed -> colors.settled to colors.onSettled
        PaymentRequestStatus.Pending, PaymentRequestStatus.Processing -> colors.pending to colors.onPending
        PaymentRequestStatus.Expired -> colors.expired to colors.onExpired
        PaymentRequestStatus.Unknown -> colors.invalid to colors.onInvalid
    }
    StatusPill(label = TextUtil.sentenceCase(status.name), container = container, content = content)
}

@Composable
private fun ArchivedPill() {
    val colors = AppTheme.statusColors
    StatusPill(label = "Archived", container = colors.expired, content = colors.onExpired)
}

