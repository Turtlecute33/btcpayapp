package com.btcpayapp.ui.screens.wallet
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.PatchTransactionRequest
import com.btcpayapp.data.api.dto.WalletTransactionData
import com.btcpayapp.data.api.dto.WalletTransactionStatus
import com.btcpayapp.data.api.endpoints.patchTransaction
import com.btcpayapp.data.api.endpoints.walletTransaction
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.BigAmount
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.continuity
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the screen is showing, as one cheap value.
 *
 * Driven by this rather than by `TransactionDetailState`, which changes on
 * every keystroke in the comment field — handing the state object to the
 * animation would replay the whole screen while somebody was typing into it.
 */
private enum class TransactionPhase { Loading, Error, Content }

data class TransactionDetailState(
    val transaction: WalletTransactionData? = null,
    val comment: String = "",
    val labels: List<String> = emptyList(),
    val newLabel: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val error: ApiException? = null,
) {
    val dirty: Boolean
        get() {
            val loaded = transaction ?: return false
            return comment != loaded.comment || labels != loaded.labels.map { it.text }
        }
}

class TransactionDetailViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
    private val transactionId: String,
) : ViewModel() {

    /**
     * The store this screen was opened in (see [StoreBinding]), so a save can
     * only ever label the transaction on screen. The shell drops the screen
     * when the store changes.
     */
    private val store = StoreBinding(graph.session)
    private val storeId: String? get() = store.id

    /**
     * An account reached as an .onion host or through a proxy (usually Tor)
     * hides the phone's address. The browser does not take that route, so an
     * explorer link asks first. The account cannot change under an open
     * screen: an account switch rebuilds every screen.
     */
    val privateRoute: Boolean = graph.session.activeAccount.value?.let { it.isOnion || it.proxy != null } == true

    private val _state = MutableStateFlow(TransactionDetailState())
    val state = _state.asStateFlow()

    init {
        load(refreshing = false)
        store.retryWhenKnown(viewModelScope) { load(refreshing = false) }
    }

    fun refresh() = load(refreshing = true)

    fun setComment(value: String) = _state.update { it.copy(comment = value) }

    fun setNewLabel(value: String) = _state.update { it.copy(newLabel = value) }

    fun addLabel() = _state.update { current ->
        val label = current.newLabel.trim()
        if (label.isEmpty() || label in current.labels) {
            current.copy(newLabel = "")
        } else {
            current.copy(labels = current.labels + label, newLabel = "")
        }
    }

    fun removeLabel(label: String) = _state.update { it.copy(labels = it.labels - label) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun load(refreshing: Boolean) {
        val storeId = storeId ?: return fail(ApiException.NoAccount())
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.transaction == null, refreshing = refreshing, error = null)
            }
            runCatching {
                graph.session.requireApi().walletTransaction(storeId, paymentMethodId, transactionId)
            }.onSuccess { transaction ->
                _state.update { current ->
                    current.copy(
                        transaction = transaction,
                        // A local edit in progress must survive a refresh.
                        comment = if (current.dirty) current.comment else transaction.comment,
                        labels = if (current.dirty) current.labels else transaction.labels.map { it.text },
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
            }.onFailure { failure -> fail(failure) }
        }
    }

    fun save() {
        val storeId = storeId ?: return fail(ApiException.NoAccount())
        val snapshot = _state.value
        if (snapshot.saving) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                graph.session.requireApi().patchTransaction(
                    storeId = storeId,
                    paymentMethodId = paymentMethodId,
                    transactionId = transactionId,
                    request = PatchTransactionRequest(
                        comment = snapshot.comment,
                        labels = snapshot.labels,
                    ),
                )
            }.onSuccess { transaction ->
                _state.update {
                    it.copy(
                        transaction = transaction,
                        comment = transaction.comment,
                        labels = transaction.labels.map { label -> label.text },
                        saving = false,
                        message = "Saved",
                    )
                }
            }.onFailure { failure -> fail(failure) }
        }
    }

    private fun fail(failure: Throwable) = _state.update {
        it.copy(
            loading = false,
            refreshing = false,
            saving = false,
            error = failure as? ApiException
                ?: ApiException.Transport(failure.message ?: "Unexpected failure"),
        )
    }
}

