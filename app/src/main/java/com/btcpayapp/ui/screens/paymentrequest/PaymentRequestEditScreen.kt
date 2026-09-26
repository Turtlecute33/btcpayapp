package com.btcpayapp.ui.screens.paymentrequest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.PaymentRequestRequest
import com.btcpayapp.data.api.endpoints.createPaymentRequest
import com.btcpayapp.data.api.endpoints.payPaymentRequest
import com.btcpayapp.data.api.endpoints.paymentRequest
import com.btcpayapp.data.api.endpoints.updatePaymentRequest
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Field keys, matching the `path` the server puts on a validation error. */
private const val FIELD_TITLE = "title"
private const val FIELD_AMOUNT = "amount"
private const val FIELD_CURRENCY = "currency"
private const val FIELD_EMAIL = "email"
private const val FIELD_REFERENCE = "referenceId"

data class PaymentRequestEditState(
    val title: String = "",
    val amount: String = "",
    val currency: String = "",
    val description: String = "",
    val email: String = "",
    val expiryDate: Long? = null,
    val referenceId: String = "",
    val allowCustomAmounts: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val payingNow: Boolean = false,
    val loadError: ApiException? = null,
    val error: ApiException? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val done: Boolean = false,
    val storeMissing: Boolean = false,
)

class PaymentRequestEditViewModel(
    private val graph: AppGraph,
    private val paymentRequestId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PaymentRequestEditState(
            currency = graph.session.activeStore.value?.defaultCurrency.orEmpty(),
            storeMissing = graph.session.activeStore.value == null,
        ),
    )
    val state = _state.asStateFlow()

    val isNew = paymentRequestId == null

    init {
        if (paymentRequestId != null) load()
    }

    fun setTitle(value: String) = edit(FIELD_TITLE) { it.copy(title = value) }
    fun setAmount(value: String) = edit(FIELD_AMOUNT) { it.copy(amount = value) }
    fun setCurrency(value: String) = edit(FIELD_CURRENCY) { it.copy(currency = value.uppercase()) }
    fun setDescription(value: String) = edit(null) { it.copy(description = value) }
    fun setEmail(value: String) = edit(FIELD_EMAIL) { it.copy(email = value) }
    fun setExpiry(value: Long?) = edit(null) { it.copy(expiryDate = value) }
    fun setReferenceId(value: String) = edit(FIELD_REFERENCE) { it.copy(referenceId = value) }
    fun setAllowCustomAmounts(value: Boolean) = edit(null) { it.copy(allowCustomAmounts = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun load() {
        val id = paymentRequestId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching { graph.session.requireApi().paymentRequest(id) }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            title = data.title,
                            amount = data.amount.toPlainString(),
                            currency = data.currency ?: it.currency,
                            // The API stores description as HTML; edit it as the
                            // text it almost always is and send it back as-is.
                            description = TextUtil.stripHtml(data.description.orEmpty()),
                            email = data.email.orEmpty(),
                            expiryDate = data.expiryDate,
                            referenceId = data.referenceId.orEmpty(),
                            allowCustomAmounts = data.allowCustomPaymentAmounts,
                            loading = false,
                            loadError = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, loadError = failure.asApiException()) }
                }
        }
    }

    fun save() {
        val snapshot = _state.value
        // Re-entrancy guard. The composable's `enabled` is one
        // recomposition behind the click, so two taps in the same
        // frame would both get through and create two of whatever this is.
        if (snapshot.saving) return
        val amount = Amounts.parse(snapshot.amount)
        val problems = buildMap {
            if (snapshot.title.isBlank()) put(FIELD_TITLE, "Give this request a title.")
            if (amount == null || amount.signum() < 0) put(FIELD_AMOUNT, "Enter a valid amount.")
            if (snapshot.currency.isBlank()) put(FIELD_CURRENCY, "Enter a currency code.")
        }
        if (problems.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = problems) }
            return
        }

        val request = PaymentRequestRequest(
            amount = amount ?: BigDecimal.ZERO,
            title = snapshot.title.trim(),
            currency = snapshot.currency.trim(),
            email = snapshot.email.trim().ifBlank { null },
            description = snapshot.description.trim().ifBlank { null },
            expiryDate = snapshot.expiryDate,
            referenceId = snapshot.referenceId.trim().ifBlank { null },
            allowCustomPaymentAmounts = snapshot.allowCustomAmounts,
        )

        val storeId = graph.session.activeStore.value?.id
        if (paymentRequestId == null && storeId == null) {
            _state.update { it.copy(storeMissing = true) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, fieldErrors = emptyMap()) }
            runCatching {
                val api = graph.session.requireApi()
                if (paymentRequestId == null) {
                    api.createPaymentRequest(storeId!!, request)
                } else {
                    api.updatePaymentRequest(paymentRequestId, request)
                }
            }
                .onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { failure -> _state.update { it.withFailure(failure.asApiException()) } }
        }
    }

    /**
     * Turns a payment request into a payable invoice. This endpoint is **not**
     * store-scoped — it is addressed by payment request id alone, so no store id
     * is passed and none is needed.
     */
    fun payNow() {
        val id = paymentRequestId ?: return
        viewModelScope.launch {
            _state.update { it.copy(payingNow = true, error = null) }
            runCatching { graph.session.requireApi().payPaymentRequest(id) }
                .onSuccess { invoice ->
                    _state.update {
                        it.copy(
                            payingNow = false,
                            message = "Invoice ${TextUtil.middleEllipsis(invoice.id, 6, 4)} created",
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(payingNow = false, message = failure.asApiException().userMessage) }
                }
        }
    }

    private fun edit(clearField: String?, transform: (PaymentRequestEditState) -> PaymentRequestEditState) {
        _state.update { current ->
            val cleared = if (clearField == null) {
                current.fieldErrors
            } else {
                current.fieldErrors - clearField
            }
            transform(current).copy(fieldErrors = cleared)
        }
    }
}

