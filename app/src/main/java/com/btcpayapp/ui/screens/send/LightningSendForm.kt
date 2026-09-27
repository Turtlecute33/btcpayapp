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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.lightning.Bolt11
import com.btcpayapp.core.lightning.Bolt11Invoice
import com.btcpayapp.core.lightning.NodeDirectory
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.mayHaveGoneThrough
import com.btcpayapp.data.api.dto.LightningPaymentData
import com.btcpayapp.data.api.dto.LightningPaymentStatus
import com.btcpayapp.data.api.dto.PayLightningInvoiceRequest
import com.btcpayapp.data.api.endpoints.lightningPayment
import com.btcpayapp.data.api.endpoints.payLightningInvoice
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.data.session.OutcomeHold
import com.btcpayapp.ui.LocalIsLocked
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ExpandableSection
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.SpendAuth
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.screens.lightning.PaymentStatusPill
import com.btcpayapp.ui.screens.lightning.LightningBinding
import com.btcpayapp.ui.screens.lightning.msatLabel
import com.btcpayapp.ui.screens.wallet.parseAmountToBtc
import com.btcpayapp.ui.screens.wallet.unitLabel
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

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

/**
 * The attempt timeout the field accepts. The server holds the answer for this
 * long, and the pay call waits 30 s more (`payLightningInvoice`), so the upper
 * bound keeps a sender from watching a spinner for many minutes.
 */
private val SEND_TIMEOUT_RANGE = 10..120

/** Above this, the fee field says what the percentage can cost. */
private val HIGH_FEE_PERCENT = BigDecimal(5)
private val HUNDRED = BigDecimal(100)

/** Keys of [buildPayRequest]'s field errors: the request's own field names. */
internal const val FIELD_AMOUNT = "amount"
internal const val FIELD_MAX_FEE_PERCENT = "maxFeePercent"
internal const val FIELD_MAX_FEE_FLAT = "maxFeeFlat"
internal const val FIELD_SEND_TIMEOUT = "sendTimeout"
private val ADVANCED_FIELDS = listOf(FIELD_MAX_FEE_PERCENT, FIELD_MAX_FEE_FLAT, FIELD_SEND_TIMEOUT)

/** How often a payment of unknown outcome is looked up. */
private const val TRACK_INTERVAL_MS = 5_000L

/** Lookups in a row that must find nothing before "not sent" is said. */
private const val NOT_FOUND_TO_GIVE_UP = 3

/** The Greenfield code for a payment hash the node has no record of. */
private const val PAYMENT_NOT_FOUND = "payment-not-found"

private val LIGHTNING_ADDRESS = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** [text] without the `lightning:` scheme, in any case, and without spaces around it. */
internal fun bareInvoice(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.startsWith("lightning:", ignoreCase = true)) {
        trimmed.substring("lightning:".length).trim()
    } else {
        trimmed
    }
}

/**
 * Why [text] cannot be paid from this screen, or null when it can (or is
 * still empty).
 *
 * Pay needs an invoice the phone itself has read. The confirmation shows the
 * amount from that reading (and the payee, when the invoice names one), and a
 * payment whose outcome is lost is tracked by the payment hash in it. An invoice the phone cannot read
 * would be confirmed blind and could not be tracked.
 */
internal fun unpayableReason(text: String, decoded: Bolt11Invoice?): String? {
    val bare = bareInvoice(text)
    return when {
        bare.isEmpty() -> null
        bare.startsWith("lnurl", ignoreCase = true) ->
            "That is an LNURL code. This screen pays BOLT11 invoices only. " +
                "Open the checkout page in a browser to pay it."

        LIGHTNING_ADDRESS.matches(bare) ->
            "That is a Lightning address. This screen pays BOLT11 invoices only."

        decoded == null || decoded.paymentHash == null || (decoded.amountMsat?.bitLength() ?: 0) > 62 ->
            "This invoice could not be read on the phone, so it cannot be paid safely here."

        else -> null
    }
}