@Composable
fun TransactionDetailScreen(
    paymentMethodId: String,
    transactionId: String,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "tx-$paymentMethodId-$transactionId") {
        TransactionDetailViewModel(it, paymentMethodId, transactionId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val guardedBack = com.btcpayapp.ui.components.confirmDiscardChanges(state.dirty, onBack)
    val cryptoCode = remember(paymentMethodId) { cryptoCodeOf(paymentMethodId) }
    val explorer = remember(cryptoCode, transactionId) { mempoolUrl(cryptoCode, transactionId) }
    var confirmExplorer by rememberSaveable { mutableStateOf(false) }
    val openExplorer = { explorer?.let { context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }

    // The explorer is on the normal internet. For an account reached as an
    // .onion host or through a proxy, that is the one request that would tie
    // this transaction to the phone's address, so it is said before it happens.
    if (confirmExplorer) {
        ConfirmDialog(
            title = "View in explorer",
            message = "This opens mempool.space over the normal internet. " +
                "It learns this transaction id and your IP address. Open anyway?",
            confirmLabel = "Open",
            onConfirm = {
                confirmExplorer = false
                openExplorer()
            },
            onDismiss = { confirmExplorer = false },
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    AppScreen(
        title = "Transaction",
        subtitle = chainSubtitle(cryptoCode),
        onBack = guardedBack,
        snackbarHostState = snackbarHostState,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        actions = {
            if (explorer != null) {
                // An explorer lookup hands this transaction id — and the
                // device's IP — to a third party that has nothing to do with the
                // user's own server. It is therefore always an explicit tap and
                // never a lookup the screen performs by itself.
                IconButton(onClick = { if (viewModel.privateRoute) confirmExplorer = true else openExplorer() }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "View in explorer")
                }
            }
            IconButton(onClick = viewModel::save, enabled = state.dirty && !state.saving) {
                Icon(Icons.Rounded.Check, contentDescription = "Save")
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> TransactionPhase.Loading
            state.transaction == null && state.error != null -> TransactionPhase.Error
            else -> TransactionPhase.Content
        }

        // Held across the swap out: retry clears `state.error` on the frame the
        // exit begins, and the outgoing branch is composed until it has finished
        // leaving.
        val lastError = remember { mutableStateOf<ApiException?>(null) }
        if (state.error != null) lastError.value = state.error

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "transaction") { shown ->
            when (shown) {
                // A spinner rather than a skeleton. What is coming is a detail
                // sheet, not a list, and a placeholder in the shape of rows
                // would promise a layout that never arrives.
                TransactionPhase.Loading -> LoadingState()

                TransactionPhase.Error -> lastError.value?.let { failure ->
                    ErrorState(error = failure, onRetry = viewModel::refresh)
                }

                TransactionPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    state.transaction?.let { transaction ->
                        Spacer(Modifier.height(16.dp))
                        // Centred by the box rather than by the figure filling
                        // the width, so the shared element is the amount
                        // itself. Marking a full-width block instead would have
                        // the row's amount fly in as a band the width of the
                        // screen.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            BigAmount(
                                amount = transaction.amount,
                                currency = cryptoCode,
                                modifier = Modifier.continuity("tx-amount-$transactionId"),
                                secondary = Dates.relative(transaction.timestamp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            val colors = AppTheme.statusColors
                            val confirmed = transaction.status == WalletTransactionStatus.Confirmed
                            StatusPill(
                                label = if (confirmed) "Confirmed" else "Unconfirmed",
                                container = if (confirmed) colors.settled else colors.pending,
                                content = if (confirmed) colors.onSettled else colors.onPending,
                                modifier = Modifier.continuity("tx-status-$transactionId"),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // The amount and the pill arrive from the row that was
                        // tapped, so the sections below follow them in rather
                        // than landing with them. Staggered against each other,
                        // because three blocks appearing on one frame reads as
                        // the screen having been there all along.
                        FormSection(title = "Details", modifier = Modifier.arrive(0)) {
                            DetailRow("Direction", if (transaction.isIncoming) "Received" else "Sent")
                            DetailRow("Confirmations", transaction.confirmations.toString())
                            DetailRow(
                                label = "Block height",
                                value = transaction.blockHeight.takeIf { it > 0 }?.toString() ?: "Not mined yet",
                            )
                            DetailRow("Time", Dates.full(transaction.timestamp))
                            Spacer(Modifier.height(4.dp))
                            CopyableField(
                                label = "Transaction hash",
                                value = transaction.transactionHash ?: transactionId,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }

                    FormSection(title = "Comment", modifier = Modifier.arrive(1)) {
                        FormField(
                            label = "Comment",
                            value = state.comment,
                            onValueChange = viewModel::setComment,
                            placeholder = "Only you and your store's users can see this",
                            singleLine = false,
                            imeAction = ImeAction.Default,
                        )
                    }

                    FormSection(title = "Labels", modifier = Modifier.arrive(2)) {
                        // The discriminator is whether there are any labels at
                        // all, not the labels themselves: adding a second one
                        // should move the chips, not dissolve the whole block.
                        AnimatedSwap(state.labels.isEmpty(), label = "labels") { empty ->
                            if (empty) {
                                Text(
                                    text = "No labels yet.",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // Keyed on the text *and* the position, so
                                    // removing one from the middle slides the
                                    // rest along rather than relabelling every
                                    // chip in place.
                                    //
                                    // The text alone would be the better key
                                    // and is not safe as one. `addLabel`
                                    // refuses a duplicate, but the list is also
                                    // rebuilt from the server, where labels
                                    // arrive grouped by *type* — two types
                                    // carrying the same text produce two
                                    // identical strings, and a repeated key in
                                    // a lazy list is a crash rather than a
                                    // cosmetic fault. The index costs a chip
                                    // its animation only when an earlier one is
                                    // removed.
                                    itemsIndexed(state.labels, key = { index, label -> "$index-$label" }) { _, label ->
                                        InputChip(
                                            selected = false,
                                            onClick = { viewModel.removeLabel(label) },
                                            label = { Text(label) },
                                            modifier = Modifier.animateItem(
                                                fadeInSpec = Motion.effects,
                                                placementSpec = Motion.spatialOffset,
                                                fadeOutSpec = Motion.effectsFast,
                                            ),
                                            trailingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Remove $label",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        FormField(
                            label = "Add a label",
                            value = state.newLabel,
                            onValueChange = viewModel::setNewLabel,
                            imeAction = ImeAction.Done,
                            trailingIcon = {
                                IconButton(
                                    onClick = viewModel::addLabel,
                                    enabled = state.newLabel.isNotBlank(),
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add label")
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * Only Bitcoin gets an explorer link: mempool.space does not index the other
 * chains BTCPay supports, and a guessed URL is worse than no button. The link
 * is mainnet's: Greenfield does not say which network a server runs, so on a
 * testnet or signet server the page finds nothing.
 */
private fun mempoolUrl(cryptoCode: String, transactionId: String): String? =
    if (cryptoCode.equals("BTC", ignoreCase = true)) {
        "https://mempool.space/tx/$transactionId"
    } else {
        null
    }