/**
 * Maps a failure onto the field it belongs to.
 *
 * A duplicate reference id comes back as a 409 with `duplicate-reference-id`
 * rather than as a validation array, but it is a field problem in every way the
 * user cares about — so it is shown under the field rather than as a banner at
 * the top that leaves them hunting for what to change.
 */
private fun PaymentRequestEditState.withFailure(failure: ApiException): PaymentRequestEditState = when {
    failure is ApiException.Server && failure.code == "duplicate-reference-id" -> copy(
        saving = false,
        fieldErrors = mapOf(FIELD_REFERENCE to "Another payment request in this store already uses that reference."),
    )

    failure is ApiException.Validation -> {
        val perField = EDITABLE_FIELDS
            .mapNotNull { path -> failure.messageFor(path)?.let { path to it } }
            .toMap()
        copy(
            saving = false,
            fieldErrors = perField,
            // Keep the banner only when nothing could be pinned to a field.
            error = failure.takeIf { perField.isEmpty() },
        )
    }

    else -> copy(saving = false, error = failure)
}

private val EDITABLE_FIELDS =
    listOf(FIELD_TITLE, FIELD_AMOUNT, FIELD_CURRENCY, FIELD_EMAIL, FIELD_REFERENCE)

/**
 * What the body of the screen is showing. A discriminator rather than the
 * state, so typing into a field does not re-animate the form around it.
 */
private enum class PaymentRequestEditPhase { Loading, Error, Form }

@Composable
fun PaymentRequestEditScreen(
    paymentRequestId: String?,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = paymentRequestId ?: "new") {
        PaymentRequestEditViewModel(it, paymentRequestId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.done) { if (state.done) onBack() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = if (viewModel.isNew) "New payment request" else "Payment request",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            AnimatedSwap(state.saving, label = "save") { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp))
                } else {
                    IconButton(onClick = viewModel::save, enabled = !state.loading) {
                        Icon(Icons.Rounded.Check, contentDescription = "Save")
                    }
                }
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> PaymentRequestEditPhase.Loading
            state.loadError != null -> PaymentRequestEditPhase.Error
            else -> PaymentRequestEditPhase.Form
        }

        AnimatedSwap(phase, label = "paymentRequestEdit") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is coming is one
                // record behind a form, not a list of rows.
                PaymentRequestEditPhase.Loading -> LoadingState(Modifier.padding(padding))

                PaymentRequestEditPhase.Error -> state.loadError?.let {
                    ErrorState(
                        error = it,
                        modifier = Modifier.padding(padding),
                        onRetry = viewModel::load,
                    )
                }

                PaymentRequestEditPhase.Form -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(
                        ApiException.NotFound("Select a store before creating a payment request.")
                            .takeIf { state.storeMissing && viewModel.isNew },
                    )

                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    FormSection(title = "Details", modifier = Modifier.arrive(0)) {
                        FormField(
                            label = "Title",
                            value = state.title,
                            onValueChange = viewModel::setTitle,
                            error = state.fieldErrors[FIELD_TITLE],
                        )
                        FormField(
                            label = "Amount",
                            value = state.amount,
                            onValueChange = viewModel::setAmount,
                            keyboardType = KeyboardType.Decimal,
                            error = state.fieldErrors[FIELD_AMOUNT],
                        )
                        FormField(
                            label = "Currency",
                            value = state.currency,
                            onValueChange = viewModel::setCurrency,
                            placeholder = "EUR",
                            error = state.fieldErrors[FIELD_CURRENCY],
                        )
                        FormField(
                            label = "Description",
                            value = state.description,
                            onValueChange = viewModel::setDescription,
                            singleLine = false,
                            supportingText = "Shown on the public page.",
                        )
                        FormField(
                            label = "Buyer email",
                            value = state.email,
                            onValueChange = viewModel::setEmail,
                            keyboardType = KeyboardType.Email,
                            error = state.fieldErrors[FIELD_EMAIL],
                        )
                    }

                    FormSection(title = "Options", modifier = Modifier.arrive(1)) {
                        ExpiryRow(
                            expiryDate = state.expiryDate,
                            onPick = viewModel::setExpiry,
                        )
                        FormField(
                            label = "Reference id",
                            value = state.referenceId,
                            onValueChange = viewModel::setReferenceId,
                            supportingText = "Optional. Must be unique within the store.",
                            error = state.fieldErrors[FIELD_REFERENCE],
                        )
                        FormSwitch(
                            title = "Allow custom amounts",
                            checked = state.allowCustomAmounts,
                            onCheckedChange = viewModel::setAllowCustomAmounts,
                            description = "The payer can pay more or less than the amount above.",
                        )
                    }

                    if (!viewModel.isNew) {
                        FormSection(title = "Invoice", modifier = Modifier.arrive(2)) {
                            Button(
                                onClick = viewModel::payNow,
                                enabled = !state.payingNow,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                                AnimatedSwap(state.payingNow, label = "payNow") { busy ->
                                    Text(
                                        text = if (busy) "Creating…" else "Create an invoice from this",
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ExpiryRow(expiryDate: Long?, onPick: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Expiry date", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = expiryDate?.let(Dates::date) ?: "No expiry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // "Clear" only exists once a date has been chosen, and it appears right
        // beside the button that was just tapped to choose one.
        AnimatedVisibility(
            visible = expiryDate != null,
            enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            TextButton(onClick = { onPick(null) }) { Text("Clear") }
        }
        TextButton(onClick = { picking = true }) { Text("Choose") }
    }

    if (picking) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = expiryDate?.times(1000))
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

