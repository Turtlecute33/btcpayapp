package com.btcpayapp.ui.screens.lightning

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import com.btcpayapp.data.api.dto.LightningBalanceData
import com.btcpayapp.data.api.dto.LightningNodeInfo
import com.btcpayapp.data.api.endpoints.LightningScope
import com.btcpayapp.data.api.endpoints.lightningBalance
import com.btcpayapp.data.api.endpoints.lightningNodeInfo
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.data.session.SessionManager
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AnimatedValue
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FigureText
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.components.NoStoreSelectedState
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared across the Lightning screens
// ---------------------------------------------------------------------------

internal const val LN_PLACEHOLDER = "—"

/**
 * The scope every Lightning call needs, fixed once it is known.
 *
 * The server's own node needs no store. Any other node is the node of the
 * store the screen opened in, or after a cold start of the first store to
 * load, because the back stack comes back before the store list (see
 * [StoreBinding]). It never follows a later switch. The shell drops every
 * store screen when the active store changes, and a screen that followed one
 * could act on store B from a form filled in for store A: a channel opened,
 * or an invoice created, on the wrong node.
 *
 * `SessionManager.requireStoreId()` throws, and a freshly paired account can
 * have no store yet, so the absence of a store is modelled as a state instead
 * of an exception.
 */
internal class LightningBinding(session: SessionManager, private val serverNode: Boolean) {
    private val store = StoreBinding(session)

    /** Null while no store is known. */
    val scope: LightningScope?
        get() = if (serverNode) LightningScope.Server else store.id?.let { LightningScope.Store(it) }

    /** Whose node this is, for a confirmation that moves funds: the store's name, or the server's. */
    val owner: String get() = if (serverNode) "the server node" else store.name

    /** The store's name for a title bar; null for the server node, or while no store is known. */
    val storeName: String? get() = if (serverNode) null else store.store?.name?.takeIf { it.isNotBlank() }

    /**
     * On a cold start, runs [block], the screen's load, once the first store
     * has loaded. Does nothing when the scope is already known.
     */
    fun retryWhenKnown(coroutines: CoroutineScope, block: () -> Unit) {
        if (!serverNode) store.retryWhenKnown(coroutines, block)
    }
}


/**
 * BTCPay answers 503 when the configured Lightning node cannot be reached — it
 * is offline, still starting, or the connection string is wrong. On a
 * self-hosted instance that is an ordinary operational state rather than a
 * fault, so it is worded for the person running the node instead of being shown
 * as a generic server error.
 */
internal fun ApiException.isNodeUnreachable(): Boolean =
    (this as? ApiException.Server)?.status == 503

/**
 * A msat string from the server, formatted, or [LN_PLACEHOLDER] when it is
 * missing or not a number this app can work with. Read through
 * [Amounts.serverDecimal], so "1e2147483647" from a broken node draws a dash
 * instead of throwing in composition.
 */
internal fun msatLabel(msat: String?, unit: BitcoinUnit): String =
    if (Amounts.serverDecimal(msat) == null) LN_PLACEHOLDER else Amounts.formatMsat(msat, unit)

/** As [msatLabel], for the sat strings of the on-chain balance and address limits. */
internal fun satsLabel(sats: String?, unit: BitcoinUnit): String {
    val value = Amounts.serverDecimal(sats) ?: return LN_PLACEHOLDER
    return Amounts.formatBitcoin(Amounts.satsToBtc(value), unit)
}

@Composable
internal fun NodeUnreachableState(onRetry: () -> Unit) {
    EmptyState(
        title = "The node is not answering",
        description = "The server reports it as offline or still starting.",
        icon = Icons.Rounded.CloudOff,
        actionLabel = "Try again",
        onAction = onRetry,
    )
}

// ---------------------------------------------------------------------------
// Node overview
// ---------------------------------------------------------------------------

data class LightningState(
    val info: LightningNodeInfo? = null,
    val balance: LightningBalanceData? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val nodeOffline: Boolean = false,
    val noStore: Boolean = false,
    val error: ApiException? = null,
)

class LightningViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    private val node = LightningBinding(graph.session, serverNode)

    private val _state = MutableStateFlow(LightningState(loading = true))
    val state = _state.asStateFlow()

    init {
        load()
        node.retryWhenKnown(viewModelScope) { load() }
    }

    fun refresh() = load(refreshing = true)

    /**
     * Reloads quietly when the screen comes back, so the balance is the one
     * after the payment just sent or the channel just opened. Skipped while a
     * load runs, which also covers the first resume, straight after [init].
     */
    fun onResume() {
        val current = _state.value
        if (current.loading || current.refreshing) return
        load()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun load(refreshing: Boolean = false) {
        val scope = node.scope
        if (scope == null) {
            _state.update { it.copy(loading = false, refreshing = false, noStore = true) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refreshing && it.info == null,
                    refreshing = refreshing,
                    noStore = false,
                    error = null,
                )
            }

            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update {
                    it.copy(loading = false, refreshing = false, error = failure.asApiException())
                }
                return@launch
            }

            // Independent calls, and the balance endpoint is missing on some
            // node implementations — a failure there must not blank the info.
            val (infoResult, balanceResult) = coroutineScope {
                val info = async { runCatching { api.lightningNodeInfo(scope, cryptoCode) } }
                val balance = async { runCatching { api.lightningBalance(scope, cryptoCode) } }
                info.await() to balance.await()
            }

            val infoError = infoResult.exceptionOrNull()?.asApiException()
            _state.update { current ->
                current.copy(
                    info = infoResult.getOrNull() ?: current.info,
                    balance = balanceResult.getOrNull() ?: current.balance,
                    loading = false,
                    refreshing = false,
                    nodeOffline = infoError?.isNodeUnreachable() == true,
                    error = infoError?.takeIf { !it.isNodeUnreachable() },
                )
            }
        }
    }
}

/**
 * What the body of the node screen is showing.
 *
 * The swap runs off this rather than off the state itself: every refresh
 * replaces the info and balance objects wholesale, and handing those to
 * `AnimatedContent` would cross-fade the card with an identical copy of itself
 * every time the operator pulled to refresh.
 */
private enum class LightningPhase { NoStore, Loading, Offline, Error, Content }

