package com.btcpayapp.ui.screens.payout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.PullPaymentData
import com.btcpayapp.data.api.endpoints.pullPayments
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
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
 * state, so a refresh returning the same list does not replay its entrance.
 */
private enum class PullPaymentsPhase { NoStore, Loading, Error, Empty, Content }

data class PullPaymentListState(
    val pullPayments: List<PullPaymentData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val includeArchived: Boolean = false,
    val error: ApiException? = null,
    val storeMissing: Boolean = false,
)

class PullPaymentListViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PullPaymentListState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest { id ->
                    _state.update { it.copy(storeMissing = id == null) }
                    if (id != null) load()
                }
        }
    }

    fun setIncludeArchived(value: Boolean) {
        _state.update { it.copy(includeArchived = value) }
        load(refreshing = true)
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun load(refreshing: Boolean = false) {
        val store = graph.session.activeStore.value?.id ?: return
        val includeArchived = _state.value.includeArchived
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refreshing && it.pullPayments.isEmpty(),
                    refreshing = refreshing,
                    error = null,
                )
            }
            runCatching { graph.session.requireApi().pullPayments(store, includeArchived) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(pullPayments = list, loading = false, refreshing = false, error = null)
                    }
                }
                .onFailure { failure ->
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
fun PullPaymentListScreen(
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel { PullPaymentListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    AppScreen(
        title = "Pull payments",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            FormSwitch(
                title = "Show archived",
                checked = state.includeArchived,
                onCheckedChange = viewModel::setIncludeArchived,
            )
            ThinDivider()

            if (state.pullPayments.isNotEmpty()) {
                ErrorBanner(
                    state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                )
            }

            val phase = when {
                state.storeMissing -> PullPaymentsPhase.NoStore
                state.loading -> PullPaymentsPhase.Loading
                state.error != null && state.pullPayments.isEmpty() -> PullPaymentsPhase.Error
                state.pullPayments.isEmpty() -> PullPaymentsPhase.Empty
                else -> PullPaymentsPhase.Content
            }

            AnimatedSwap(phase, label = "pullPayments") { shown ->
                when (shown) {
                    PullPaymentsPhase.NoStore -> EmptyState(
                        title = "No store selected",
                        description = "Choose a store to see its pull payments.",
                        icon = Icons.Rounded.Redeem,
                    )

                    PullPaymentsPhase.Loading -> SkeletonList()

                    // Read through the nullable: a retry clears the error on the
                    // frame this branch starts leaving, and it is still composed.
                    PullPaymentsPhase.Error -> state.error?.let {
                        ErrorState(error = it, onRetry = viewModel::refresh)
                    }

                    PullPaymentsPhase.Empty -> EmptyState(
                        title = "No pull payments yet",
                        description = "A pull payment is a link someone else redeems to claim funds from this store.",
                        icon = Icons.Rounded.Redeem,
                        actionLabel = "Create one",
                        onAction = onCreate,
                    )

                    PullPaymentsPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.pullPayments, key = { it.id }) { pullPayment ->
                            // Row and rule move as one, so showing or hiding
                            // archived entries slides rather than redraws.
                            Column(Modifier.animateItem()) {
                                PullPaymentRow(
                                    pullPayment = pullPayment,
                                    onClick = { onOpen(pullPayment.id) },
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
private fun PullPaymentRow(pullPayment: PullPaymentData, onClick: () -> Unit) {
    val colors = AppTheme.statusColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = pullPayment.name.ifBlank { pullPayment.id },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = describeWindow(pullPayment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pullPayment.autoApproveClaims || pullPayment.archived) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pullPayment.autoApproveClaims) {
                        StatusPill(
                            label = "Auto-approve",
                            container = colors.settled,
                            content = colors.onSettled,
                        )
                    }
                    if (pullPayment.autoApproveClaims && pullPayment.archived) Spacer(Modifier.width(6.dp))
                    if (pullPayment.archived) {
                        StatusPill(label = "Archived", container = colors.expired, content = colors.onExpired)
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        AmountText(
            amount = pullPayment.amount,
            currency = pullPayment.currency,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun describeWindow(pullPayment: PullPaymentData): String {
    val starts = pullPayment.startsAt?.let { "From ${Dates.date(it)}" }
    val expires = pullPayment.expiresAt?.let { "until ${Dates.date(it)}" }
    return when {
        starts != null && expires != null -> "$starts $expires"
        starts != null -> starts
        expires != null -> "Open $expires"
        else -> "No time limit"
    }
}
