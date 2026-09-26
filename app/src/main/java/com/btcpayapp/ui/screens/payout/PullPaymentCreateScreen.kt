package com.btcpayapp.ui.screens.payout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.CreatePullPaymentRequest
import com.btcpayapp.data.api.endpoints.createPullPayment
import com.btcpayapp.data.session.SessionManager
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
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
    val submitting: Boolean = false,
    val error: ApiException? = null,
    val nameError: String? = null,
    val amountError: String? = null,
    val notice: String? = null,
    val createdId: String? = null,
)

class PullPaymentCreateViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(
        PullPaymentCreateState(currency = graph.session.activeStore.value?.defaultCurrency.orEmpty()),
    )
    val state = _state.asStateFlow()

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
    fun setCurrency(value: String) = _state.update { it.copy(currency = value.uppercase()) }
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

    fun submit() {
        val snapshot = _state.value
        // Re-entrancy guard. The composable's `enabled` is one
        // recomposition behind the click, so two taps in the same
        // frame would both get through and create two of whatever this is.
        if (snapshot.submitting) return
        val amount = Amounts.parse(snapshot.amount)

        if (snapshot.name.isBlank()) {
            _state.update { it.copy(nameError = "Give this pull payment a name.") }
            return
        }
        if (amount == null || amount.signum() <= 0) {
            _state.update { it.copy(amountError = "Enter an amount greater than zero.") }
            return
        }
        if (snapshot.selectedMethods.isEmpty()) {
            _state.update { it.copy(notice = "Choose at least one payout method.") }
            return
        }
        val store = graph.session.activeStore.value?.id
        if (store == null) {
            _state.update { it.copy(notice = "Select a store first.") }
            return
        }

        val request = CreatePullPaymentRequest(
            name = snapshot.name.trim(),
            description = snapshot.description.trim().ifBlank { null },
            amount = amount,
            currency = snapshot.currency.trim().ifBlank { "BTC" },
            BOLT11Expiration = snapshot.bolt11ExpirationDays.toIntOrNull() ?: 30,
            startsAt = snapshot.startsAt,
            expiresAt = snapshot.expiresAt,
            payoutMethods = snapshot.selectedMethods.toList(),
            autoApproveClaims = snapshot.autoApproveClaims,
        )

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching { graph.session.requireApi().createPullPayment(store, request) }
                .onSuccess { created -> _state.update { it.copy(submitting = false, createdId = created.id) } }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = failure as? ApiException
                                ?: ApiException.Transport(failure.message ?: "Unexpected failure"),
                        )
                    }
                }
        }
    }
}

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

    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    AppScreen(
        title = "New pull payment",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            AnimatedSwap(state.submitting, label = "submit") { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp))
                } else {
                    IconButton(onClick = viewModel::submit) {
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
                FormField(
                    label = "Amount",
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
                DateRow(label = "Starts at", epochSeconds = state.startsAt, onPick = viewModel::setStartsAt)
                DateRow(label = "Expires at", epochSeconds = state.expiresAt, onPick = viewModel::setExpiresAt)
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
}

@Composable
private fun DateRow(label: String, epochSeconds: Long?, onPick: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = epochSeconds?.let(Dates::date) ?: "Not set",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // "Clear" only exists once a date has been chosen, and it appears right
        // beside the button that was just tapped to choose one.
        AnimatedVisibility(
            visible = epochSeconds != null,
            enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            TextButton(onClick = { onPick(null) }) { Text("Clear") }
        }
        TextButton(onClick = { picking = true }) { Text("Choose") }
    }

    if (picking) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = epochSeconds?.times(1000))
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // BTCPay wants unix seconds; the picker returns millis.
                        onPick(picker.selectedDateMillis?.div(1000))
                        picking = false
                    },
                ) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = picker)
        }
    }
}
