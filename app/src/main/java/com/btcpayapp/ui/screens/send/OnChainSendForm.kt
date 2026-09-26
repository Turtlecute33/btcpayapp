package com.btcpayapp.ui.screens.send
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.security.AuthOutcome
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.CreateTransactionRequest
import com.btcpayapp.data.api.dto.TransactionDestination
import com.btcpayapp.data.api.endpoints.createTransaction
import com.btcpayapp.data.api.endpoints.walletFeeRate
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ExpandableSection
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.screens.wallet.cryptoCodeOf
import com.btcpayapp.ui.screens.wallet.formatAmountInput
import com.btcpayapp.ui.screens.wallet.parseAmountToBtc
import com.btcpayapp.ui.screens.wallet.unitLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Next block, roughly an hour, roughly four hours. */
private val FEE_TARGETS = listOf(1, 6, 24)

data class WalletSendState(
    val destination: String = "",
    val amountInput: String = "",
    val sendEverything: Boolean = false,
    val feeRates: Map<Int, Double> = emptyMap(),
    val selectedTarget: Int? = 6,
    val customFeeRate: String = "",
    val rbf: Boolean = true,
    val excludeUnconfirmed: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    /**
     * False when this app knows the server will not sign — a key granted
     * without signing rights, or a wallet the server holds no key for.
     */
    val canSpend: Boolean = true,
    /**
     * True only when that was already known when the screen opened.
     *
     * The distinction matters for what gets drawn. Known in advance, there is
     * no point offering a form at all. Learned from a refused send, replacing
     * the form with an explanation would take the server's own error off the
     * screen at the moment it is most worth reading — so the form stays, with
     * the error on it and the button disabled.
     */
    val blockedUpFront: Boolean = false,
    val error: ApiException? = null,
) {
    /** True once the operator has typed something in the custom rate field. */
    val usingCustomFeeRate: Boolean get() = customFeeRate.isNotBlank()

    /**
     * A typed rate always wins over the estimator's chip — and a typed rate
     * that does not parse is an error, not a reason to quietly use the chip.
     *
     * Sent verbatim, `"0"` would go out as `feerate = 0.0`, producing a
     * transaction that never confirms and can only be rescued by RBF. Falling
     * back to the server estimate on `"1.2.3"` while every chip still draws
     * unselected would let the operator believe their rate was in force when
     * it was not.
     */
    val customFeeRateValue: Double?
        get() = Amounts.parse(customFeeRate)?.toDouble()?.takeIf { it > 0.0 && it.isFinite() }

    val feeRateError: String?
        get() = if (usingCustomFeeRate && customFeeRateValue == null) {
            "Enter a fee rate greater than zero, in sat/vB."
        } else {
            null
        }

    val effectiveFeeRate: Double?
        get() = if (usingCustomFeeRate) customFeeRateValue else selectedTarget?.let(feeRates::get)
}

class WalletSendViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletSendState())
    val state = _state.asStateFlow()

    private val unit: BitcoinUnit get() = graph.settings.settings.value.bitcoinUnit

    init {
        loadFeeRates()
        // Set eagerly rather than only from the collector below: the composable
        // reads `state.value` before any coroutine has run, and a watch-only
        // wallet flashing a live send form is exactly the wrong first frame.
        val allowed = graph.session.canSpendOnChain(paymentMethodId)
        _state.update { it.copy(canSpend = allowed, blockedUpFront = !allowed) }
        viewModelScope.launch {
            graph.session.unspendableMethods.collect {
                _state.update { it.copy(canSpend = graph.session.canSpendOnChain(paymentMethodId)) }
            }
        }
    }

    fun setDestination(value: String) = _state.update { it.copy(destination = value) }

    fun setAmount(value: String) = _state.update { it.copy(amountInput = value) }

    fun setSendEverything(value: Boolean) = _state.update { it.copy(sendEverything = value) }

    fun setTarget(target: Int) = _state.update { it.copy(selectedTarget = target, customFeeRate = "") }

    fun setCustomFeeRate(value: String) = _state.update { it.copy(customFeeRate = value) }

    fun setRbf(value: Boolean) = _state.update { it.copy(rbf = value) }

    fun setExcludeUnconfirmed(value: Boolean) = _state.update { it.copy(excludeUnconfirmed = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** No prompt host, and the user asked for spends to be confirmed. */
    fun reportAuthUnavailable() = _state.update {
        it.copy(
            error = ApiException.Transport(
                "This device cannot show the confirmation prompt, so the payment was not sent. " +
                    "Turn off “Confirm spends” in Settings if you want to send without it.",
            ),
        )
    }

    fun reportAuthFailed() = _state.update {
        it.copy(error = ApiException.Transport("Not authenticated, so the payment was not sent."))
    }

    /**
     * A BIP21 URI carries an amount as well as an address; a bare address must
     * not disturb an amount the user has already typed.
     */
    fun applyScanned(raw: String) {
        if (!cryptoCodeOf(paymentMethodId).equals("BTC", true)) {
            _state.update { it.copy(error = ApiException.Transport("Enter a destination for ${cryptoCodeOf(paymentMethodId)} manually. This scanner recognizes Bitcoin payment requests.")) }
            return
        }
        when (val payload = ScanParser.parse(raw)) {
            is ScannedPayload.Bip21 -> _state.update { current ->
                current.copy(
                    destination = payload.address,
                    amountInput = payload.amountBtc
                        ?.takeIf { it.signum() > 0 }
                        ?.let { formatAmountInput(it, unit) }
                        ?: current.amountInput,
                )
            }

            is ScannedPayload.BitcoinAddress ->
                _state.update { it.copy(destination = payload.address) }

            // Anything else (a BOLT11 invoice, a server URL) cannot be paid from
            // an on-chain wallet, so the field is left alone rather than filled
            // with something that will only fail at the server.
            else -> Unit
        }
    }

    private fun loadFeeRates() {
        val storeId = runCatching { graph.session.requireStoreId() }.getOrElse {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            val api = runCatching { graph.session.requireApi() }.getOrNull() ?: return@launch
            // A missing estimate is not an error worth showing: the custom
            // sat/vB field is always there as a fallback.
            val rates = FEE_TARGETS.map { target -> async {
                runCatching { api.walletFeeRate(storeId, paymentMethodId, target) }
                    .getOrNull()
                    ?.let { target to it.feeRate }
            } }.awaitAll().filterNotNull().toMap()
            _state.update { it.copy(feeRates = rates) }
        }
    }

    /**
     * BTCPay's create-transaction endpoint has three response shapes for one
     * route: an unsigned PSBT when `signWithSeed` is false, a raw hex string
     * when it signs but does not broadcast, and the transaction object when it
     * does both. This screen deliberately uses only the sign-and-broadcast
     * variant — the other two need an external signer this app does not have —
     * and puts a confirmation dialog in front of it instead of a preview step.
     */
    fun send() {
        val storeId = runCatching { graph.session.requireStoreId() }.getOrElse {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
        val snapshot = _state.value
        val amountBtc = parseAmountToBtc(snapshot.amountInput, unit, cryptoCodeOf(paymentMethodId)) ?: return
        if (snapshot.destination.isBlank() || amountBtc.signum() <= 0 || snapshot.sending || snapshot.feeRateError != null) return

        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            runCatching {
                graph.session.requireApi().createTransaction(
                    storeId = storeId,
                    paymentMethodId = paymentMethodId,
                    request = CreateTransactionRequest(
                        destinations = listOf(
                            TransactionDestination(
                                destination = snapshot.destination.trim(),
                                amount = amountBtc,
                                subtractFromAmount = snapshot.sendEverything,
                            ),
                        ),
                        feerate = snapshot.effectiveFeeRate,
                        proceedWithBroadcast = true,
                        signWithSeed = true,
                        rbf = snapshot.rbf,
                        excludeUnconfirmed = snapshot.excludeUnconfirmed,
                    ),
                )
            }.onSuccess {
                _state.update { it.copy(sending = false, sent = true) }
            }.onFailure { failure ->
                val error = failure.asApiException()
                // Remember a refusal to sign, so the Wallet tab and this screen
                // both stop offering a send that cannot work. Greenfield reports
                // a watch-only store and an under-scoped key identically, so
                // both land here and get the same honest explanation.
                if (error.meansCannotSign()) graph.session.markUnspendable(paymentMethodId)
                _state.update { it.copy(sending = false, error = error) }
            }
        }
    }
}

/**
 * Whether a failed send means "this wallet can never be spent from here"
 * rather than "try again".
 *
 * Deliberately narrow. A false positive disables the Send button until the app
 * is restarted, so a timeout, a routing problem or an insufficient-funds answer
 * must not be read as a permanent refusal.
 */
private fun ApiException.meansCannotSign(): Boolean = when (this) {
    is ApiException.Forbidden -> true
    is ApiException.Server -> code in CANNOT_SIGN_CODES
    else -> false
}

private val CANNOT_SIGN_CODES = setOf(
    "no-private-keys",
    "not-available",
    "wallet-not-found",
    "unsupported-operation",
)

/**
 * The on-chain half of the send form: everything the sender fills in below the
 * destination, and the confirmation that stands in front of a broadcast.
 *
 * The destination field itself is not here. It is the one thing both rails have
 * in common — and the thing that decides which of them is on screen — so it
 * belongs to [SendScreen], which owns the switch between the two.
 */
@Composable
internal fun ColumnScope.OnChainSendForm(
    viewModel: WalletSendViewModel,
    state: WalletSendState,
    cryptoCode: String,
    onSent: () -> Unit,
) {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()

    // `BiometricPrompt` needs a FragmentActivity. `LocalActivity` rather than
    // casting `LocalContext`, which is not reliably the Activity.
    val activity = LocalActivity.current as? FragmentActivity

    var confirming by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.sent) {
        if (state.sent) onSent()
    }

    val amountBtc = remember(state.amountInput, settings.bitcoinUnit, cryptoCode) {
        parseAmountToBtc(state.amountInput, settings.bitcoinUnit, cryptoCode)
    }
    val amountError = when {
        state.amountInput.isBlank() -> null
        amountBtc == null -> "Not a number"
        amountBtc.signum() <= 0 -> "Must be greater than zero"
        else -> null
    }
    val canSend = state.destination.isNotBlank() &&
        amountBtc != null &&
        amountBtc.signum() > 0 &&
        // A bad custom rate blocks the send instead of silently reverting
        // to the estimate behind the operator's back.
        state.feeRateError == null &&
        // A server that has already refused to sign will refuse again; the
        // banner above says so rather than letting the button retry for ever.
        state.canSpend &&
        !state.sending

    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

    FormField(
        label = "Amount (${unitLabel(settings.bitcoinUnit, cryptoCode)})",
        value = state.amountInput,
        onValueChange = viewModel::setAmount,
        error = amountError,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )

    FormSection(title = "Network fee") {
        FeeTargetRow(
            rates = state.feeRates,
            selected = state.selectedTarget,
            custom = state.customFeeRate.isNotBlank(),
            onSelect = viewModel::setTarget,
        )
    }

    // The four controls below are right nearly always, and a merchant
    // sending from the counter should not have to read past them. They
    // stay one tap away, with the collapsed row stating what is in force.
    AdvancedOptions(
        state = state,
        expanded = advancedOpen,
        onToggle = { advancedOpen = !advancedOpen },
        onCustomFeeRate = viewModel::setCustomFeeRate,
        onSendEverything = viewModel::setSendEverything,
        onRbf = viewModel::setRbf,
        onExcludeUnconfirmed = viewModel::setExcludeUnconfirmed,
    )

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = { confirming = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        enabled = canSend,
    ) {
        // The label and the spinner cross-fade rather than swap on the
        // frame. This button is pressed once and then watched, so the
        // moment it changes is the only confirmation the sender gets
        // that the tap was taken — a snap is easy to miss and easy to
        // mistake for nothing having happened.
        AnimatedSwap(state.sending, label = "send") { sending ->
            if (sending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Sending…")
                }
            } else {
                Text("Review and send")
            }
        }
    }

    Spacer(Modifier.height(32.dp))

    if (confirming) {
        val feeLabel = state.effectiveFeeRate?.let { "%.2f sat/vB".format(it) } ?: "the server's estimate"
        ConfirmDialog(
            title = "Send this payment?",
            message = buildString {
                append("Sending ")
                append(state.amountInput.ifBlank { "0" })
                append(' ')
                append(unitLabel(settings.bitcoinUnit, cryptoCode))
                if (state.sendEverything) append(" (fee taken from this amount)")
                append(" to ")
                append(TextUtil.middleEllipsis(state.destination.trim(), 12, 10))
                append(" at ")
                append(feeLabel)
                append(". A broadcast transaction cannot be recalled.")
            },
            confirmLabel = "Send",
            destructive = true,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                scope.launch {
                    // Fails closed. Reading it as `confirmSpends && activity !=
                    // null` would let a cast that returned null broadcast the
                    // transaction with no prompt at all, silently, despite
                    // the user having switched the control on. A
                    // security control that degrades to "allow" is not one.
                    if (!settings.confirmSpendsWithBiometrics) {
                        viewModel.send()
                        return@launch
                    }
                    if (activity == null) {
                        viewModel.reportAuthUnavailable()
                        return@launch
                    }
                    when (Biometrics.prompt(
                        activity = activity,
                        title = "Confirm payment",
                        subtitle = "Authenticate to broadcast this transaction",
                    )) {
                        is AuthOutcome.Success -> viewModel.send()
                        // Dismissing is a deliberate "no"; say nothing.
                        is AuthOutcome.Cancelled -> Unit
                        else -> viewModel.reportAuthFailed()
                    }
                }
            },
        )
    }
}

