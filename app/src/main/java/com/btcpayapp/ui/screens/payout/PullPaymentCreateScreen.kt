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
import androidx.compose.runtime.setValue
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
import com.btcpayapp.data.api.dto.CreatePullPaymentRequest
import com.btcpayapp.data.api.endpoints.createPullPayment
import com.btcpayapp.data.session.OutcomeHold
import com.btcpayapp.data.session.SessionManager
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.DateRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.data.session.StoreBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared across the payout screens in this package
// ---------------------------------------------------------------------------

/**
 * The payout rails a store can actually pay out over.
 *
 * LNURL-Pay is a way of *reaching* a Lightning node rather than a separate rail,
 * so `BTC-LNURL` folds into `BTC-LN`; without that, a store with both switched
 * on would offer the same destination twice under two names.
 */
internal fun offeredPayoutMethodIds(session: SessionManager): List<String> =
    session.enabledPaymentMethodIds
        .map { id ->
            if (id.endsWith("-LNURL", ignoreCase = true)) "${id.substringBeforeLast('-')}-LN" else id
        }
        .distinctBy { it.uppercase() }

internal fun payoutMethodLabel(payoutMethodId: String): String {
    val code = payoutMethodId.substringBefore('-')
    return when {
        payoutMethodId.endsWith("-LN", ignoreCase = true) -> "$code Lightning"
        payoutMethodId.endsWith("-CHAIN", ignoreCase = true) -> "$code on-chain"
        else -> payoutMethodId
    }
}

// ---------------------------------------------------------------------------

data class PullPaymentCreateState(
    val name: String = "",
    val description: String = "",
    val amount: String = "",
    val currency: String = "",
    val methods: List<String> = emptyList(),
    val selectedMethods: Set<String> = emptySet(),
    val bolt11ExpirationDays: String = "30",
    val startsAt: Long? = null,
    val expiresAt: Long? = null,
    val autoApproveClaims: Boolean = false,
    /** An auto-approving request waiting for its confirmation. */
    val confirming: CreatePullPaymentRequest? = null,
    val submitting: Boolean = false,
    /** A create may have been made ([mayHaveGoneThrough]). Stays set: a second try could be a second claimable link. */
    val outcomeUnknown: Boolean = false,
    val error: ApiException? = null,
    val nameError: String? = null,
    val amountError: String? = null,
    val notice: String? = null,
    val createdId: String? = null,
) {
    /** What the amount is in, and is sent as: BTC while the currency field is blank. */
    val amountCurrency: String
        get() = currency.trim().ifBlank { "BTC" }
}

class PullPaymentCreateViewModel(private val graph: AppGraph) : ViewModel() {

    /** The store this screen was opened in (see [StoreBinding]). */
    private val bound = StoreBinding(graph.session)

    /** Whose funds the claims take, for the confirmation. */
    val storeName: String get() = bound.name

    private val _state = MutableStateFlow(
        PullPaymentCreateState(currency = graph.session.activeStore.value?.defaultCurrency.orEmpty()),
    )
    val state = _state.asStateFlow()

    /**
     * Held while [PullPaymentCreateState.outcomeUnknown] is on screen, so a store
     * or account switch cannot close it before it is read: a second try could
     * make a second claimable link. Owned here and not by the composable, so a
     * tab switch or a screen on top does not release it. It ends when this
     * screen is closed.
     */
    private val outcomeHold = OutcomeHold(graph.session).also(::addCloseable)

    /** Changes the state and the hold together. Use it for every change that can set [PullPaymentCreateState.outcomeUnknown]. */
    private fun edit(transform: (PullPaymentCreateState) -> PullPaymentCreateState) =
        outcomeHold.set(_state.updateAndGet(transform).outcomeUnknown)

