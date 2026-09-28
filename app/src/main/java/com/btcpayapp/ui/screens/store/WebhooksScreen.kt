package com.btcpayapp.ui.screens.store

import com.btcpayapp.ui.theme.Motion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.btcpayapp.data.api.dto.WebhookData
import com.btcpayapp.data.api.dto.WebhookDeliveryData
import com.btcpayapp.data.api.dto.WebhookDeliveryStatus
import com.btcpayapp.data.api.endpoints.deleteWebhook
import com.btcpayapp.data.api.endpoints.redeliverWebhook
import com.btcpayapp.data.api.endpoints.webhookDeliveries
import com.btcpayapp.data.api.endpoints.webhooks
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DELIVERY_COUNT = 20

/** Deliveries are fetched per webhook, on demand, and cached under its id. */
data class DeliveryFeed(
    val items: List<WebhookDeliveryData> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** A delivery the user asked to send again, waiting for them to confirm. */
data class PendingRedelivery(val webhookId: String, val delivery: WebhookDeliveryData)

data class WebhooksState(
    val webhooks: List<WebhookData> = emptyList(),
    // True from the start: the first load runs on the first resume, and an
    // empty list before it would flash "No webhooks".
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val expandedId: String? = null,
    val deliveries: Map<String, DeliveryFeed> = emptyMap(),
    val pendingDelete: WebhookData? = null,
    val pendingRedelivery: PendingRedelivery? = null,
    val message: String? = null,
)

class WebhooksViewModel(private val graph: AppGraph) : ViewModel() {

    private val bound = StoreBinding(graph.session)
    private val _state = MutableStateFlow(WebhooksState())
    val state = _state.asStateFlow()

    private val storeId get() = bound.id

    // The screen loads on every resume (so a webhook made in the editor shows
    // on return); this covers a cold start, where the store comes later.
    init {
        bound.retryWhenKnown(viewModelScope) { load() }
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun toggleDeliveries(webhookId: String) {
        val open = _state.value.expandedId == webhookId
        _state.update { it.copy(expandedId = if (open) null else webhookId) }
        if (!open) loadDeliveries(webhookId)
    }

    fun askDelete(webhook: WebhookData) = _state.update { it.copy(pendingDelete = webhook) }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val store = storeId ?: return
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            runCatching { graph.session.requireApi().deleteWebhook(store, target.id) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            webhooks = current.webhooks.filterNot { it.id == target.id },
                            message = "Webhook removed.",
                        )
                    }
                }
                .onFailure { failure -> _state.update { it.copy(message = failure.asApiException().plainMessage()) } }
        }
    }

    fun askRedeliver(webhookId: String, delivery: WebhookDeliveryData) =
        _state.update { it.copy(pendingRedelivery = PendingRedelivery(webhookId, delivery)) }

    fun dismissRedeliver() = _state.update { it.copy(pendingRedelivery = null) }

    fun confirmRedeliver() {
        val pending = _state.value.pendingRedelivery ?: return
        _state.update { it.copy(pendingRedelivery = null) }
        redeliver(pending.webhookId, pending.delivery.id)
    }

    private fun redeliver(webhookId: String, deliveryId: String) {
        val store = storeId ?: return
        viewModelScope.launch {
            runCatching { graph.session.requireApi().redeliverWebhook(store, webhookId, deliveryId) }
                .onSuccess {
                    _state.update { it.copy(message = "Queued for redelivery.") }
                    loadDeliveries(webhookId)
                }
                .onFailure { failure -> _state.update { it.copy(message = failure.asApiException().plainMessage()) } }
        }
    }

    private fun loadDeliveries(webhookId: String) {
        val store = storeId ?: return
        viewModelScope.launch {
            _state.update { it.withFeed(webhookId) { feed -> feed.copy(loading = true, error = null) } }
            runCatching { graph.session.requireApi().webhookDeliveries(store, webhookId, DELIVERY_COUNT) }
                .onSuccess { list ->
                    _state.update {
                        it.withFeed(webhookId) { feed -> feed.copy(items = list, loading = false, error = null) }
                    }
                }
                .onFailure { failure ->
                    val text = failure.asApiException().plainMessage()
                    _state.update { it.withFeed(webhookId) { feed -> feed.copy(loading = false, error = text) } }
                }
        }
    }

    fun load(refreshing: Boolean = false) {
        val store = storeId
        if (store == null) {
            _state.update { it.copy(loading = false, refreshing = false, error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.webhooks.isEmpty(), refreshing = refreshing, error = null)
            }
            runCatching { graph.session.requireApi().webhooks(store) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(webhooks = list.distinctBy(WebhookData::id), loading = false, refreshing = false, error = null)
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = failure.asApiException())
                    }
                }
        }
    }

    private fun WebhooksState.withFeed(id: String, block: (DeliveryFeed) -> DeliveryFeed): WebhooksState =
        copy(deliveries = deliveries + (id to block(deliveries[id] ?: DeliveryFeed())))
}


