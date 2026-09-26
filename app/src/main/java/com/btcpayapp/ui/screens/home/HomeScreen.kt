package com.btcpayapp.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.btcpayapp.ui.theme.Motion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.R
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.LightningBalanceData
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.dto.WalletOverviewData
import com.btcpayapp.data.api.endpoints.LightningScope
import com.btcpayapp.data.api.endpoints.invoices
import com.btcpayapp.data.api.endpoints.lightningBalance
import com.btcpayapp.data.api.endpoints.notifications
import com.btcpayapp.data.api.endpoints.walletOverview
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AnimatedValue
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.StatusChip
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.pressScale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

data class HomeState(
    val recent: List<InvoiceData> = emptyList(),
    val settledToday: BigDecimal = BigDecimal.ZERO,
    val settledTodayCount: Int = 0,
    val todayTotals: Map<String, Pair<BigDecimal, Int>> = emptyMap(),
    val onChain: Map<String, WalletOverviewData> = emptyMap(),
    /** Keyed by payment method id, e.g. `BTC-LN`. Absent when the node did not answer. */
    val lightning: Map<String, LightningBalanceData> = emptyMap(),
    val unseenNotifications: Int = 0,
    val refreshing: Boolean = false,
    val loading: Boolean = true,
    val error: ApiException? = null,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    val store = graph.session.activeStore
    val stores = graph.session.stores
    val account = graph.session.activeAccount

    /**
     * Surfaced on the empty state. Without it a rejected key, an unreachable
     * host or an expired certificate all render as "no store yet", which is the
     * single most confusing thing this screen could do.
     */
    val sessionError = graph.session.lastError

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(graph.session.activeStore, graph.session.paymentMethodsLoaded) { store, loaded ->
                store?.id to loaded
            }
                .distinctUntilChanged()
                .collectLatest { (storeId, _) ->
                    loadJob?.cancel()
                    _state.value = HomeState()
                    if (storeId != null) load(refreshing = false)
                }
        }
    }

    /**
     * Refreshes the session as well as this screen. The store list lives in
     * [com.btcpayapp.data.session.SessionManager], so a screen-only refresh
     * would leave a session that failed at startup stuck forever.
     */
    fun refresh() {
        viewModelScope.launch {
            graph.session.refresh()
            graph.session.activeStore.value?.id?.let { graph.session.refreshPaymentMethods(it) }
            load(refreshing = true)
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun selectStore(storeId: String) {
        viewModelScope.launch { graph.session.selectStore(storeId) }
    }

    private fun load(refreshing: Boolean) {
        val storeId = graph.session.activeStore.value?.id ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = refreshing, loading = !refreshing && it.recent.isEmpty()) }

            val api = runCatching { graph.session.requireApi() }.getOrNull()
            if (api == null) {
                _state.update { it.copy(loading = false, refreshing = false) }
                return@launch
            }

            val startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toEpochSecond()

            // Independent calls, so they run together. A failure in one does not
            // blank the rest of the dashboard.
            val recent = async { runCatching { api.invoices(storeId, take = 8) } }
            val today = async {
                runCatching {
                    api.invoices(
                        storeId = storeId,
                        statuses = listOf(InvoiceStatus.Settled),
                        startDate = startOfDay,
                        take = 100,
                    )
                }
            }
            val unseen = async { runCatching { api.notifications(seen = false, take = 20) } }
            val wallets = graph.session.enabledPaymentMethodIds
                .filter { it.endsWith("-CHAIN", ignoreCase = true) }
                .map { id -> id to async { runCatching { api.walletOverview(storeId, id) } } }

            // `-LNURL` rides on the same node as `-LN`, so it would only double
            // up the same balance. A node that is enabled but unreachable fails
            // here and is simply left out, exactly as an on-chain failure is.
            val nodes = graph.session.enabledPaymentMethodIds
                .filter { it.endsWith("-LN", ignoreCase = true) }
                .map { id ->
                    id to async {
                        runCatching {
                            api.lightningBalance(
                                LightningScope.Store(storeId),
                                id.substringBefore('-'),
                            )
                        }
                    }
                }

            awaitAll(recent, today, unseen)

            val todayInvoices = today.await().getOrNull().orEmpty()
            val balances = wallets.mapNotNull { (id, deferred) ->
                deferred.await().getOrNull()?.let { id to it }
            }.toMap()
            val nodeBalances = nodes.mapNotNull { (id, deferred) ->
                deferred.await().getOrNull()?.let { id to it }
            }.toMap()

            _state.update { current ->
                current.copy(
                    recent = recent.await().getOrElse { current.recent },
                    settledToday = todayInvoices.fold(BigDecimal.ZERO) { sum, invoice -> sum + invoice.paidAmount },
                    settledTodayCount = todayInvoices.size,
                    todayTotals = todayInvoices.groupBy { it.currency }.mapValues { (_, invoices) ->
                        invoices.fold(BigDecimal.ZERO) { sum, invoice -> sum + invoice.paidAmount } to invoices.size
                    },
                    onChain = balances,
                    lightning = nodeBalances,
                    unseenNotifications = unseen.await().getOrNull()?.size ?: current.unseenNotifications,
                    loading = false,
                    refreshing = false,
                    error = recent.await().exceptionOrNull() as? ApiException,
                )
            }
        }
    }
}