    init {
        viewModelScope.launch {
            // The payment methods arrive after the store does, so the chips are
            // filled in as they land rather than read once at construction.
            graph.session.paymentMethods.collectLatest {
                val offered = offeredPayoutMethodIds(graph.session)
                _state.update { current ->
                    current.copy(
                        methods = offered,
                        selectedMethods = current.selectedMethods
                            .intersect(offered.toSet())
                            .ifEmpty { offered.toSet() },
                    )
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, nameError = null) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }
    fun setAmount(value: String) = _state.update { it.copy(amount = value, amountError = null) }
    // Clears the amount error too: how many decimals are allowed depends on the currency.
    fun setCurrency(value: String) = _state.update { it.copy(currency = value.uppercase(), amountError = null) }
    fun setExpirationDays(value: String) = _state.update { it.copy(bolt11ExpirationDays = value.filter(Char::isDigit)) }
    fun setStartsAt(value: Long?) = _state.update { it.copy(startsAt = value) }
    fun setExpiresAt(value: Long?) = _state.update { it.copy(expiresAt = value) }
    fun setAutoApprove(value: Boolean) = _state.update { it.copy(autoApproveClaims = value) }

    fun toggleMethod(id: String) = _state.update { current ->
        current.copy(
            selectedMethods = if (id in current.selectedMethods) {
                current.selectedMethods - id
            } else {
                current.selectedMethods + id
            },
        )
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun reportNotice(text: String) = _state.update { it.copy(notice = text) }

    fun dismissConfirm() = _state.update { it.copy(confirming = null) }

    /**
     * Checks the form. A pull payment that approves its own claims pays
     * whoever holds the link with no one reviewing the claim, so that one is
     * confirmed first and then passes the spend prompt; any other is
     * created straight away, since each claim still waits for approval.
     */
    fun submit() {
        val snapshot = _state.value
        // Re-entrancy guard. The composable's `enabled` is one
        // recomposition behind the click, so two taps in the same
        // frame would both get through and create two of whatever this is.
        if (snapshot.submitting || snapshot.outcomeUnknown) return
        val currency = snapshot.amountCurrency
        val amount = Amounts.inCurrency(snapshot.amount, currency)

        if (snapshot.name.isBlank()) {
            _state.update { it.copy(nameError = "Give this pull payment a name.") }
            return
        }
        if (amount == null) {
            _state.update {
                it.copy(amountError = Amounts.inCurrencyProblem(snapshot.amount, currency) ?: "Enter an amount greater than zero.")
            }
            return
        }
        if (snapshot.selectedMethods.isEmpty()) {
            _state.update { it.copy(notice = "Choose at least one payout method.") }
            return
        }
        if (bound.id == null) {
            _state.update { it.copy(notice = ApiException.NoAccount().userMessage) }
            return
        }

        val request = CreatePullPaymentRequest(
            name = snapshot.name.trim(),
            description = snapshot.description.trim().ifBlank { null },
            amount = amount,
            currency = currency,
            BOLT11Expiration = snapshot.bolt11ExpirationDays.toIntOrNull() ?: 30,
            startsAt = snapshot.startsAt,
            expiresAt = snapshot.expiresAt,
            payoutMethods = snapshot.selectedMethods.toList(),
            autoApproveClaims = snapshot.autoApproveClaims,
        )
        if (request.autoApproveClaims) _state.update { it.copy(confirming = request) } else create(request)
    }

    /**
     * Marked as a payment in flight, as a refund is: a store switch must not
     * cancel a create whose answer decides whether it is safe to try again.
     */
    fun create(request: CreatePullPaymentRequest) {
        val snapshot = _state.value
        if (snapshot.submitting || snapshot.outcomeUnknown) return
        val store = bound.id ?: return
        _state.update { it.copy(submitting = true, error = null, confirming = null) }
        viewModelScope.launch {
            runCatching {
                graph.session.spending { graph.session.requireApi().createPullPayment(store, request) }
            }
                .onSuccess { created -> _state.update { it.copy(submitting = false, createdId = created.id) } }
                .onFailure { failure ->
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

private const val OUTCOME_UNKNOWN =
    "The pull payment may have been created. Check Pull payments before you try again."

/**
 * Creating a pull payment.
 *
 * A pull payment is a claimable link: instead of the store pushing funds out, it
 * publishes a page (and an LNURL-withdraw) that the recipient opens to enter
 * their own address or invoice and claim up to the stated amount. Refunds are
 * the usual case. Because the mechanism is explained by the resulting screen —
 * a link and a QR — the copy here stays short and only says what each field
 * changes.
 */
@Composable
fun PullPaymentCreateScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val viewModel = appViewModel { PullPaymentCreateViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    // Leaving would cancel the create with the view model and lose its answer,
    // which says whether it is safe to try again. So back waits for it.
    BackHandler(enabled = state.submitting) {}

    AppScreen(
        title = "New pull payment",
        onBack = { if (!state.submitting) onBack() },
        snackbarHostState = snackbarHostState,
        actions = {
            AnimatedSwap(state.submitting, label = "submit") { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp))
                } else {
                    IconButton(onClick = viewModel::submit, enabled = !state.outcomeUnknown) {
                        Icon(Icons.Rounded.Check, contentDescription = "Create")
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
            ErrorBanner(state.error, onDismiss = viewModel::dismissError)
            FormProblem(OUTCOME_UNKNOWN.takeIf { state.outcomeUnknown })

            FormSection(title = "Details", modifier = Modifier.arrive(0)) {
                FormField(
                    label = "Name",
                    value = state.name,
                    onValueChange = viewModel::setName,
                    supportingText = "Shown to whoever opens the claim link.",
                    error = state.nameError,
                )
                FormField(
                    label = "Description",
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    singleLine = false,
                )
                // Read in the currency below whatever the sat/BTC setting, so
                // the label names it: in BTC, "50000" is 50,000 BTC, not sats.
                FormField(
                    label = "Amount (${state.amountCurrency})",
                    value = state.amount,
                    onValueChange = viewModel::setAmount,
                    keyboardType = KeyboardType.Decimal,
                    error = state.amountError,
                )
                FormField(
                    label = "Currency",
                    value = state.currency,
                    onValueChange = viewModel::setCurrency,
                    placeholder = "BTC",
                )
            }

            FormSection(title = "Payout methods", modifier = Modifier.arrive(1)) {
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
                                    selected = id in state.selectedMethods,
                                    onClick = { viewModel.toggleMethod(id) },
                                    label = { Text(payoutMethodLabel(id)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }

            FormSection(title = "Limits", modifier = Modifier.arrive(2)) {
                FormField(
                    label = "BOLT11 expiration (days)",
                    value = state.bolt11ExpirationDays,
                    onValueChange = viewModel::setExpirationDays,
                    keyboardType = KeyboardType.Number,
                    supportingText = "How long a claimed Lightning invoice stays payable.",
                )
                DateRow(
                    label = "Starts at",
                    epochSeconds = state.startsAt,
                    endOfDay = false,
                    onPick = viewModel::setStartsAt,
                )
                DateRow(
                    label = "Expires at",
                    epochSeconds = state.expiresAt,
                    endOfDay = true,
                    onPick = viewModel::setExpiresAt,
                )
                FormSwitch(
                    title = "Auto-approve claims",
                    checked = state.autoApproveClaims,
                    onCheckedChange = viewModel::setAutoApprove,
                    description = "Claims are approved as they arrive, with no review step.",
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Not masked in privacy mode: this is where the operator reads the amount
    // before anyone with the link can claim it unreviewed.
    // The methods are named, because "BTC" alone does not say on-chain or Lightning.
    state.confirming?.let { request ->
        val amount = Amounts.format(request.amount, request.currency)
        val methods = request.payoutMethods.joinToString(", ", transform = ::payoutMethodLabel)
        ConfirmDialog(
            title = "Create this pull payment?",
            message = "Amount: $amount from ${viewModel.storeName}.\nPaid out over: $methods.\n" +
                "Claims will be paid without approval.",
            confirmLabel = "Create",
            onDismiss = viewModel::dismissConfirm,
            onConfirm = {
                viewModel.dismissConfirm()
                val subtitle = "$amount from ${viewModel.storeName}"
                scope.afterSpendGate(gate, "Confirm pull payment", subtitle, viewModel::reportNotice) {
                    viewModel.create(request)
                }
            },
        )
    }
}
