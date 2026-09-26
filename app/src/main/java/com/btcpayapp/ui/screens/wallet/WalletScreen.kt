package com.btcpayapp.ui.screens.wallet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.lightning.Bolt11
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.LightningInvoiceData
import com.btcpayapp.data.api.dto.LightningInvoiceStatus
import com.btcpayapp.data.api.dto.LightningPaymentData
import com.btcpayapp.data.api.dto.LightningPaymentStatus
import com.btcpayapp.data.api.dto.WalletOverviewData
import com.btcpayapp.data.api.dto.WalletTransactionData
import com.btcpayapp.data.api.dto.WalletTransactionStatus
import com.btcpayapp.data.api.endpoints.LightningScope
import com.btcpayapp.data.api.endpoints.lightningBalance
import com.btcpayapp.data.api.endpoints.lightningInvoices
import com.btcpayapp.data.api.endpoints.lightningPayments
import com.btcpayapp.data.api.endpoints.walletOverview
import com.btcpayapp.data.api.endpoints.walletTransactions
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DirectionIcon
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.SkeletonRow
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.continuity
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

private const val RECENT_TRANSACTION_LIMIT = 25
private const val RECENT_LIGHTNING_LIMIT = 15

/**
 * Which of the four screens the wallet currently is.
 *
 * The swap between them is animated, and it is driven by this rather than by
 * `WalletState`: the state object changes on every background refresh, so
 * handing it to the animation would replay the whole transition each time a
 * balance came back identical to the one already on screen.
 */
private enum class WalletPhase { Loading, Error, Empty, Content }

internal const val TAB_ON_CHAIN = 0
internal const val TAB_LIGHTNING = 1

/** `BTC-CHAIN` → `BTC`. */
internal fun cryptoCodeOf(paymentMethodId: String): String = paymentMethodId.substringBefore('-')

/**
 * On-chain values arrive BTC-denominated from Greenfield. Bitcoin honours the
 * user's sat/BTC preference; the altcoins BTCPay supports have no sat unit, so
 * they keep their own code.
 */
internal fun formatOnChain(amount: BigDecimal, cryptoCode: String, unit: BitcoinUnit): String =
    if (cryptoCode.equals("BTC", ignoreCase = true)) {
        Amounts.formatBitcoin(amount, unit)
    } else {
        Amounts.format(amount, cryptoCode)
    }

data class WalletState(
    val paymentMethodIds: List<String> = emptyList(),
    val selected: String? = null,
    val lightningCryptoCode: String? = null,
    /** False until the store's payment methods have been read at least once. */
    val methodsKnown: Boolean = false,
    val tab: Int = TAB_ON_CHAIN,

    val overview: WalletOverviewData? = null,
    val transactions: List<WalletTransactionData> = emptyList(),

    /** Spendable channel balance, in msat. Null when the node did not answer. */
    val lightningLocalMsat: String? = null,
    val lightningInvoices: List<LightningInvoiceData> = emptyList(),
    val lightningPayments: List<LightningPaymentData> = emptyList(),
    /** False until the Lightning tab has been opened at least once. */
    val lightningHistoryLoaded: Boolean = false,
    val lightningHistoryLoading: Boolean = false,
    /** The node is configured but not answering; not an error worth a banner. */
    val lightningOffline: Boolean = false,

    /** False when this app is certain it cannot sign for [selected]. */
    val canSpendOnChain: Boolean = true,
    /** False when the paired key was granted without the use-node permission. */
    val canSpendLightning: Boolean = true,

    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
) {
    val hasLightning: Boolean get() = lightningCryptoCode != null
    val hasOnChain: Boolean get() = paymentMethodIds.isNotEmpty()

    /** Lightning is only a tab when the store actually has a node configured. */
    val effectiveTab: Int get() = if (hasLightning) tab else TAB_ON_CHAIN
}

class WalletViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(WalletState())
    val state = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // The enabled payment methods arrive asynchronously after a store
            // switch, so the wallet is driven by them rather than by the store
            // alone — otherwise the first load fires against an unknown method.
            combine(graph.session.activeStore, graph.session.paymentMethods, graph.session.paymentMethodsLoaded) { store, methods, loaded ->
                Triple(store?.id, methods, loaded)
            }.distinctUntilChanged().collectLatest { (storeId, methods, loaded) ->
                loadJob?.cancel()
                val enabled = methods.filter { it.enabled }
                val onChain = enabled
                    .map { it.paymentMethodId }
                    .filter { it.endsWith("-CHAIN", ignoreCase = true) }
                val lightning = enabled
                    .map { it.paymentMethodId }
                    .firstOrNull { it.endsWith("-LN", ignoreCase = true) }

                _state.update { current ->
                    val selected = current.selected?.takeIf(onChain::contains) ?: onChain.firstOrNull()
                    current.copy(
                        paymentMethodIds = onChain,
                        selected = selected,
                        lightningCryptoCode = lightning?.let(::cryptoCodeOf),
                        methodsKnown = loaded,
                        // A store with Lightning and no on-chain wallet opens on
                        // the only tab it has.
                        tab = if (onChain.isEmpty() && lightning != null) TAB_LIGHTNING else current.tab,
                        overview = null,
                        transactions = emptyList(),
                        lightningLocalMsat = null,
                        lightningInvoices = emptyList(),
                        lightningPayments = emptyList(),
                        lightningHistoryLoaded = false,
                        lightningOffline = false,
                        canSpendOnChain = selected?.let(graph.session::canSpendOnChain) ?: true,
                        canSpendLightning = graph.session.canSpendLightning(),
                        error = null,
                    )
                }
                if (storeId != null && loaded && (onChain.isNotEmpty() || lightning != null)) load()
            }
        }
        viewModelScope.launch {
            // Without this the tab would spin for ever when the payment-method
            // call itself failed: that failure is recorded on the session, not
            // here, and nothing else would ever populate the method list.
            graph.session.lastError.collectLatest { sessionError ->
                if (sessionError != null && _state.value.paymentMethodIds.isEmpty()) {
                    _state.update { it.copy(error = sessionError, loading = false) }
                }
            }
        }
        viewModelScope.launch {
            // A send that the server refused to sign disables the button here
            // too, rather than only on the screen that found out.
            graph.session.unspendableMethods.collectLatest {
                _state.update { current ->
                    current.copy(
                        canSpendOnChain = current.selected?.let(graph.session::canSpendOnChain) ?: true,
                    )
                }
            }
        }
    }

    fun select(paymentMethodId: String) {
        if (_state.value.selected == paymentMethodId) return
        _state.update {
            it.copy(
                selected = paymentMethodId,
                overview = null,
                transactions = emptyList(),
                canSpendOnChain = graph.session.canSpendOnChain(paymentMethodId),
            )
        }
        load()
    }

    /**
     * The balance of both rails loads with the screen, because the merged total
     * at the top needs it. The Lightning *history* is two more round trips, so
     * it waits until someone actually looks at that tab — on a node behind Tor
     * that is the difference between a wallet that opens and one that hangs.
     */
    fun selectTab(index: Int) {
        if (_state.value.tab == index) return
        _state.update { it.copy(tab = index) }
        if (index == TAB_LIGHTNING && !_state.value.lightningHistoryLoaded) loadLightningHistory()
    }

    fun refresh() {
        viewModelScope.launch {
            graph.session.refresh()
            graph.session.activeStore.value?.id?.let { graph.session.refreshPaymentMethods(it) }
            load(refreshing = true)
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Loads both rails together.
     *
     * The on-chain wallet and the Lightning node are separate subsystems on the
     * server and fail separately — a node that is down must not blank the
     * on-chain balance, and a store with no on-chain wallet must still show its
     * channels. So every call is independent and its failure is local.
     */
    private fun load(refreshing: Boolean = false) {
        val storeId = graph.session.activeStore.value?.id ?: return
        val snapshot = _state.value
        val paymentMethodId = snapshot.selected
        val lightningCode = snapshot.lightningCryptoCode
        if (paymentMethodId == null && lightningCode == null) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refreshing && it.overview == null && it.lightningLocalMsat == null,
                    refreshing = refreshing,
                    error = null,
                )
            }

            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update {
                    it.copy(loading = false, refreshing = false, error = failure.asApiException())
                }
                return@launch
            }

            coroutineScope {
                val onChain = async {
                    paymentMethodId?.let {
                        runCatching {
                            val overview = api.walletOverview(storeId, it)
                            val transactions = api.walletTransactions(
                                storeId = storeId,
                                paymentMethodId = it,
                                limit = RECENT_TRANSACTION_LIMIT,
                            )
                            overview to transactions
                        }
                    }
                }
                val balance = async {
                    lightningCode?.let {
                        runCatching { api.lightningBalance(LightningScope.Store(storeId), it) }
                    }
                }

                val onChainResult = onChain.await()
                val balanceResult = balance.await()

                // Only the on-chain failure is worth a banner. The node being
                // unreachable is an ordinary state on a self-hosted instance and
                // gets a quiet line in the Lightning tab instead.
                val onChainError = onChainResult?.exceptionOrNull()?.asApiException()
                val nodeError = balanceResult?.exceptionOrNull()?.asApiException()

                _state.update { current ->
                    current.copy(
                        overview = onChainResult?.getOrNull()?.first ?: current.overview,
                        transactions = onChainResult?.getOrNull()?.second ?: current.transactions,
                        lightningLocalMsat = balanceResult?.getOrNull()?.offchain?.local
                            ?: current.lightningLocalMsat,
                        lightningOffline = nodeError != null,
                        loading = false,
                        refreshing = false,
                        error = onChainError,
                    )
                }
            }

            // Kept fresh once it has been looked at, so pull-to-refresh on the
            // Lightning tab does what it says.
            if (_state.value.lightningHistoryLoaded || _state.value.tab == TAB_LIGHTNING) {
                loadLightningHistory()
            }
        }
    }

    private fun loadLightningHistory() {
        val storeId = graph.session.activeStore.value?.id ?: return
        val cryptoCode = _state.value.lightningCryptoCode ?: return
        viewModelScope.launch {
            _state.update { it.copy(lightningHistoryLoading = true) }
            val api = runCatching { graph.session.requireApi() }.getOrElse {
                _state.update { it.copy(lightningHistoryLoading = false) }
                return@launch
            }
            val scope = LightningScope.Store(storeId)
            val (invoices, payments) = coroutineScope {
                val a = async { runCatching { api.lightningInvoices(scope = scope, cryptoCode = cryptoCode) } }
                val b = async { runCatching { api.lightningPayments(scope = scope, cryptoCode = cryptoCode) } }
                a.await() to b.await()
            }
            _state.update { current ->
                current.copy(
                    lightningInvoices = invoices.getOrNull()
                        ?.sortedByDescending { it.paidAt ?: it.expiresAt }
                        ?.take(RECENT_LIGHTNING_LIMIT)
                        ?: current.lightningInvoices,
                    lightningPayments = payments.getOrNull()
                        ?.sortedByDescending { it.createdAt ?: 0L }
                        ?.take(RECENT_LIGHTNING_LIMIT)
                        ?: current.lightningPayments,
                    lightningHistoryLoaded = true,
                    lightningHistoryLoading = false,
                )
            }
        }
    }
}

