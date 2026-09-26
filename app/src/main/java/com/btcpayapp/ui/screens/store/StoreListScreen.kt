package com.btcpayapp.ui.screens.store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.endpoints.createStore
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Picks the store the rest of the app works against.
 *
 * The list is the session's copy rather than a fresh fetch: the session already
 * keeps it current, and re-reading it here would make switching stores feel
 * slower than it is.
 */
data class StoreListState(
    val stores: List<StoreData> = emptyList(),
    val activeStoreId: String? = null,
    val refreshing: Boolean = false,
    val creating: Boolean = false,
    val showCreate: Boolean = false,
    val error: ApiException? = null,
    val done: Boolean = false,
)

class StoreListViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(StoreListState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(graph.session.stores, graph.session.activeStore) { stores, active ->
                stores to active?.id
            }.collect { (stores, activeId) ->
                _state.update { it.copy(stores = stores, activeStoreId = activeId) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            graph.session.refresh()
            _state.update { it.copy(refreshing = false, error = graph.session.lastError.value) }
        }
    }

    fun select(storeId: String) {
        if (storeId == _state.value.activeStoreId) {
            _state.update { it.copy(done = true) }
            return
        }
        viewModelScope.launch {
            graph.session.selectStore(storeId)
            _state.update { it.copy(done = true) }
        }
    }

    fun showCreate(show: Boolean) = _state.update { it.copy(showCreate = show) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun create(name: String, currency: String) {
        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }
            runCatching {
                graph.session.requireApi().createStore(
                    StoreData(name = name.trim(), defaultCurrency = currency.trim().uppercase()),
                )
            }.onSuccess { created ->
                graph.session.refresh()
                // A new store is almost always the one you want to work in next.
                graph.session.selectStore(created.id)
                _state.update { it.copy(creating = false, showCreate = false, done = true) }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        creating = false,
                        error = failure as? ApiException
                            ?: ApiException.Transport(failure.message ?: "Unexpected failure"),
                    )
                }
            }
        }
    }
}

@Composable
fun StoreListScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { StoreListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.done) {
        if (state.done) onBack()
    }

    AppScreen(
        title = "Stores",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showCreate(true) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New store") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ErrorBanner(state.error, onDismiss = viewModel::dismissError, onRetry = viewModel::refresh)

            AnimatedSwap(state.stores.isEmpty(), Modifier.fillMaxSize(), label = "stores") { empty ->
                if (empty) {
                    EmptyState(
                        title = "No stores yet",
                        description = "A store holds your payment methods, rates and invoices. " +
                            "Create one to start taking payments.",
                        icon = Icons.Rounded.Store,
                        actionLabel = "Create a store",
                        onAction = { viewModel.showCreate(true) },
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.stores, key = { it.id }) { store ->
                            Column(Modifier.animateItem()) {
                                StoreListRow(
                                    store = store,
                                    active = store.id == state.activeStoreId,
                                    onClick = { viewModel.select(store.id) },
                                )
                                ThinDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showCreate) {
        CreateStoreDialog(
            busy = state.creating,
            onCreate = viewModel::create,
            onDismiss = { viewModel.showCreate(false) },
        )
    }
}

@Composable
private fun StoreListRow(store: StoreData, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = store.name.ifBlank { store.id },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = store.defaultCurrency,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (store.archived) {
                    StatusPill(
                        label = "Archived",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // The tick is the only answer the screen gives to a tap — it selects a
        // store and then leaves — so it has to be seen arriving rather than
        // simply be there on the next frame.
        AnimatedVisibility(
            visible = active,
            enter = scaleIn(Motion.spatial) + fadeIn(Motion.effects),
            exit = scaleOut(Motion.spatial) + fadeOut(Motion.effectsFast),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Active store",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CreateStoreDialog(
    busy: Boolean,
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("USD") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New store") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FormField(
                    label = "Name",
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                )
                FormField(
                    label = "Default currency",
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    enabled = !busy,
                    supportingText = "Everything else can be changed afterwards.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, currency) },
                enabled = !busy && name.isNotBlank() && currency.isNotBlank(),
            ) {
                AnimatedSwap(busy, label = "create") { working ->
                    if (working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Create")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
