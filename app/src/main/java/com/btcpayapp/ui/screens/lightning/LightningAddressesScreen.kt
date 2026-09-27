package com.btcpayapp.ui.screens.lightning

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.LightningAddressData
import com.btcpayapp.data.api.endpoints.deleteLightningAddress
import com.btcpayapp.data.api.endpoints.lightningAddresses
import com.btcpayapp.data.api.endpoints.upsertLightningAddress
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.copyToClipboard
import com.btcpayapp.ui.components.NoStoreSelectedState
import com.btcpayapp.data.session.StoreBinding
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LightningAddressesState(
    val addresses: List<LightningAddressData> = emptyList(),
    /** Host of the active account; the right-hand side of every address. */
    val host: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val noStore: Boolean = false,
    val error: ApiException? = null,

    // --- Editor -------------------------------------------------------------
    val sheetOpen: Boolean = false,
    val original: LightningAddressData? = null,
    val username: String = "",
    val currencyCode: String = "",
    val min: String = "",
    val max: String = "",
    val minError: String? = null,
    val maxError: String? = null,
    val saving: Boolean = false,
    val formError: String? = null,

    val pendingDelete: String? = null,
    val message: String? = null,
)

class LightningAddressesViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(LightningAddressesState(loading = true))
    val state = _state.asStateFlow()

    /**
     * The store this screen was opened for, fixed once known; see
     * [StoreBinding]. The shell drops store screens on a switch, and one that
     * read the active store at save time could write an address for store A
     * into store B.
     */
    private val bound = StoreBinding(graph.session)
    private val storeId: String? get() = bound.id

    init {
        load()
        bound.retryWhenKnown(viewModelScope) { load() }
        viewModelScope.launch {
            graph.session.activeAccount.collectLatest { account ->
                _state.update { it.copy(host = account?.host) }
            }
        }
    }

    fun refresh() = load(refreshing = true)

    /**
     * Reloads quietly when the screen comes back. Skipped while a load runs,
     * which also covers the first resume, straight after [init].
     */
    fun onResume() {
        val current = _state.value
        if (current.loading || current.refreshing) return
        load()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun openCreate() = _state.update {
        it.copy(
            sheetOpen = true,
            original = null,
            username = "",
            currencyCode = "",
            min = "",
            max = "",
            minError = null,
            maxError = null,
            formError = null,
        )
    }

    fun openEdit(address: LightningAddressData) = _state.update {
        it.copy(
            sheetOpen = true,
            original = address,
            username = address.username,
            currencyCode = address.currencyCode.orEmpty(),
            // Through `toInput`, so a loaded "1.125" becomes "1.1250" and
            // saving the form unchanged does not trip the ambiguity check.
            min = limitInput(address.min),
            max = limitInput(address.max),
            minError = null,
            maxError = null,
            formError = null,
        )
    }

    fun closeSheet() = _state.update { it.copy(sheetOpen = false, formError = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value, formError = null) }

    fun setCurrency(value: String) = _state.update { it.copy(currencyCode = value, formError = null) }

    fun setMin(value: String) = _state.update { it.copy(min = value, minError = null, formError = null) }

    fun setMax(value: String) = _state.update { it.copy(max = value, maxError = null, formError = null) }

    fun requestDelete(username: String) = _state.update { it.copy(pendingDelete = username) }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    fun save() {
        val snapshot = _state.value
        // `enabled` is one recomposition behind the click.
        if (snapshot.saving) return
        val username = snapshot.username.trim()
        if (username.isEmpty()) {
            _state.update { it.copy(formError = "Choose a username.") }
            return
        }
        val min = limitOf(snapshot.min)
        val max = limitOf(snapshot.max)
        val minError = limitProblem(snapshot.min)
        val maxError = limitProblem(snapshot.max)
            ?: if (min != null && max != null && min > max) "Must be at least the minimum." else null
        if (minError != null || maxError != null) {
            _state.update { it.copy(minError = minError, maxError = maxError) }
            return
        }
        val store = storeId ?: run {
            _state.update { it.copy(noStore = true) }
            return
        }

        // Keep whatever invoice metadata was configured elsewhere; this screen
        // does not edit it and must not silently drop it. The limits go out as
        // plain '.' decimals: the serializer refuses anything else, so "0,5"
        // can never reach the server as 5.
        val payload = (snapshot.original ?: LightningAddressData()).copy(
            username = username,
            currencyCode = snapshot.currencyCode.trim().uppercase().ifBlank { null },
            min = min?.toPlainString(),
            max = max?.toPlainString(),
        )

        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            runCatching { graph.session.requireApi().upsertLightningAddress(store, payload) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(saving = false, sheetOpen = false, message = "Address saved.")
                    }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(saving = false, formError = failure.asApiException().userMessage)
                    }
                }
        }
    }

    fun confirmDelete() {
        val username = _state.value.pendingDelete ?: return
        val store = storeId ?: run {
            _state.update { it.copy(pendingDelete = null, noStore = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(pendingDelete = null) }
            runCatching { graph.session.requireApi().deleteLightningAddress(store, username) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            addresses = current.addresses.filterNot { it.username == username },
                            message = "Address removed.",
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(error = failure.asApiException()) }
                }
        }
    }

    private fun load(refreshing: Boolean = false) {
        val store = storeId
        if (store == null) {
            _state.update { it.copy(loading = false, refreshing = false, noStore = true) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refreshing && it.addresses.isEmpty(),
                    refreshing = refreshing,
                    noStore = false,
                    error = null,
                )
            }
            runCatching { graph.session.requireApi().lightningAddresses(store) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(addresses = list, loading = false, refreshing = false, error = null)
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

/** A limit as the server sent it, as field text that [limitOf] reads back unchanged. */
private fun limitInput(text: String?): String =
    Amounts.serverDecimal(text)?.let { Amounts.toInput(it, 3) }.orEmpty()

/** A limit field: blank is no limit. Null also when [limitProblem] has something to say. */
private fun limitOf(input: String): BigDecimal? =
    input.takeIf { it.isNotBlank() }?.let(Amounts::parse)?.takeIf { it.signum() >= 0 }

/** The field error for a limit, or null when it is blank or fine. */
private fun limitProblem(input: String): String? =
    Amounts.parseProblem(input) ?: Amounts.parse(input)?.takeIf { it.signum() < 0 }?.let { "Enter 0 or more." }

/**
 * What the body of the addresses screen is showing.
 *
 * A discriminator rather than the address list, so that saving one and
 * reloading the list does not cross-fade the screen with a copy of itself.
 */
private enum class AddressesPhase { NoStore, Loading, Error, Empty, Content }

@Composable
fun LightningAddressesScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { LightningAddressesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit = LocalSettings.current.bitcoinUnit
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    AppScreen(
        title = "Lightning addresses",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (!state.noStore) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openCreate,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New") },
                )
            }
        },
    ) { padding ->
        val phase = when {
            state.noStore -> AddressesPhase.NoStore
            state.loading -> AddressesPhase.Loading
            state.error != null && state.addresses.isEmpty() -> AddressesPhase.Error
            state.addresses.isEmpty() -> AddressesPhase.Empty
            else -> AddressesPhase.Content
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "addresses") { shown ->
            when (shown) {
                AddressesPhase.NoStore -> NoStoreSelectedState()

                AddressesPhase.Loading -> SkeletonList()

                // Read through `?.let` rather than `!!`. The branch on its way
                // out of a swap stays composed while it fades, and by then the
                // error it was built from has usually been cleared.
                AddressesPhase.Error -> state.error?.let { error ->
                    ErrorState(error = error, onRetry = viewModel::refresh)
                }

                AddressesPhase.Empty -> EmptyState(
                    title = "No Lightning addresses",
                    description = "A Lightning address lets someone pay this store by typing " +
                        "name@server instead of scanning an invoice.",
                    icon = Icons.Rounded.AlternateEmail,
                    actionLabel = "Add one",
                    onAction = viewModel::openCreate,
                )

                AddressesPhase.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        ErrorBanner(
                            error = state.error,
                            onDismiss = viewModel::dismissError,
                            onRetry = viewModel::refresh,
                        )
                    }
                    // Indexed as well as named: `username` is declared with a
                    // blank default, so a server that omits one would hand two
                    // cards the same key, and a repeated key in a lazy list is
                    // a crash rather than a cosmetic fault.
                    itemsIndexed(
                        items = state.addresses,
                        key = { index, address -> "$index-${address.username}" },
                    ) { _, address ->
                        AddressCard(
                            address = address,
                            host = state.host,
                            unit = unit,
                            // Keyed on the username, so removing one lets the
                            // cards below close the gap rather than jumping up
                            // into it.
                            modifier = Modifier.animateItem(),
                            onCopy = { full ->
                                copyToClipboard(context, "Lightning address", full)
                            },
                            onEdit = { viewModel.openEdit(address) },
                            onDelete = { viewModel.requestDelete(address.username) },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (state.sheetOpen) {
        ModalBottomSheet(onDismissRequest = viewModel::closeSheet, sheetState = sheetState) {
            AddressSheet(
                state = state,
                onUsername = viewModel::setUsername,
                onCurrency = viewModel::setCurrency,
                onMin = viewModel::setMin,
                onMax = viewModel::setMax,
                onSave = viewModel::save,
            )
        }
    }

    state.pendingDelete?.let { username ->
        ConfirmDialog(
            title = "Remove this address?",
            message = "“$username” will stop resolving straight away. Anyone who saved it will " +
                "no longer be able to pay this store with it.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun AddressCard(
    address: LightningAddressData,
    host: String?,
    unit: BitcoinUnit,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val full = remember(address.username, host) {
        if (host.isNullOrBlank()) address.username else "${address.username}@$host"
    }

    AppCard(modifier = modifier) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = full,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onCopy(full) }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy address",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit address", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Remove address",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("Currency: ")
                    append(address.currencyCode?.takeIf { it.isNotBlank() } ?: "store default")
                    append(" · Min ")
                    append(satsLabel(address.min, unit))
                    append(" · Max ")
                    append(satsLabel(address.max, unit))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddressSheet(
    state: LightningAddressesState,
    onUsername: (String) -> Unit,
    onCurrency: (String) -> Unit,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = if (state.original == null) "New address" else "Edit address",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        FormField(
            label = "Username",
            value = state.username,
            onValueChange = onUsername,
            // The username is the identifier the API keys on, so renaming an
            // existing address would create a second one instead.
            enabled = !state.saving && state.original == null,
            supportingText = state.host?.let { "The address will be ${state.username.ifBlank { "name" }}@$it" },
        )

        FormField(
            label = "Currency",
            value = state.currencyCode,
            onValueChange = onCurrency,
            placeholder = "SATS",
            supportingText = "Leave empty to use the store's default currency.",
            enabled = !state.saving,
        )

        FormField(
            label = "Minimum (sat)",
            value = state.min,
            onValueChange = onMin,
            error = state.minError,
            enabled = !state.saving,
            keyboardType = KeyboardType.Number,
        )

        FormField(
            label = "Maximum (sat)",
            value = state.max,
            onValueChange = onMax,
            error = state.maxError,
            enabled = !state.saving,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )

        FormProblem(state.formError, verticalPadding = 4.dp)

        Button(
            onClick = onSave,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            AnimatedSwap(state.saving, label = "saveAddress") { saving ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text("Save")
                }
            }
        }
    }
}
