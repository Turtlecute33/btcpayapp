package com.btcpayapp.ui.screens.payout

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.endpoints.approvePayout
import com.btcpayapp.data.api.endpoints.cancelPayout
import com.btcpayapp.data.api.endpoints.markPayoutPaid
import com.btcpayapp.data.api.endpoints.payouts
import com.btcpayapp.data.api.endpoints.rates
import com.btcpayapp.data.model.BitcoinUnit
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
import com.btcpayapp.ui.components.ReviewLine
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.reviewDestination
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.screens.wallet.cryptoCodeOf
import com.btcpayapp.ui.theme.MonospaceStyle
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** The policy behind approve, mark paid, cancel and create. */
internal const val MANAGE_PAYOUTS = "btcpay.store.canmanagepayouts"

/**
 * What the body of the screen is showing.
 *
 * Cheap on purpose: a refresh that returns the same payouts leaves this
 * unchanged, so the list does not replay its entrance every thirty seconds.
 */
private enum class PayoutsPhase { NoStore, Loading, Error, Empty, Content }

/** What sits at the end of a payout row: a spinner, a menu, or nothing. */
private enum class PayoutRowTail { Busy, Menu, None }

enum class PayoutAction { Approve, MarkPaid, Cancel }

/** An action waiting for its confirmation dialog. */
data class PendingPayoutAction(val action: PayoutAction, val payout: PayoutData)

/**
 * The store's rate now for a payout priced in another currency than the coin
 * it pays: the rate an approval locks sets how much it pays. [rate] is null
 * when the server gave none.
 */
data class ApproveRate(val payoutId: String, val rate: BigDecimal?)

/** The pair whose rate sets what approving [payout] pays, or null when it is priced in the coin it pays. */
internal fun approveRatePair(payout: PayoutData): String? {
    val coin = cryptoCodeOf(payout.payoutMethodId)
    if (coin.isBlank() || payout.originalCurrency.isBlank() || coin.equals(payout.originalCurrency, ignoreCase = true)) return null
    return "${coin.uppercase()}_${payout.originalCurrency.uppercase()}"
}

data class PayoutListState(
    val payouts: List<PayoutData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val busyId: String? = null,
    val error: ApiException? = null,
    val filter: PayoutState? = null,
    val storeMissing: Boolean = false,
    val pending: PendingPayoutAction? = null,
    val approveRate: ApproveRate? = null,
    val message: String? = null,
) {
    val visible: List<PayoutData>
        get() = filter?.let { wanted -> payouts.filter { it.state == wanted } } ?: payouts
}

class PayoutListViewModel(private val graph: AppGraph) : ViewModel() {

    /** Every approval goes to the store whose payouts are on screen (see [StoreBinding]). */
    private val bound = StoreBinding(graph.session)

    /** Whose funds an approval commits, for the review and the prompt. */
    val storeName: String get() = bound.name

    /** False hides New and every row action, which the server would refuse with a 403. */
    val canManage: Boolean
        get() = bound.id?.let { graph.session.hasPermission(MANAGE_PAYOUTS, it) } == true

    // Loading from the start: the first read waits for the screen to resume,
    // and until then "no payouts yet" would be a claim nobody has checked.
    private val _state = MutableStateFlow(PayoutListState(storeMissing = bound.id == null, loading = bound.id != null))
    val state = _state.asStateFlow()

    init {
        bound.retryWhenKnown(viewModelScope) {
            _state.update { it.copy(storeMissing = false, loading = true) }
            load()
        }
    }

    /**
     * Loads on every return to the screen, so a payout approved or paid
     * elsewhere (the web UI, a processor) does not sit on screen in its old
     * state waiting for a pull to refresh.
     */
    fun onResume() = load()