/**
 * What the screen is showing, as three flat values rather than as the store and
 * the error themselves.
 *
 * The distinction matters: a refresh hands back a new but equal [StoreData]
 * every time, and a swap keyed on that object would replay the whole dashboard
 * entrance on every pull-to-refresh.
 */
private enum class HomePhase { Failed, Empty, Dashboard }

@Composable
fun HomeScreen(
    onOpenInvoice: (String) -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenLightning: (cryptoCode: String) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenStores: () -> Unit,
    onCreateInvoice: () -> Unit,
    onScan: () -> Unit,
) {
    val viewModel = appViewModel { HomeViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val settings = LocalSettings.current

    var storePickerOpen by remember { mutableStateOf(false) }
    var invoicesOpen by rememberSaveable { mutableStateOf(false) }

    AppScreen(
        title = store?.name ?: stringResource(R.string.app_name),
        subtitle = account?.host,
        large = true,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        floatingActionButton = {
            // No Terminal or Scan "quick actions" here: Terminal is a tab in
            // the bar at the bottom of this very screen and Scan is an icon at
            // the top of it. Repeating both would buy nothing and cost a
            // section header and a row of chrome. Creating an invoice is the
            // one action with no other home.
            if (store != null) {
                ExtendedFloatingActionButton(
                    onClick = onCreateInvoice,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Invoice") },
                )
            }
        },
        actions = {
            IconButton(onClick = onScan) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan")
            }
            BadgedBox(
                badge = {
                    if (state.unseenNotifications > 0) {
                        Badge { Text(state.unseenNotifications.coerceAtMost(99).toString()) }
                    }
                },
            ) {
                IconButton(onClick = onOpenNotifications) {
                    Icon(Icons.Rounded.Notifications, contentDescription = "Notifications")
                }
            }
            IconButton(onClick = onOpenAccounts) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Servers")
            }
        },
    ) { padding ->
        // The session clears its last error on the very update that delivers
        // the store, so a failure card reading it live would empty itself
        // halfway through being carried off screen by the swap below. Written
        // during composition, which is safe because the value is only read back
        // on the branch that did not write it.
        val lastFailure = remember { mutableStateOf<ApiException?>(null) }
        if (sessionError != null) lastFailure.value = sessionError

        val phase = when {
            store != null -> HomePhase.Dashboard
            sessionError != null -> HomePhase.Failed
            else -> HomePhase.Empty
        }

        AnimatedSwap(
            targetState = phase,
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "home",
        ) { current ->
            when (current) {
                HomePhase.Failed -> lastFailure.value?.let { failure ->
                    ErrorState(
                        error = failure,
                        onRetry = viewModel::refresh,
                        onFix = onOpenAccounts,
                        fixLabel = "Check the connection",
                    )
                }

                HomePhase.Empty -> EmptyState(
                    title = "No store yet",
                    description = "This account has no store, or the key cannot see one. " +
                        "Create a store on the server, then pull to refresh.",
                    icon = Icons.Rounded.Storefront,
                    actionLabel = "Retry",
                    onAction = viewModel::refresh,
                )

                HomePhase.Dashboard -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(
                        error = state.error,
                        onDismiss = viewModel::dismissError,
                        onRetry = viewModel::refresh,
                    )

                    if (stores.size > 1) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .arrive(0)
                                .clickable { storePickerOpen = !storePickerOpen }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (storePickerOpen) "Choose a store" else "Switch store",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        AnimatedVisibility(
                            visible = storePickerOpen,
                            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                        ) {
                            Column {
                                stores.forEach { candidate ->
                                    StoreRow(
                                        store = candidate,
                                        selected = candidate.id == store?.id,
                                        onClick = {
                                            viewModel.selectStore(candidate.id)
                                            storePickerOpen = false
                                        },
                                    )
                                }
                                ThinDivider()
                            }
                        }
                    }

                    val totals = state.todayTotals.ifEmpty { mapOf((store?.defaultCurrency ?: "USD") to (BigDecimal.ZERO to 0)) }
                    totals.forEach { (currency, summary) ->
                        // Keyed on the currency, because these cards remember
                        // the previous amount to decide which way the figure
                        // rolls. `todayTotals` is grouped over a newest-first
                        // list, so its order changes when a different currency
                        // settles last — and without a key the second card's
                        // slot would be handed to a different currency while
                        // still holding the first one's history, rolling one
                        // store's takings into another's.
                        key(currency) {
                            TodayCard(
                                amount = summary.first,
                                currency = currency,
                                count = summary.second,
                                masked = settings.privacyMode,
                                limited = state.settledTodayCount >= 100,
                            )
                        }
                    }

                    if (state.onChain.isNotEmpty() || state.lightning.isNotEmpty()) {
                        // The balances land a moment after the rest of the
                        // screen, so the whole group arrives as one block. A
                        // `Column` purely to carry that entrance — it changes
                        // nothing about the layout.
                        Column(Modifier.arrive(2)) {
                            // No "Wallet" link in the header: every row below already
                            // opens it, and Wallet is a tab in the bar at the bottom of
                            // this screen.
                            SectionHeader("Balances")
                            state.onChain.forEach { (methodId, overview) ->
                                BalanceRow(
                                    // "BTC on-chain" says Bitcoin twice to anyone who has
                                    // only ever seen Bitcoin. The code earns its place on a
                                    // store that really does run two chains, and nowhere else.
                                    label = chainSubtitle(methodId.substringBefore('-'), prefix = "On-chain")
                                        ?: "On-chain",
                                    overview = overview,
                                    onClick = onOpenWallet,
                                )
                            }
                            state.lightning.forEach { (methodId, balance) ->
                                val cryptoCode = methodId.substringBefore('-')
                                LightningBalanceRow(
                                    label = chainSubtitle(cryptoCode, prefix = "Lightning") ?: "Lightning",
                                    balance = balance,
                                    onClick = { onOpenLightning(cryptoCode) },
                                )
                            }
                        }
                    }

                    // Collapsed by default, but as a section header like every other
                    // block on this screen — not as a settings-style row with a chevron
                    // and a summary line. The shared `ExpandableSection` is right on a
                    // form and wrong here: it puts a tall bodyLarge row with a caption
                    // directly under "Balances", so two adjacent groups on one screen
                    // would announce themselves in two completely different voices.
                    SectionHeader(
                        title = "Recent invoices",
                        modifier = Modifier.arrive(3),
                        action = {
                            TextButton(onClick = { invoicesOpen = !invoicesOpen }) {
                                Text(if (invoicesOpen) "Hide" else "Show")
                            }
                        },
                    )

                    AnimatedVisibility(
                        visible = invoicesOpen,
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        Column {
                            if (state.recent.isEmpty() && !state.loading) {
                                Text(
                                    text = "Nothing yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            state.recent.forEach { invoice ->
                                RecentInvoiceRow(invoice = invoice, onClick = { onOpenInvoice(invoice.id) })
                                ThinDivider()
                            }
                            TextButton(
                                onClick = onOpenInvoices,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text("See all")
                            }
                        }
                    }

                    // Clears the FAB, which would otherwise sit on the last row.
                    Spacer(Modifier.height(88.dp))
                }
            }
        }
    }
}

