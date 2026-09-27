package com.btcpayapp.ui.screens.payout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.lightning.Bolt11
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.mayHaveGoneThrough
import com.btcpayapp.data.api.dto.CreatePayoutRequest
import com.btcpayapp.data.api.dto.PullPaymentData
import com.btcpayapp.data.api.endpoints.createPayout
import com.btcpayapp.data.api.endpoints.pullPayment
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.screens.wallet.cryptoCodeOf
import com.btcpayapp.ui.screens.wallet.formatAmountInput
import com.btcpayapp.ui.screens.send.amountProblem
import com.btcpayapp.data.session.OutcomeHold
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.screens.wallet.parseAmountToBtc
import com.btcpayapp.ui.screens.wallet.unitLabel
import java.math.BigDecimal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The amount to send the server for what was typed, or null when it is not a
 * positive amount.
 *
 * Greenfield reads a payout amount in the pull payment's currency when there
 * is one ([pullPaymentCurrency]), and in the method's crypto otherwise. Only
 * the second follows the user's sat/BTC choice: typed in sat mode, "50000" is
 * 0.0005 BTC, where it used to be sent as 50,000 BTC. In the pull payment's
 * currency no more decimals are taken than the review shows ([Amounts.inCurrency]).
 */
internal fun payoutAmount(
    text: String,
    unit: BitcoinUnit,
    pullPaymentCurrency: String?,
    cryptoCode: String = "BTC",
): BigDecimal? =
    if (pullPaymentCurrency != null) {
        Amounts.inCurrency(text, pullPaymentCurrency)
    } else {
        parseAmountToBtc(text, unit, cryptoCode)?.takeIf { it.signum() > 0 }
    }

/**
 * What a BOLT11 [destination] asks for, in the method's coin, when the server
 * would fill a blank amount with exactly that; else null.
 *
 * Greenfield takes a blank amount from the invoice only when there is no pull
 * payment or the pull payment is in the invoice's coin. In another currency
 * it claims all that is left of the pull payment instead, a figure this app
 * cannot show before it is sent, so there the amount must be typed.
 */
internal fun impliedInvoiceAmount(destination: String, cryptoCode: String, pullPaymentCurrency: String?): BigDecimal? {
    if (pullPaymentCurrency != null && !pullPaymentCurrency.equals(cryptoCode, ignoreCase = true)) return null
    val msat = Bolt11.decode(destination)?.amountMsat ?: return null
    return Amounts.msatToBtc(msat.toString()).takeIf { it.signum() > 0 }
}

/**
 * Why [text] is not a payout amount. In the method's coin this is the send
 * form's rule; in a pull payment's currency it is [Amounts.inCurrency]'s.
 */
private fun payoutAmountProblem(text: String, unit: BitcoinUnit, pullPaymentCurrency: String?, cryptoCode: String): String =
    when {
        text.isBlank() -> "Enter an amount."
        pullPaymentCurrency == null -> amountProblem(text, unit, cryptoCode)
        else -> Amounts.inCurrencyProblem(text, pullPaymentCurrency)
    } ?: "Enter an amount greater than zero."

/** What the review shows, and exactly what is then sent: nothing is read from the form after it. */
data class PayoutReview(
    val destination: String,
    /** In [currency]. */
    val amount: BigDecimal,
    val currency: String,
    /**
     * [amount] is the BOLT11 destination's own, decoded here so the review can
     * show it. It is not sent: the server reads the same figure from the invoice.
     */
    val fromInvoice: Boolean,
    val payoutMethodId: String,
    val pullPaymentId: String?,
    val approved: Boolean,
)