    fun setFilter(filter: PayoutState?) {
        _state.update { it.copy(filter = filter) }
        load(refreshing = true)
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun reportMessage(text: String) = _state.update { it.copy(message = text) }

    fun request(action: PayoutAction, payout: PayoutData) {
        _state.update { it.copy(pending = PendingPayoutAction(action, payout), approveRate = null) }
        val store = bound.id ?: return
        val pair = approveRatePair(payout)?.takeIf { action == PayoutAction.Approve } ?: return
        viewModelScope.launch {
            val rate = attempt { graph.session.requireApi().rates(store, listOf(pair)) }.getOrNull()
                ?.firstOrNull { it.currencyPair.equals(pair, ignoreCase = true) && it.errors.isEmpty() }
                ?.rate?.takeIf { it.signum() > 0 }
            _state.update { if (it.pending?.payout?.id == payout.id) it.copy(approveRate = ApproveRate(payout.id, rate)) else it }
        }
    }

    fun dismissPending() = _state.update { it.copy(pending = null) }

    /**
     * The revision is the server's optimistic-concurrency guard: it moves every
     * time anything touches the payout, so it has to go back exactly as it came.
     * A rejection means the row on screen is stale, not that the approval was
     * wrong — which is why the failure path reloads instead of retrying.
     */
    fun approve(payout: PayoutData) = act(payout.id, "Payout approved") { store ->
        graph.session.requireApi().approvePayout(store, payout.id, payout.revision)
    }

    fun markPaid(payout: PayoutData) = act(payout.id, "Marked as paid") { store ->
        graph.session.requireApi().markPayoutPaid(store, payout.id)
    }

    fun cancel(payout: PayoutData) = act(payout.id, "Payout cancelled") { store ->
        graph.session.requireApi().cancelPayout(store, payout.id)
    }

    /**
     * Runs one row action, marked as a payment in flight. Each one decides
     * whether a payout is paid: approving commits the store to paying (a
     * processor may pay at once), and mark paid and cancel stop it for good.
     * So a store switch must not cancel the request and lose its answer.
     */
    private fun act(payoutId: String, success: String, block: suspend (storeId: String) -> Unit) {
        val store = bound.id ?: return
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = payoutId) }
        viewModelScope.launch {
            runCatching { graph.session.spending { block(store) } }
                .onSuccess {
                    _state.update { it.copy(busyId = null, message = success) }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    val api = failure.asApiException()
                    _state.update { it.copy(busyId = null, message = explain(api)) }
                    // Both settle by reading the list again: a stale row, or a
                    // request that may have gone through. Never by a resend.
                    if (api is ApiException.OutcomeUnknown || (api as? ApiException.Server)?.code == "old-revision") {
                        load(refreshing = true)
                    }
                }
        }
    }

    private fun load(refreshing: Boolean = false) {
        val store = bound.id ?: return
        val filter = _state.value.filter
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.payouts.isEmpty(), refreshing = refreshing, error = null)
            }
            // Unpaged on the server (GetStorePayouts has no skip/take), so a
            // very large store gets the plain "answer too large" error here.
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
private fun explain(error: ApiException): String {
    if (error is ApiException.OutcomeUnknown) {
        return "No clear answer came from the server, so this may have gone through. " +
            "The list has been reloaded. Check the payout before you try again."
    }
    return when ((error as? ApiException.Server)?.code) {
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
    val gate = rememberSpendGate()
    val unit = LocalSettings.current.bitcoinUnit

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Leaving would cancel a row action with the view model and lose its
    // answer, which says whether it went through. So back waits for it.
    val busy = state.busyId != null
    BackHandler(enabled = busy) {}

    AppScreen(
        title = "Payouts",
        onBack = { if (!busy) onBack() },
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (viewModel.canManage) {
                ExtendedFloatingActionButton(
                    onClick = onCreate,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New") },
                )
            }
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

                    PayoutsPhase.Empty -> {
                        val offerCreate = state.filter == null && viewModel.canManage
                        EmptyState(
                            title = if (state.filter == null) "No payouts yet" else "Nothing under this filter",
                            description = "Payouts are outgoing payments, either claimed from a pull payment or made here.",
                            icon = Icons.Rounded.Payments,
                            actionLabel = "Create one".takeIf { offerCreate },
                            onAction = onCreate.takeIf { offerCreate },
                        )
                    }

                    PayoutsPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.visible, key = { it.id }) { payout ->
                            // Row and rule travel together, so a payout leaving
                            // the current filter does not strand its divider.
                            Column(Modifier.animateItem()) {
                                PayoutRow(
                                    payout = payout,
                                    busy = state.busyId == payout.id,
                                    canManage = viewModel.canManage,
                                    onAction = { action -> viewModel.request(action, payout) },
                                )
                                ThinDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.pending?.let { (action, payout) ->
        val store = "Store" to viewModel.storeName
        val source = "Source" to (payout.pullPaymentId?.let { "Pull payment $it" } ?: "Direct payout")
        when (action) {
            // Approving commits the store to paying, and a processor may pay
            // at once. So the whole destination is shown first, then the same
            // prompt as a send, which fails closed.
            PayoutAction.Approve -> PayoutReviewDialog(
                title = "Approve this payout?",
                amount = payout.originalAmount,
                currency = payout.originalCurrency,
                payoutMethodId = payout.payoutMethodId,
                destination = payout.destination,
                details = listOf(store, source) + approveRateLines(payout, state.approveRate, unit),
                confirmLabel = "Approve",
                // Not before the rate is known or known to be missing: it
                // sets how much the approval pays.
                confirmEnabled = approveRatePair(payout) == null || state.approveRate?.payoutId == payout.id,
                onDismiss = viewModel::dismissPending,
                onConfirm = {
                    viewModel.dismissPending()
                    val subtitle = payoutPrompt(
                        reviewAmount(payout.originalAmount, payout.originalCurrency, unit),
                        viewModel.storeName,
                        payout.destination,
                    )
                    scope.afterSpendGate(gate, "Confirm payout", subtitle, viewModel::reportMessage) {
                        viewModel.approve(payout)
                    }
                },
            )

            // Irreversible: the server refuses any later change to a
            // completed payout, so a processor will never pay it. Reviewed as
            // an approval is, so a tap on the wrong row shows whose it is.
            PayoutAction.MarkPaid -> PayoutReviewDialog(
                title = "Mark as paid?",
                message = "The server records this payout as paid and will not send it. This cannot be undone.",
                amount = payout.originalAmount,
                currency = payout.originalCurrency,
                payoutMethodId = payout.payoutMethodId,
                destination = payout.destination,
                details = listOf(store, source),
                confirmLabel = "Mark paid",
                destructive = true,
                onDismiss = viewModel::dismissPending,
                onConfirm = {
                    viewModel.dismissPending()
                    viewModel.markPaid(payout)
                },
            )

            PayoutAction.Cancel -> ConfirmDialog(
                title = "Cancel payout?",
                message = "${TextUtil.middleEllipsis(payout.destination, 14, 10)} will not be paid. " +
                    "This cannot be undone.",
                confirmLabel = "Cancel payout",
                onConfirm = {
                    viewModel.dismissPending()
                    viewModel.cancel(payout)
                },
                onDismiss = viewModel::dismissPending,
                destructive = true,
            )
        }
    }
}

@Composable
private fun PayoutRow(
    payout: PayoutData,
    busy: Boolean,
    canManage: Boolean,
    onAction: (PayoutAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canApprove = payout.state == PayoutState.AwaitingApproval
    val canMarkPaid = payout.state == PayoutState.AwaitingPayment || payout.state == PayoutState.InProgress
    // Not InProgress: a transaction is already out, and the server refuses to
    // cancel it.
    val canCancel = canApprove || payout.state == PayoutState.AwaitingPayment

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
            canManage && (canApprove || canMarkPaid || canCancel) -> PayoutRowTail.Menu
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
                                    onAction(PayoutAction.Approve)
                                },
                            )
                        }
                        if (canMarkPaid) {
                            DropdownMenuItem(
                                text = { Text("Mark paid") },
                                leadingIcon = { Icon(Icons.Rounded.Done, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAction(PayoutAction.MarkPaid)
                                },
                            )
                        }
                        if (canCancel) {
                            DropdownMenuItem(
                                text = { Text("Cancel") },
                                leadingIcon = { Icon(Icons.Rounded.Cancel, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAction(PayoutAction.Cancel)
                                },
                            )
                        }
                    }
                }

                PayoutRowTail.None -> Spacer(Modifier.width(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The payout review, shared with PayoutCreateScreen
// ---------------------------------------------------------------------------

/**
 * [amount] as a payout review and its spend prompt show it: BTC in the user's
 * unit, any other currency in its own. Never masked, not even in privacy mode:
 * the review is where the operator reads the amount before it is committed,
 * as on the send screens.
 */
internal fun reviewAmount(amount: BigDecimal, currency: String, unit: BitcoinUnit): String =
    if (currency.equals("BTC", ignoreCase = true)) Amounts.formatBitcoin(amount, unit) else Amounts.format(amount, currency)

/**
 * The review lines for the rate of a payout priced in another currency than
 * the coin it pays: the store's rate now and about what the approval pays at
 * it. Empty when the payout is priced in its coin.
 */
private fun approveRateLines(payout: PayoutData, known: ApproveRate?, unit: BitcoinUnit): List<Pair<String, String>> {
    if (approveRatePair(payout) == null) return emptyList()
    val coin = cryptoCodeOf(payout.payoutMethodId).uppercase()
    val rate = known?.takeIf { it.payoutId == payout.id } ?: return listOf("Rate now" to "Loading…")
    val value = rate.rate ?: return listOf("Rate now" to "Not known")
    return listOf(
        "Rate now" to "1 $coin = ${Amounts.format(value, payout.originalCurrency)}",
        "About" to reviewAmount(payout.originalAmount.divide(value, 8, RoundingMode.HALF_UP), coin, unit),
    )
}

/** The spend prompt's subtitle for a payout: what it pays, from which store, and to whom, as the review showed them. */
internal fun payoutPrompt(amount: String, store: String, destination: String): String =
    "Pay $amount from $store to ${TextUtil.middleEllipsis(destination, 8, 6)}"

/**
 * The review in front of every payout this app approves, marks paid or
 * creates: amount, method, [details] and the full destination, after
 * [message] when the title alone does not say what happens. The content
 * scrolls, so a 300-character BOLT11 is readable to its end on a small phone.
 */
@Composable
internal fun PayoutReviewDialog(
    title: String,
    amount: BigDecimal,
    currency: String,
    payoutMethodId: String,
    destination: String,
    details: List<Pair<String, String>>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    val unit = LocalSettings.current.bitcoinUnit
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message?.let { Text(it) }
                ReviewLine("Amount") {
                    Text(reviewAmount(amount, currency, unit), style = MaterialTheme.typography.titleMedium)
                }
                ReviewLine("Method") { Text(payoutMethodLabel(payoutMethodId), style = MaterialTheme.typography.bodyLarge) }
                details.forEach { (label, value) ->
                    ReviewLine(label) { Text(value, style = MaterialTheme.typography.bodyLarge) }
                }
                ReviewLine("Destination") { Text(reviewDestination(destination), style = MonospaceStyle) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    text = confirmLabel,
                    color = when {
                        !confirmEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
