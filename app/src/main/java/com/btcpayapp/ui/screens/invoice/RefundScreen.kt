package com.btcpayapp.ui.screens.invoice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.mayHaveGoneThrough
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.RefundInvoiceRequest
import com.btcpayapp.data.api.dto.RefundTriggerData
import com.btcpayapp.data.api.dto.RefundVariant
import com.btcpayapp.data.api.endpoints.invoiceWithPaymentMethods
import com.btcpayapp.data.api.endpoints.refundInvoice
import com.btcpayapp.data.api.endpoints.refundTrigger
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.screens.payout.payoutMethodLabel
import com.btcpayapp.data.session.OutcomeHold
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

private val HUNDRED = BigDecimal(100)

/**
 * One refund figure: an amount, its currency and, for a figure in the payment
 * method's own currency, the divisibility it is shown at.
 *
 * The Fiat variant is in the *invoice* currency (the server rounds it there);
 * the other three are in the payment currency. Formatting all four with the
 * payment currency showed "50 BTC" for a $50 invoice.
 */
internal data class RefundFigure(val amount: BigDecimal, val currency: String, val divisibility: Int? = null) {
    val text: String
        get() = divisibility?.let { "${Amounts.trim(amount, it)} $currency" } ?: Amounts.format(amount, currency)
}

/** What [variant] refunds before any withholding, or null when the server gave no figure for it. */
internal fun RefundTriggerData.figureFor(variant: RefundVariant): RefundFigure? {
    fun paid(amount: BigDecimal?) = amount?.let { RefundFigure(it, paymentCurrency, paymentCurrencyDivisibility) }
    return when (variant) {
        RefundVariant.CurrentRate -> paid(paymentAmountNow)
        RefundVariant.RateThen -> paid(paymentAmountThen)
        RefundVariant.OverpaidAmount -> paid(overpaidPaymentAmount)
        RefundVariant.Fiat -> RefundFigure(invoiceAmount, invoiceCurrency)
        RefundVariant.Custom, RefundVariant.Unknown -> null
    }
}

/**
 * The withheld share, 0 to 100, with blank read as 0. Null for anything else,
 * which is a field error that blocks the refund. An unreadable "10%" used to
 * be dropped from the request, so the customer could claim everything.
 */
internal fun withheldPercent(text: String): BigDecimal? =
    if (text.isBlank()) BigDecimal.ZERO else Amounts.parse(text)?.takeIf { it.signum() >= 0 && it <= HUNDRED }

/** The field error for the withholding, or null when [withheldPercent] accepts it. */
internal fun withholdProblem(text: String): String? =
    if (withheldPercent(text) != null) null else Amounts.parseProblem(text) ?: "Enter a percentage from 0 to 100."

/** [amount] less [withheld] percent. Exact: dividing by 100 only moves the point. */
internal fun refundAfterWithholding(amount: BigDecimal, withheld: BigDecimal): BigDecimal =
    amount.multiply(HUNDRED.subtract(withheld)).movePointLeft(2)

/**
 * Whether the server approves the customer's claim by itself, so no one
 * reviews it before it is paid. BTCPay sets AutoApproveClaims for RateThen,
 * CurrentRate and OverpaidAmount, and for Custom when its currency is the
 * payment method's own (GreenfieldInvoiceController.RefundInvoice). Compared
 * without case: a doubtful match warns rather than stays quiet.
 */
internal fun refundAutoApproved(variant: RefundVariant, customCurrency: String, paymentCurrency: String): Boolean =
    when (variant) {
        RefundVariant.RateThen, RefundVariant.CurrentRate, RefundVariant.OverpaidAmount -> true
        RefundVariant.Custom -> customCurrency.trim().equals(paymentCurrency.trim(), ignoreCase = true)
        RefundVariant.Fiat, RefundVariant.Unknown -> false
    }

/**
 * Refunds in BTCPay are not a push. The server creates a **pull payment** — a
 * claimable link the customer redeems with their own wallet — which is why this
 * screen ends by navigating to the pull payment rather than showing a
 * transaction.
 */
