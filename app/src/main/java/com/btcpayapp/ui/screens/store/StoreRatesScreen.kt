package com.btcpayapp.ui.screens.store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.RateSourceData
import com.btcpayapp.data.api.dto.StoreRateConfiguration
import com.btcpayapp.data.api.dto.StoreRateResult
import com.btcpayapp.data.api.endpoints.previewRateConfiguration
import com.btcpayapp.data.api.endpoints.rateConfiguration
import com.btcpayapp.data.api.endpoints.rateSources
import com.btcpayapp.data.api.endpoints.rates
import com.btcpayapp.data.api.endpoints.updateRateConfiguration
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedPage
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The store's exchange rate configuration.
 *
 * BTCPay keeps two independent configurations — `primary` and `fallback` — and
 * only consults the second when the first returns nothing. They are edited and
 * saved separately, hence the tabs.
 */
private const val PRIMARY = "primary"
private const val FALLBACK = "fallback"

data class StoreRatesState(
    val tab: Int = 0,
    val primary: StoreRateConfiguration? = null,
    val fallback: StoreRateConfiguration? = null,
    val sources: List<RateSourceData> = emptyList(),
    val pairs: List<String> = emptyList(),
    val currentRates: List<StoreRateResult> = emptyList(),
    val previewRates: List<StoreRateResult> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val previewing: Boolean = false,
    val error: ApiException? = null,
    val message: String? = null,
    val primaryDirty: Boolean = false,
    val fallbackDirty: Boolean = false,
) {
    val rateSource: String get() = if (tab == 0) PRIMARY else FALLBACK
    val config: StoreRateConfiguration? get() = if (tab == 0) primary else fallback
}

class StoreRatesViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(StoreRatesState())
    val state = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    private val storeId get() = graph.session.activeStore.value?.id

    init {
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest {
                    loadJob?.cancel()
                    _state.value = StoreRatesState()
                    load()
                }
        }
    }

    fun load(refreshing: Boolean = false) {
        val store = storeId
        val currency = graph.session.activeStore.value?.defaultCurrency
        if (store == null || currency == null) {
            _state.update { it.copy(loading = false, error = ApiException.NotFound("No store is selected.")) }
            return
        }
        val pairs = pairsFor(currency)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.primary == null, refreshing = refreshing, error = null, pairs = pairs)
            }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(loading = false, refreshing = false, error = failure.asApiException()) }
                return@launch
            }

            val primaryCall = async { runCatching { api.rateConfiguration(store, PRIMARY) } }
            // A server with no fallback configured answers 404 here; that is not
            // an error worth blanking the screen for.
            val fallbackCall = async { runCatching { api.rateConfiguration(store, FALLBACK) } }
            val sourcesCall = async { runCatching { api.rateSources() } }
            val ratesCall = async { runCatching { api.rates(store, pairs) } }
            awaitAll(primaryCall, fallbackCall, sourcesCall, ratesCall)

            val primary = primaryCall.await()
            val fallback = fallbackCall.await()
            val sources = sourcesCall.await()
            val currentRates = ratesCall.await()
            _state.update {
                it.copy(
                    primary = if (it.primaryDirty) it.primary else primary.getOrNull() ?: it.primary,
                    fallback = if (it.fallbackDirty) it.fallback else fallback.getOrNull() ?: it.fallback,
                    sources = sources.getOrDefault(it.sources),
                    currentRates = currentRates.getOrDefault(emptyList()),
                    loading = false,
                    refreshing = false,
                    error = primary.exceptionOrNull()?.asApiException(),
                )
            }
        }
    }

    fun refresh() = load(refreshing = true)

    fun selectTab(index: Int) = _state.update { it.copy(tab = index, previewRates = emptyList()) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun edit(transform: (StoreRateConfiguration) -> StoreRateConfiguration) = _state.update { current ->
        val updated = current.config?.let(transform) ?: return@update current
        if (current.tab == 0) current.copy(primary = updated, primaryDirty = true) else current.copy(fallback = updated, fallbackDirty = true)
    }

    fun save() {
        val store = storeId ?: return
        val snapshot = _state.value
        val config = snapshot.config ?: return
        if (snapshot.saving) return
        loadJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { graph.session.requireApi().updateRateConfiguration(store, config, snapshot.rateSource) }
                .onSuccess { saved ->
                    _state.update { current ->
                        val next = if (snapshot.tab == 0) {
                            if (current.primary == config) current.copy(primary = saved, primaryDirty = false) else current
                        } else {
                            if (current.fallback == config) current.copy(fallback = saved, fallbackDirty = false) else current
                        }
                        next.copy(saving = false, message = "Rate settings saved.")
                    }
                }
                .onFailure { failure -> _state.update { it.copy(saving = false, error = failure.asApiException()) } }
        }
    }

    fun preview() {
        val store = storeId ?: return
        val snapshot = _state.value
        val config = snapshot.config ?: return
        viewModelScope.launch {
            _state.update { it.copy(previewing = true, error = null, previewRates = emptyList()) }
            runCatching { graph.session.requireApi().previewRateConfiguration(store, config, snapshot.pairs) }
                .onSuccess { results -> _state.update { it.copy(previewing = false, previewRates = results) } }
                .onFailure { failure -> _state.update { it.copy(previewing = false, error = failure.asApiException()) } }
        }
    }

    /** A handful of pairs worth checking: the store's own currency first. */
    private fun pairsFor(currency: String): List<String> =
        listOf(currency.uppercase(), "USD", "EUR")
            .distinct()
            .filter { !it.equals("BTC", ignoreCase = true) }
            .take(3)
            .map { "BTC_$it" }

}