@Composable
fun WalletScreen(
    onReceive: (String) -> Unit,
    /** Payment method, Lightning crypto code, and which rail to open on. */
    onSend: (String?, String?, Boolean) -> Unit,
    onOpenTransaction: (String, String) -> Unit,
    onOpenUtxos: (String) -> Unit,
    onOpenLightning: (String) -> Unit,
    onLightningReceive: (String) -> Unit,
) {
    val viewModel = appViewModel { WalletViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit = LocalSettings.current.bitcoinUnit
    val selected = state.selected
    val tab = state.effectiveTab

    // Hoisted above the list, so `substringBefore` does not re-run for every
    // row on every recomposition.
    val txCryptoCode = remember(selected) { cryptoCodeOf(selected.orEmpty()) }

    AppScreen(
        title = "Wallet",
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        actions = {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(
                onClick = { menuOpen = true },
                enabled = selected != null || state.hasLightning,
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (selected != null) {
                    DropdownMenuItem(
                        text = { Text("Coins") },
                        onClick = {
                            menuOpen = false
                            onOpenUtxos(selected)
                        },
                    )
                }
                state.lightningCryptoCode?.let { code ->
                    DropdownMenuItem(
                        text = { Text("Lightning node") },
                        onClick = {
                            menuOpen = false
                            onOpenLightning(code)
                        },
                    )
                }
            }
        },
    ) { padding ->
        val phase = when {
            // The method list is still on its way in; an empty state here would
            // accuse a perfectly good store of having no wallet.
            state.loading || (!state.methodsKnown && state.error == null) -> WalletPhase.Loading
            state.error != null && state.overview == null && !state.hasLightning -> WalletPhase.Error
            !state.hasOnChain && !state.hasLightning -> WalletPhase.Empty
            else -> WalletPhase.Content
        }

        // The error screen outlives the error itself. Retry clears `state.error`
        // on the frame the swap out begins, and the outgoing branch stays
        // composed until it has finished leaving — long enough to blank its own
        // wording first if it read the live value.
        val lastError = remember { mutableStateOf<ApiException?>(null) }
        if (state.error != null) lastError.value = state.error

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "wallet") { shown ->
            when (shown) {
                WalletPhase.Loading -> SkeletonList()

                WalletPhase.Error -> lastError.value?.let { failure ->
                    ErrorState(error = failure, onRetry = viewModel::refresh)
                }

                WalletPhase.Empty -> EmptyState(
                    title = "No wallet yet",
                    description = "This store has neither an on-chain wallet nor a Lightning node. " +
                        "Add a derivation scheme or a node in the store's payment settings first.",
                    icon = Icons.Rounded.AccountBalanceWallet,
                )

                WalletPhase.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {

                    item {
                        ErrorBanner(
                            state.error,
                            onDismiss = viewModel::dismissError,
                            onRetry = viewModel::refresh,
                        )
                    }

                    item {
                        BalanceCard(
                            state = state,
                            cryptoCode = txCryptoCode,
                            unit = unit,
                        )
                    }

                    if (state.paymentMethodIds.size > 1) {
                        item {
                            PaymentMethodRow(
                                ids = state.paymentMethodIds,
                                selected = selected,
                                onSelect = viewModel::select,
                            )
                        }
                    }

                    if (state.hasLightning && state.hasOnChain) {
                        item {
                            PrimaryTabRow(selectedTabIndex = tab) {
                                Tab(
                                    selected = tab == TAB_ON_CHAIN,
                                    onClick = { viewModel.selectTab(TAB_ON_CHAIN) },
                                    text = { Text("On-chain") },
                                )
                                Tab(
                                    selected = tab == TAB_LIGHTNING,
                                    onClick = { viewModel.selectTab(TAB_LIGHTNING) },
                                    text = { Text("Lightning") },
                                )
                            }
                        }
                    }

                    // The actions sit under the tab row on purpose: Receive acts
                    // on the rail the tab selected, and putting it above the row
                    // would leave it meaning whichever of the two the reader
                    // assumed.
                    //
                    // Send reads the tab as a preference rather than as the
                    // answer. One screen takes both rails and decides from what
                    // is pasted into it, so the tab only says which half it
                    // opens on — and being on the wrong one costs a tap rather
                    // than a trip back to here.
                    item {
                        ActionRow(
                            canSend = (state.hasOnChain && state.canSpendOnChain) ||
                                (state.hasLightning && state.canSpendLightning),
                            onReceive = {
                                if (tab == TAB_LIGHTNING) {
                                    state.lightningCryptoCode?.let(onLightningReceive)
                                } else {
                                    selected?.let(onReceive)
                                }
                            },
                            onSend = {
                                onSend(selected, state.lightningCryptoCode, tab == TAB_LIGHTNING)
                            },
                        )
                    }

                    // Composed whether or not they are shown. A send the server
                    // refuses flips these while the screen is open, and a note
                    // conjured into the list by an `if` cannot arrive by pushing
                    // the rows below it down — it can only replace one of them.
                    item {
                        CapabilityNote(
                            visible = tab == TAB_ON_CHAIN && !state.canSpendOnChain,
                            title = "Watch only",
                            detail = "the server will not sign for this wallet",
                        )
                    }
                    item {
                        CapabilityNote(
                            visible = tab == TAB_LIGHTNING && !state.canSpendLightning,
                            title = "Read only",
                            detail = "this app\u2019s key cannot use the node",
                        )
                    }

                    if (tab == TAB_LIGHTNING) {
                        lightningActivity(state, unit)
                        // The node's own screens — channels, peers, addresses —
                        // are an operator's tools rather than a merchant's, so
                        // they sit at the end of the history instead of above
                        // it. The overflow menu reaches them without scrolling.
                        state.lightningCryptoCode?.let { code ->
                            item { ManageNodeRow(onClick = { onOpenLightning(code) }) }
                        }
                    } else {
                        onChainActivity(state, txCryptoCode, selected, onOpenTransaction)
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * One balance, then where it actually sits.
 *
 * A merchant taking payments over both rails has one number in mind — what the
 * store holds — and finding it by adding two cards together is work the app
 * should be doing. The split line underneath keeps the detail without a second
 * card: which rail holds what still matters the moment a channel needs
 * rebalancing or a payout is due.
 */
@Composable
private fun BalanceCard(state: WalletState, cryptoCode: String, unit: BitcoinUnit) {
    val bitcoin = cryptoCode.equals("BTC", ignoreCase = true) || !state.hasOnChain
    val onChainBtc = state.overview?.balance
    val lightningBtc = state.lightningLocalMsat?.let(Amounts::msatToBtc)

    AppCard {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "Total balance",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            // Only Bitcoin can be totalled across the two rails. An altcoin
            // on-chain wallet and a Lightning node are different assets, and
            // adding them would be a made-up number.
            //
            // The one figure in the app that changes while somebody is looking
            // at it, so it is also the one that rolls rather than redraws: a
            // payment landing at the counter should be visible as the number
            // moving, and the direction it moves in is the answer to the only
            // question being asked.
            if (bitcoin) {
                AmountText(
                    amount = (onChainBtc ?: BigDecimal.ZERO) + (lightningBtc ?: BigDecimal.ZERO),
                    currency = "BTC",
                    style = MaterialTheme.typography.headlineMedium,
                    animated = true,
                )
            } else {
                AmountText(
                    amount = onChainBtc ?: BigDecimal.ZERO,
                    currency = cryptoCode,
                    style = MaterialTheme.typography.headlineMedium,
                    animated = true,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (state.hasOnChain) {
                    RailFigure(
                        label = "On-chain",
                        value = formatOnChain(onChainBtc ?: BigDecimal.ZERO, cryptoCode, unit),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.hasLightning) {
                    RailFigure(
                        label = "Lightning",
                        value = when {
                            state.lightningOffline -> "Node offline"
                            lightningBtc != null -> Amounts.formatBitcoin(lightningBtc, unit)
                            else -> "—"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Unconfirmed is the one on-chain figure that changes a decision:
            // it is money that cannot be spent yet. Zero means nothing to say.
            val unconfirmed = state.overview?.unconfirmedBalance
                ?.takeIf { it.signum() != 0 && state.hasOnChain }

            // The wording is held across the exit. The figure is already gone
            // on the frame the last confirmation lands, and a line that empties
            // itself and only then collapses reads as a fault rather than as
            // the good news it is.
            val note = remember { mutableStateOf("") }
            if (unconfirmed != null) {
                note.value = "${formatOnChain(unconfirmed, cryptoCode, unit)} still unconfirmed"
            }

            AnimatedVisibility(
                visible = unconfirmed != null,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = note.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * No icon. A chain link beside the word "On-chain" and a bolt beside the word
 * "Lightning" repeat what the label already says, and two of them in a row
 * turn a balance into a badge collection.
 */
@Composable
private fun RailFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionRow(
    canSend: Boolean,
    onReceive: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onReceive, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Receive")
        }
        OutlinedButton(onClick = onSend, modifier = Modifier.weight(1f), enabled = canSend) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Send")
        }
    }
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

private fun LazyListScope.onChainActivity(
    state: WalletState,
    cryptoCode: String,
    selected: String?,
    onOpenTransaction: (String, String) -> Unit,
) {
    if (state.transactions.isEmpty()) {
        item { ActivityEmpty("Nothing has moved through this wallet yet.") }
        return
    }
    // Keyed on the transaction hash so a refresh moves rows rather than
    // discarding and re-measuring every visible one, which would make the
    // balance list jump and lose scroll position on every pull-to-refresh. The
    // index fallback covers the rare hash-less row without risking a duplicate
    // key, which would be a crash.
    items(
        count = state.transactions.size,
        key = { index -> state.transactions[index].transactionHash ?: "row-$index" },
    ) { index ->
        val transaction = state.transactions[index]
        // The row and its divider are one moving object. Animating the row
        // alone would leave the line it sits on behind, which reads as the
        // list tearing rather than reordering.
        Column(
            Modifier.animateItem(
                fadeInSpec = Motion.effects,
                placementSpec = Motion.spatialOffset,
                fadeOutSpec = Motion.effectsFast,
            ),
        ) {
            TransactionRow(
                transaction = transaction,
                cryptoCode = cryptoCode,
                onClick = {
                    val hash = transaction.transactionHash
                    if (selected != null && hash != null) onOpenTransaction(selected, hash)
                },
            )
            ThinDivider()
        }
    }
}

/**
 * Invoices and payments in one list, newest first.
 *
 * The node reports them as two collections because it settles them differently;
 * to the person reading the screen they are simply money in and money out, and
 * interleaving them by time is the only way "what happened this afternoon"
 * reads as one answer.
 */
private fun LazyListScope.lightningActivity(state: WalletState, unit: BitcoinUnit) {
    if (state.lightningOffline) {
        item { ActivityEmpty("The node is not answering. Its balance and history will appear once it is up.") }
        return
    }

    val rows = lightningRows(state)
    if (rows.isEmpty()) {
        // The node's history is two more round trips, often over Tor, so the
        // wait is long enough to be worth describing. Rows in outline say what
        // is coming and hold the space it will take; the word "Loading" would
        // say only that something was happening somewhere.
        if (state.lightningHistoryLoading || !state.lightningHistoryLoaded) {
            items(count = 3, key = { "ln-skeleton-$it" }) {
                SkeletonRow(Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            }
        } else {
            item { ActivityEmpty("Nothing has moved over Lightning yet.") }
        }
        return
    }
    items(count = rows.size, key = { rows[it].key }) { index ->
        Column(
            Modifier.animateItem(
                fadeInSpec = Motion.effects,
                placementSpec = Motion.spatialOffset,
                fadeOutSpec = Motion.effectsFast,
            ),
        ) {
            LightningRow(row = rows[index], unit = unit)
            ThinDivider()
        }
    }
}

/** One row of Lightning history, from either collection. */
private data class LightningActivity(
    val key: String,
    val incoming: Boolean,
    val title: String,
    val status: String,
    val settled: Boolean,
    val failed: Boolean,
    val timestamp: Long,
    val amountMsat: String?,
)

private fun lightningRows(state: WalletState): List<LightningActivity> {
    val invoices = state.lightningInvoices
        // An unpaid invoice is a request, not something that happened. Showing
        // every expired one would bury the payments that did land.
        .filter { it.status == LightningInvoiceStatus.Paid }
        .map { invoice ->
            LightningActivity(
                key = "in:${invoice.paymentHash.ifBlank { invoice.BOLT11 }}",
                incoming = true,
                title = Bolt11.decode(invoice.BOLT11)?.description?.takeIf { it.isNotBlank() }
                    ?: TextUtil.middleEllipsis(invoice.paymentHash, 10, 8),
                status = "Received",
                settled = true,
                failed = false,
                timestamp = invoice.paidAt ?: invoice.expiresAt,
                amountMsat = invoice.amountReceived ?: invoice.amount,
            )
        }

    val payments = state.lightningPayments.map { payment ->
        LightningActivity(
            key = "out:${payment.paymentHash.ifBlank { payment.BOLT11 }}",
            incoming = false,
            title = Bolt11.decode(payment.BOLT11)?.description?.takeIf { it.isNotBlank() }
                ?: TextUtil.middleEllipsis(payment.paymentHash, 10, 8),
            status = when (payment.status) {
                LightningPaymentStatus.Complete -> "Sent"
                LightningPaymentStatus.Pending -> "In flight"
                LightningPaymentStatus.Failed -> "Failed"
                LightningPaymentStatus.Unknown -> "Unknown"
            },
            settled = payment.status == LightningPaymentStatus.Complete,
            failed = payment.status == LightningPaymentStatus.Failed,
            timestamp = payment.createdAt ?: 0L,
            amountMsat = payment.totalAmount,
        )
    }

    // `distinctBy` because some node backends repeat a row inside one page, and
    // a duplicate key in a LazyColumn is a crash rather than a cosmetic fault.
    // The `in:`/`out:` prefixes keep both legs of a self-payment, which is what
    // actually happened and should be visible.
    return (invoices + payments)
        .distinctBy { it.key }
        .sortedByDescending { it.timestamp }
        .take(RECENT_LIGHTNING_LIMIT)
}

@Composable
private fun LightningRow(row: LightningActivity, unit: BitcoinUnit) {
    val colors = AppTheme.statusColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectionIcon(incoming = row.incoming)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = row.status,
                    container = when {
                        row.failed -> colors.invalid
                        row.settled -> colors.settled
                        else -> colors.pending
                    },
                    content = when {
                        row.failed -> colors.onInvalid
                        row.settled -> colors.onSettled
                        else -> colors.onPending
                    },
                )
                if (row.timestamp > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Dates.relative(row.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (row.amountMsat.isNullOrBlank()) "—" else Amounts.formatMsat(row.amountMsat, unit),
            style = MaterialTheme.typography.titleMedium,
            color = if (row.incoming) colors.incoming else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * One line, not a paragraph.
 *
 * The full explanation belongs on the Send screen, where someone is actually
 * trying to spend and needs to know why they cannot. Here it only has to say
 * that the button above is off on purpose.
 */
@Composable
private fun CapabilityNote(visible: Boolean, title: String, detail: String) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Visibility,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$title · $detail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManageNodeRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Channels, node info and addresses",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityEmpty(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PaymentMethodRow(ids: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ids) { id ->
            FilterChip(
                selected = id == selected,
                onClick = { onSelect(id) },
                label = { Text(cryptoCodeOf(id)) },
            )
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: WalletTransactionData,
    cryptoCode: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.statusColors
    val confirmed = transaction.status == WalletTransactionStatus.Confirmed

    // Only a row that can be opened has anything to flow into. A hash-less row
    // is not clickable, and letting it claim a continuity key would mean every
    // such row claiming the same one — two elements under one key is a crash,
    // not a missed animation.
    val hash = transaction.transactionHash

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = transaction.transactionHash != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectionIcon(incoming = transaction.isIncoming)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = transaction.comment.takeIf { it.isNotBlank() }
                    ?: transaction.labels.firstOrNull()?.text?.takeIf { it.isNotBlank() }
                    ?: transaction.transactionHash?.let { TextUtil.middleEllipsis(it, 10, 8) }
                    ?: "Unknown transaction",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = if (confirmed) "${transaction.confirmations} conf" else "Unconfirmed",
                    container = if (confirmed) colors.settled else colors.pending,
                    content = if (confirmed) colors.onSettled else colors.onPending,
                    modifier = if (hash != null) Modifier.continuity("tx-status-$hash") else Modifier,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Dates.relative(transaction.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        AmountText(
            amount = transaction.amount,
            currency = cryptoCode,
            modifier = if (hash != null) Modifier.continuity("tx-amount-$hash") else Modifier,
            style = MaterialTheme.typography.titleMedium,
            color = if (transaction.isIncoming) colors.incoming else MaterialTheme.colorScheme.onSurface,
        )
    }
}
