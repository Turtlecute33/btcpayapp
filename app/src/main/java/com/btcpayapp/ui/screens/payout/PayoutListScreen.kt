package com.btcpayapp.ui.screens.payout

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.security.AuthOutcome
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.endpoints.approvePayout
import com.btcpayapp.data.api.endpoints.cancelPayout
import com.btcpayapp.data.api.endpoints.markPayoutPaid
import com.btcpayapp.data.api.endpoints.payouts
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.PayoutStatusChip
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.ThinDivider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val FILTERS: List<PayoutState?> = listOf(
    null,
    PayoutState.AwaitingApproval,
    PayoutState.AwaitingPayment,
    PayoutState.InProgress,
    PayoutState.Completed,
    PayoutState.Cancelled,
)

/**
 * What the body of the screen is showing.
 *
 * Cheap on purpose: a refresh that returns the same payouts leaves this
 * unchanged, so the list does not replay its entrance every thirty seconds.
 */
private enum class PayoutsPhase { NoStore, Loading, Error, Empty, Content }

/** What sits at the end of a payout row: a spinner, a menu, or nothing. */
private enum class PayoutRowTail { Busy, Menu, None }

data class PayoutListState(
    val payouts: List<PayoutData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val busyId: String? = null,
    val error: ApiException? = null,
    val filter: PayoutState? = null,
    val storeMissing: Boolean = false,
    val pendingCancel: PayoutData? = null,
    val message: String? = null,
) {
    val visible: List<PayoutData>
        get() = filter?.let { wanted -> payouts.filter { it.state == wanted } } ?: payouts
}

class PayoutListViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PayoutListState())
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

    fun setFilter(filter: PayoutState?) {
        _state.update { it.copy(filter = filter) }
        load(refreshing = true)
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun reportMessage(text: String) = _state.update { it.copy(message = text) }

    fun requestCancel(payout: PayoutData) = _state.update { it.copy(pendingCancel = payout) }

    fun dismissCancel() = _state.update { it.copy(pendingCancel = null) }

    /**
     * The revision is the server's optimistic-concurrency guard: it moves every
     * time anything touches the payout, so it has to go back exactly as it came.
     * A rejection means the row on screen is stale, not that the approval was
     * wrong — which is why the failure path reloads instead of retrying.
     */
    fun approve(payout: PayoutData) = act(payout.id, "Payout approved") {
        graph.session.requireApi().approvePayout(payout.id, payout.revision)
    }

    fun markPaid(payout: PayoutData) = act(payout.id, "Marked as paid") {
        graph.session.requireApi().markPayoutPaid(payout.id)
    }

    fun confirmCancel() {
        val target = _state.value.pendingCancel ?: return
        _state.update { it.copy(pendingCancel = null) }
        act(target.id, "Payout cancelled") {
            graph.session.requireApi().cancelPayout(target.id)
        }
    }

    private fun act(payoutId: String, success: String, block: suspend () -> Unit) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = payoutId) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    _state.update { it.copy(busyId = null, message = success) }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    val api = failure.asApiException()
                    _state.update { it.copy(busyId = null, message = explain(api)) }
                    if ((api as? ApiException.Server)?.code == "old-revision") load(refreshing = true)
                }
        }
    }

    private fun load(refreshing: Boolean = false) {
        val store = graph.session.activeStore.value?.id ?: return
        val filter = _state.value.filter
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.payouts.isEmpty(), refreshing = refreshing, error = null)
            }
            runCatching {
                graph.session.requireApi().payouts(
                    storeId = store,
                    // Cancelled payouts are noise under a specific filter but
                    // dishonest to omit from "all".
                    includeCancelled = filter == null || filter == PayoutState.Cancelled,
                )
            }
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            payouts = list.sortedByDescending(PayoutData::date),
                            loading = false,
                            refreshing = false,
                            error = null,
                        )
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

/** Server codes, rendered as something a merchant can act on. */
private fun explain(error: ApiException): String = when ((error as? ApiException.Server)?.code) {
    "old-revision" ->
        "That payout changed while it was on screen. The list has been reloaded — check it and try again."
    "rate-unavailable" ->
        "No exchange rate is available for that currency right now. Try again in a moment."
    "amount-too-low" ->
        "That amount is below the minimum this payout method accepts."
    "invalid-state" ->
        "That payout is no longer in a state that allows this."
    else -> error.userMessage
}

