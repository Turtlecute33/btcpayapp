package com.btcpayapp.ui.screens.lightning

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.lightning.NodeDirectory
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.LightningChannelData
import com.btcpayapp.data.api.dto.OpenChannelRequest
import com.btcpayapp.data.api.endpoints.connectToLightningNode
import com.btcpayapp.data.api.endpoints.lightningChannels
import com.btcpayapp.data.api.endpoints.openLightningChannel
import com.btcpayapp.data.api.endpoints.walletFeeRate
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.theme.AppTheme
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Block target used to pre-fill the channel-opening fee.
 *
 * Six blocks rather than one: a channel open is not time-critical, and the
 * funding output is spent once and then left alone for months. Paying
 * next-block rates for it is money thrown away.
 */
private const val CHANNEL_FEE_BLOCK_TARGET = 6

data class LightningChannelsState(
    val channels: List<LightningChannelData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val nodeOffline: Boolean = false,
    val noStore: Boolean = false,
    val error: ApiException? = null,

    // --- Connect / open form ------------------------------------------------
    val sheetOpen: Boolean = false,
    val nodeUri: String = "",
    val channelAmountSats: String = "",
    val feeRate: String = "",
    /** True while [feeRate] still holds the estimate rather than a typed value. */
    val feeRateIsEstimate: Boolean = false,
    val busy: Boolean = false,
    val formError: String? = null,
    val message: String? = null,

    // --- Peer detail --------------------------------------------------------
    val peerDetail: LightningChannelData? = null,
    val nicknameDraft: String? = null,
)

class LightningChannelsViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(LightningChannelsState(loading = true))
    val state = _state.asStateFlow()

    init {
        if (serverNode) {
            load()
        } else {
            viewModelScope.launch {
                graph.session.activeStore
                    .map { it?.id }
                    .distinctUntilChanged()
                    .collectLatest { storeId ->
                        if (storeId != null) {
                            load()
                            loadFeeEstimate()
                        } else {
                            delay(NO_STORE_GRACE_MS)
                            _state.update { it.copy(loading = false, noStore = true) }
                        }
                    }
            }
        }
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun openSheet() = _state.update { it.copy(sheetOpen = true, formError = null) }

    fun closeSheet() = _state.update { it.copy(sheetOpen = false, formError = null) }

    fun setNodeUri(value: String) = _state.update { it.copy(nodeUri = value, formError = null) }

    fun setChannelAmount(value: String) = _state.update { it.copy(channelAmountSats = value, formError = null) }

    fun setFeeRate(value: String) = _state.update {
        it.copy(feeRate = value, feeRateIsEstimate = false, formError = null)
    }

    // --- Peer naming --------------------------------------------------------

    fun showPeer(channel: LightningChannelData) = _state.update { it.copy(peerDetail = channel) }

    fun dismissPeer() = _state.update { it.copy(peerDetail = null, nicknameDraft = null) }

    fun startRename() {
        val pubkey = _state.value.peerDetail?.remoteNode ?: return
        val existing = graph.settings.settings.value
            .lightningNodeNicknames[NodeDirectory.normalise(pubkey)]
        _state.update { it.copy(nicknameDraft = existing.orEmpty()) }
    }

    fun setNicknameDraft(value: String) = _state.update { it.copy(nicknameDraft = value) }

    fun cancelRename() = _state.update { it.copy(nicknameDraft = null) }

    /**
     * Saves — or, on a blank name, clears — the nickname for the peer whose
     * sheet is open. Stays on the device; see
     * [com.btcpayapp.core.lightning.NodeDirectory].
     */
    fun saveNickname() {
        val snapshot = _state.value
        val pubkey = snapshot.peerDetail?.remoteNode?.let(NodeDirectory::normalise) ?: return
        val name = snapshot.nicknameDraft?.trim().orEmpty().take(40)
        viewModelScope.launch {
            graph.settings.update { settings ->
                val nicknames = settings.lightningNodeNicknames.toMutableMap()
                if (name.isEmpty()) nicknames.remove(pubkey) else nicknames[pubkey] = name
                settings.copy(lightningNodeNicknames = nicknames.toMap())
            }
            _state.update { it.copy(nicknameDraft = null) }
        }
    }

    /** Fills the peer field from a scanned `pubkey@host:port`. */
    fun applyScan(raw: String) {
        when (val payload = ScanParser.parse(raw)) {
            is ScannedPayload.NodeUri -> _state.update {
                it.copy(nodeUri = payload.uri, sheetOpen = true, formError = null)
            }

            else -> _state.update {
                it.copy(sheetOpen = true, formError = "That code is not a node address.")
            }
        }
    }

    fun connectPeer() {
        val uri = _state.value.nodeUri.trim()
        if (uri.isEmpty()) {
            _state.update { it.copy(formError = "Enter a node address first.") }
            return
        }
        val scope = graph.lightningScopeOrNull(serverNode) ?: run {
            _state.update { it.copy(noStore = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, formError = null) }
            runCatching { graph.session.requireApi().connectToLightningNode(scope, uri, cryptoCode) }
                .onSuccess {
                    _state.update { it.copy(busy = false, message = "Connected to the peer.") }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busy = false, formError = failure.asApiException().channelMessage())
                    }
                }
        }
    }

    fun openChannel() {
        val snapshot = _state.value
        val uri = snapshot.nodeUri.trim()
        if (uri.isEmpty()) {
            _state.update { it.copy(formError = "Enter a node address first.") }
            return
        }
        val amount = Amounts.parse(snapshot.channelAmountSats)?.takeIf { it.signum() > 0 }
        if (amount == null) {
            _state.update { it.copy(formError = "Enter the channel size in satoshi.") }
            return
        }
        val feeRate = Amounts.parse(snapshot.feeRate)?.toDouble()?.takeIf { it > 0 && it.isFinite() }
        if (feeRate == null) {
            _state.update { it.copy(formError = "Enter a fee rate in sat/vB.") }
            return
        }

        val scope = graph.lightningScopeOrNull(serverNode) ?: run {
            _state.update { it.copy(noStore = true) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, formError = null) }
            runCatching {
                graph.session.requireApi().openLightningChannel(
                    scope = scope,
                    request = OpenChannelRequest(
                        nodeURI = uri,
                        // Satoshi on the wire, unlike every other Lightning
                        // amount in this API.
                        channelAmount = amount.setScale(0, RoundingMode.DOWN).toPlainString(),
                        feeRate = feeRate,
                    ),
                    cryptoCode = cryptoCode,
                )
            }.onSuccess {
                _state.update {
                    it.copy(
                        busy = false,
                        sheetOpen = false,
                        nodeUri = "",
                        channelAmountSats = "",
                        message = "Channel opening. It will show once the funding transaction confirms.",
                    )
                }
                load(refreshing = true)
            }.onFailure { failure ->
                _state.update {
                    it.copy(busy = false, formError = failure.asApiException().channelMessage())
                }
            }
        }
    }

    /**
     * Pre-fills the fee rate from the store's on-chain estimator.
     *
     * A required field that starts empty is a dead end for anyone who does not
     * already know what sat/vB is reasonable today, and guessing a constant
     * would be worse — fee markets move by two orders of magnitude. A failure
     * here is silent: the field simply stays empty and asks for a number, which
     * is where it was before.
     */
    private fun loadFeeEstimate() {
        if (serverNode) return
        val storeId = graph.session.activeStore.value?.id ?: return
        viewModelScope.launch {
            val api = runCatching { graph.session.requireApi() }.getOrNull() ?: return@launch
            val rate = runCatching {
                api.walletFeeRate(storeId, "$cryptoCode-CHAIN", CHANNEL_FEE_BLOCK_TARGET)
            }.getOrNull()?.feeRate?.takeIf { it > 0 && it.isFinite() } ?: return@launch

            _state.update { current ->
                // Never overwrite a number the operator typed while this was in
                // flight.
                if (current.feeRate.isNotBlank() && !current.feeRateIsEstimate) current
                else current.copy(
                    feeRate = Amounts.trim(BigDecimal.valueOf(rate), 2),
                    feeRateIsEstimate = true,
                )
            }
        }
    }

    private fun load(refreshing: Boolean = false) {
        val scope = graph.lightningScopeOrNull(serverNode)
        if (scope == null) {
            _state.update { it.copy(loading = false, refreshing = false, noStore = true) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = !refreshing && it.channels.isEmpty(),
                    refreshing = refreshing,
                    noStore = false,
                    error = null,
                )
            }
            runCatching { graph.session.requireApi().lightningChannels(scope, cryptoCode) }
                .onSuccess { channels ->
                    _state.update {
                        it.copy(
                            channels = channels,
                            loading = false,
                            refreshing = false,
                            nodeOffline = false,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    val error = failure.asApiException()
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            nodeOffline = error.isNodeUnreachable(),
                            error = error.takeIf { e -> !e.isNodeUnreachable() },
                        )
                    }
                }
        }
    }
}