/**
 * The server answers a redelivery request for an evicted delivery with a 409 and
 * the code `webhookdelivery-pruned`. Its raw message talks about pruning policy,
 * which means nothing to a merchant, so it is replaced here.
 */
private fun ApiException.plainMessage(): String =
    if (this is ApiException.Server && code.equals("webhookdelivery-pruned", ignoreCase = true)) {
        "That delivery is too old to send again — the server no longer keeps its payload."
    } else {
        userMessage
    }

/**
 * What the body of the screen is showing.
 *
 * The swap is driven by this rather than by the state itself, so a background
 * refresh that returns the same four webhooks does not cross-fade the list with
 * a copy of itself.
 */
private enum class WebhooksPhase { Loading, Error, Empty, Content }

/** The same idea for the delivery list nested inside an expanded webhook. */
private enum class DeliveryPhase { Loading, Error, Empty, Content }

@Composable
fun WebhooksScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
) {
    val viewModel = appViewModel { WebhooksViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Also on the way back from the editor: the view model outlives it, and a
    // stale "No webhooks" invites a second, duplicate webhook.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    state.pendingDelete?.let { target ->
        ConfirmDialog(
            title = "Delete this webhook?",
            message = "The server will stop notifying ${target.url}. Undelivered events are lost.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }

    // The delivery list carries no event type, so the delivery is named by
    // when it was sent.
    state.pendingRedelivery?.let { pending ->
        ConfirmDialog(
            title = "Send this event again?",
            message = "Your server posts the event from ${Dates.full(pending.delivery.timestamp)} to " +
                "the webhook once more. A shop that does not check for repeats may act on it twice.",
            confirmLabel = "Send again",
            onConfirm = viewModel::confirmRedeliver,
            onDismiss = viewModel::dismissRedeliver,
        )
    }

    AppScreen(
        title = "Webhooks",
        subtitle = "Event notifications sent by this store",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (state.webhooks.isNotEmpty()) {
                ErrorBanner(
                    error = state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                )
            }

            val phase = when {
                state.loading -> WebhooksPhase.Loading
                state.error != null && state.webhooks.isEmpty() -> WebhooksPhase.Error
                state.webhooks.isEmpty() -> WebhooksPhase.Empty
                else -> WebhooksPhase.Content
            }

            AnimatedSwap(phase, Modifier.fillMaxSize(), label = "webhooks") { shown ->
                when (shown) {
                    WebhooksPhase.Loading -> SkeletonList()

                    // Not `state.error!!`: the outgoing half of a swap outlives
                    // the state that chose it, so this branch can still be
                    // composed a frame after the error was dismissed.
                    WebhooksPhase.Error -> ErrorState(
                        error = state.error,
                        onRetry = viewModel::refresh,
                    )

                    WebhooksPhase.Empty -> EmptyState(
                        title = "No webhooks",
                        description = "A webhook posts invoice and payout events to a URL you control.",
                        icon = Icons.Rounded.Webhook,
                        actionLabel = "Add one",
                        onAction = { onEdit(null) },
                    )

                    WebhooksPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.webhooks, key = { it.id }) { webhook ->
                            WebhookCard(
                                webhook = webhook,
                                expanded = state.expandedId == webhook.id,
                                feed = state.deliveries[webhook.id],
                                modifier = Modifier.animateItem(),
                                onOpen = { onEdit(webhook.id) },
                                onToggleDeliveries = { viewModel.toggleDeliveries(webhook.id) },
                                onDelete = { viewModel.askDelete(webhook) },
                                onRedeliver = { viewModel.askRedeliver(webhook.id, it) },
                            )
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebhookCard(
    webhook: WebhookData,
    expanded: Boolean,
    feed: DeliveryFeed?,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggleDeliveries: () -> Unit,
    onDelete: () -> Unit,
    onRedeliver: (WebhookDeliveryData) -> Unit,
) {
    val colors = AppTheme.statusColors
    val events = webhook.authorizedEvents
    val eventSummary = if (events.everything) {
        "All events"
    } else {
        "${events.specificEvents.size} selected ${if (events.specificEvents.size == 1) "event" else "events"}"
    }

    AppCard(modifier = modifier, onClick = onOpen) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TextUtil.middleEllipsis(webhook.url, 28, 18),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    label = if (webhook.enabled) "Enabled" else "Disabled",
                    container = if (webhook.enabled) colors.settled else colors.expired,
                    content = if (webhook.enabled) colors.onSettled else colors.onExpired,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = eventSummary,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleDeliveries) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Deliveries")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete webhook",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                DeliveryList(feed = feed, onRedeliver = onRedeliver)
            }
        }
    }
}

@Composable
private fun DeliveryList(feed: DeliveryFeed?, onRedeliver: (WebhookDeliveryData) -> Unit) {
    val current = feed ?: DeliveryFeed(loading = true)
    val error = current.error

    val phase = when {
        current.loading -> DeliveryPhase.Loading
        error != null -> DeliveryPhase.Error
        current.items.isEmpty() -> DeliveryPhase.Empty
        else -> DeliveryPhase.Content
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        AnimatedSwap(phase, label = "deliveries") { shown ->
            when (shown) {
                DeliveryPhase.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }

                DeliveryPhase.Error -> Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                DeliveryPhase.Empty -> Text(
                    text = "Nothing has been delivered yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // These rows are a plain `Column`, but that `Column` is inside
                // a lazy item, so an `arrive` stagger here would replay every
                // time the webhook scrolled back into view. The panel opening
                // is already animated by the `AnimatedVisibility` above; the
                // rows do not need an entrance of their own.
                DeliveryPhase.Content -> Column(Modifier.fillMaxWidth()) {
                    current.items.forEach { delivery ->
                        DeliveryRow(
                            delivery = delivery,
                            onRedeliver = { onRedeliver(delivery) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryRow(
    delivery: WebhookDeliveryData,
    modifier: Modifier = Modifier,
    onRedeliver: () -> Unit,
) {
    val colors = AppTheme.statusColors
    val (container, content) = when (delivery.status) {
        WebhookDeliveryStatus.HttpSuccess -> colors.settled to colors.onSettled
        WebhookDeliveryStatus.HttpError, WebhookDeliveryStatus.Failed -> colors.invalid to colors.onInvalid
        WebhookDeliveryStatus.Unknown -> colors.expired to colors.onExpired
    }

    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                label = TextUtil.sentenceCase(delivery.status.name),
                container = container,
                content = content,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = delivery.httpCode?.toString() ?: "no reply",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = Dates.relative(delivery.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The standard 48dp target, and a confirmation behind it: a shop
            // backend may fulfil a resent order event twice.
            IconButton(onClick = onRedeliver) {
                Icon(imageVector = Icons.Rounded.Replay, contentDescription = "Send again")
            }
        }
        delivery.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