data class RefundState(
    val invoice: InvoiceData? = null,
    val trigger: RefundTriggerData? = null,
    val triggerError: ApiException? = null,
    val methodIds: List<String> = emptyList(),
    val selectedMethodId: String? = null,
    val variant: RefundVariant = RefundVariant.CurrentRate,
    val customAmount: String = "",
    val customCurrency: String = "",
    val subtractPercentage: String = "",
    val name: String = "",
    val description: String = "",
    val loading: Boolean = true,
    val submitting: Boolean = false,
    /** A create may have been made ([mayHaveGoneThrough]). Stays set: a second try could make a second claimable refund. */
    val outcomeUnknown: Boolean = false,
    val notice: String? = null,
    val error: ApiException? = null,
) {
    /** The payment method's currency, which decides whether a Custom refund is approved by the server. */
    val paymentCurrency: String
        get() = trigger?.paymentCurrency?.takeIf { it.isNotBlank() }
            ?: invoice?.paymentMethods?.firstOrNull { it.paymentMethodId == selectedMethodId }?.currency
                ?.takeIf { it.isNotBlank() }
            ?: selectedMethodId?.substringBefore('-').orEmpty()

    /**
     * What the customer can claim: the chosen variant's figure less the
     * withholding. Null while that is not known, or the input is not valid,
     * and then nothing can be submitted: the review must show the real figure.
     */
    internal val refundFigure: RefundFigure?
        get() {
            val withheld = withheldPercent(subtractPercentage) ?: return null
            val base = when (variant) {
                RefundVariant.Custom -> customCurrency.trim().takeIf { it.isNotEmpty() }
                    ?.let { currency -> Amounts.inCurrency(customAmount, currency)?.let { RefundFigure(it, currency) } }
                else -> trigger?.figureFor(variant)
            } ?: return null
            return base.copy(amount = refundAfterWithholding(base.amount, withheld))
        }

    val canSubmit: Boolean
        get() = !submitting && !outcomeUnknown && selectedMethodId != null && refundFigure != null
}

