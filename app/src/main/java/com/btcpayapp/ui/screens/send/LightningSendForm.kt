package com.btcpayapp.ui.screens.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.lightning.Bolt11
import com.btcpayapp.core.lightning.Bolt11Invoice
import com.btcpayapp.core.lightning.NodeDirectory
import com.btcpayapp.core.security.AuthOutcome
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.LightningPaymentData
import com.btcpayapp.data.api.dto.LightningPaymentStatus
import com.btcpayapp.data.api.dto.PayLightningInvoiceRequest
import com.btcpayapp.data.api.endpoints.payLightningInvoice
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ExpandableSection
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.screens.lightning.PaymentStatusPill
import com.btcpayapp.ui.screens.lightning.lightningScopeOrNull
import com.btcpayapp.ui.screens.lightning.msatLabel
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Defaults for the routing-fee ceiling and the attempt timeout.
 *
 * Three per cent is what LND and BTCPay's own screens use, and a blank field
 * here does not mean "no limit" — it means whatever that node happens to be
 * configured with, which the operator cannot see from the phone. Naming the
 * number is the difference between a deliberate limit and an unknown one.
 *
 * The flat ceiling is deliberately left empty: applied on top of the
 * percentage it becomes the binding constraint on small payments, and a
 * 21-sat cap silently fails routing for anything over a few thousand sat.
 */
private const val DEFAULT_MAX_FEE_PERCENT = "3"
private const val DEFAULT_SEND_TIMEOUT_SECONDS = "30"

data class LightningSendState(
    val bolt11: String = "",
    val amount: String = "",
    val maxFeePercent: String = DEFAULT_MAX_FEE_PERCENT,
    val maxFeeFlatSats: String = "",
    val sendTimeout: String = DEFAULT_SEND_TIMEOUT_SECONDS,
    val sending: Boolean = false,
    val result: LightningPaymentData? = null,
    val error: ApiException? = null,
    val formError: String? = null,
    /** Plain guidance for a scan this screen cannot act on. */
    val notice: String? = null,
    val noStore: Boolean = false,
) {
    /**
     * What the pasted invoice says, read on the device. Null when the field is
     * empty or holds something that is not a BOLT11 invoice.
     *
     * `by lazy` rather than a getter: this is read from composition as well as
     * from [LightningSendViewModel.pay], and a bech32 pass per read would run
     * on every recomposition of every unrelated field on the screen. A new
     * state instance decodes once and then remembers.
     */
    val decoded: Bolt11Invoice? by lazy { Bolt11.decode(bolt11) }

    /**
     * The amount field only means anything for an invoice that leaves the
     * amount open. An invoice that names its own amount is not negotiable, and
     * sending a different one is an error rather than a discount — so the field
     * is hidden instead of ignored.
     *
     * An invoice that will not decode also gets the field: the node may still
     * accept a form this reader does not handle, and a blank amount is then the
     * only thing that could block a legitimate payment.
     */
    val needsAmount: Boolean get() = decoded?.isAmountless ?: bolt11.isNotBlank()
}

class LightningSendViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(LightningSendState())
    val state = _state.asStateFlow()

    fun setBolt11(value: String) = _state.update {
        it.copy(bolt11 = value, formError = null, notice = null)
    }

    fun setAmount(value: String) = _state.update { it.copy(amount = value, formError = null) }

    fun setMaxFeePercent(value: String) = _state.update { it.copy(maxFeePercent = value, formError = null) }

    fun setMaxFeeFlat(value: String) = _state.update { it.copy(maxFeeFlatSats = value, formError = null) }

    fun setSendTimeout(value: String) = _state.update { it.copy(sendTimeout = value, formError = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** No prompt host, and the user asked for spends to be confirmed. */
    fun reportAuthUnavailable() = _state.update {
        it.copy(
            error = ApiException.Transport(
                "This device cannot show the confirmation prompt, so the invoice was not paid. " +
                    "Turn off “Confirm spends” in Settings if you want to pay without it.",
            ),
        )
    }

    fun reportAuthFailed() = _state.update {
        it.copy(error = ApiException.Transport("Not authenticated, so the invoice was not paid."))
    }

    fun reset() = _state.update { LightningSendState() }

    /**
     * Explains an LNURL that has landed in the invoice field.
     *
     * It is the one thing this rail is handed that it cannot pay: [SendScreen]
     * sorts an address onto the on-chain form and an invoice needs no
     * explanation, but an LNURL is unambiguously Lightning and still not
     * something a node can be asked to pay directly. The code stays in the
     * field, where the sender can see what they pasted.
     *
     * Written after the field, not with it: [setBolt11] clears the notice, so a
     * notice set first would be wiped by the value it is about.
     */
    fun noteLnurl() = _state.update {
        it.copy(
            notice = "That is an LNURL code. This screen pays BOLT11 invoices only — " +
                "open the checkout page in a browser to pay an LNURL.",
        )
    }

    fun pay(unit: BitcoinUnit) {
        val snapshot = _state.value
        if (snapshot.sending) return
        // A scanned invoice may still carry the URI scheme; the API wants the bare form.
        val bolt11 = snapshot.bolt11.trim().removePrefix("lightning:").removePrefix("LIGHTNING:")
        if (bolt11.isEmpty()) {
            _state.update { it.copy(formError = "Paste or scan an invoice first.") }
            return
        }

        val scope = graph.lightningScopeOrNull(serverNode) ?: run {
            _state.update { it.copy(noStore = true) }
            return
        }

        // Guarded by `needsAmount`, because the field is only on screen when
        // that is true.
        //
        // The amount survives in state after the field animates away, so an
        // invoice that names its own amount could otherwise be rejected
        // because of a stray character typed for a *previous*, amountless one
        // — with the complaint rendered against the invoice field, and the
        // field it actually refers to nowhere to be seen. The value is already
        // ignored when the invoice names an amount, four lines below; refusing
        // to send over it would be inconsistent.
        if (snapshot.needsAmount &&
            snapshot.amount.isNotBlank() &&
            Amounts.parse(snapshot.amount)?.signum() != 1
        ) {
            _state.update { it.copy(formError = "Enter a positive amount or leave it empty to use the invoice amount.") }
            return
        }
        // Only an amountless invoice takes an amount from this screen. Sending
        // one alongside an invoice that already names its amount is rejected by
        // the node, and the rejection reads like a routing failure.
        val amountMsat = snapshot.amount
            .takeIf { snapshot.needsAmount }
            ?.let(Amounts::parse)
            ?.takeIf { it.signum() > 0 }
            ?.let { entered ->
                val sats = if (unit == BitcoinUnit.Btc) Amounts.btcToSats(entered) else entered
                Amounts.satsToMsat(sats)
            }

        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null, formError = null, result = null) }
            runCatching {
                // The route is `/invoices/pay` — there is no bare `/pay`. BTCPay
                // answers 202 while the payment is still in flight, so a
                // non-terminal status in the response is the normal outcome and
                // must not be presented as a failure.
                graph.session.requireApi().payLightningInvoice(
                    scope = scope,
                    request = PayLightningInvoiceRequest(
                        BOLT11 = bolt11,
                        amount = amountMsat,
                        maxFeePercent = snapshot.maxFeePercent.trim().ifBlank { null },
                        maxFeeFlat = snapshot.maxFeeFlatSats.trim().ifBlank { null },
                        sendTimeout = snapshot.sendTimeout.trim().toIntOrNull(),
                    ),
                    cryptoCode = cryptoCode,
                )
            }.onSuccess { payment ->
                _state.update { it.copy(sending = false, result = payment, error = null) }
            }.onFailure { failure ->
                _state.update { it.copy(sending = false, error = failure.asApiException()) }
            }
        }
    }
}

/** The one documented failure worth rewording; everything else already reads well. */
private fun ApiException.paymentMessage(): String = when ((this as? ApiException.Server)?.code) {
    "could-not-find-route" ->
        "No route to that destination. The node could not find a path with enough liquidity — " +
            "try a smaller amount, or a higher fee limit."

    else -> userMessage
}

/**
 * The three things the pay button can be.
 *
 * A discriminator rather than the label, so that the spinner and the wording
 * cross-fade together as one control changing state — and so the outgoing half
 * is not re-rendered from a `sending` flag that has already flipped.
 */
private enum class SendButton { Idle, Expired, Sending }

/**
 * The Lightning half of the send form: everything below the shared destination
 * field, which is the invoice itself and lives in [SendScreen].
 *
 * The result of a payment is not here either. It replaces the whole screen
 * rather than the part of it below the invoice, so [SendScreen] renders it.
 */