/** What the body is showing, independent of which tab it belongs to. */
private enum class RatesPhase { Loading, Error, Empty, Content }

/** The three things the app bar's trailing slot can be. */
private enum class RatesSaveAction { Busy, Ready, None }

@Composable
fun StoreRatesScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { StoreRatesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val guardedBack = com.btcpayapp.ui.components.confirmDiscardChanges(state.primaryDirty || state.fallbackDirty, onBack)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = "Rates",
        onBack = guardedBack,
        snackbarHostState = snackbarHostState,
        actions = {
            val action = when {
                state.saving -> RatesSaveAction.Busy
                state.config != null -> RatesSaveAction.Ready
                else -> RatesSaveAction.None
            }
            AnimatedSwap(action, label = "save") { shown ->
                when (shown) {
                    RatesSaveAction.Busy ->
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    RatesSaveAction.Ready -> TextButton(onClick = viewModel::save) { Text("Save") }
                    RatesSaveAction.None -> Unit
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("Primary") },
                )
                Tab(
                    selected = state.tab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("Fallback") },
                )
            }

            // Primary and fallback are peers with an order, so switching tabs
            // travels sideways; with only two of them, moving to the fallback
            // is forward and going back to the primary is not.
            //
            // The page reads its configuration from the tab it was composed
            // with rather than from the state, or the outgoing half of the
            // slide would already be showing the tab being moved to.
            AnimatedPage(state.tab, forward = state.tab == 1, label = "rateTab") { tab ->
                val config = if (tab == 0) state.primary else state.fallback
                val phase = when {
                    state.loading -> RatesPhase.Loading
                    state.error != null && state.primary == null -> RatesPhase.Error
                    config == null -> RatesPhase.Empty
                    else -> RatesPhase.Content
                }

                AnimatedSwap(phase, label = "rates") { shown ->
                    when (shown) {
                        // A spinner, not a skeleton: behind this is a form for
                        // one configuration, not a list of rows.
                        RatesPhase.Loading -> LoadingState()

                        RatesPhase.Error -> ErrorState(
                            error = state.error,
                            onRetry = viewModel::refresh,
                        )

                        RatesPhase.Empty -> EmptyState(
                            title = if (tab == 1) "No fallback configuration" else "No rate configuration",
                            description = "This server does not keep a ${if (tab == 1) FALLBACK else PRIMARY} " +
                                "rate source for the store, or the key cannot read it.",
                            actionLabel = "Try again",
                            onAction = viewModel::refresh,
                        )

                        RatesPhase.Content -> {
                            // The outgoing half of a swap outlives the state
                            // that chose it, so this can still be composed
                            // after the configuration has gone.
                            val current = config ?: return@AnimatedSwap

                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                                RateConfigSection(
                                    config = current,
                                    sources = state.sources,
                                    viewModel = viewModel,
                                    modifier = Modifier.arrive(0),
                                )
                                ThinDivider()
                                RatePreviewSection(
                                    state = state,
                                    viewModel = viewModel,
                                    modifier = Modifier.arrive(1),
                                )
                                ThinDivider()
                                CurrentRatesSection(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    modifier = Modifier.arrive(2),
                                )

                                Spacer(Modifier.height(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateConfigSection(
    config: StoreRateConfiguration,
    sources: List<RateSourceData>,
    viewModel: StoreRatesViewModel,
    modifier: Modifier = Modifier,
) {
    FormSection("Configuration", modifier) {
        FormField(
            label = "Spread (%)",
            value = config.spread,
            onValueChange = { value -> viewModel.edit { it.copy(spread = value) } },
            keyboardType = KeyboardType.Decimal,
            supportingText = "Applied on top of the fetched rate. 2 means a buyer pays 2% more " +
                "than the market rate.",
        )
        FormDropdown(
            label = "Preferred source",
            options = sources.map { it.id },
            selected = config.preferredSource.orEmpty(),
            onSelect = { value -> viewModel.edit { it.copy(preferredSource = value.ifBlank { null }) } },
            enabled = !config.isCustomScript && sources.isNotEmpty(),
            optionLabel = { id -> sources.firstOrNull { it.id == id }?.name?.ifBlank { id } ?: id },
            supportingText = if (config.isCustomScript) {
                "Ignored while a custom script is in use."
            } else {
                "The exchange asked first for this store's rates."
            },
        )
        FormSwitch(
            title = "Custom script",
            description = "Write the rate rules by hand instead of picking a single exchange.",
            checked = config.isCustomScript,
            onCheckedChange = { value -> viewModel.edit { it.copy(isCustomScript = value) } },
        )
        // Flipping the switch above swaps a read-only summary for a multi-line
        // editor. Two separate reveals rather than one swap, because the
        // summary is also absent when there is no script at all.
        AnimatedVisibility(
            visible = config.isCustomScript,
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            FormField(
                label = "Script",
                value = config.effectiveScript,
                onValueChange = { value -> viewModel.edit { it.copy(effectiveScript = value) } },
                singleLine = false,
                supportingText = "One rule per line, for example BTC_USD = kraken(BTC_USD);",
            )
        }
        AnimatedVisibility(
            visible = !config.isCustomScript && config.effectiveScript.isNotBlank(),
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            Text(
                text = config.effectiveScript,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RatePreviewSection(
    state: StoreRatesState,
    viewModel: StoreRatesViewModel,
    modifier: Modifier = Modifier,
) {
    FormSection("Preview", modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::preview, enabled = !state.previewing) {
                Text("Test these settings")
            }
            // Sideways rather than vertically: the spinner sits beside the
            // button, and expanding it downward would shift the whole section.
            AnimatedVisibility(
                visible = state.previewing,
                enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        Text(
            text = "Runs the configuration above without saving it, for ${state.pairs.joinToString(", ")}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        state.previewRates.forEachIndexed { index, result ->
            RateResultRow(result, Modifier.arrive(index))
        }
    }
}

@Composable
private fun CurrentRatesSection(
    state: StoreRatesState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FormSection("Current rates", modifier) {
        AnimatedSwap(state.currentRates.isEmpty(), label = "currentRates") { empty ->
            if (empty) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "No rates yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefresh) { Text("Fetch") }
                }
            } else {
                Column {
                    state.currentRates.forEachIndexed { index, result ->
                        RateResultRow(result, Modifier.arrive(index))
                    }
                }
            }
        }
    }
}

@Composable
private fun RateResultRow(result: StoreRateResult, modifier: Modifier = Modifier) {
    if (result.errors.isEmpty()) {
        DetailRow(
            label = result.currencyPair,
            value = Amounts.trim(result.rate, 8),
            modifier = modifier,
            monospace = true,
        )
    } else {
        DetailRow(
            label = result.currencyPair,
            value = result.errors.joinToString("\n"),
            modifier = modifier,
            valueColor = MaterialTheme.colorScheme.error,
        )
    }
}