/**
 * The pay request for what was typed, or the field errors that stop it.
 *
 * [invoiceMsat] is the invoice's own amount, null for an invoice that leaves
 * it open: only then is an amount sent, and then one is required, because the
 * confirmation must name what leaves. Every number is parsed with the app's
 * one amount rule and sent in plain '.' form. Sent as typed, a German "0,5" %
 * could reach the server as 5 %, and a lone thousands separator could
 * pay a thousandth of the confirmed amount.
 */
internal fun buildPayRequest(
    bolt11: String,
    invoiceMsat: Long?,
    amountText: String,
    unit: BitcoinUnit,
    maxFeePercentText: String,
    sendTimeoutText: String,
    maxFeeFlatText: String = "",
): Pair<PayLightningInvoiceRequest?, Map<String, String>> {
    val errors = LinkedHashMap<String, String>()

    val amountMsat = if (invoiceMsat != null) {
        null
    } else {
        val btc = parseAmountToBtc(amountText, unit)?.takeIf { it.signum() > 0 }
        // amountProblem is null only for blank text here.
        if (btc == null) errors[FIELD_AMOUNT] = amountProblem(amountText, unit) ?: "Enter the amount to pay."
        btc?.let { Amounts.satsToMsat(Amounts.btcToSats(it)) }
    }

    val maxFeePercent = maxFeePercentText.takeIf { it.isNotBlank() }?.let { text ->
        val value = feePercentOf(text)?.toPlainString()
        if (value == null) errors[FIELD_MAX_FEE_PERCENT] = "Enter a percentage from 0 to 100."
        value
    }

    val maxFeeFlat = maxFeeFlatText.takeIf { it.isNotBlank() }?.let { text ->
        val value = flatFeeOf(text)?.toPlainString()
        if (value == null) errors[FIELD_MAX_FEE_FLAT] = "Enter a whole number of sats, or leave it empty."
        value
    }

    val sendTimeout = sendTimeoutOf(sendTimeoutText)
    if (sendTimeout == null) {
        errors[FIELD_SEND_TIMEOUT] =
            "Enter a whole number of seconds from ${SEND_TIMEOUT_RANGE.first} to ${SEND_TIMEOUT_RANGE.last}."
    }

    if (errors.isNotEmpty()) return null to errors
    return PayLightningInvoiceRequest(
        BOLT11 = bolt11,
        amount = amountMsat,
        maxFeePercent = maxFeePercent,
        maxFeeFlat = maxFeeFlat,
        sendTimeout = sendTimeout,
    ) to emptyMap()
}

private fun feePercentOf(text: String): BigDecimal? =
    Amounts.parse(text)?.takeIf { it.signum() >= 0 && it <= HUNDRED }

private fun flatFeeOf(text: String): BigDecimal? =
    Amounts.parse(text)?.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 }?.stripTrailingZeros()

/** Blank means the default, which is sent too: the pay call's own wait is sized from it. */
private fun sendTimeoutOf(text: String): Int? =
    if (text.isBlank()) DEFAULT_SEND_TIMEOUT_SECONDS.toInt() else text.trim().toIntOrNull()?.takeIf { it in SEND_TIMEOUT_RANGE }

/**
 * The most the node may spend on routing [amountMsat], in sat, or null when
 * the request leaves it to the node's own default.
 *
 * An upper bound on purpose. Nodes combine the two limits differently (LND
 * uses the flat one when set, Core Lightning allows the larger), so the larger
 * of the two is the one figure true for both. A zero limit counts as not set:
 * LND ignores it and uses its own default, so "0 sat" would understate what
 * the node may spend.
 */
internal fun feeLimitSats(amountMsat: Long, maxFeePercent: String?, maxFeeFlat: String?): Long? {
    val byPercent = maxFeePercent?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { percent ->
        BigDecimal(amountMsat).multiply(percent).divide(BigDecimal(100_000), 0, RoundingMode.CEILING).toLong()
    }
    val flat = maxFeeFlat?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.toLong()
    return listOfNotNull(byPercent, flat).maxOrNull()
}