@Composable
internal fun ColumnScope.LightningSendForm(
    viewModel: LightningSendViewModel,
    state: LightningSendState,
) {
    val settings = LocalSettings.current
    val uiScope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    // `Biometrics.authenticate` hosts a BiometricPrompt, which needs a
    // FragmentActivity. MainActivity is one; if the cast ever fails the payment
    // still goes through the confirmation dialog rather than being blocked, so
    // an unusual host cannot make the screen unusable.
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }

    val decoded = state.decoded
    val expired = decoded?.isExpired() == true

    val notice = rememberLast(state.notice)
    AnimatedVisibility(
        visible = state.notice != null,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::dismissNotice) { Text("Dismiss") }
        }
    }

    // The preview opens under the field as the invoice decodes
    // and closes again when the field is cleared. A card that
    // appeared between two frames would shift everything below
    // it by its own height, which is exactly when the reader is
    // looking for what they just pasted.
    val preview = rememberLast(decoded)
    AnimatedVisibility(
        visible = decoded != null,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        preview?.let {
            InvoicePreview(
                invoice = it,
                unit = settings.bitcoinUnit,
                nicknames = settings.lightningNodeNicknames,
            )
        }
    }

    // Only an amountless invoice needs this, so it stays off the
    // screen for the ordinary case where the invoice decides.
    AnimatedVisibility(
        visible = state.needsAmount,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        FormField(
            label = if (settings.bitcoinUnit == BitcoinUnit.Btc) "Amount (BTC)" else "Amount (sat)",
            value = state.amount,
            onValueChange = viewModel::setAmount,
            supportingText = if (decoded != null) {
                "This invoice leaves the amount to you."
            } else {
                "Only used when the invoice carries no amount."
            },
            enabled = !state.sending,
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        )
    }

    AdvancedSection(
        state = state,
        expanded = advancedOpen,
        onToggle = { advancedOpen = !advancedOpen },
        onMaxFeePercent = viewModel::setMaxFeePercent,
        onMaxFeeFlat = viewModel::setMaxFeeFlat,
        onSendTimeout = viewModel::setSendTimeout,
    )

    FormProblem(state.error?.paymentMessage())

    Button(
        onClick = { confirming = true },
        enabled = !state.sending && state.bolt11.isNotBlank() && !expired,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        val button = when {
            state.sending -> SendButton.Sending
            expired -> SendButton.Expired
            else -> SendButton.Idle
        }
        AnimatedSwap(button, label = "pay") { shownButton ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shownButton == SendButton.Sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    when (shownButton) {
                        SendButton.Sending -> "Paying…"
                        SendButton.Expired -> "Invoice expired"
                        SendButton.Idle -> "Pay invoice"
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(32.dp))

    if (confirming) {
        ConfirmDialog(
            title = "Pay this invoice?",
            message = confirmationMessage(
                decoded = decoded,
                typedAmount = state.amount.takeIf { state.needsAmount },
                unit = settings.bitcoinUnit,
                nicknames = settings.lightningNodeNicknames,
            ),
            confirmLabel = "Pay",
            onConfirm = {
                confirming = false
                uiScope.launch {
                    // Fails closed, as the on-chain half does. Reading it as
                    // `confirmSpends && activity != null` would let a cast that
                    // came back null pay the invoice with no prompt at all
                    // despite the user having switched the control on — and a
                    // Lightning payment is gone the moment it settles.
                    if (!settings.confirmSpendsWithBiometrics) {
                        viewModel.pay(settings.bitcoinUnit)
                        return@launch
                    }
                    if (activity == null) {
                        viewModel.reportAuthUnavailable()
                        return@launch
                    }
                    when (Biometrics.prompt(
                        activity = activity,
                        title = "Confirm payment",
                        subtitle = "Authenticate to send this Lightning payment",
                    )) {
                        is AuthOutcome.Success -> viewModel.pay(settings.bitcoinUnit)
                        // Dismissing is a deliberate "no"; say nothing.
                        is AuthOutcome.Cancelled -> Unit
                        else -> viewModel.reportAuthFailed()
                    }
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * What the invoice says, before any of it is paid.
 *
 * Read on the device rather than asked of the server: the alternative discloses
 * the payee and the amount to the server before the operator has decided
 * whether to go ahead, and leaves this card blank whenever the node is down.
 */
@Composable
private fun InvoicePreview(
    invoice: Bolt11Invoice,
    unit: BitcoinUnit,
    nicknames: Map<String, String>,
) {
    val expired = remember(invoice) { invoice.isExpired() }
    val payee = invoice.payeeNode?.let { NodeDirectory.label(it, nicknames) }

    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (invoice.isAmountless) {
                    "Any amount"
                } else {
                    msatLabel(invoice.amountMsat?.toString(), unit)
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            invoice.description?.let { description ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                )
            }

            // Only shown when the invoice carried the optional `n` field and
            // the pubkey resolves to a name. An unnamed key adds a row of hex
            // that tells the reader nothing they can act on.
            payee?.takeIf { NodeDirectory.isNamed(invoice.payeeNode.orEmpty(), nicknames) }
                ?.let { name ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "To $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (expired) {
                    "Expired ${Dates.relative(invoice.expiresAt)} — ask for a new invoice"
                } else {
                    "Expires ${Dates.relative(invoice.expiresAt)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (expired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun AdvancedSection(
    state: LightningSendState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMaxFeePercent: (String) -> Unit,
    onMaxFeeFlat: (String) -> Unit,
    onSendTimeout: (String) -> Unit,
) {
    ExpandableSection(
        title = "Fee limit and timeout",
        summary = advancedSummary(state),
        expanded = expanded,
        onToggle = onToggle,
    ) {
        FormField(
            label = "Maximum fee (%)",
            value = state.maxFeePercent,
            onValueChange = onMaxFeePercent,
            placeholder = DEFAULT_MAX_FEE_PERCENT,
            supportingText = "Of the amount being sent. Empty leaves it to the node.",
            enabled = !state.sending,
            keyboardType = KeyboardType.Decimal,
        )
        FormField(
            label = "Maximum fee (sat)",
            value = state.maxFeeFlatSats,
            onValueChange = onMaxFeeFlat,
            placeholder = "No flat cap",
            supportingText = "A flat ceiling on top of the percentage. Usually best left empty.",
            enabled = !state.sending,
            keyboardType = KeyboardType.Number,
        )
        FormField(
            label = "Timeout (seconds)",
            value = state.sendTimeout,
            onValueChange = onSendTimeout,
            placeholder = DEFAULT_SEND_TIMEOUT_SECONDS,
            supportingText = "How long the node keeps trying before giving up.",
            enabled = !state.sending,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )
    }
}

/** The collapsed row still has to say what is in force underneath it. */
private fun advancedSummary(state: LightningSendState): String {
    val fee = when {
        state.maxFeePercent.isNotBlank() && state.maxFeeFlatSats.isNotBlank() ->
            "at most ${state.maxFeePercent.trim()}% and ${state.maxFeeFlatSats.trim()} sat"

        state.maxFeePercent.isNotBlank() -> "at most ${state.maxFeePercent.trim()}%"
        state.maxFeeFlatSats.isNotBlank() -> "at most ${state.maxFeeFlatSats.trim()} sat"
        else -> "the node's own fee limit"
    }
    val timeout = state.sendTimeout.trim().toIntOrNull()
    return if (timeout != null) "$fee, giving up after ${timeout}s" else fee
}

private fun confirmationMessage(
    decoded: Bolt11Invoice?,
    typedAmount: String?,
    unit: BitcoinUnit,
    nicknames: Map<String, String>,
): String = buildString {
    val amount = when {
        decoded?.amountMsat != null -> msatLabel(decoded.amountMsat.toString(), unit)
        !typedAmount.isNullOrBlank() -> "$typedAmount ${if (unit == BitcoinUnit.Btc) "BTC" else "sat"}"
        else -> null
    }
    if (amount != null) append("Paying $amount") else append("Paying this invoice")

    decoded?.payeeNode
        ?.takeIf { NodeDirectory.isNamed(it, nicknames) }
        ?.let { append(" to ${NodeDirectory.label(it, nicknames)}") }

    decoded?.description?.takeIf { it.isNotBlank() }?.let { append(" for “${it.take(60)}”") }
    append(". The payment leaves the node straight away and cannot be reversed.")
}

@Composable
internal fun PaymentResult(
    payment: LightningPaymentData,
    unit: BitcoinUnit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Only for a payment the node has actually settled. A tick over a
            // payment still in flight would be a lie, and the one over a failed
            // one an insult.
            if (payment.status == LightningPaymentStatus.Complete) {
                SuccessCheck(size = 64.dp)
                Spacer(Modifier.height(16.dp))
            }
            PaymentStatusPill(payment.status)
            Spacer(Modifier.height(12.dp))
            Text(
                text = msatLabel(payment.totalAmount, unit),
                modifier = Modifier.arrive(1),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (payment.status == LightningPaymentStatus.Pending) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Still in flight. The node will keep trying until it succeeds or times out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp).arrive(2),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        AppCard(modifier = Modifier.arrive(3)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                DetailRow("Fee paid", msatLabel(payment.feeAmount, unit))
                payment.createdAt?.let { DetailRow("Sent", Dates.full(it)) }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp).arrive(4)) {
            Spacer(Modifier.height(8.dp))
            CopyableField(label = "Payment hash", value = payment.paymentHash, truncate = true)
            payment.preimage?.takeIf { it.isNotBlank() }?.let { preimage ->
                Spacer(Modifier.height(12.dp))
                // The preimage is the receipt for this payment; treat it as a
                // secret on the clipboard even though it is not a credential.
                CopyableField(label = "Preimage", value = preimage, truncate = true, sensitive = true)
            }
        }

        Spacer(Modifier.height(24.dp))
        // Last, and by a longer beat than the lines above: these are the one
        // thing on this screen that can be pressed by mistake.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(6),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("Pay another") }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
        Spacer(Modifier.height(32.dp))
    }
}