@Composable
fun PayoutListScreen(
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel { PayoutListViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current as? FragmentActivity
    val confirmSpends = LocalSettings.current.confirmSpendsWithBiometrics

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val approve: (PayoutData) -> Unit = { payout ->
        scope.launch {
            // Approving locks in a rate and commits the store to sending money,
            // so it sits behind the same prompt as a spend — and fails closed.
            // A null `activity` refuses rather than skipping the prompt, which
            // would approve the payout with no prompt whatsoever.
            if (!confirmSpends) {
                viewModel.approve(payout)
                return@launch
            }
            if (activity == null) {
                viewModel.reportMessage(
                    "This device cannot show the confirmation prompt, so the payout was not " +
                        "approved. Turn off “Confirm spends” in Settings to approve without it.",
                )
                return@launch
            }
            when (Biometrics.prompt(
                activity = activity,
                title = "Approve payout",
                subtitle = Amounts.format(payout.originalAmount, payout.originalCurrency),
            )) {
                is AuthOutcome.Success -> viewModel.approve(payout)
                is AuthOutcome.Cancelled -> Unit
                else -> viewModel.reportMessage("Not authenticated, so the payout was not approved.")
            }
        }
    }

    AppScreen(
        title = "Payouts",
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
                items(FILTERS) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter?.let { TextUtil.sentenceCase(it.name) } ?: "All") },
                    )
                }
            }

            if (state.payouts.isNotEmpty()) {
                ErrorBanner(
                    state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                )
            }

            val phase = when {
                state.storeMissing -> PayoutsPhase.NoStore
                state.loading -> PayoutsPhase.Loading
                state.error != null && state.payouts.isEmpty() -> PayoutsPhase.Error
                state.visible.isEmpty() -> PayoutsPhase.Empty
                else -> PayoutsPhase.Content
            }

            AnimatedSwap(phase, label = "payouts") { shown ->
                when (shown) {
                    PayoutsPhase.NoStore -> EmptyState(
                        title = "No store selected",
                        description = "Choose a store to see its payouts.",
                        icon = Icons.Rounded.Payments,
                    )

                    PayoutsPhase.Loading -> SkeletonList()

                    // Read through the nullable: a retry clears the error on the
                    // frame this branch starts leaving, and it is still composed.
                    PayoutsPhase.Error -> state.error?.let {
                        ErrorState(error = it, onRetry = viewModel::refresh)
                    }

                    PayoutsPhase.Empty -> EmptyState(
                        title = if (state.filter == null) "No payouts yet" else "Nothing under this filter",
                        description = "Payouts are outgoing payments, either claimed from a pull payment or made here.",
                        icon = Icons.Rounded.Payments,
                        actionLabel = "Create one".takeIf { state.filter == null },
                        onAction = onCreate.takeIf { state.filter == null },
                    )

                    PayoutsPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.visible, key = { it.id }) { payout ->
                            // Row and rule travel together, so a payout leaving
                            // the current filter does not strand its divider.
                            Column(Modifier.animateItem()) {
                                PayoutRow(
                                    payout = payout,
                                    busy = state.busyId == payout.id,
                                    onApprove = { approve(payout) },
                                    onMarkPaid = { viewModel.markPaid(payout) },
                                    onCancel = { viewModel.requestCancel(payout) },
                                )
                                ThinDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.pendingCancel?.let { target ->
        ConfirmDialog(
            title = "Cancel payout?",
            message = "${TextUtil.middleEllipsis(target.destination, 14, 10)} will not be paid. " +
                "This cannot be undone.",
            confirmLabel = "Cancel payout",
            onConfirm = viewModel::confirmCancel,
            onDismiss = viewModel::dismissCancel,
            destructive = true,
        )
    }
}

@Composable
private fun PayoutRow(
    payout: PayoutData,
    busy: Boolean,
    onApprove: () -> Unit,
    onMarkPaid: () -> Unit,
    onCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canApprove = payout.state == PayoutState.AwaitingApproval
    val canMarkPaid = payout.state == PayoutState.AwaitingPayment || payout.state == PayoutState.InProgress
    val canCancel = canApprove || canMarkPaid

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = TextUtil.middleEllipsis(payout.destination, 14, 10),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PayoutStatusChip(state = payout.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${payoutMethodLabel(payout.payoutMethodId)} · ${Dates.relative(payout.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            AmountText(
                amount = payout.originalAmount,
                currency = payout.originalCurrency,
                style = MaterialTheme.typography.titleMedium,
            )
            // Only present once approved, when a rate has been locked in.
            val settled = payout.payoutAmount
            if (settled != null) {
                AmountText(
                    amount = settled,
                    currency = payout.payoutCurrency ?: payout.originalCurrency,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Approving swaps the menu for a spinner under the finger that asked
        // for it, which is the one moment on this row worth animating.
        val tail = when {
            busy -> PayoutRowTail.Busy
            canCancel -> PayoutRowTail.Menu
            else -> PayoutRowTail.None
        }

        AnimatedSwap(tail, label = "payoutAction") { shown ->
            when (shown) {
                PayoutRowTail.Busy ->
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp))

                PayoutRowTail.Menu -> Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Payout actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canApprove) {
                            DropdownMenuItem(
                                text = { Text("Approve") },
                                leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onApprove()
                                },
                            )
                        }
                        if (canMarkPaid) {
                            DropdownMenuItem(
                                text = { Text("Mark paid") },
                                leadingIcon = { Icon(Icons.Rounded.Done, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onMarkPaid()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Cancel") },
                            leadingIcon = { Icon(Icons.Rounded.Cancel, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCancel()
                            },
                        )
                    }
                }

                PayoutRowTail.None -> Spacer(Modifier.width(16.dp))
            }
        }
    }
}
