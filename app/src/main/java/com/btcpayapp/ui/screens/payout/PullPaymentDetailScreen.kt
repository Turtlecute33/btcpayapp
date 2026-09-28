package com.btcpayapp.ui.screens.payout
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.LnurlData
import com.btcpayapp.data.api.dto.PayoutData
import com.btcpayapp.data.api.dto.PullPaymentData
import com.btcpayapp.data.api.endpoints.archivePullPayment
import com.btcpayapp.data.api.endpoints.pullPayment
import com.btcpayapp.data.api.endpoints.pullPaymentLnurl
import com.btcpayapp.data.api.endpoints.pullPaymentPayouts
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AmountText
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.BigAmount
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.PayoutStatusChip
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the body of the screen is showing.
 *
 * A discriminator rather than the state, so a refresh that returns the same
 * pull payment does not replay the entrance of the whole page.
 */
private enum class PullPaymentDetailPhase { Loading, Error, Content }

data class PullPaymentDetailState(
    val pullPayment: PullPaymentData? = null,
    val lnurl: LnurlData? = null,
    val payouts: List<PayoutData> = emptyList(),
    /**
     * The last claims read failed. Kept apart from [payouts] being empty: shown
     * as "nothing claimed", a failed read led merchants to refund twice.
     */
    val claimsError: ApiException? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val confirmingArchive: Boolean = false,
    val message: String? = null,
)

class PullPaymentDetailViewModel(
    private val graph: AppGraph,
    private val pullPaymentId: String,
) : ViewModel() {

    /**
     * The store this screen was opened in (see [StoreBinding]), read when the
     * archive runs; archiving is store-scoped on servers before 2.4.
     */
    private val bound = StoreBinding(graph.session)
    private val storeId: String? get() = bound.id

    private val _state = MutableStateFlow(PullPaymentDetailState())
    val state = _state.asStateFlow()

    /** Loads on every return to the screen, so claims made meanwhile show up. */
    fun onResume() = load()

    fun refresh() = load(refreshing = true)

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun requestArchive() = _state.update { it.copy(confirmingArchive = true) }

    fun dismissArchive() = _state.update { it.copy(confirmingArchive = false) }

    fun confirmArchive() {
        _state.update { it.copy(confirmingArchive = false) }
        val store = storeId ?: run {
            _state.update { it.copy(message = ApiException.NoAccount().userMessage) }
            return
        }
        viewModelScope.launch {
            runCatching { graph.session.requireApi().archivePullPayment(store, pullPaymentId) }
                .onSuccess {
                    _state.update { it.copy(message = "Pull payment archived") }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.asApiException().userMessage) }
                }
        }
    }

    fun load(refreshing: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.pullPayment == null, refreshing = refreshing, error = null)
            }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update {
                    it.copy(loading = false, refreshing = false, error = failure.asApiException())
                }
                return@launch
            }

            runCatching { api.pullPayment(pullPaymentId) }
                .onSuccess { data -> _state.update { it.copy(pullPayment = data) } }
                .onFailure { failure ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = failure.asApiException())
                    }
                    return@launch
                }

            // The LNURL route only exists when the store can pay out over
            // Lightning, so a failure here hides the section instead of failing
            // the screen.
            runCatching { api.pullPaymentLnurl(pullPaymentId) }
                .onSuccess { data -> _state.update { it.copy(lnurl = data) } }
                .onFailure { _state.update { it.copy(lnurl = null) } }

            // A failure keeps the claims already on screen and says so.
            runCatching { api.pullPaymentPayouts(pullPaymentId, includeCancelled = true) }
                .onSuccess { list ->
                    _state.update { it.copy(payouts = list.distinctBy(PayoutData::id).sortedByDescending(PayoutData::date), claimsError = null) }
                }
                .onFailure { failure -> _state.update { it.copy(claimsError = failure.asApiException()) } }

            _state.update { it.copy(loading = false, refreshing = false) }
        }
    }
}