/**
 * Shown in place of the on-chain form when the server will not sign for this
 * wallet — known before anything is typed, rather than discovered by a refused
 * broadcast.
 *
 * Not a disabled form: there is nothing useful to type into one, and a screen
 * full of live fields behind a dead button reads as a bug rather than as a
 * deliberate limit. When the store also has a Lightning node the rail switch
 * stays above this, so the sender's next move is one tap away.
 */
@Composable
internal fun OnChainWatchOnlyState(modifier: Modifier = Modifier) {
    EmptyState(
        modifier = modifier,
        title = "This wallet is watch only",
        description = "The server will not sign for it. Either the private key is not held " +
            "there — it lives on a hardware wallet or another signer — or this app's API " +
            "key was granted without signing rights.\n\nReceiving still works, and so does " +
            "the history. To spend, sign with the wallet that holds the key.",
        icon = Icons.Rounded.Visibility,
    )
}

@Composable
private fun AdvancedOptions(
    state: WalletSendState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCustomFeeRate: (String) -> Unit,
    onSendEverything: (Boolean) -> Unit,
    onRbf: (Boolean) -> Unit,
    onExcludeUnconfirmed: (Boolean) -> Unit,
) {
    ExpandableSection(
        title = "Coin control and fee options",
        summary = advancedSummary(state),
        // A bad custom rate is the one thing in here that blocks the send, so
        // it has to be legible while the section is shut.
        summaryIsError = state.feeRateError != null,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        FormField(
            label = "Custom fee rate (sat/vB)",
            value = state.customFeeRate,
            onValueChange = onCustomFeeRate,
            placeholder = "Leave empty to use the estimate above",
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
            error = state.feeRateError,
        )
        FormSwitch(
            title = "Subtract fee from amount",
            checked = state.sendEverything,
            onCheckedChange = onSendEverything,
            description = "Takes the network fee out of the amount above instead of adding it on top.",
        )
        FormSwitch(
            title = "Replace by fee",
            checked = state.rbf,
            onCheckedChange = onRbf,
            description = "Lets you bump the fee later if the transaction gets stuck.",
        )
        FormSwitch(
            title = "Exclude unconfirmed inputs",
            checked = state.excludeUnconfirmed,
            onCheckedChange = onExcludeUnconfirmed,
            description = "Spends only coins that already have a confirmation.",
        )
    }
}

/** What the collapsed row has to admit to, in one line. */
private fun advancedSummary(state: WalletSendState): String {
    state.feeRateError?.let { return it }
    return listOfNotNull(
        state.customFeeRateValue?.let { "%.2f sat/vB".format(it) },
        "fee ${if (state.sendEverything) "taken from the amount" else "added on top"}",
        if (state.rbf) "replaceable" else "not replaceable",
        "confirmed coins only".takeIf { state.excludeUnconfirmed },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun FeeTargetRow(
    rates: Map<Int, Double>,
    selected: Int?,
    custom: Boolean,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(FEE_TARGETS) { target ->
            val rate = rates[target]
            FilterChip(
                selected = !custom && selected == target,
                onClick = { onSelect(target) },
                enabled = rate != null,
                label = {
                    Text(
                        text = buildString {
                            append(targetLabel(target))
                            if (rate != null) append(" · %.1f".format(rate))
                        },
                    )
                },
            )
        }
    }
}

private fun targetLabel(blockTarget: Int): String = when (blockTarget) {
    1 -> "Next block"
    6 -> "~1 hour"
    24 -> "~4 hours"
    else -> "~$blockTarget blocks"
}