/**
 * A payment whose outcome the pay call did not give: the node answered
 * "pending" or "unknown", or the answer was lost. [paymentHash] is the one the
 * phone read from the invoice, so the lookup does not depend on the answer
 * that went missing.
 */
data class LightningTracking(
    val paymentHash: String,
    /** The node said it is still trying, as opposed to nobody knowing. */
    val inFlight: Boolean,
    /**
     * The node has answered with this payment at least once. After that, "no
     * such payment" contradicts the node's own answer instead of showing that
     * the payment never started, so it is never read as "not sent".
     */
    val seen: Boolean,
    /** Lookups in a row where the node said it has no such payment. */
    val notFound: Int = 0,
) {
    /**
     * Only a payment the node never showed, and then said [NOT_FOUND_TO_GIVE_UP]
     * times in a row that it does not have, counts as not sent. An error in
     * between breaks the row, and an empty 404 is not that answer.
     */
    val notSent: Boolean get() = !seen && notFound >= NOT_FOUND_TO_GIVE_UP

    /** This tracking after one lookup that did not settle it: the [payment] read, or the [error] instead. */
    fun after(payment: LightningPaymentData?, error: ApiException?): LightningTracking = when {
        payment != null -> copy(inFlight = payment.status == LightningPaymentStatus.Pending, seen = true, notFound = 0)
        error is ApiException.NotFound && error.code == PAYMENT_NOT_FOUND -> copy(inFlight = false, notFound = notFound + 1)
        else -> copy(notFound = 0)
    }
}

data class LightningSendState(
    val bolt11: String = "",
    val amount: String = "",
    val maxFeePercent: String = DEFAULT_MAX_FEE_PERCENT,
    val maxFeeFlatSats: String = "",
    val sendTimeout: String = DEFAULT_SEND_TIMEOUT_SECONDS,
    val sending: Boolean = false,
    /** A settled answer: complete or failed. */
    val result: LightningPaymentData? = null,
    val tracking: LightningTracking? = null,
    /** Shown above Pay: a refusal from the node, a refused confirmation, or a lookup that found nothing. */
    val problem: String? = null,
    val noStore: Boolean = false,
) {
    /** What is sent as `BOLT11`. */
    val invoice: String get() = bareInvoice(bolt11)

    /**
     * What the pasted invoice says, read on the device. Null when the field is
     * empty or holds something that is not a BOLT11 invoice.
     *
     * `by lazy` rather than a getter: this is read from composition as well as
     * from [LightningSendViewModel.pay], and a bech32 pass per read would run
     * on every recomposition of every unrelated field on the screen. A new
     * state instance decodes once and then remembers.
     */
    val decoded: Bolt11Invoice? by lazy { Bolt11.decode(invoice) }

    /** See [unpayableReason]. */
    val unpayable: String? by lazy { unpayableReason(bolt11, decoded) }

    /** The invoice's own amount; [unpayable] refuses one too large for a Long. */
    val invoiceMsat: Long? get() = decoded?.amountMsat?.takeIf { it.bitLength() <= 62 }?.toLong()

    /**
     * The amount field only means anything for an invoice that leaves the
     * amount open. An invoice that names its own amount is not negotiable, and
     * sending a different one is an error rather than a discount — so the field
     * is hidden instead of ignored.
     */
    val needsAmount: Boolean get() = decoded?.isAmountless == true

    /**
     * The request for this state. The invoice goes in lower case, the form
     * [Bolt11.decode] read: bech32 ignores case, so it is the same invoice,
     * and the server then parses exactly what the confirmation was built from.
     */
    fun payRequest(unit: BitcoinUnit): Pair<PayLightningInvoiceRequest?, Map<String, String>> = buildPayRequest(
        invoice.lowercase(Locale.ROOT), invoiceMsat, amount, unit, maxFeePercent, sendTimeout, maxFeeFlatSats,
    )
}

class LightningSendViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    // Fixed once known: the shell closes this screen when the store changes,
    // so the pay and every lookup after it go to the node the form was opened on.
    private val node = LightningBinding(graph.session, serverNode)

    /**
     * Who pays, for the confirmation: the store's name, or the server's own
     * node. A multi-store operator must see which node is about to pay.
     */
    val payer: String get() = node.owner

    // A store or account switch drops this screen and its view model, and with
    // them the payment hash being tracked. So no switch from the pay until the
    // sender is done with the result. Held here, not in the composable: a tab
    // switch or a screen pushed on top hides the result but does not end it.
    private val outcomeHold = OutcomeHold(graph.session).also(::addCloseable)

    private val _state = MutableStateFlow(LightningSendState(noStore = node.scope == null))
    val state = _state.asStateFlow()

    /**
     * Every state change goes through here, so the hold follows the state. It
     * starts with the pay request, so there is no moment between the request's
     * end and the hold.
     */
    private fun updateState(transform: (LightningSendState) -> LightningSendState) {
        val next = _state.updateAndGet(transform)
        outcomeHold.set(next.sending || next.tracking != null || next.result != null)
    }

    init {
        node.retryWhenKnown(viewModelScope) { updateState { it.copy(noStore = false) } }
    }

    /** Ignored while a payment is running or being tracked: the form is not on screen then. */
    private fun edit(transform: (LightningSendState) -> LightningSendState) = updateState {
        if (it.sending || it.tracking != null) it else transform(it).copy(problem = null)
    }

    fun setBolt11(value: String) = edit { it.copy(bolt11 = value) }

    fun setAmount(value: String) = edit { it.copy(amount = value) }

    fun setMaxFeePercent(value: String) = edit { it.copy(maxFeePercent = value) }

    fun setMaxFeeFlat(value: String) = edit { it.copy(maxFeeFlatSats = value) }

    fun setSendTimeout(value: String) = edit { it.copy(sendTimeout = value) }

    /** A refused confirmation ([SpendAuth.Refused]). */
    fun showProblem(message: String) = updateState { it.copy(problem = message) }

    fun reset() = edit { LightningSendState(noStore = node.scope == null) }

    /**
     * Pays the invoice in the field, as the confirmation showed it.
     *
     * Runs as a payment in flight, so a store switch cannot cancel it and lose
     * the answer. An answer that is not final, or no answer at all, goes to
     * tracking rather than to an error with a live Pay button: the
     * node may still be routing, and the sender must not pay again by another
     * way while it is.
     */
    fun pay(unit: BitcoinUnit) {
        val snapshot = _state.value
        if (snapshot.sending || snapshot.tracking != null || snapshot.result != null) return
        val scope = node.scope ?: run {
            updateState { it.copy(noStore = true) }
            return
        }
        val paymentHash = snapshot.decoded?.paymentHash
        if (snapshot.unpayable != null || paymentHash == null) return
        val request = snapshot.payRequest(unit).first ?: return

        updateState { it.copy(sending = true, problem = null) }
        viewModelScope.launch {
            runCatching {
                graph.session.spending { graph.session.requireApi().payLightningInvoice(scope, request, cryptoCode) }
            }.onSuccess { payment ->
                updateState {
                    when (payment.status) {
                        LightningPaymentStatus.Complete, LightningPaymentStatus.Failed ->
                            it.copy(sending = false, result = payment)

                        LightningPaymentStatus.Pending, LightningPaymentStatus.Unknown -> it.copy(
                            sending = false,
                            tracking = LightningTracking(
                                paymentHash,
                                inFlight = payment.status == LightningPaymentStatus.Pending,
                                seen = true,
                            ),
                        )
                    }
                }
            }.onFailure { failure ->
                val error = failure.asApiException()
                updateState {
                    if (error.mayHavePaid()) {
                        it.copy(sending = false, tracking = LightningTracking(paymentHash, inFlight = false, seen = false))
                    } else {
                        it.copy(sending = false, problem = error.paymentMessage())
                    }
                }
            }
        }
    }

    /**
     * Looks the tracked payment up every [TRACK_INTERVAL_MS] until it is final,
     * or until [LightningTracking.notSent].
     *
     * Called from the screen under `repeatOnLifecycle`, so it stops while the
     * app is in the background or locked.
     */
    suspend fun track() {
        val scope = node.scope ?: return
        while (true) {
            val tracking = _state.value.tracking ?: return
            val outcome = runCatching {
                graph.session.requireApi().lightningPayment(scope, tracking.paymentHash, cryptoCode)
            }
            val payment = outcome.getOrNull()
            if (payment?.status == LightningPaymentStatus.Complete || payment?.status == LightningPaymentStatus.Failed) {
                updateState { it.copy(tracking = null, result = payment) }
                return
            }
            val next = tracking.after(payment, outcome.exceptionOrNull()?.asApiException())
            if (next.notSent) {
                updateState {
                    it.copy(
                        tracking = null,
                        problem = "No payment was found for this invoice, so it was not sent. You can pay again.",
                    )
                }
                return
            }
            updateState { it.copy(tracking = next) }
            delay(TRACK_INTERVAL_MS)
        }
    }
}