@Composable
private fun TodayCard(amount: BigDecimal, currency: String, count: Int, masked: Boolean, limited: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp).arrive(1),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(if (limited) "Today · latest 100 settled invoices" else "Settled today", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val formatted = Amounts.format(amount, currency)

            // Formatted here rather than handed to `AmountText`, because this
            // figure is denominated in the *invoice's* currency: a BTC-priced
            // store would otherwise have its takings restated in the reader's
            // chosen bitcoin unit, which is right for a wallet balance and
            // wrong for a day's till.
            //
            // Compared as numbers, not as strings: "9.99" to "10.00" is a rise
            // and sorting those two strings says the opposite.
            var previous by remember { mutableStateOf(amount) }
            val rising = amount >= previous
            SideEffect { previous = amount }

            AnimatedValue(
                value = if (masked) Amounts.masked(formatted) else formatted,
                upward = rising,
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (count == 1) "1 invoice" else "$count invoices",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BalanceRow(label: String, overview: WalletOverviewData, onClick: () -> Unit) {
    val settings = LocalSettings.current
    val interactions = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (overview.unconfirmedBalance.signum() != 0) {
                Text(
                    text = "${Amounts.formatBitcoin(overview.unconfirmedBalance, settings.bitcoinUnit)} unconfirmed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Animated because this is one of the few figures on the screen that
        // moves while it is being looked at: a payment confirming during a
        // refresh should visibly change the balance, not silently replace it.
        AmountText(
            amount = overview.balance,
            currency = "BTC",
            style = MaterialTheme.typography.titleMedium,
            animated = true,
        )
    }
}

/**
 * Spendable Lightning balance, which is `offchain.local` — not capacity.
 *
 * Remote balance is what the channel partner holds, i.e. what can still be
 * *received*; showing the two added together would overstate what the merchant
 * can actually pay out, so only the local side is the headline figure and the
 * receivable side is a subtitle.
 */
@Composable
private fun LightningBalanceRow(label: String, balance: LightningBalanceData, onClick: () -> Unit) {
    val settings = LocalSettings.current
    val offchain = balance.offchain
    val interactions = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            val receivable = Amounts.msatToSats(offchain?.remote)
            if (receivable.signum() != 0) {
                Text(
                    text = "${Amounts.formatMsat(offchain?.remote, settings.bitcoinUnit)} receivable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Spendable balance moves with every Lightning payment taken, so it
        // rolls to its new value for the same reason the on-chain row does.
        AmountText(
            amount = Amounts.msatToBtc(offchain?.local),
            currency = "BTC",
            style = MaterialTheme.typography.titleMedium,
            animated = true,
        )
    }
}

@Composable
private fun StoreRow(store: StoreData, selected: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .padding(end = 0.dp),
        ) {
            if (selected) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(store.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = store.defaultCurrency,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentInvoiceRow(invoice: InvoiceData, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Deliberately *not* a `continuity` element, unlike the identical
            // amount on the invoice list.
            //
            // Both screens show the same invoices, and tapping "See all" puts
            // both on screen at once for the length of the push — so the two
            // rows for one invoice would claim the same shared key
            // simultaneously. Compose 1.12 has no guard against that and no
            // documented behaviour for it, and the failure would land on one
            // of the most-travelled paths in the app. The list keeps the
            // transition; this row takes the ordinary push.
            Text(
                text = Amounts.format(invoice.amount, invoice.currency),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Dates.relative(invoice.createdTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(status = invoice.status)
    }
}