/**
 * The documented failures of `POST /channels` and `/connect`. The server's own
 * message is terse and assumes the reader knows the protocol, so each code gets
 * a sentence that says what to do next.
 */
private fun ApiException.channelMessage(): String = when ((this as? ApiException.Server)?.code) {
    "channel-already-exists" -> "There is already a channel with that node."
    "cannot-afford-funding" -> "The node does not hold enough confirmed on-chain funds for a channel that size."
    "need-more-confirmations" -> "The node's on-chain funds need more confirmations. Try again in a few blocks."
    "peer-not-connected" -> "The node is not connected to that peer. Connect first, then open the channel."
    "chain-not-synced" -> "The node is still syncing the chain. Wait until it has caught up."
    "could-not-connect" -> "Could not reach that node. Check the address and that the peer is online."
    else -> userMessage
}

/**
 * What the body of the channels screen is showing.
 *
 * A discriminator rather than the channel list itself: the list is replaced
 * wholesale on every poll and every refresh, and handing that to
 * `AnimatedContent` would cross-fade the screen with a copy of itself each
 * time.
 */
private enum class ChannelsPhase { NoStore, Loading, Offline, Error, Empty, Content }

@Composable
fun LightningChannelsScreen(
    cryptoCode: String,
    serverNode: Boolean,
    scanResult: String?,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "ln-channels:$cryptoCode:$serverNode") {
        LightningChannelsViewModel(it, cryptoCode, serverNode)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val unit = settings.bitcoinUnit
    val nicknames = settings.lightningNodeNicknames
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState()
    val peerSheetState = rememberModalBottomSheetState()

    LaunchedEffect(scanResult) {
        scanResult?.let(viewModel::applyScan)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    AppScreen(
        title = "Channels",
        subtitle = channelsSubtitle(state.channels, serverNode, cryptoCode),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (!state.noStore) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openSheet,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Open channel") },
                )
            }
        },
    ) { padding ->
        val phase = when {
            state.noStore -> ChannelsPhase.NoStore
            state.loading -> ChannelsPhase.Loading
            state.nodeOffline -> ChannelsPhase.Offline
            state.error != null && state.channels.isEmpty() -> ChannelsPhase.Error
            state.channels.isEmpty() -> ChannelsPhase.Empty
            else -> ChannelsPhase.Content
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "channels") { shown ->
            when (shown) {
                ChannelsPhase.NoStore -> NoStoreSelectedState()

                ChannelsPhase.Loading -> SkeletonList()

                ChannelsPhase.Offline -> NodeUnreachableState(onRetry = viewModel::refresh)

                // Read through `?.let` rather than `!!`. The branch on its way
                // out of a swap stays composed while it fades, and by then the
                // error it was built from has usually been cleared.
                ChannelsPhase.Error -> state.error?.let { error ->
                    ErrorState(error = error, onRetry = viewModel::refresh)
                }

                ChannelsPhase.Empty -> EmptyState(
                    title = "No channels",
                    description = "Open a channel with a peer to send and receive over Lightning.",
                    icon = Icons.Rounded.Hub,
                    actionLabel = "Open a channel",
                    onAction = viewModel::openSheet,
                )

                ChannelsPhase.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        ErrorBanner(
                            error = state.error,
                            onDismiss = viewModel::dismissError,
                            onRetry = viewModel::refresh,
                        )
                    }
                    // No stable key: a node may hold several channels with the same
                    // peer, and `channelPoint` is null until funding is broadcast.
                    // Which also rules out `animateItem`, since without a key a row
                    // is only ever identified by the position it already holds.
                    items(state.channels) { channel ->
                        ChannelCard(
                            channel = channel,
                            unit = unit,
                            nicknames = nicknames,
                            onClick = { viewModel.showPeer(channel) },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (state.sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeSheet,
            sheetState = sheetState,
        ) {
            PeerSheet(
                state = state,
                onNodeUri = viewModel::setNodeUri,
                onChannelAmount = viewModel::setChannelAmount,
                onFeeRate = viewModel::setFeeRate,
                onScan = onScan,
                onConnect = viewModel::connectPeer,
                onOpen = viewModel::openChannel,
            )
        }
    }

    state.peerDetail?.let { channel ->
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissPeer,
            sheetState = peerSheetState,
        ) {
            PeerDetailSheet(
                channel = channel,
                unit = unit,
                nicknames = nicknames,
                onRename = viewModel::startRename,
            )
        }
    }

    state.nicknameDraft?.let { draft ->
        NicknameDialog(
            draft = draft,
            peer = state.peerDetail?.remoteNode.orEmpty(),
            onChange = viewModel::setNicknameDraft,
            onSave = viewModel::saveNickname,
            onDismiss = viewModel::cancelRename,
        )
    }
}

/** "3 of 4 active", so the header carries the count the list would. */
private fun channelsSubtitle(
    channels: List<LightningChannelData>,
    serverNode: Boolean,
    cryptoCode: String,
): String? {
    val prefix = chainSubtitle(cryptoCode, prefix = "Server node".takeIf { serverNode })
    if (channels.isEmpty()) return prefix
    val active = channels.count { it.isActive }
    val health = if (active == channels.size) "all active" else "$active of ${channels.size} active"
    return listOfNotNull(prefix, health).joinToString(" · ")
}

/**
 * One channel, at a glance.
 *
 * Deliberately three lines: who, how the liquidity sits, and nothing else. The
 * channel point, the full pubkey and the public/private flag live in the sheet
 * behind a tap — they are looked at once when something is wrong, not
 * every time the list is opened.
 */
@Composable
private fun ChannelCard(
    channel: LightningChannelData,
    unit: BitcoinUnit,
    nicknames: Map<String, String>,
    onClick: () -> Unit,
) {
    val colors = AppTheme.statusColors
    val name = remember(channel.remoteNode, nicknames) {
        NodeDirectory.label(channel.remoteNode, nicknames)
    }
    val split = remember(channel.capacity, channel.localBalance) { liquidityOf(channel) }

    AppCard(onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (!channel.isActive) {
                    StatusPill(label = "Inactive", container = colors.expired, content = colors.onExpired)
                } else if (!channel.isPublic) {
                    // Public is the norm and needs no badge; private does,
                    // because a private channel will not route for anyone.
                    StatusPill(label = "Private", container = colors.pending, content = colors.onPending)
                }
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { split.localShare },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = "Spendable ${msatLabel(split.local, unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Receivable ${msatLabel(split.remote, unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PeerDetailSheet(
    channel: LightningChannelData,
    unit: BitcoinUnit,
    nicknames: Map<String, String>,
    onRename: () -> Unit,
) {
    val colors = AppTheme.statusColors
    val name = remember(channel.remoteNode, nicknames) {
        NodeDirectory.label(channel.remoteNode, nicknames)
    }
    val named = remember(channel.remoteNode, nicknames) {
        NodeDirectory.isNamed(channel.remoteNode, nicknames)
    }
    val split = remember(channel.capacity, channel.localBalance) { liquidityOf(channel) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (named) "Named on this device or from the bundled list" else "This peer has no name yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                label = if (channel.isActive) "Active" else "Inactive",
                container = if (channel.isActive) colors.settled else colors.expired,
                content = if (channel.isActive) colors.onSettled else colors.onExpired,
            )
            StatusPill(
                label = if (channel.isPublic) "Public" else "Private",
                container = colors.expired,
                content = colors.onExpired,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            SheetFigure("Spendable", msatLabel(split.local, unit), Modifier.weight(1f))
            SheetFigure("Receivable", msatLabel(split.remote, unit), Modifier.weight(1f))
            SheetFigure("Capacity", msatLabel(channel.capacity, unit), Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        CopyableField(label = "Node public key", value = channel.remoteNode, truncate = true)

        channel.channelPoint?.takeIf { it.isNotBlank() }?.let { point ->
            Spacer(Modifier.height(12.dp))
            CopyableField(label = "Channel point", value = point, truncate = true)
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
            Text(if (named) "Rename this peer" else "Name this peer")
        }
    }
}

@Composable
private fun SheetFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/**
 * An `AlertDialog` rather than another bottom sheet: a sheet lives in its own
 * window, so `adjustResize` never reaches it and the keyboard covers the field.
 */
@Composable
private fun NicknameDialog(
    draft: String,
    peer: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this peer") },
        text = {
            Column {
                Text(
                    text = "Only stored on this phone. It is never sent to the server or " +
                        "to anyone else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // Not `FormField`: that adds the 16.dp gutter every screen
                // needs, which inside a dialog lands on top of the dialog's own.
                OutlinedTextField(
                    value = draft,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    placeholder = { Text(NodeDirectory.short(peer)) },
                    supportingText = { Text("Leave empty to remove the name.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * How the capacity is split.
 *
 * `remote` is capacity minus local, which is what every Lightning interface
 * shows as inbound liquidity. It is an upper bound rather than an exact figure —
 * the channel reserve and unsettled HTLCs are not visible through Greenfield —
 * so it is labelled "receivable" rather than promised.
 */
private data class Liquidity(val local: String, val remote: String, val localShare: Float)

private fun liquidityOf(channel: LightningChannelData): Liquidity {
    val capacity = channel.capacity.toBigIntegerOrNull() ?: BigInteger.ZERO
    val local = channel.localBalance.toBigIntegerOrNull() ?: BigInteger.ZERO
    val remote = (capacity - local).coerceAtLeast(BigInteger.ZERO)
    val share = if (capacity.signum() <= 0) {
        0f
    } else {
        BigDecimal(local)
            .divide(BigDecimal(capacity), 4, RoundingMode.DOWN)
            .toFloat()
            .coerceIn(0f, 1f)
    }
    return Liquidity(local = local.toString(), remote = remote.toString(), localShare = share)
}

@Composable
private fun PeerSheet(
    state: LightningChannelsState,
    onNodeUri: (String) -> Unit,
    onChannelAmount: (String) -> Unit,
    onFeeRate: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "Connect a peer",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        FormField(
            label = "Node address",
            value = state.nodeUri,
            onValueChange = onNodeUri,
            placeholder = "pubkey@host:port",
            supportingText = "Connect first, then open a channel with the same peer.",
            enabled = !state.busy,
            trailingIcon = {
                IconButton(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan a node address")
                }
            },
        )

        OutlinedButton(
            onClick = onConnect,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("Connect")
        }

        FormField(
            label = "Channel size (sat)",
            value = state.channelAmountSats,
            onValueChange = onChannelAmount,
            placeholder = "100000",
            supportingText = "Funded from the node's on-chain balance.",
            enabled = !state.busy,
            keyboardType = KeyboardType.Number,
        )

        FormField(
            label = "Fee rate (sat/vB)",
            value = state.feeRate,
            onValueChange = onFeeRate,
            placeholder = "5",
            supportingText = if (state.feeRateIsEstimate) {
                "The server's estimate for confirming within about an hour."
            } else {
                null
            },
            enabled = !state.busy,
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        )

        FormProblem(state.formError, verticalPadding = 4.dp)

        Button(
            onClick = onOpen,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // The spinner cross-fades in beside the label rather than shoving
            // it sideways. Opening a channel takes a few seconds against a node
            // behind Tor, and the label is what says which of the two buttons
            // on this sheet was pressed.
            AnimatedSwap(state.busy, label = "openChannel") { busy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text("Open channel")
                }
            }
        }
    }
}