class RefundViewModel(
    private val graph: AppGraph,
    private val invoiceId: String,
) : ViewModel() {

    /** The store the invoice belongs to, so every call goes there (see [StoreBinding]). */
    private val bound = StoreBinding(graph.session)

    /** Whose funds the refund claims, for the confirmation. */
    val storeName: String get() = bound.name

    private val _state = MutableStateFlow(RefundState())
    val state = _state.asStateFlow()

    /**
     * Held while [RefundState.outcomeUnknown] is on screen, so a store or account
     * switch cannot close it before it is read: a second try could make a second
     * refund. Owned here and not by the composable, so a tab switch or a screen
     * on top does not release it. It ends when this screen is closed.
     */
    private val outcomeHold = OutcomeHold(graph.session).also(::addCloseable)

    /** Changes the state and the hold together. Use it for every change that can set [RefundState.outcomeUnknown]. */
    private fun edit(transform: (RefundState) -> RefundState) =
        outcomeHold.set(_state.updateAndGet(transform).outcomeUnknown)

    init {
        load()
        bound.retryWhenKnown(viewModelScope) { load() }
    }

    fun reload() = load()

    private fun load() {
        val store = bound.id ?: run {
            _state.update { it.copy(loading = false, error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = it.invoice == null, error = null) }
            runCatching {
                // Fills paymentMethods on servers before 2.4.1 too; without it
                // no method is listed there and the refund cannot be made.
                graph.session.requireApi().invoiceWithPaymentMethods(store, invoiceId)
            }.onSuccess { data ->
                val paid = data.paymentMethods.orEmpty()
                    .filter { it.payments.isNotEmpty() }
                    .map { it.paymentMethodId }
                _state.update {
                    it.copy(
                        invoice = data,
                        methodIds = paid,
                        selectedMethodId = paid.firstOrNull(),
                        customCurrency = data.currency,
                        name = "Refund ${data.id.take(8)}",
                        loading = false,
                    )
                }
                paid.firstOrNull()?.let(::loadTrigger)
            }.onFailure { failure ->
                _state.update { it.copy(loading = false, error = failure.asApiException()) }
            }
        }
    }

    fun selectMethod(methodId: String) {
        _state.update { it.copy(selectedMethodId = methodId, trigger = null, triggerError = null) }
        loadTrigger(methodId)
    }

    fun retryTrigger() {
        _state.value.selectedMethodId?.let(::loadTrigger)
    }

    /**
     * The figures behind every option but Custom. A failure is shown with a
     * retry, because without them those options cannot be reviewed and so
     * cannot be submitted. An answer for a method that is no longer selected
     * is dropped, so an option never shows another method's amount.
     */
    private fun loadTrigger(methodId: String) {
        val store = bound.id ?: return
        _state.update { it.copy(triggerError = null) }
        viewModelScope.launch {
            runCatching {
                graph.session.requireApi().refundTrigger(store, invoiceId, methodId)
            }.onSuccess { data ->
                _state.update { if (it.selectedMethodId == methodId) it.copy(trigger = data) else it }
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update { if (it.selectedMethodId == methodId) it.copy(triggerError = error) else it }
            }
        }
    }

    fun update(transform: (RefundState) -> RefundState) = edit { transform(it).copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun reportNotice(text: String) = _state.update { it.copy(notice = text) }

    fun submit(onCreated: (String) -> Unit) {
        val snapshot = _state.value
        // A refund creates a claimable pull payment against the store's funds,
        // so a double tap is a second one the customer can also redeem. Like
        // every other write path in the app, it re-checks its in-flight flag
        // here rather than relying on the button's `enabled`, which lags the
        // click by a recomposition.
        if (!snapshot.canSubmit) return
        val store = bound.id ?: return
        val methodId = snapshot.selectedMethodId?.toPayoutMethodId() ?: return
        val withheld = withheldPercent(snapshot.subtractPercentage) ?: return
        val custom = snapshot.variant == RefundVariant.Custom

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, notice = null) }
            runCatching {
                graph.session.spending {
                    graph.session.requireApi().refundInvoice(
                        storeId = store,
                        invoiceId = invoiceId,
                        request = RefundInvoiceRequest(
                            name = snapshot.name.takeIf { it.isNotBlank() },
                            description = snapshot.description.takeIf { it.isNotBlank() },
                            // Both: servers before 2.4.1 read only the single id,
                            // and refund with the invoice's default method without it.
                            payoutMethods = listOf(methodId),
                            payoutMethodId = methodId,
                            refundVariant = snapshot.variant,
                            subtractPercentage = withheld.takeIf { it.signum() > 0 },
                            customAmount = Amounts.parse(snapshot.customAmount).takeIf { custom },
                            customCurrency = snapshot.customCurrency.trim().takeIf { custom },
                        ),
                    )
                }
            }.onSuccess {
                // Cleared on success too. Left true, a no-op navigation (the
                // screen already popped) would leave the spinner spinning
                // forever and the button disabled.
                _state.update { current -> current.copy(submitting = false) }
                onCreated(it.id)
            }.onFailure { failure ->
                val error = failure.asApiException()
                edit {
                    if (error.mayHaveGoneThrough()) {
                        it.copy(submitting = false, outcomeUnknown = true)
                    } else {
                        it.copy(submitting = false, error = error)
                    }
                }
            }
        }
    }
}

private const val OUTCOME_UNKNOWN = "The refund may have been created. Check Pull payments before you try again."

/** Payout method ids collapse LNURL onto Lightning. */
private fun String.toPayoutMethodId(): String =
    if (endsWith("-LNURL", ignoreCase = true)) substringBefore('-') + "-LN" else this

