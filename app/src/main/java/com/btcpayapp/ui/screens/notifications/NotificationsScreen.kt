package com.btcpayapp.ui.screens.notifications
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.dto.NotificationData
import com.btcpayapp.data.api.dto.NotificationSettingItem
import com.btcpayapp.data.api.endpoints.deleteNotification
import com.btcpayapp.data.api.endpoints.markNotification
import com.btcpayapp.data.api.endpoints.notificationSettings
import com.btcpayapp.data.api.endpoints.notifications
import com.btcpayapp.data.api.endpoints.updateNotificationSettings
import com.btcpayapp.ui.LocalOpenInStore
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.theme.Motion
import com.btcpayapp.ui.OpenLinkDialog
import com.btcpayapp.ui.webHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 50

/**
 * What the body of the screen is showing.
 *
 * A discriminator rather than the state itself, so marking one notification as
 * seen does not re-animate the whole list underneath it.
 */
private enum class NotificationsPhase { Loading, Error, Empty, Content }

/** The same, for the contents of the settings sheet. */
private enum class NotificationSettingsPhase { Loading, Error, Empty, Content }

data class NotificationsState(
    val items: List<NotificationData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val unseenOnly: Boolean = false,
    val settingsOpen: Boolean = false,
    val settings: List<NotificationSettingItem> = emptyList(),
    val settingsLoading: Boolean = false,
    val settingsError: String? = null,
    val baseUrl: String? = null,
    val message: String? = null,
    /**
     * Set by a tap, cleared by the screen once it has acted on it. An in-app
     * target carries a store id only when it is in another store; a web target
     * waits here for the user's yes or no.
     */
    val navigateTo: NotificationTarget? = null,
)

