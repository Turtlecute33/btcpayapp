package com.btcpayapp.ui.screens.invoice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.CheckoutOptions
import com.btcpayapp.data.api.dto.CreateInvoiceRequest
import com.btcpayapp.data.api.dto.SpeedPolicy
import com.btcpayapp.data.api.endpoints.createInvoice
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class CreateInvoiceState(
    val amount: String = "",
    val currency: String = "",
    val orderId: String = "",
    val itemDescription: String = "",
    val buyerEmail: String = "",
    val notes: String = "",
    val topUp: Boolean = false,
    val expirationMinutes: String = "",
    val speedPolicy: SpeedPolicy? = null,
    val selectedMethods: Set<String> = emptySet(),
    val redirectUrl: String = "",
    val creating: Boolean = false,
    val error: ApiException? = null,
    val availableMethods: List<String> = emptyList(),
    val currencies: List<String> = emptyList(),
) {
    val amountValue: BigDecimal? get() = Amounts.parse(amount)
    val canSubmit: Boolean get() = !creating && (topUp || (amountValue?.signum() ?: 0) > 0)
}

class CreateInvoiceViewModel(
    private val graph: AppGraph,
    prefillAmount: String?,
    prefillCurrency: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateInvoiceState(amount = prefillAmount.orEmpty()))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(graph.session.activeStore, graph.session.paymentMethods) { store, methods ->
                store to methods
            }.collect { (store, methods) ->
                _state.update {
                    it.copy(
                        currency = it.currency.ifBlank { prefillCurrency ?: store?.defaultCurrency ?: "USD" },
                        currencies = listOfNotNull(store?.defaultCurrency, "USD", "EUR", "GBP", "SATS", "BTC")
                            .distinct(),
                        availableMethods = methods.filter { it.enabled }.map { it.paymentMethodId },
                    )
                }
            }
        }
    }

    fun update(transform: (CreateInvoiceState) -> CreateInvoiceState) = _state.update(transform)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun submit(onCreated: (String) -> Unit) {
        val snapshot = _state.value
        val storeId = runCatching { graph.session.requireStoreId() }.getOrElse {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
        if (!snapshot.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }

            val metadata = buildJsonObject {
                snapshot.orderId.takeIf { it.isNotBlank() }?.let { put("orderId", JsonPrimitive(it)) }
                snapshot.itemDescription.takeIf { it.isNotBlank() }?.let { put("itemDesc", JsonPrimitive(it)) }
                snapshot.buyerEmail.takeIf { it.isNotBlank() }?.let { put("buyerEmail", JsonPrimitive(it)) }
                snapshot.notes.takeIf { it.isNotBlank() }?.let { put("posData", JsonPrimitive(it)) }
            }

            val checkout = CheckoutOptions(
                speedPolicy = snapshot.speedPolicy,
                paymentMethods = snapshot.selectedMethods.takeIf { it.isNotEmpty() }?.toList(),
                expirationMinutes = snapshot.expirationMinutes.toIntOrNull(),
                redirectURL = snapshot.redirectUrl.takeIf { it.isNotBlank() },
            )

            runCatching {
                graph.session.requireApi().createInvoice(
                    storeId = storeId,
                    request = CreateInvoiceRequest(
                        // A null amount is what makes BTCPay create a top-up
                        // invoice that accepts whatever the payer sends.
                        amount = if (snapshot.topUp) null else snapshot.amountValue,
                        currency = snapshot.currency,
                        metadata = metadata.takeIf { it.isNotEmpty() },
                        checkout = checkout,
                    ),
                )
            }.onSuccess { onCreated(it.id) }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            creating = false,
                            error = failure as? ApiException
                                ?: ApiException.Transport(failure.message ?: "Could not create the invoice"),
                        )
                    }
                }
        }
    }
}