data class PayoutCreateState(
    val destination: String = "",
    val amount: String = "",
    val methods: List<String> = emptyList(),
    val selectedMethod: String? = null,
    val approveImmediately: Boolean = false,
    val pullPaymentId: String = "",
    /** The pull payment [pullPaymentId] names, once read. */
    val pullPayment: PullPaymentData? = null,
    val pullPaymentError: String? = null,
    val review: PayoutReview? = null,
    val submitting: Boolean = false,
    val created: Boolean = false,
    /** A create may have been made ([mayHaveGoneThrough]). Stays set: a second try could be a second payout. */
    val outcomeUnknown: Boolean = false,
    val destinationError: String? = null,
    val amountError: String? = null,
    val scanNote: String? = null,
    val message: String? = null,
) {
    /**
     * The currency the amount field is in: the pull payment's when an id is
     * set, else the method's crypto. Null while the id is set but its pull
     * payment is not read yet, because then no one knows what "10" means.
     */
    val amountCurrency: String?
        get() = if (pullPaymentId.isBlank()) {
            selectedMethod?.let(::cryptoCodeOf) ?: "BTC"
        } else {
            pullPayment?.currency
        }
}

class PayoutCreateViewModel(private val graph: AppGraph) : ViewModel() {

    /** The store this screen was opened in (see [StoreBinding]). */
    private val bound = StoreBinding(graph.session)

    /** Whose funds the payout claims, for the review and the prompt. */
    val storeName: String get() = bound.name

    private val _state = MutableStateFlow(PayoutCreateState())
    val state = _state.asStateFlow()

    /**
     * Held while [PayoutCreateState.outcomeUnknown] is on screen, so a store or
     * account switch cannot close it before it is read: a second try could make
     * a second payout. Owned here and not by the composable, so a tab switch or
     * a screen on top does not release it. It ends when this screen is closed.
     */
    private val outcomeHold = OutcomeHold(graph.session).also(::addCloseable)

    /** Changes the state and the hold together. Use it for every change that can set [PayoutCreateState.outcomeUnknown]. */
    private fun edit(transform: (PayoutCreateState) -> PayoutCreateState) =
        outcomeHold.set(_state.updateAndGet(transform).outcomeUnknown)

    private val unit: BitcoinUnit get() = graph.settings.settings.value.bitcoinUnit

    private var pullPaymentRead: Job? = null

    init {
        viewModelScope.launch {
            graph.session.paymentMethods.collectLatest {
                val offered = offeredPayoutMethodIds(graph.session)
                _state.update { current ->
                    current.copy(
                        methods = offered,
                        selectedMethod = current.selectedMethod?.takeIf { it in offered }
                            ?: offered.firstOrNull(),
                    )
                }
            }
        }
    }

    fun setDestination(value: String) =
        _state.update { it.copy(destination = value, destinationError = null, scanNote = null) }

    fun setAmount(value: String) = _state.update { it.copy(amount = value, amountError = null) }

    fun selectMethod(id: String) = _state.update { it.copy(selectedMethod = id, amountError = null) }

    fun setApproveImmediately(value: Boolean) = _state.update { it.copy(approveImmediately = value) }

    fun setPullPaymentId(value: String) {
        val id = value.trim()
        val changed = _state.value.pullPaymentId.trim() != id
        _state.update {
            if (changed) {
                it.copy(pullPaymentId = value, pullPayment = null, pullPaymentError = null, amountError = null)
            } else {
                it.copy(pullPaymentId = value)
            }
        }
        if (changed) readPullPayment(id, pauseMs = 500)
    }