class NotificationsViewModel(private val graph: AppGraph) : ViewModel() {
    private var loadJob: kotlinx.coroutines.Job? = null
    private var bulkJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(
        NotificationsState(baseUrl = graph.session.activeAccount.value?.baseUrl),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.session.activeAccount
                .map { it?.baseUrl }
                .distinctUntilChanged()
                .collectLatest { url ->
                    _state.update { it.copy(baseUrl = url) }
                    load(refreshing = false)
                }
        }
    }

    /**
     * Marking seen and deleting need this permission. A "Watch only" key
     * lacks it, so those actions are hidden rather than failing on every tap.
     */
    val canManage: Boolean
        get() = graph.session.hasPermission("btcpay.user.canmanagenotificationsforuser")

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun consumeNavigation() = _state.update { it.copy(navigateTo = null) }

    /**
     * What a tap on a notification does.
     *
     * Nearly every link BTCPay writes is a page this app already has, so the
     * browser is the last resort rather than the first answer. A store-scoped
     * destination brings its store with it: the invoice, payout and pull-payment
     * endpoints are all under `/stores/{storeId}/`, so opening one of those
     * screens against whichever store happened to be active shows the wrong
     * store's data or an error.
     *
     * The switch is the shell's, not this model's: it is refused while a
     * payment is in flight, drops the old store's screens and only then opens
     * the target, the same path a tap on a system notification takes. Switching here and
     * then navigating would race that reset, which can pop the target again.
     */
    fun open(notification: NotificationData) {
        if (canManage) markSeen(notification.id)
        val next = when (val target = notificationTarget(notification.link, _state.value.baseUrl)) {
            is NotificationTarget.Web -> target

            NotificationTarget.None -> null

            is NotificationTarget.InApp -> {
                val storeId = notification.storeId ?: target.storeId
                when {
                    storeId == null || storeId == graph.session.activeStore.value?.id -> target.copy(storeId = null)
                    // A store this account cannot see is one no screen here can
                    // fill, so that link opens in the browser rather than as an
                    // empty screen with the right title.
                    graph.session.stores.value.none { it.id == storeId } ->
                        notification.link?.let { absoluteLink(_state.value.baseUrl, it) }?.let(NotificationTarget::Web)
                    else -> target.copy(storeId = storeId)
                }
            }
        }
        // The same rule as a link from outside the app: a web address opens
        // only when webHost can say where it goes.
        if (next == null || next is NotificationTarget.Web && webHost(next.url) == null) return
        _state.update { it.copy(navigateTo = next) }
    }

    fun setUnseenOnly(value: Boolean) {
        _state.update { it.copy(unseenOnly = value) }
        load(refreshing = true)
    }

    fun markSeen(id: String) {
        if (_state.value.items.firstOrNull { it.id == id }?.seen == true) return
        viewModelScope.launch {
            runCatching { graph.session.requireApi().markNotification(id, true) }
                .onSuccess { updated ->
                    _state.update { current ->
                        current.copy(items = current.items.map { if (it.id == id) updated else it })
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.asApiException().userMessage) }
                }
        }
    }

    fun markAllSeen() {
        if (bulkJob?.isActive == true) return
        val unseen = _state.value.items.filterNot { it.seen }
        if (unseen.isEmpty()) return
        bulkJob = viewModelScope.launch {
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(message = failure.asApiException().userMessage) }
                return@launch
            }
            // There is no bulk endpoint, so this is one call per unseen item.
            var failures = 0
            unseen.chunked(4).forEach { batch ->
                failures += batch.map { item -> async {
                    attempt { api.markNotification(item.id, true) }
                } }.awaitAll().count { it.isFailure }
            }
            if (failures > 0) _state.update { it.copy(message = "$failures notifications could not be marked as seen. Try again.") }
            load(refreshing = true)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { graph.session.requireApi().deleteNotification(id) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            items = current.items.filterNot { it.id == id },
                            message = "Notification removed.",
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.asApiException().userMessage) }
                }
        }
    }

    // --- Settings ----------------------------------------------------------

    fun openSettings() {
        // Last visit's switches are not shown while this loads: a failed load
        // must not leave old values on screen for a save to send back.
        _state.update { it.copy(settingsOpen = true, settingsLoading = true, settingsError = null, settings = emptyList()) }
        viewModelScope.launch {
            runCatching { graph.session.requireApi().notificationSettings() }
                .onSuccess { data ->
                    _state.update {
                        it.copy(settingsLoading = false, settings = data.notifications, settingsError = null)
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(settingsLoading = false, settingsError = failure.asApiException().userMessage)
                    }
                }
        }
    }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    /**
     * The API is expressed the other way round from the switches: it takes the
     * list of identifiers to *disable*, so "on" means "absent from that list".
     * The inversion happens here, once, rather than in the composable.
     */
    fun setNotificationEnabled(identifier: String, enabled: Boolean) {
        if (_state.value.settingsLoading) return
        val previous = _state.value.settings
        val next = _state.value.settings.map {
            if (it.identifier == identifier) it.copy(enabled = enabled) else it
        }
        _state.update { it.copy(settings = next, settingsLoading = true, settingsError = null) }

        viewModelScope.launch {
            val disabled = next.filterNot { it.enabled }.map { it.identifier }
            runCatching { graph.session.requireApi().updateNotificationSettings(disabled) }
                .onSuccess { data -> _state.update { it.copy(settings = data.notifications, settingsLoading = false) } }
                .onFailure { failure ->
                    // The switches come back as they were, with one line
                    // above them; the sheet stays usable for another try.
                    val reason = failure.asApiException().userMessage
                    _state.update {
                        it.copy(settings = previous, settingsLoading = false, settingsError = "Could not save the setting. $reason")
                    }
                }
        }
    }

    fun load(refreshing: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.items.isEmpty(), refreshing = refreshing, error = null)
            }
            val unseenOnly = _state.value.unseenOnly
            runCatching {
                graph.session.requireApi().notifications(
                    seen = if (unseenOnly) false else null,
                    take = PAGE_SIZE,
                )
            }
                .onSuccess { list ->
                    _state.update {
                        it.copy(items = list.distinctBy(NotificationData::id), loading = false, refreshing = false, error = null)
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = failure.asApiException())
                    }
                }
        }
    }

}

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
) {
    val viewModel = appViewModel { NotificationsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val openInStore = LocalOpenInStore.current

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    // The tap resolves in the view model; the act of going there is the
    // screen's, and one in another store goes through the shell's switch.
    LaunchedEffect(state.navigateTo) {
        val target = state.navigateTo as? NotificationTarget.InApp ?: return@LaunchedEffect
        val storeId = target.storeId
        if (storeId != null) openInStore(storeId, target.route) else onNavigate(target.route)
        viewModel.consumeNavigation()
    }

    // The server writes these links, and a plugin can write any address, so a
    // web link is asked about as one from outside the app is.
    (state.navigateTo as? NotificationTarget.Web)?.let { target ->
        OpenLinkDialog(target.url, onOpen = { uriHandler.openUri(target.url) }, onDismiss = viewModel::consumeNavigation)
    }

    if (state.settingsOpen) {
        NotificationSettingsSheet(
            loading = state.settingsLoading,
            settings = state.settings,
            error = state.settingsError,
            onToggle = viewModel::setNotificationEnabled,
            onDismiss = viewModel::closeSettings,
        )
    }

    AppScreen(
        title = "Notifications",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        actions = {
            if (viewModel.canManage) {
                IconButton(onClick = viewModel::markAllSeen) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = "Mark all as seen")
                }
            }
            IconButton(onClick = viewModel::openSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Notification settings")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !state.unseenOnly,
                    onClick = { viewModel.setUnseenOnly(false) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = state.unseenOnly,
                    onClick = { viewModel.setUnseenOnly(true) },
                    label = { Text("Unseen") },
                )
            }

            if (state.items.isNotEmpty()) {
                ErrorBanner(state.error, onDismiss = viewModel::dismissError, onRetry = viewModel::refresh)
            }

            val phase = when {
                state.loading -> NotificationsPhase.Loading
                state.error != null && state.items.isEmpty() -> NotificationsPhase.Error
                state.items.isEmpty() -> NotificationsPhase.Empty
                else -> NotificationsPhase.Content
            }

            AnimatedSwap(phase, label = "notifications") { shown ->
                when (shown) {
                    NotificationsPhase.Loading -> SkeletonList()

                    // Read through the nullable: a retry clears the error on the
                    // frame this branch starts leaving, and it is still composed.
                    NotificationsPhase.Error -> state.error?.let {
                        ErrorState(error = it, onRetry = viewModel::refresh)
                    }

                    NotificationsPhase.Empty -> EmptyState(
                        title = if (state.unseenOnly) "Nothing unseen" else "No notifications",
                        description = "Invoice, payout and server notices land here.",
                        icon = Icons.Rounded.Notifications,
                    )

                    NotificationsPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { notification ->
                            // Row and divider move as one, so deleting a
                            // notification closes the gap rather than leaving a
                            // rule behind while the rest slides up.
                            Column(Modifier.animateItem()) {
                                NotificationRow(
                                    notification = notification,
                                    onClick = { viewModel.open(notification) },
                                    onDelete = if (viewModel.canManage) {
                                        { viewModel.delete(notification.id) }
                                    } else {
                                        null
                                    },
                                )
                                ThinDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: NotificationData,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Bodies are HTML fragments written by the server. They are stripped to plain
    // text rather than rendered: a WebView here would run remote markup with the
    // app's own privacy posture, for the sake of a bold tag.
    val body = remember(notification.body) { TextUtil.stripHtml(notification.body) }

    // The dot recolours and the weight of the line does not. Tapping a
    // notification marks it seen under the finger, and a dot that changes
    // colour between two frames reads as a redraw; the sentence beside it is
    // what the user is reading, and text that dissolves mid-read has to be read
    // again. Compose cannot interpolate a font weight anyway.
    val dot by animateColorAsState(
        targetValue = if (notification.seen) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = Motion.color,
        label = "seen",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // The dot and the weight are visual only; this says it aloud.
            .semantics { if (!notification.seen) stateDescription = "Unread" }
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(color = dot, shape = CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = body.ifBlank { notification.type.ifBlank { "Notification" } },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (notification.seen) FontWeight.Normal else FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Dates.relative(notification.createdTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDelete != null) Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Notification actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationSettingsSheet(
    loading: Boolean,
    settings: List<NotificationSettingItem>,
    error: String?,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "What to be told about",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            // An error with switches to show is a failed save, said above
            // them; without any it is a failed load, and is all there is.
            val phase = when {
                loading -> NotificationSettingsPhase.Loading
                error != null && settings.isEmpty() -> NotificationSettingsPhase.Error
                settings.isEmpty() -> NotificationSettingsPhase.Empty
                else -> NotificationSettingsPhase.Content
            }

            AnimatedSwap(phase, label = "notificationSettings") { shown ->
                when (shown) {
                    NotificationSettingsPhase.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }

                    NotificationSettingsPhase.Error -> Text(
                        text = rememberLast(error).orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    NotificationSettingsPhase.Empty -> Text(
                        text = "This server exposes no notification types.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // No `arrive` stagger on the switches: saving a change puts
                    // the sheet back through its loading state, so an entrance
                    // here would replay on every toggle.
                    NotificationSettingsPhase.Content -> Column(Modifier.fillMaxWidth()) {
                        if (error != null) {
                            Text(
                                text = error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        settings.forEach { item ->
                            FormSwitch(
                                title = item.name.ifBlank { item.identifier },
                                checked = item.enabled,
                                onCheckedChange = { enabled -> onToggle(item.identifier, enabled) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
