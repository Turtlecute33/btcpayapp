package com.btcpayapp.ui.screens.apps
import kotlinx.coroutines.async

import com.btcpayapp.ui.theme.Motion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.isLocalNetworkHost
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.AppData
import com.btcpayapp.data.api.dto.AppItemStats
import com.btcpayapp.data.api.dto.AppSalesSeriesPoint
import com.btcpayapp.data.api.dto.AppSalesStats
import com.btcpayapp.data.api.endpoints.appSales
import com.btcpayapp.data.api.endpoints.appTopItems
import com.btcpayapp.data.api.endpoints.deleteApp
import com.btcpayapp.data.api.endpoints.storeApps
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val APP_TYPE_POINT_OF_SALE = "PointOfSale"
const val APP_TYPE_CROWDFUND = "Crowdfund"

private const val SALES_DAYS = 7
private const val TOP_ITEMS = 5

/**
 * What the body of the screen is currently showing.
 *
 * The swap is driven by this rather than by [AppsState] itself, so a background
 * refresh that returns the same apps does not re-animate the whole list.
 */
private enum class AppsPhase { Loading, Error, Empty, Content }

/** The same idea one level down, for the statistics panel inside a row. */
private enum class AppStatsPhase { Loading, Error, Content }

data class AppStats(
    val sales: AppSalesStats? = null,
    val topItems: List<AppItemStats> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class AppsState(
    /** The store [apps] belong to. Rows and actions never outlive it. */
    val storeId: String? = null,
    val apps: List<AppData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val expandedId: String? = null,
    val stats: Map<String, AppStats> = emptyMap(),
    val pendingDelete: AppData? = null,
    val qrTarget: AppData? = null,
    val baseUrl: String? = null,
    /** Set when [baseUrl] is an address customers may not reach; see [publicLinkWarning]. */
    val linkWarning: String? = null,
    val message: String? = null,
)

class AppsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(AppsState(baseUrl = graph.session.activeAccount.value?.baseUrl))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.session.activeAccount
                .map { it?.baseUrl to it?.host }
                .distinctUntilChanged()
                .collectLatest { (url, host) ->
                    _state.update { it.copy(baseUrl = url, linkWarning = host?.let(::publicLinkWarning)) }
                }
        }
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest { id ->
                    // The old store's apps go at once, not when the reload
                    // lands: until then they would sit under the new store's
                    // name, with Delete one tap away.
                    _state.update {
                        it.copy(
                            storeId = id,
                            apps = emptyList(),
                            loading = false,
                            refreshing = false,
                            error = null,
                            expandedId = null,
                            stats = emptyMap(),
                            pendingDelete = null,
                            qrTarget = null,
                        )
                    }
                    if (id != null) load(refreshing = false)
                }
        }
    }

    fun refresh() = load(refreshing = true)

    /**
     * A quiet reload when the screen comes back, from an edit screen or
     * another app, so a new or renamed app shows up. Ignored while a load
     * runs.
     */
    fun reload() {
        val current = _state.value
        if (!current.loading && !current.refreshing) load()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun showQr(app: AppData) = _state.update { it.copy(qrTarget = app) }

    fun hideQr() = _state.update { it.copy(qrTarget = null) }

    fun askDelete(app: AppData) = _state.update { it.copy(pendingDelete = app) }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    fun toggleStats(appId: String) {
        val open = _state.value.expandedId == appId
        _state.update { it.copy(expandedId = if (open) null else appId) }
        if (!open) loadStats(appId)
    }

    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            runCatching { graph.session.requireApi().deleteApp(target.id) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            apps = current.apps.filterNot { it.id == target.id },
                            message = "“${target.appName}” deleted.",
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.asApiException().userMessage) }
                }
        }
    }

    private fun loadStats(appId: String) {
        viewModelScope.launch {
            _state.update { it.withStats(appId) { stats -> stats.copy(loading = true, error = null) } }

            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                val text = failure.asApiException().userMessage
                _state.update { it.withStats(appId) { stats -> stats.copy(loading = false, error = text) } }
                return@launch
            }

            val salesCall = async { runCatching { api.appSales(appId, SALES_DAYS) } }
            val itemsCall = async { runCatching { api.appTopItems(appId, TOP_ITEMS) } }
            val sales = salesCall.await()
            val items = itemsCall.await()

            _state.update {
                it.withStats(appId) { stats ->
                    stats.copy(
                        loading = false,
                        sales = sales.getOrNull(),
                        topItems = items.getOrNull() ?: emptyList(),
                        error = sales.exceptionOrNull()?.asApiException()?.userMessage,
                    )
                }
            }
        }
    }

    fun load(refreshing: Boolean = false) {
        val store = _state.value.storeId ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.apps.isEmpty(), refreshing = refreshing, error = null)
            }
            // A late answer for a store the user has left is dropped.
            runCatching { graph.session.requireApi().storeApps(store) }
                .onSuccess { list ->
                    _state.update {
                        if (it.storeId != store) it else it.copy(apps = list.distinctBy(AppData::id), loading = false, refreshing = false, error = null)
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

    private fun AppsState.withStats(id: String, block: (AppStats) -> AppStats): AppsState =
        copy(stats = stats + (id to block(stats[id] ?: AppStats())))

}

@Composable
fun AppsScreen(
    onBack: () -> Unit,
    onEditPointOfSale: (String?) -> Unit,
    onEditCrowdfund: (String?) -> Unit,
) {
    val viewModel = appViewModel { AppsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var createMenuOpen by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    state.pendingDelete?.let { target ->
        ConfirmDialog(
            title = "Delete “${target.appName}”?",
            message = "The public page stops working straight away. Invoices it already created stay.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }

    state.qrTarget?.let { target ->
        AppQrSheet(
            app = target,
            link = publicLink(state.baseUrl, target.id),
            warning = state.linkWarning,
            onDismiss = viewModel::hideQr,
        )
    }

    val grouped = remember(state.apps) {
        state.apps.groupBy { it.appType.ifBlank { "Other" } }.toSortedMap()
    }

    AppScreen(
        title = "Apps",
        subtitle = "Point of sale and crowdfund pages",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { createMenuOpen = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New") },
                )
                DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Point of sale") },
                        leadingIcon = { Icon(Icons.Rounded.PointOfSale, contentDescription = null) },
                        onClick = {
                            createMenuOpen = false
                            onEditPointOfSale(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Crowdfund") },
                        leadingIcon = { Icon(Icons.Rounded.Campaign, contentDescription = null) },
                        onClick = {
                            createMenuOpen = false
                            onEditCrowdfund(null)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (state.apps.isNotEmpty()) {
                ErrorBanner(state.error, onDismiss = viewModel::dismissError, onRetry = viewModel::refresh)
            }

            val phase = when {
                state.loading -> AppsPhase.Loading
                state.error != null && state.apps.isEmpty() -> AppsPhase.Error
                state.apps.isEmpty() -> AppsPhase.Empty
                else -> AppsPhase.Content
            }

            AnimatedSwap(phase, label = "apps") { shown ->
                when (shown) {
                    AppsPhase.Loading -> SkeletonList()

                    // Read through the nullable rather than asserting: on the
                    // frame a retry starts, the error is already gone while this
                    // branch is still on screen leaving.
                    AppsPhase.Error -> state.error?.let {
                        ErrorState(error = it, onRetry = viewModel::refresh)
                    }

                    AppsPhase.Empty -> EmptyState(
                        title = "No apps yet",
                        description = "A point of sale gives this store a tappable till; a crowdfund gives it " +
                            "a public funding page.",
                        icon = Icons.Rounded.Apps,
                        actionLabel = "Create a point of sale",
                        onAction = { onEditPointOfSale(null) },
                    )

                    AppsPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        grouped.forEach { (type, group) ->
                            item(key = "header-$type") {
                                SectionHeader(
                                    title = TextUtil.sentenceCase(type),
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            items(group.size, key = { group[it].id }) { index ->
                                val app = group[index]
                                AppRow(
                                    app = app,
                                    expanded = state.expandedId == app.id,
                                    stats = state.stats[app.id],
                                    modifier = Modifier.animateItem(),
                                    onOpen = {
                                        when (app.appType) {
                                            APP_TYPE_CROWDFUND -> onEditCrowdfund(app.id)
                                            else -> onEditPointOfSale(app.id)
                                        }
                                    },
                                    onToggleStats = { viewModel.toggleStats(app.id) },
                                    onOpenInBrowser = {
                                        publicLink(state.baseUrl, app.id)?.let(uriHandler::openUri)
                                    },
                                    onShowQr = { viewModel.showQr(app) },
                                    onDelete = { viewModel.askDelete(app) },
                                )
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }
}

private fun publicLink(baseUrl: String?, appId: String): String? =
    baseUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/apps/$appId" }

/**
 * The warning to show with a public link, or null.
 *
 * Links to apps and payment requests are built from the address this phone
 * uses to reach the server; BTCPay builds its own from the request host, so
 * there is no public URL to ask it for. An .onion or local-network address
 * works from here, and a customer's phone often cannot open it.
 */
internal fun publicLinkWarning(host: String): String? =
    if (isLocalNetworkHost(host)) "This link uses $host, which customers may not be able to open." else null

@Composable
private fun AppRow(
    app: AppData,
    expanded: Boolean,
    stats: AppStats?,
    onOpen: () -> Unit,
    onToggleStats: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.statusColors
    var menuOpen by remember { mutableStateOf(false) }

    AppCard(modifier = modifier, onClick = onOpen) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.appName.ifBlank { app.id },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${TextUtil.sentenceCase(app.appType)} · created ${Dates.date(app.created)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (app.archived) {
                    StatusPill(
                        label = "Archived",
                        container = colors.expired,
                        content = colors.onExpired,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Actions for ${app.appName}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open in browser") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenInBrowser()
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
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            TextButton(onClick = onToggleStats, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Statistics")
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                StatsPanel(stats)
            }
        }
    }
}

@Composable
private fun StatsPanel(stats: AppStats?) {
    val current = stats ?: AppStats(loading = true)
    val error = current.error
    val series = current.sales?.series.orEmpty()

    val phase = when {
        current.loading -> AppStatsPhase.Loading
        error != null -> AppStatsPhase.Error
        else -> AppStatsPhase.Content
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        AnimatedSwap(phase, label = "appStats") { shown ->
            when (shown) {
                AppStatsPhase.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }

                AppStatsPhase.Error -> Text(
                    text = rememberLast(error).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                AppStatsPhase.Content -> Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "${current.sales?.salesCount ?: 0} sales in the last $SALES_DAYS days",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (series.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        SalesBars(series)
                    }
                    if (current.topItems.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("Best sellers", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        // No `arrive` stagger, despite this being exactly the
                        // shape that wants one. The panel lives inside a lazy
                        // row, so the entrance would replay every time the app
                        // was scrolled off screen and back — a list that
                        // shimmers whenever it is scrolled past is worse than
                        // one that never introduced itself.
                        current.topItems.forEach { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                            ) {
                                Text(
                                    text = item.title.ifBlank { item.itemCode },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${item.salesCount} · ${maskedIfPrivate(item.totalFormatted)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * A weighted-box bar row rather than a charting library: seven bars do not
 * justify another dependency, and this keeps the drawing in the theme's hands.
 */
@Composable
private fun SalesBars(series: List<AppSalesSeriesPoint>) {
    val peak = series.maxOfOrNull { it.salesCount }?.coerceAtLeast(1) ?: 1

    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        series.forEach { point ->
            val fraction = (point.salesCount.toFloat() / peak).coerceIn(0.04f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (point.salesCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = series.firstOrNull()?.label.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = series.lastOrNull()?.label.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppQrSheet(app: AppData, link: String?, warning: String?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = app.appName.ifBlank { app.id },
                modifier = Modifier.arrive(0),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            if (link == null) {
                Text(
                    text = "No server address is known for this account, so the public link cannot be built.",
                    modifier = Modifier.arrive(1),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                if (warning != null) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                // No `arrive` here. `QrCode` is complete on its first frame, and
                // whatever reveals it animates it from the outside.
                QrCode(content = link, contentDescription = "Link to ${app.appName}")
                Spacer(Modifier.height(16.dp))
                CopyableField(
                    label = "Public link",
                    value = link,
                    modifier = Modifier.arrive(2),
                    truncate = true,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