@Composable
fun PullPaymentDetailScreen(
    pullPaymentId: String,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = pullPaymentId) { PullPaymentDetailViewModel(it, pullPaymentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    val pullPayment = state.pullPayment
    val claimLink = pullPayment?.viewLink

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = pullPayment?.name?.ifBlank { "Pull payment" } ?: "Pull payment",
        subtitle = pullPayment?.let { TextUtil.middleEllipsis(it.id, 8, 6) },
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(
                enabled = claimLink != null,
                onClick = {
                    claimLink?.let { link ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                            putExtra(Intent.EXTRA_SUBJECT, pullPayment.name)
                        }
                        context.safeStartActivity(Intent.createChooser(intent, "Share claim link"))
                    }
                },
            ) {
                Icon(Icons.Rounded.Share, contentDescription = "Share claim link")
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Archive") },
                    leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                    enabled = pullPayment?.archived == false,
                    onClick = {
                        menuOpen = false
                        viewModel.requestArchive()
                    },
                )
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> PullPaymentDetailPhase.Loading
            state.error != null && pullPayment == null -> PullPaymentDetailPhase.Error
            pullPayment == null -> PullPaymentDetailPhase.Loading
            else -> PullPaymentDetailPhase.Content
        }

        AnimatedSwap(phase, label = "pullPayment") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is coming is one
                // record with a hero figure, not a list of rows, so a row
                // outline would guess the wrong shape.
                PullPaymentDetailPhase.Loading -> LoadingState(Modifier.padding(padding))

                PullPaymentDetailPhase.Error -> state.error?.let {
                    ErrorState(
                        error = it,
                        modifier = Modifier.padding(padding),
                        onRetry = viewModel::refresh,
                    )
                }

                PullPaymentDetailPhase.Content -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item { pullPayment?.let { PullPaymentHeader(it) } }

                    if (claimLink != null) {
                        item {
                            Column {
                                SectionHeader("Claim link")
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    HiddenCode(content = claimLink, contentDescription = "Claim link")
                                    // A bearer claim, like the code above: shortened
                                    // on screen, and copied as a secret, so clipboard
                                    // histories leave it out. The copy is whole.
                                    CopyableField(label = "Link", value = claimLink, sensitive = true, truncate = true)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Whoever opens this enters their own address or invoice to claim.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    state.lnurl?.takeIf { it.lnurlBech32.isNotBlank() }?.let { lnurl ->
                        item {
                            Column(Modifier.padding(top = 16.dp)) {
                                SectionHeader("LNURL withdraw")
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    // Wallets expect the `lightning:` scheme on the
                                    // QR even though the copyable string is the bare
                                    // bech32.
                                    HiddenCode(
                                        content = "lightning:${lnurl.lnurlBech32}",
                                        contentDescription = "LNURL withdraw code",
                                    )
                                    CopyableField(
                                        label = "LNURL",
                                        value = lnurl.lnurlBech32,
                                        sensitive = true,
                                        truncate = true,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Column(Modifier.padding(top = 16.dp)) {
                            SectionHeader("Claims")
                            val claimsError = state.claimsError
                            // The line goes as the first claim lands, so it
                            // gives way rather than being overwritten.
                            AnimatedVisibility(
                                visible = claimsError == null && state.payouts.isEmpty(),
                                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                            ) {
                                Text(
                                    text = "Nothing has been claimed yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            AnimatedVisibility(
                                visible = claimsError != null,
                                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Could not load claims.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = viewModel::refresh) { Text("Retry") }
                                }
                            }
                        }
                    }

                    items(state.payouts, key = { it.id }) { payout ->
                        // Claim and rule travel together when a new one is
                        // approved above them.
                        Column(Modifier.animateItem()) {
                            ClaimRow(payout)
                            ThinDivider()
                        }
                    }

                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }

    if (state.confirmingArchive) {
        ConfirmDialog(
            title = "Archive pull payment?",
            message = "The claim link stops working. Claims already approved are unaffected.",
            confirmLabel = "Archive",
            onConfirm = viewModel::confirmArchive,
            onDismiss = viewModel::dismissArchive,
            destructive = true,
        )
    }
}

/**
 * A claim code, collapsed until asked for.
 *
 * These codes are bearer claims: anyone whose camera sees one can take the
 * funds, and with auto-approve nobody reviews that claim. So the screen opens
 * with them folded away, Share stays the usual way to hand one over, and in
 * privacy mode they cannot be shown at all.
 */
@Composable
private fun HiddenCode(content: String, contentDescription: String) {
    val privacyMode = LocalSettings.current.privacyMode
    var shown by rememberSaveable(content) { mutableStateOf(false) }

    AnimatedVisibility(
        visible = shown && !privacyMode,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        QrCode(content = content, contentDescription = contentDescription)
    }
    if (privacyMode) {
        Text(
            text = "The code stays hidden in privacy mode. Use Share or copy the link.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide code" else "Show code") }
    }
}

@Composable
private fun PullPaymentHeader(pullPayment: PullPaymentData) {
    val colors = AppTheme.statusColors

    // No `arrive` stagger, for the same reason rows never carry one: this
    // header is a lazy item, and with a long enough claims list below it the
    // header is disposed when it scrolls away and would introduce itself all
    // over again on the way back. The screen's own push is its entrance.
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        // Masked in privacy mode: this is the store's own record, not a
        // figure shown to a customer.
        BigAmount(
            amount = pullPayment.amount,
            currency = pullPayment.currency,
            modifier = Modifier.fillMaxWidth(),
            secondary = pullPayment.name.takeIf { it.isNotBlank() },
        )

        // Archiving flips a flag on a screen the operator is still looking at,
        // so the badge grows in rather than appearing between two frames.
        AnimatedVisibility(
            visible = pullPayment.autoApproveClaims || pullPayment.archived,
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                    ,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pullPayment.autoApproveClaims) {
                    StatusPill(label = "Auto-approve", container = colors.settled, content = colors.onSettled)
                    Spacer(Modifier.width(8.dp))
                }
                if (pullPayment.archived) {
                    StatusPill(label = "Archived", container = colors.expired, content = colors.onExpired)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (pullPayment.description.isNotBlank()) {
            DetailRow(
                label = "Description",
                value = TextUtil.stripHtml(pullPayment.description),
            )
        }
        DetailRow(
            label = "Starts",
            value = pullPayment.startsAt?.let(Dates::full) ?: "Immediately",
        )
        DetailRow(
            label = "Expires",
            value = pullPayment.expiresAt?.let(Dates::full) ?: "Never",
        )
        DetailRow(
            label = "Invoice validity",
            value = "${pullPayment.BOLT11Expiration} days",
        )
        ThinDivider()
    }
}

@Composable
private fun ClaimRow(payout: PayoutData) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = TextUtil.middleEllipsis(payout.destination, 14, 10),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PayoutStatusChip(state = payout.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Dates.relative(payout.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        AmountText(
            amount = payout.originalAmount,
            currency = payout.originalCurrency,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