@Composable
fun LightningScreen(
    cryptoCode: String,
    serverNode: Boolean,
    onBack: () -> Unit,
    onChannels: () -> Unit,
    onPayments: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onAddresses: () -> Unit,
) {
    val viewModel = appViewModel(key = "lightning:$cryptoCode:$serverNode") {
        LightningViewModel(it, cryptoCode, serverNode)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit = LocalSettings.current.bitcoinUnit

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    AppScreen(
        title = "Lightning",
        subtitle = chainSubtitle(cryptoCode, prefix = "Server node".takeIf { serverNode }),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
    ) { padding ->
        val phase = when {
            state.noStore -> LightningPhase.NoStore
            state.loading -> LightningPhase.Loading
            state.nodeOffline -> LightningPhase.Offline
            state.error != null && state.info == null -> LightningPhase.Error
            else -> LightningPhase.Content
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "lightning") { shown ->
            when (shown) {
                LightningPhase.NoStore -> NoStoreSelectedState()

                // A spinner rather than a skeleton: what is coming is one card
                // and a handful of figures, not a list of rows, and a
                // placeholder of the wrong shape is worse than none at all.
                LightningPhase.Loading -> LoadingState()

                LightningPhase.Offline -> NodeUnreachableState(onRetry = viewModel::refresh)

                // Read through `?.let` rather than `!!`. The branch on its way
                // out of a swap stays composed while it fades, and by then the
                // error it was built from has usually been cleared.
                LightningPhase.Error -> state.error?.let { error ->
                    ErrorState(error = error, onRetry = viewModel::refresh)
                }

                LightningPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding),
                ) {
                    ErrorBanner(
                        error = state.error,
                        onDismiss = viewModel::dismissError,
                        onRetry = viewModel::refresh,
                    )

                    // The sections arrive in reading order rather than all at
                    // once. The order is the order an operator checks them in:
                    // is the node up, what can I do with it, what is in it.
                    state.info?.let { NodeCard(info = it, modifier = Modifier.arrive(0)) }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(1),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = onSend, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Send")
                        }
                        FilledTonalButton(onClick = onReceive, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Receive")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    AppCard(modifier = Modifier.arrive(2)) {
                        Column {
                            // No subtitles. "Peers, capacity and liquidity" under
                            // the word "Channels" is the label written out twice;
                            // it would double the height of this card to say
                            // nothing the title does not already say.
                            ActionRow(Icons.Rounded.Hub, "Channels", onChannels)
                            ThinDivider()
                            ActionRow(Icons.Rounded.SwapHoriz, "Invoices and payments", onPayments)
                            // Lightning addresses are a store feature; the server
                            // node has no store to attach a username to.
                            if (!serverNode) {
                                ThinDivider()
                                ActionRow(Icons.Rounded.AlternateEmail, "Lightning addresses", onAddresses)
                            }
                        }
                    }

                    BalanceSection(balance = state.balance, unit = unit)

                    val uris = state.info?.nodeURIs.orEmpty()
                    if (uris.isNotEmpty()) {
                        // One index for the three pieces below, so the heading,
                        // the addresses and the code arrive as the single block
                        // they are read as.
                        SectionHeader("Node address", Modifier.arrive(5))
                        Column(Modifier.padding(horizontal = 16.dp).arrive(5)) {
                            // No snackbar on copy: `copyToClipboard` says "Copied"
                            // below Android 13 and the system does from 13.
                            uris.forEach { uri ->
                                CopyableField(label = "Node URI", value = uri, truncate = true)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(5),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            QrCode(
                                content = uris.first(),
                                modifier = Modifier.fillMaxWidth(0.72f),
                                contentDescription = "Node URI as a QR code",
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * Alias, channel health and the three figures an operator checks — in two rows
 * rather than five labelled ones. Version and block height
 * answer "is this node current"; the pills answer "is it working". Everything
 * else about the node is a tap away on the rows below.
 */
@Composable
private fun NodeCard(info: LightningNodeInfo, modifier: Modifier = Modifier) {
    val swatch = remember(info.color) { parseNodeColour(info.color) }

    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (swatch != null) {
                    ColourSwatch(swatch)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = info.alias?.takeIf { it.isNotBlank() } ?: "Unnamed node",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            ChannelCountPills(info)

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Figure("Peers", info.peersCount?.toString() ?: LN_PLACEHOLDER, Modifier.weight(1f))
                Figure("Block", info.blockHeight.toString(), Modifier.weight(1f))
                Figure(
                    label = "Version",
                    // Node versions run to "0.18.3-beta commit=v0.18.3-beta";
                    // the part before the first space is the part anyone reads.
                    value = info.version?.takeIf { it.isNotBlank() }?.substringBefore(' ')
                        ?: LN_PLACEHOLDER,
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
    }
}

/** A small label-over-value pair; the density workhorse of these screens. */
@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        FigureText(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A [Figure] whose number moves while it is being read.
 *
 * Only the two channel balances get this. They are the figures a payment
 * changes under the operator's eyes, and one that simply swaps on the frame
 * reads as a redraw rather than as money moving. Everything else on this
 * screen — the version, the block height, the on-chain balance — changes when
 * the operator asks it to, which needs no announcing.
 *
 * Direction is taken from the raw millisatoshi rather than from the formatted
 * string, because the BTC/sat setting can make a rise sort as a fall. Read
 * through [Amounts.serverDecimal]: a million-digit string from a broken node
 * would otherwise be parsed on the main thread.
 *
 * The value is masked in privacy mode; a masked value never changes, so it
 * does not roll either.
 */
@Composable
private fun LiveFigure(
    label: String,
    value: String,
    msat: String?,
    modifier: Modifier = Modifier,
) {
    val amount = Amounts.serverDecimal(msat) ?: BigDecimal.ZERO
    var previous by remember { mutableStateOf(amount) }
    val rising = amount >= previous
    SideEffect { previous = amount }

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        AnimatedValue(
            value = maskedIfPrivate(value),
            upward = rising,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChannelCountPills(info: LightningNodeInfo) {
    val colors = AppTheme.statusColors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(
            label = "${info.activeChannelsCount ?: 0} active",
            container = colors.settled,
            content = colors.onSettled,
        )
        CountPill(info.pendingChannelsCount, "pending", colors.pending, colors.onPending)
        CountPill(info.inactiveChannelsCount, "inactive", colors.expired, colors.onExpired)
    }
}

/**
 * A pill that is there only while its count is not zero.
 *
 * A channel going pending or dropping out is news, and news that appears
 * between two frames is news the operator misses. It widens into the row
 * instead, and closes the same way.
 *
 * The count is latched rather than read straight through, because
 * `AnimatedVisibility` keeps the pill composed for as long as it takes to
 * collapse — by which point the count that justified it is zero, and a pill
 * reading "0 pending" on its way out is worse than no animation at all.
 */
@Composable
private fun RowScope.CountPill(count: Int?, noun: String, container: Color, content: Color) {
    val present = count ?: 0
    var latched by remember { mutableIntStateOf(present) }
    if (present > 0) latched = present

    AnimatedVisibility(
        visible = present > 0,
        enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        StatusPill(label = "$latched $noun", container = container, content = content)
    }
}

/**
 * Balances, with the rows that say nothing left out.
 *
 * "Opening", "Closing", "Unconfirmed" and "Reserved" are zero almost always and
 * absent on several node backends, so as fixed rows they would be seven lines
 * of which four read "—". They appear only when there is really something in
 * them — which is also the only time anyone needs to see them.
 *
 * Every figure here is the store's own money, so privacy mode masks it.
 */
@Composable
private fun BalanceSection(balance: LightningBalanceData?, unit: BitcoinUnit) {
    if (balance == null) return

    balance.offchain?.let { offchain ->
        SectionHeader("In channels", Modifier.arrive(3))
        AppCard(modifier = Modifier.arrive(3)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    LiveFigure("Spendable", msatLabel(offchain.local, unit), offchain.local, Modifier.weight(1f))
                    LiveFigure("Receivable", msatLabel(offchain.remote, unit), offchain.remote, Modifier.weight(1f))
                }
                val pending = listOfNotNull(
                    offchain.opening.takeIfPositive()?.let { "opening ${maskedIfPrivate(msatLabel(it, unit))}" },
                    offchain.closing.takeIfPositive()?.let { "closing ${maskedIfPrivate(msatLabel(it, unit))}" },
                )
                if (pending.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = pending.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    balance.onchain?.let { onchain ->
        SectionHeader("On-chain", Modifier.arrive(4))
        AppCard(modifier = Modifier.arrive(4)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Figure("Confirmed", maskedIfPrivate(satsLabel(onchain.confirmed, unit)), Modifier.weight(1f))
                    onchain.unconfirmed.takeIfPositive()?.let {
                        Figure("Unconfirmed", maskedIfPrivate(satsLabel(it, unit)), Modifier.weight(1f))
                    }
                    onchain.reserved.takeIfPositive()?.let {
                        Figure("Reserved", maskedIfPrivate(satsLabel(it, unit)), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Null for absent, unparsable, or zero — the three cases worth hiding. */
internal fun String?.takeIfPositive(): String? =
    this?.takeIf { Amounts.serverDecimal(it)?.signum() == 1 }

@Composable
private fun ActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A plain coloured dot; the node's advertised colour is decorative only. */
@Composable
private fun ColourSwatch(colour: Color) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(colour))
}

/** Nodes advertise `#rrggbb`; anything else is ignored rather than guessed at. */
private fun parseNodeColour(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}