@Composable
fun CreateInvoiceScreen(
    prefillAmount: String?,
    prefillCurrency: String?,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val viewModel = appViewModel { CreateInvoiceViewModel(it, prefillAmount, prefillCurrency) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }

    val validation = state.error as? ApiException.Validation

    AppScreen(title = "New invoice", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ErrorBanner(
                // A validation failure is already shown against the field it
                // belongs to. Folded into the nullable rather than left as a
                // surrounding `if`, so that a banner replaced by a field error
                // collapses out instead of disappearing between two frames.
                error = state.error.takeIf { validation == null },
                onDismiss = viewModel::dismissError,
            )

            FormSwitch(
                title = "Any amount",
                description = "Creates a top-up invoice the payer decides the amount for.",
                checked = state.topUp,
                onCheckedChange = { checked -> viewModel.update { it.copy(topUp = checked) } },
            )

            // The switch above removes this field, so the form closes over the
            // gap rather than jumping shut under the finger that flipped it.
            AnimatedVisibility(
                visible = !state.topUp,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                FormField(
                    label = "Amount",
                    value = state.amount,
                    onValueChange = { value -> viewModel.update { it.copy(amount = value) } },
                    keyboardType = KeyboardType.Decimal,
                    error = validation?.messageFor("amount"),
                )
            }

            FormDropdown(
                label = "Currency",
                options = state.currencies,
                selected = state.currency.takeIf { it.isNotBlank() },
                onSelect = { value -> viewModel.update { it.copy(currency = value) } },
                supportingText = validation?.messageFor("currency"),
            )

            SectionHeader("Order")

            FormField(
                label = "Description",
                value = state.itemDescription,
                onValueChange = { value -> viewModel.update { it.copy(itemDescription = value) } },
                placeholder = "What is being sold",
            )

            FormField(
                label = "Order id",
                value = state.orderId,
                onValueChange = { value -> viewModel.update { it.copy(orderId = value) } },
                supportingText = "Indexed by the server, so it is searchable later.",
            )

            FormField(
                label = "Buyer email",
                value = state.buyerEmail,
                onValueChange = { value -> viewModel.update { it.copy(buyerEmail = value) } },
                keyboardType = KeyboardType.Email,
            )

            TextButton(
                onClick = { advanced = !advanced },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(if (advanced) "Fewer options" else "More options")
            }

            // Wrapped in a Column because `AnimatedVisibility` gives its content
            // a single slot, not a column, and these are a dozen siblings.
            AnimatedVisibility(
                visible = advanced,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column {
                    SectionHeader("Checkout")

                    FormField(
                        label = "Expires after (minutes)",
                        value = state.expirationMinutes,
                        onValueChange = { value -> viewModel.update { it.copy(expirationMinutes = value.filter(Char::isDigit)) } },
                        keyboardType = KeyboardType.Number,
                        supportingText = "Leave blank to use the store default.",
                    )

                    FormDropdown(
                        label = "Confirmation policy",
                        options = listOf(
                            SpeedPolicy.HighSpeed,
                            SpeedPolicy.MediumSpeed,
                            SpeedPolicy.LowMediumSpeed,
                            SpeedPolicy.LowSpeed,
                        ),
                        selected = state.speedPolicy,
                        onSelect = { value -> viewModel.update { it.copy(speedPolicy = value) } },
                        optionLabel = { policy ->
                            "${policy.name} — ${policy.confirmations} confirmation${if (policy.confirmations == 1) "" else "s"}"
                        },
                        supportingText = "Leave unset to use the store default.",
                    )

                    if (state.availableMethods.isNotEmpty()) {
                        SectionHeader("Payment methods")
                        Text(
                            text = "Leave all off to offer every method the store has enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        state.availableMethods.forEach { method ->
                            FormSwitch(
                                title = method,
                                checked = method in state.selectedMethods,
                                onCheckedChange = { checked ->
                                    viewModel.update {
                                        it.copy(
                                            selectedMethods = if (checked) {
                                                it.selectedMethods + method
                                            } else {
                                                it.selectedMethods - method
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    FormField(
                        label = "Redirect URL",
                        value = state.redirectUrl,
                        onValueChange = { value -> viewModel.update { it.copy(redirectUrl = value) } },
                        supportingText = "Supports the {InvoiceId} and {OrderId} placeholders.",
                        imeAction = ImeAction.Done,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.submit(onCreated) },
                enabled = state.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
            ) {
                // The label is replaced by the spinner rather than blinking out
                // for it: this is the tap that creates the invoice, and a button
                // that empties itself reads as a button that has failed.
                AnimatedSwap(state.creating, label = "create") { creating ->
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Create and show the code")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