@Composable
fun RefundScreen(
    invoiceId: String,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val viewModel = appViewModel(key = "refund-$invoiceId") { RefundViewModel(it, invoiceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()
    var reviewing by remember { mutableStateOf(false) }

    // Leaving would cancel the create with the view model and lose its answer,
    // which says whether it is safe to try again. So back waits for it.
    BackHandler(enabled = state.submitting) {}

    AppScreen(title = "Refund", onBack = { if (!state.submitting) onBack() }) { padding ->
        // A spinner rather than a skeleton: what is coming is a form, and a
        // placeholder that guessed the wrong shape would be worse than none.
        AnimatedSwap(state.loading, label = "refund") { loading ->
            if (loading) {
                LoadingState(Modifier.padding(padding))
                return@AnimatedSwap
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                ErrorBanner(
                    state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = if (state.invoice == null) viewModel::reload else null,
                )

                Text(
                    text = "The customer receives a claim link and redeems it with their own wallet. " +
                        "Nothing is sent until they do.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )

                if (state.methodIds.size > 1) {
                    SectionHeader("Paid with")
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.methodIds.forEach { id ->
                            FilterChip(
                                selected = id == state.selectedMethodId,
                                onClick = { viewModel.selectMethod(id) },
                                label = { Text(id) },
                            )
                        }
                    }
                }

                SectionHeader("How much")

                // Without these figures the options cannot be reviewed, so a
                // failure says so and offers another try.
                ErrorBanner(state.triggerError, onRetry = viewModel::retryTrigger)

                val trigger = state.trigger
                val options = listOf(
                    RefundVariant.CurrentRate to "Value at today's rate",
                    RefundVariant.RateThen to "Value at the rate when paid",
                    RefundVariant.Fiat to "The invoice amount",
                    RefundVariant.OverpaidAmount to "Only the overpayment",
                    RefundVariant.Custom to "A custom amount",
                )

                options.forEach { (variant, label) ->
                    val figure = trigger?.figureFor(variant)
                    val enabled = variant == RefundVariant.Custom || figure != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.variant == variant,
                                enabled = enabled,
                                onClick = { viewModel.update { it.copy(variant = variant) } },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.variant == variant,
                            onClick = null,
                            enabled = enabled,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (figure != null) {
                                Text(
                                    text = maskedIfPrivate(figure.text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // These two open under the radio button that asked for them, so
                // they grow out of the row rather than shoving the rest of the
                // form down a screenful between two frames.
                AnimatedVisibility(
                    visible = state.variant == RefundVariant.Custom,
                    enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                    exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                ) {
                    Column {
                        FormField(
                            label = "Amount",
                            value = state.customAmount,
                            onValueChange = { value -> viewModel.update { it.copy(customAmount = value) } },
                            keyboardType = KeyboardType.Decimal,
                            error = Amounts.inCurrencyProblem(state.customAmount, state.customCurrency.trim()),
                        )
                        FormField(
                            label = "Currency",
                            value = state.customCurrency,
                            onValueChange = { value -> viewModel.update { it.copy(customCurrency = value.uppercase()) } },
                        )
                    }
                }

                FormField(
                    label = "Withhold (%)",
                    value = state.subtractPercentage,
                    onValueChange = { value -> viewModel.update { it.copy(subtractPercentage = value) } },
                    keyboardType = KeyboardType.Decimal,
                    supportingText = "Deducted from the refund, for a restocking fee or similar.",
                    error = withholdProblem(state.subtractPercentage),
                )

                SectionHeader("Label")

                FormField(
                    label = "Name",
                    value = state.name,
                    onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                )

                FormField(
                    label = "Description",
                    value = state.description,
                    onValueChange = { value -> viewModel.update { it.copy(description = value) } },
                    singleLine = false,
                )

                Spacer(Modifier.height(16.dp))

                FormProblem(if (state.outcomeUnknown) OUTCOME_UNKNOWN else state.notice)

                Button(
                    onClick = { if (state.canSubmit) reviewing = true },
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(52.dp),
                ) {
                    // A refund creates money the customer can claim, so the
                    // button has to be visibly busy and not merely inert.
                    AnimatedSwap(state.submitting, label = "refundBusy") { submitting ->
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Create the refund")
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // Every variant is reviewed, not only the ones the server approves by
    // itself: the figure here is the first time the operator sees the amount
    // after the withholding, and a pull payment cannot be taken back once the
    // link is out. So it is shown in privacy mode too, here and in the prompt.
    // The invoice and the payout method are named too: "BTC" alone does not
    // say whether the customer claims on-chain or over Lightning.
    val figure = state.refundFigure
    val methodId = state.selectedMethodId?.toPayoutMethodId()
    if (reviewing && figure != null && methodId != null) {
        val autoApproved = refundAutoApproved(state.variant, state.customCurrency, state.paymentCurrency)
        val amount = figure.text
        ConfirmDialog(
            title = "Create this refund?",
            message = buildString {
                append("Refund ").append(amount).append(" from ").append(viewModel.storeName).append(".\n")
                append("Invoice: ").append(invoiceId).append('\n')
                append("Paid out over: ").append(payoutMethodLabel(methodId)).append('\n')
                append("The customer claims it with a link.")
                if (autoApproved) append(" Claims are paid without approval.")
            },
            confirmLabel = "Create",
            onDismiss = { reviewing = false },
            onConfirm = {
                reviewing = false
                val subtitle = "$amount from ${viewModel.storeName}"
                scope.afterSpendGate(gate, "Confirm refund", subtitle, viewModel::reportNotice) {
                    viewModel.submit(onCreated)
                }
            },
        )
    }
}