/**
 * Whether the node may have paid despite this failure, so the payment must be
 * tracked rather than offered again: [mayHaveGoneThrough], and also a timeout
 * or an answer this app could not read. The server asks the node for the
 * payment after paying, and that lookup can fail after the money left.
 */
private fun ApiException.mayHavePaid(): Boolean =
    mayHaveGoneThrough() || this is ApiException.Timeout || this is ApiException.Decoding

/** The one documented failure worth rewording; everything else already reads well. */
private fun ApiException.paymentMessage(): String = when ((this as? ApiException.Server)?.code) {
    "could-not-find-route" ->
        "No route to that destination. The node could not find a path with enough liquidity — " +
            "try a smaller amount, or a higher fee limit."

    else -> userMessage
}

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
    val unit = settings.bitcoinUnit
    val uiScope = rememberCoroutineScope()
    // Supplies the activity for the prompt, and refuses when there is none.
    val gate = rememberSpendGate()
    // Not saveable. The view model and its invoice do not come back after
    // process death, so a saved "open" would open the dialog for the next
    // invoice pasted, with no Pay tap for it.
    var confirming by remember { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    val decoded = state.decoded
    val built = remember(state, unit) { state.payRequest(unit) }
    val request = built.first
    val errors = built.second

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
                unit = unit,
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
        val typed = remember(state.amount, unit) { parseAmountToBtc(state.amount, unit)?.takeIf { it.signum() > 0 } }
        FormField(
            label = "Amount (${unitLabel(unit)})",
            value = state.amount,
            onValueChange = viewModel::setAmount,
            supportingText = typed?.let { "= ${Amounts.inOtherUnit(it, unit)}" }
                ?: "This invoice leaves the amount to you.",
            // Not while the field is still empty: that is the state it opens in.
            error = errors[FIELD_AMOUNT]?.takeIf { state.amount.isNotBlank() },
            enabled = !state.sending,
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        )
    }

    AdvancedSection(
        state = state,
        errors = errors,
        expanded = advancedOpen,
        onToggle = { advancedOpen = !advancedOpen },
        onMaxFeePercent = viewModel::setMaxFeePercent,
        onMaxFeeFlat = viewModel::setMaxFeeFlat,
        onSendTimeout = viewModel::setSendTimeout,
    )

    FormProblem(state.unpayable ?: state.problem)

    Button(
        onClick = { confirming = true },
        // An invoice that looks expired stays payable: the phone's clock can
        // be wrong, and the node refuses a truly expired one anyway. The
        // preview and the confirmation both say it.
        enabled = !state.sending && state.unpayable == null && decoded != null && request != null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        // The spinner and the wording cross-fade together as one control
        // changing state.
        AnimatedSwap(state.sending, label = "pay") { sending ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(12.dp))
                }
                Text(if (sending) "Paying…" else "Pay invoice")
            }
        }
    }

    Spacer(Modifier.height(32.dp))

    if (confirming && request != null && decoded != null) {
        val amountMsat = state.invoiceMsat ?: request.amount?.toLongOrNull() ?: 0L
        val amount = Amounts.formatMsat(amountMsat.toString(), unit)
        val from = " from ${viewModel.payer}"
        ConfirmDialog(
            title = "Pay this invoice?",
            message = confirmationMessage(
                amountMsat = amountMsat,
                from = from,
                payee = decoded.payeeNode?.let {
                    NodeDirectory.trustedName(it, settings.lightningNodeNicknames) ?: NodeDirectory.short(it)
                },
                description = decoded.description,
                feeLimitSats = feeLimitSats(amountMsat, request.maxFeePercent, request.maxFeeFlat),
                expired = decoded.isExpired(),
                unit = unit,
            ),
            confirmLabel = "Pay",
            onConfirm = {
                confirming = false
                uiScope.afterSpendGate(gate, "Confirm payment", "Pay $amount$from", viewModel::showProblem) {
                    viewModel.pay(unit)
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
    val payee = invoice.payeeNode
    // A nickname or a curated name is one the app can stand behind. A name
    // from the bundled table was chosen by the node's owner, so it is shown
    // as a claim, never as the payee.
    val trusted = payee?.let { NodeDirectory.trustedName(it, nicknames) }
    val claimed = payee?.takeIf { trusted == null }?.let(NodeDirectory::wellKnownName)

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
            val payeeLine = trusted?.let { "To $it" } ?: claimed?.let { "Calls itself “$it” (not verified)" }
            payeeLine?.let { line ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (expired) {
                    "Expired ${Dates.relative(invoice.expiresAt)} by this phone's clock — ask for a new invoice"
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
    errors: Map<String, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMaxFeePercent: (String) -> Unit,
    onMaxFeeFlat: (String) -> Unit,
    onSendTimeout: (String) -> Unit,
) {
    val highFee = feePercentOf(state.maxFeePercent)?.let { it > HIGH_FEE_PERCENT } == true
    ExpandableSection(
        title = "Fee limit and timeout",
        summary = advancedSummary(state, errors),
        // A bad value in here blocks Pay, so it has to be legible while the
        // section is shut.
        summaryIsError = ADVANCED_FIELDS.any { it in errors },
        expanded = expanded,
        onToggle = onToggle,
    ) {
        FormField(
            label = "Maximum fee (%)",
            value = state.maxFeePercent,
            onValueChange = onMaxFeePercent,
            placeholder = DEFAULT_MAX_FEE_PERCENT,
            supportingText = if (highFee) {
                "High: the node may spend up to this share of the amount on routing."
            } else {
                "Of the amount being sent. Empty leaves it to the node."
            },
            error = errors[FIELD_MAX_FEE_PERCENT],
            enabled = !state.sending,
            keyboardType = KeyboardType.Decimal,
        )
        FormField(
            label = "Maximum fee (sat)",
            value = state.maxFeeFlatSats,
            onValueChange = onMaxFeeFlat,
            placeholder = "No flat cap",
            supportingText = "A flat ceiling on top of the percentage. Usually best left empty.",
            error = errors[FIELD_MAX_FEE_FLAT],
            enabled = !state.sending,
            keyboardType = KeyboardType.Number,
        )
        FormField(
            label = "Timeout (seconds)",
            value = state.sendTimeout,
            onValueChange = onSendTimeout,
            placeholder = DEFAULT_SEND_TIMEOUT_SECONDS,
            supportingText = "How long the node keeps trying, from ${SEND_TIMEOUT_RANGE.first} " +
                "to ${SEND_TIMEOUT_RANGE.last} seconds.",
            error = errors[FIELD_SEND_TIMEOUT],
            enabled = !state.sending,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )
    }
}

/**
 * The collapsed row still has to say what is in force underneath it, and it
 * says it as the values will be sent ("0.5%", not the "0,5" typed).
 */
private fun advancedSummary(state: LightningSendState, errors: Map<String, String>): String {
    ADVANCED_FIELDS.firstNotNullOfOrNull { errors[it] }?.let { return it }
    val percent = feePercentOf(state.maxFeePercent)?.toPlainString()
    val flat = flatFeeOf(state.maxFeeFlatSats)?.toPlainString()
    val fee = when {
        percent != null && flat != null -> "at most $percent% and $flat sat"
        percent != null -> "at most $percent%"
        flat != null -> "at most $flat sat"
        else -> "the node's own fee limit"
    }
    val timeout = sendTimeoutOf(state.sendTimeout)
    return if (timeout != null) "$fee, giving up after ${timeout}s" else fee
}

/**
 * The confirmation text, from the values that will be sent.
 *
 * [from] is " from <store>" or " from the server node". The payee is a
 * name only when the app can stand behind it ([NodeDirectory.trustedName]),
 * else the shortened key. The description is the payee's own text, so it is
 * kept to one short line in quotes: an invoice must not be able to push the
 * amount or the fee limit off the dialog.
 */
private fun confirmationMessage(
    amountMsat: Long,
    from: String,
    payee: String?,
    description: String?,
    feeLimitSats: Long?,
    expired: Boolean,
    unit: BitcoinUnit,
): String = buildString {
    val btc = Amounts.msatToBtc(amountMsat.toString())
    append("Pay ${Amounts.formatBitcoin(btc, unit)} (${Amounts.inOtherUnit(btc, unit)})$from")
    payee?.let { append(" to $it") }
    description
        ?.let(::oneLine)
        ?.takeIf { it.isNotEmpty() }
        ?.let { append(" for “${if (it.length > 60) it.take(60) + "…" else it}”") }
    append(". Routing fee limit: ")
    append(feeLimitSats?.let { Amounts.formatBitcoin(Amounts.satsToBtc(BigDecimal(it)), BitcoinUnit.Sat) } ?: "the node's default limit")
    append(".")
    if (expired) append(" By this phone's clock the invoice has expired, so the node may refuse it.")
    append(" The payment cannot be reversed.")
}

private val SPACES = Regex(" {2,}")

/**
 * [text] as one plain line: every kind of line break or space becomes one
 * space, and control and direction-override characters go, so the payee's
 * text cannot reorder or push away the lines after it.
 */
internal fun oneLine(text: String): String = text
    .map { if (it.isWhitespace()) ' ' else it }
    .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    .joinToString("")
    .replace(SPACES, " ")
    .trim()

/**
 * While the outcome of a payment is not known.
 *
 * Replaces the form, so there is no Pay button to press a second time. The
 * lookup runs only while this is on screen and the app is unlocked, as the
 * checkout's does.
 */
@Composable
internal fun PaymentTracking(
    tracking: LightningTracking,
    onTrack: suspend () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = LocalIsLocked.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(tracking.paymentHash, locked, lifecycleOwner) {
        if (locked) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { onTrack() }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (tracking.inFlight) "Still in flight" else "Payment status unknown — do not pay again",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (tracking.inFlight) {
                "The node is still trying to pay. The result shows here when it is known."
            } else {
                "No clear answer came about this payment, so the app is checking with the node. " +
                    "The result shows here when it is known."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        CopyableField(label = "Payment hash", value = tracking.paymentHash, truncate = true)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        Spacer(Modifier.height(32.dp))
    }
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
            // failed one would be an insult.
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
