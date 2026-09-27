package com.btcpayapp.ui.screens.paymentrequest

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.PaymentRequestRequest
import com.btcpayapp.data.api.endpoints.createPaymentRequest
import com.btcpayapp.data.api.endpoints.payPaymentRequest
import com.btcpayapp.data.api.endpoints.paymentRequest
import com.btcpayapp.data.api.endpoints.updatePaymentRequest
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DateRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.confirmDiscardChanges
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
    /**
     * The description as the server holds it: HTML from the web editor, with
     * links, lists and images. The form edits its text; see [descriptionToSend].
     */
    val originalHtml: String? = null,
    /** Not on the form, but sent back: the server detaches a form it is not sent and deletes the buyer's answers. */
    val formId: String? = null,
    /** As [formId]: the buyer's answers, which a server that rebuilds the request would delete. */
    val formResponse: JsonObject? = null,
    val dirty: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val payingNow: Boolean = false,
    val loadError: ApiException? = null,
    val error: ApiException? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val done: Boolean = false,
    val storeMissing: Boolean = false,
) {
    /** The description has markup the text field cannot hold, which an edit drops. */
    val formatted: Boolean get() = originalHtml?.let { TextUtil.stripHtml(it) != it.trim() } == true
}

class PaymentRequestEditViewModel(
    private val graph: AppGraph,
    private val paymentRequestId: String?,
) : ViewModel() {

    /**
     * The store this screen was opened for, and the only one it acts on (see
     * [StoreBinding]). A store switch closes the screen (the shell's reset),
     * so it never changes.
     */
    private val bound = StoreBinding(graph.session)
    private val storeId: String? get() = bound.id

    private val _state = MutableStateFlow(
        PaymentRequestEditState(
            currency = bound.store?.defaultCurrency.orEmpty(),
            storeMissing = storeId == null,
        ),
    )
    val state = _state.asStateFlow()

    val isNew = paymentRequestId == null

    init {
        if (paymentRequestId != null) load()
        // After a cold start the store comes later: bind it, then load.
        bound.retryWhenKnown(viewModelScope) {
            _state.update {
                it.copy(storeMissing = false, currency = it.currency.ifBlank { bound.store?.defaultCurrency.orEmpty() })
            }
            if (paymentRequestId != null) load()
        }
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

    private fun requireStore(): String = storeId ?: throw ApiException.NoAccount()

    fun load() {
        val id = paymentRequestId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching { graph.session.requireApi().paymentRequest(requireStore(), id) }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            title = data.title,
                            // `toInput`, not `toPlainString`: "12.500" would be
                            // refused as ambiguous when saved unchanged.
                            amount = Amounts.toInput(data.amount, 8),
                            currency = data.currency ?: it.currency,
                            originalHtml = data.description,
                            description = TextUtil.stripHtml(data.description.orEmpty()),
                            email = data.email.orEmpty(),
                            expiryDate = data.expiryDate,
                            referenceId = data.referenceId.orEmpty(),
                            allowCustomAmounts = data.allowCustomPaymentAmounts,
                            formId = data.formId,
                            formResponse = data.formResponse,
                            dirty = false,
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
            if (amount == null || amount.signum() < 0) {
                put(FIELD_AMOUNT, Amounts.parseProblem(snapshot.amount) ?: "Enter a valid amount.")
            }
            if (snapshot.currency.isBlank()) put(FIELD_CURRENCY, "Enter a currency code.")
        }
        if (problems.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = problems) }
            return
        }

        // Every field, changed or not: the update is not partial, and the
        // server clears whatever the body leaves out.
        val request = PaymentRequestRequest(
            amount = amount ?: BigDecimal.ZERO,
            title = snapshot.title.trim(),
            currency = snapshot.currency.trim(),
            email = snapshot.email.trim().ifBlank { null },
            description = descriptionToSend(snapshot.originalHtml, snapshot.description),
            expiryDate = snapshot.expiryDate,
            referenceId = snapshot.referenceId.trim().ifBlank { null },
            allowCustomPaymentAmounts = snapshot.allowCustomAmounts,
            formId = snapshot.formId,
            formResponse = snapshot.formResponse,
        )

        val store = storeId
        if (store == null) {
            _state.update { it.copy(storeMissing = true) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, fieldErrors = emptyMap()) }
            runCatching {
                val api = graph.session.requireApi()
                if (paymentRequestId == null) {
                    api.createPaymentRequest(store, request)
                } else {
                    api.updatePaymentRequest(store, paymentRequestId, request)
                }
            }
                .onSuccess { _state.update { it.copy(saving = false, done = true, dirty = false) } }
                .onFailure { failure -> _state.update { it.withFailure(failure.asApiException()) } }
        }
    }

    /**
     * Turns the saved payment request (not the edits on screen) into a
     * payable invoice. Store-scoped like every payment-request route: the
     * unscoped one exists only from 2.4.0.
     */
    fun payNow() {
        val id = paymentRequestId ?: return
        viewModelScope.launch {
            _state.update { it.copy(payingNow = true, error = null) }
            runCatching { graph.session.requireApi().payPaymentRequest(requireStore(), id) }
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
            transform(current).copy(fieldErrors = cleared, dirty = true)
        }
    }
}

/**
 * The description to send back.
 *
 * The server holds HTML and the form edits its text, so sending the text
 * back would flatten every link, list and image the web editor made, on any
 * save, even one that only changed the title. While the text is unchanged
 * the original HTML goes back as it came; only a real edit replaces it.
 */
internal fun descriptionToSend(originalHtml: String?, edited: String): String? {
    val text = edited.trim()
    return if (text == TextUtil.stripHtml(originalHtml.orEmpty())) originalHtml else text.ifBlank { null }
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
    // The toolbar and the system back both ask first once something changed.
    val back = confirmDiscardChanges(state.dirty, onBack)

    LaunchedEffect(state.done) { if (state.done) onBack() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = if (viewModel.isNew) "New payment request" else "Payment request",
        onBack = back,
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
                            supportingText = if (state.formatted) {
                                "Shown on the public page. Editing it removes the links, images and " +
                                    "formatting added on the web."
                            } else {
                                "Shown on the public page."
                            },
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
                        // The end of the picked day: a request picked to expire
                        // on the 10th stays payable all of the 10th.
                        DateRow(
                            label = "Expiry date",
                            epochSeconds = state.expiryDate,
                            endOfDay = true,
                            onPick = viewModel::setExpiry,
                            emptyText = "No expiry",
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