    /**
     * Reads the pull payment [id] names, for its currency. After [pauseMs], so
     * typing an id is one request and not one per key. An answer for an id
     * that is no longer in the field is dropped. The last failure is cleared
     * as a new read starts, so it never stands in front of a later answer.
     */
    private fun readPullPayment(id: String, pauseMs: Long = 0) {
        pullPaymentRead?.cancel()
        if (id.isEmpty()) return
        _state.update { it.copy(pullPaymentError = null) }
        pullPaymentRead = viewModelScope.launch {
            delay(pauseMs)
            runCatching { graph.session.requireApi().pullPayment(id) }
                .onSuccess { data ->
                    _state.update { if (it.pullPaymentId.trim() == id) it.copy(pullPayment = data) else it }
                }
                .onFailure { failure ->
                    val error = failure.asApiException()
                    val text = if (error is ApiException.NotFound) "No pull payment has this id." else error.userMessage
                    _state.update { if (it.pullPaymentId.trim() == id) it.copy(pullPaymentError = text) else it }
                }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun reportMessage(text: String) = _state.update { it.copy(message = text) }

    fun dismissReview() = _state.update { it.copy(review = null) }

    /**
     * Applies a scan. The rail is decided by what was scanned rather than left
     * to the user, because a BOLT11 invoice on the on-chain method is rejected
     * only after a round trip.
     */
    fun applyScan(raw: String) {
        when (val payload = ScanParser.parse(raw)) {
            is ScannedPayload.BitcoinAddress -> useDestination(payload.address, lightning = false)

            is ScannedPayload.Bip21 -> {
                val invoice = payload.lightning
                if (payload.address.isBlank() && invoice != null) {
                    // `bitcoin:?lightning=…` has no address; its invoice is the
                    // destination.
                    useDestination(invoice, lightning = true)
                } else {
                    useDestination(payload.address, lightning = false, amountBtc = payload.amountBtc)
                }
            }

            is ScannedPayload.Bolt11 -> useDestination(payload.invoice, lightning = true)

            is ScannedPayload.Lnurl -> useDestination(payload.value, lightning = true)

            else -> _state.update {
                it.copy(
                    destination = payload.raw,
                    scanNote = "That code was not recognised as a payout destination.",
                )
            }
        }
    }

    private fun useDestination(value: String, lightning: Boolean, amountBtc: BigDecimal? = null) {
        val suffix = if (lightning) "-LN" else "-CHAIN"
        val match = _state.value.methods.firstOrNull { it.endsWith(suffix, ignoreCase = true) }
        val article = if (lightning) "a Lightning" else "an on-chain"
        val rail = if (lightning) "Lightning" else "on-chain"

        _state.update { current ->
            val next = current.copy(selectedMethod = match ?: current.selectedMethod)
            // A BIP21 amount is BTC. It is copied only where the field means
            // BTC: in a USD pull payment "0.001" would be 0.001 USD.
            val amount = amountBtc?.takeIf { next.amountCurrency.equals("BTC", ignoreCase = true) }?.let { btc ->
                if (next.pullPaymentId.isBlank()) formatAmountInput(btc, unit) else Amounts.toInput(btc, 8)
            }
            next.copy(
                destination = value,
                amount = amount ?: current.amount,
                destinationError = null,
                amountError = if (amount != null) null else current.amountError,
                scanNote = buildString {
                    append(
                        if (match != null) {
                            "Recognised $article destination — ${payoutMethodLabel(match)} selected."
                        } else {
                            "Recognised $article destination, but this store does not offer $rail payouts."
                        },
                    )
                    if (amountBtc != null && amount == null) append(" Its amount is in BTC, so it was not copied.")
                },
            )
        }
    }

    /**
     * Checks the form and opens the review. Nothing is sent from here: the
     * review shows the parsed amount in its unit, the method and the whole
     * destination, and [create] then sends exactly that.
     */
    fun review() {
        val snapshot = _state.value
        // Re-entrancy guard. The composable's `enabled` is one
        // recomposition behind the click, so two taps in the same
        // frame would both get through and create two of whatever this is.
        if (snapshot.submitting || snapshot.outcomeUnknown) return
        val destination = snapshot.destination.trim()
        if (destination.isEmpty()) {
            _state.update { it.copy(destinationError = "Enter or scan a destination.") }
            return
        }
        val method = snapshot.selectedMethod
        if (method == null) {
            _state.update { it.copy(message = "Choose a payout method.") }
            return
        }
        if (bound.id == null) {
            _state.update { it.copy(message = ApiException.NoAccount().userMessage) }
            return
        }

        val pullPaymentId = snapshot.pullPaymentId.trim().ifEmpty { null }
        val pullPayment = snapshot.pullPayment
        if (pullPaymentId != null && pullPayment == null) {
            // Its currency is what the amount means, so there is no review
            // without it. A failed read is tried again from here.
            snapshot.pullPaymentError?.let { readPullPayment(pullPaymentId) }
            _state.update { it.copy(message = snapshot.pullPaymentError ?: "Wait for the pull payment to load.") }
            return
        }

        // A blank amount is taken only from a BOLT11 that states one, and the
        // review shows that figure. Any other blank is refused by the server
        // or, with a pull payment, claims all that is left of it, unseen.
        val cryptoCode = cryptoCodeOf(method)
        val invoiceAmount = if (snapshot.amount.isBlank()) {
            impliedInvoiceAmount(destination, cryptoCode, pullPayment?.currency)
        } else {
            null
        }
        val amount = invoiceAmount ?: payoutAmount(snapshot.amount, unit, pullPayment?.currency, cryptoCode)
        if (amount == null) {
            _state.update {
                it.copy(amountError = payoutAmountProblem(snapshot.amount, unit, pullPayment?.currency, cryptoCode))
            }
            return
        }

        _state.update {
            it.copy(
                review = PayoutReview(
                    destination = destination,
                    amount = amount,
                    currency = pullPayment?.currency ?: cryptoCode,
                    fromInvoice = invoiceAmount != null,
                    payoutMethodId = method,
                    pullPaymentId = pullPaymentId,
                    approved = snapshot.approveImmediately,
                ),
            )
        }
    }

    /**
     * Sends what [review] showed. Marked as a payment in flight: approved, a
     * processor may pay it at once, and a store switch must not cancel a
     * create whose answer decides whether it is safe to try again.
     */
    fun create(review: PayoutReview) {
        val snapshot = _state.value
        if (snapshot.submitting || snapshot.outcomeUnknown) return
        val store = bound.id ?: return
        _state.update { it.copy(submitting = true, review = null) }
        viewModelScope.launch {
            runCatching {
                graph.session.spending {
                    graph.session.requireApi().createPayout(
                        storeId = store,
                        request = CreatePayoutRequest(
                            destination = review.destination,
                            amount = review.amount.takeUnless { review.fromInvoice },
                            payoutMethodId = review.payoutMethodId,
                            pullPaymentId = review.pullPaymentId,
                            approved = review.approved,
                        ),
                    )
                }
            }
                .onSuccess { _state.update { it.copy(submitting = false, created = true) } }
                .onFailure { failure ->
                    val error = failure.asApiException()
                    edit {
                        if (error.mayHaveGoneThrough()) {
                            it.copy(submitting = false, outcomeUnknown = true)
                        } else {
                            it.withFailure(error)
                        }
                    }
                }
        }
    }
}

private const val OUTCOME_UNKNOWN = "The payout may have been created. Check the payout list before you try again."

/** Server codes, turned into something that says what to change. */
private fun PayoutCreateState.withFailure(error: ApiException): PayoutCreateState =
    when ((error as? ApiException.Server)?.code) {
        "duplicate-destination" -> copy(
            submitting = false,
            destinationError = "A payout to that destination already exists.",
        )
        "payment-method-not-supported" -> copy(
            submitting = false,
            message = "That pull payment does not offer the selected payout method.",
        )
        "amount-too-low" -> copy(
            submitting = false,
            amountError = "That is below the minimum this payout method accepts.",
        )
        "overdraft" -> copy(
            submitting = false,
            amountError = "That is more than the pull payment has left to claim.",
        )
        "archived" -> copy(submitting = false, message = "That pull payment has been archived.")
        "expired" -> copy(submitting = false, message = "That pull payment has expired.")
        "not-started" -> copy(submitting = false, message = "That pull payment has not started yet.")
        else -> copy(submitting = false, message = error.userMessage)
    }

@Composable
fun PayoutCreateScreen(
    scanResult: String?,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel { PayoutCreateViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val unit = LocalSettings.current.bitcoinUnit
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()

    LaunchedEffect(scanResult) { scanResult?.let(viewModel::applyScan) }

    LaunchedEffect(state.created) { if (state.created) onBack() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Leaving would cancel the create with the view model and lose its answer,
    // which says whether it is safe to try again. So back waits for it.
    BackHandler(enabled = state.submitting) {}

    AppScreen(
        title = "New payout",
        onBack = { if (!state.submitting) onBack() },
        snackbarHostState = snackbarHostState,
        actions = {
            AnimatedSwap(state.submitting, label = "submit") { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp))
                } else {
                    IconButton(onClick = viewModel::review, enabled = !state.outcomeUnknown) {
                        Icon(Icons.Rounded.Check, contentDescription = "Review payout")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            FormProblem(OUTCOME_UNKNOWN.takeIf { state.outcomeUnknown })

            FormSection(title = "Destination", modifier = Modifier.arrive(0)) {
                FormField(
                    label = "Address, invoice or LNURL",
                    value = state.destination,
                    onValueChange = viewModel::setDestination,
                    singleLine = false,
                    error = state.destinationError,
                    supportingText = state.scanNote,
                    trailingIcon = {
                        IconButton(onClick = onScan) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan a destination")
                        }
                    },
                )
                // Labelled with what the number means, which changes with the
                // pull payment id below and, without one, with the sat/BTC
                // setting.
                val currency = state.amountCurrency
                FormField(
                    label = when {
                        currency == null -> "Amount"
                        state.pullPaymentId.isNotBlank() -> "Amount ($currency)"
                        else -> "Amount (${unitLabel(unit, currency)})"
                    },
                    value = state.amount,
                    onValueChange = viewModel::setAmount,
                    keyboardType = KeyboardType.Decimal,
                    // The same rule as impliedInvoiceAmount: a blank is filled
                    // from an invoice only in the invoice's own coin.
                    supportingText = when {
                        currency == null -> "The unit follows the pull payment, once it has loaded."
                        currency.equals(state.selectedMethod?.let(::cryptoCodeOf) ?: "BTC", ignoreCase = true) ->
                            "Leave empty to pay the amount a Lightning invoice asks for."
                        else -> null
                    },
                    error = state.amountError,
                )
            }

            FormSection(title = "Payout method", modifier = Modifier.arrive(1)) {
                // The methods arrive a moment after the screen does, once the
                // session has reported what the store has switched on.
                AnimatedSwap(state.methods.isEmpty(), label = "methods") { none ->
                    if (none) {
                        Text(
                            text = "This store has no payment method switched on, so there is nothing to pay out over.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.methods, key = { it }) { id ->
                                FilterChip(
                                    selected = state.selectedMethod == id,
                                    onClick = { viewModel.selectMethod(id) },
                                    label = { Text(payoutMethodLabel(id)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }

            FormSection(title = "Options", modifier = Modifier.arrive(2)) {
                FormSwitch(
                    title = "Approve immediately",
                    checked = state.approveImmediately,
                    onCheckedChange = viewModel::setApproveImmediately,
                    description = "Locks in a rate now. A payout processor can then send it with no other step.",
                )
                FormField(
                    label = "Pull payment id",
                    value = state.pullPaymentId,
                    onValueChange = viewModel::setPullPaymentId,
                    error = state.pullPaymentError,
                    supportingText = state.pullPayment?.let { "Charged against “${it.name.ifBlank { it.id }}”, in ${it.currency}." }
                        ?: "Optional. Charges this payout against an existing pull payment.",
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    state.review?.let { review ->
        val approvedNow = if (review.approved) {
            "Yes. A payout processor can send it at once."
        } else {
            "No. It waits for approval in the payout list."
        }
        PayoutReviewDialog(
            title = if (review.approved) "Create and approve this payout?" else "Create this payout?",
            amount = review.amount,
            currency = review.currency,
            payoutMethodId = review.payoutMethodId,
            destination = review.destination,
            details = listOfNotNull(
                "Store" to viewModel.storeName,
                review.pullPaymentId?.let { "Pull payment" to it },
                "Approved now" to approvedNow,
            ),
            confirmLabel = if (review.approved) "Create and approve" else "Create",
            onDismiss = viewModel::dismissReview,
            onConfirm = {
                viewModel.dismissReview()
                if (!review.approved) {
                    viewModel.create(review)
                } else {
                    // Approved is money committed, so it takes the same prompt
                    // as a send, which fails closed.
                    val subtitle = payoutPrompt(
                        reviewAmount(review.amount, review.currency, unit),
                        viewModel.storeName,
                        review.destination,
                    )
                    scope.afterSpendGate(gate, "Confirm payout", subtitle, viewModel::reportMessage) {
                        viewModel.create(review)
                    }
                }
            },
        )
    }
}
