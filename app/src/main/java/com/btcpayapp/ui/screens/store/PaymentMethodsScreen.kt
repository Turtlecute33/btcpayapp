package com.btcpayapp.ui.screens.store

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.PaymentMethodData
import com.btcpayapp.data.api.dto.UpdatePaymentMethodRequest
import com.btcpayapp.data.api.endpoints.paymentMethods
import com.btcpayapp.data.api.endpoints.updatePaymentMethod
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The store's payment methods, grouped by kind.
 *
 * Identifiers are the BTCPay 2.0 form: `BTC-CHAIN`, `BTC-LN`, `BTC-LNURL`.
 * They replaced the 1.x names — `BTC` for on-chain and `BTC_LightningLike` for
 * Lightning — so the kind has to be read off the suffix rather than assumed
 * from the crypto code, and an unrecognised suffix is shown rather than hidden.
 */
data class PaymentMethodsState(
    val methods: List<PaymentMethodData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val busy: Set<String> = emptySet(),
    val error: ApiException? = null,
)

class PaymentMethodsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PaymentMethodsState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest { load() }
        }
    }

    fun load(refreshing: Boolean = false) {
        val storeId = graph.session.activeStore.value?.id
        if (storeId == null) {
            _state.update { it.copy(loading = false, error = ApiException.NotFound("No store is selected.")) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.methods.isEmpty(), refreshing = refreshing, error = null)
            }
            runCatching { graph.session.requireApi().paymentMethods(storeId, includeConfig = false) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(methods = list.sortedBy { m -> m.paymentMethodId }, loading = false, refreshing = false)
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, refreshing = false, error = failure.asApiException()) }
                }
        }
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun setEnabled(paymentMethodId: String, enabled: Boolean) {
        val storeId = graph.session.activeStore.value?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + paymentMethodId, error = null) }
            runCatching {
                // Only `enabled` is sent: a null field on this request means
                // "leave unchanged", so an omitted config cannot wipe the wallet.
                graph.session.requireApi().updatePaymentMethod(
                    storeId = storeId,
                    paymentMethodId = paymentMethodId,
                    request = UpdatePaymentMethodRequest(enabled = enabled),
                )
            }.onSuccess { updated ->
                graph.session.refreshPaymentMethods(storeId)
                _state.update { current ->
                    current.copy(
                        methods = current.methods.map { if (it.paymentMethodId == paymentMethodId) updated else it },
                        busy = current.busy - paymentMethodId,
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(busy = it.busy - paymentMethodId, error = failure.asApiException()) }
            }
        }
    }

}

/** What the body is showing; a cheap discriminator, not the method list. */
private enum class PaymentMethodsPhase { Loading, Error, Empty, Content }

@Composable
fun PaymentMethodsScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val viewModel = appViewModel { PaymentMethodsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val groups = remember(state.methods) { groupPaymentMethods(state.methods) }

    AppScreen(
        title = "Payment methods",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        val phase = when {
            state.loading -> PaymentMethodsPhase.Loading
            state.error != null && state.methods.isEmpty() -> PaymentMethodsPhase.Error
            state.methods.isEmpty() -> PaymentMethodsPhase.Empty
            else -> PaymentMethodsPhase.Content
        }

        AnimatedSwap(phase, label = "paymentMethods") { shown ->
            when (shown) {
                PaymentMethodsPhase.Loading -> SkeletonList(Modifier.padding(padding), rows = 4)

                PaymentMethodsPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::refresh,
                )

                PaymentMethodsPhase.Empty -> EmptyState(
                    title = "Nothing to configure",
                    description = "This server has no payment methods available for the store.",
                    icon = Icons.Rounded.Payments,
                    modifier = Modifier.padding(padding),
                )

                PaymentMethodsPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    groups.forEachIndexed { index, group ->
                        Column(Modifier.arrive(index)) {
                            SectionHeader(group.kind.title)
                            group.methods.forEach { method ->
                                PaymentMethodRow(
                                    method = method,
                                    icon = group.kind.icon,
                                    busy = method.paymentMethodId in state.busy,
                                    onToggle = { viewModel.setEnabled(method.paymentMethodId, it) },
                                    onClick = { onEdit(method.paymentMethodId) },
                                )
                                ThinDivider()
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodRow(
    method: PaymentMethodData,
    icon: ImageVector,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(method.paymentMethodId, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (method.enabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The switch is replaced by a spinner for the length of a round trip.
        // Swapping them on the frame makes a slow server look like a control
        // that vanished; cross-fading says the same control is thinking.
        AnimatedSwap(busy, label = "methodBusy") { working ->
            if (working) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                // Disabled on `busy`, not on `working`. `working` is this
                // branch's own copy of the flag and is false for as long as
                // the outgoing branch takes to fade — during which the switch
                // is invisible but still answers a tap, and a second tap sends
                // a second PUT that races the first. Reading the live flag
                // closes that window.
                Switch(
                    checked = method.enabled,
                    onCheckedChange = onToggle,
                    enabled = !busy,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Grouping
// ---------------------------------------------------------------------------

enum class PaymentMethodKind(val title: String, val icon: ImageVector) {
    OnChain("On-chain", Icons.Rounded.Link),
    Lightning("Lightning", Icons.Rounded.Bolt),
    Lnurl("LNURL", Icons.Rounded.QrCode2),
    Other("Other", Icons.Rounded.Payments),
}

data class PaymentMethodGroup(val kind: PaymentMethodKind, val methods: List<PaymentMethodData>)

/** `-LNURL` is tested before `-LN` only for readability; the two never overlap. */
internal fun paymentMethodKind(paymentMethodId: String): PaymentMethodKind = when {
    paymentMethodId.endsWith("-LNURL", ignoreCase = true) -> PaymentMethodKind.Lnurl
    paymentMethodId.endsWith("-LN", ignoreCase = true) -> PaymentMethodKind.Lightning
    paymentMethodId.endsWith("-CHAIN", ignoreCase = true) -> PaymentMethodKind.OnChain
    else -> PaymentMethodKind.Other
}

private fun groupPaymentMethods(methods: List<PaymentMethodData>): List<PaymentMethodGroup> =
    PaymentMethodKind.entries.mapNotNull { kind ->
        methods.filter { paymentMethodKind(it.paymentMethodId) == kind }
            .takeIf { it.isNotEmpty() }
            ?.let { PaymentMethodGroup(kind, it) }
    }
