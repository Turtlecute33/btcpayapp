package com.btcpayapp.ui.screens.wallet

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.WalletUtxoData
import com.btcpayapp.data.api.endpoints.walletUtxos
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** What the screen is showing, kept cheap so a refresh does not replay it. */
private enum class UtxoPhase { Loading, Error, Empty, Content }

enum class UtxoSort(val label: String) {
    Amount("Largest first"),
    Age("Newest first"),
}

data class UtxoState(
    val utxos: List<WalletUtxoData> = emptyList(),
    val sort: UtxoSort = UtxoSort.Amount,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
) {
    val total: BigDecimal
        get() = utxos.fold(BigDecimal.ZERO) { running, utxo -> running + utxo.amount }

    val sorted: List<WalletUtxoData>
        get() = when (sort) {
            UtxoSort.Amount -> utxos.sortedByDescending { it.amount }
            UtxoSort.Age -> utxos.sortedByDescending { it.timestamp }
        }
}

class UtxoViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
) : ViewModel() {

    /** The store this screen was opened in (see [StoreBinding]); the shell drops it on a switch. */
    private val store = StoreBinding(graph.session)
    private val storeId: String? get() = store.id

    private val _state = MutableStateFlow(UtxoState())
    val state = _state.asStateFlow()

    init {
        load(refreshing = false)
        store.retryWhenKnown(viewModelScope) { load(refreshing = false) }
    }

    fun refresh() = load(refreshing = true)

    fun setSort(sort: UtxoSort) = _state.update { it.copy(sort = sort) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun load(refreshing: Boolean) {
        val storeId = storeId ?: return _state.update { it.copy(error = ApiException.NoAccount()) }
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.utxos.isEmpty(), refreshing = refreshing, error = null)
            }
            runCatching {
                graph.session.requireApi().walletUtxos(storeId, paymentMethodId)
            }.onSuccess { utxos ->
                _state.update { it.copy(utxos = utxos, loading = false, refreshing = false, error = null) }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = failure as? ApiException
                            ?: ApiException.Transport(failure.message ?: "Unexpected failure"),
                    )
                }
            }
        }
    }
}

@Composable
fun UtxoScreen(paymentMethodId: String, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "utxos-$paymentMethodId") {
        UtxoViewModel(it, paymentMethodId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val cryptoCode = remember(paymentMethodId) { cryptoCodeOf(paymentMethodId) }

    val total = remember(state.utxos, settings.bitcoinUnit) {
        formatOnChain(state.total, cryptoCode, settings.bitcoinUnit)
    }
    val count = state.utxos.size
    // The app bar is the one amount the component library cannot mask for
    // us, and a wallet total is exactly what privacy mode exists to hide.
    val subtitle = "$count ${if (count == 1) "coin" else "coins"} · ${maskedIfPrivate(total)}"

    AppScreen(
        title = "Coins",
        subtitle = subtitle,
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
    ) { padding ->
        val phase = when {
            state.loading -> UtxoPhase.Loading
            state.error != null && state.utxos.isEmpty() -> UtxoPhase.Error
            state.utxos.isEmpty() -> UtxoPhase.Empty
            else -> UtxoPhase.Content
        }

        // Held across the swap out: retry clears `state.error` on the frame the
        // exit begins, and the outgoing branch is composed until it has finished
        // leaving.
        val lastError = remember { mutableStateOf<ApiException?>(null) }
        if (state.error != null) lastError.value = state.error

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "coins") { shown ->
            when (shown) {
                UtxoPhase.Loading -> SkeletonList()

                UtxoPhase.Error -> lastError.value?.let { failure ->
                    ErrorState(error = failure, onRetry = viewModel::refresh)
                }

                UtxoPhase.Empty -> EmptyState(
                    title = "No coins",
                    description = "This wallet holds no unspent outputs. Once it receives a payment, " +
                        "each output appears here.",
                    icon = Icons.Rounded.Toll,
                )

                UtxoPhase.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item { ErrorBanner(state.error, onDismiss = viewModel::dismissError) }

                    item {
                        SortRow(selected = state.sort, onSelect = viewModel::setSort)
                    }

                    // Changing the sort reorders the same coins rather than
                    // replacing them, and the outpoint key is what lets each
                    // card travel to its new place instead of being redrawn
                    // with somebody else's amount on it.
                    items(state.sorted, key = { it.outpoint }) { utxo ->
                        UtxoCard(
                            utxo = utxo,
                            cryptoCode = cryptoCode,
                            modifier = Modifier.animateItem(
                                fadeInSpec = Motion.effects,
                                placementSpec = Motion.spatialOffset,
                                fadeOutSpec = Motion.effectsFast,
                            ),
                        )
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SortRow(selected: UtxoSort, onSelect: (UtxoSort) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(UtxoSort.entries.toList()) { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.label) },
            )
        }
    }
}

@Composable
private fun UtxoCard(utxo: WalletUtxoData, cryptoCode: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmountText(
                    amount = utxo.amount,
                    currency = cryptoCode,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (utxo.confirmations > 0) {
                        "${utxo.confirmations} conf"
                    } else {
                        "Unconfirmed"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = Dates.relative(utxo.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = TextUtil.middleEllipsis(utxo.address, 14, 10),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))
            CopyableField(label = "Outpoint", value = utxo.outpoint, truncate = true)

            if (utxo.labels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = utxo.labels.joinToString(" · ") { it.text },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
