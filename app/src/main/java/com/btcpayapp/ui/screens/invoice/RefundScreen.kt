package com.btcpayapp.ui.screens.invoice

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
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.RefundInvoiceRequest
import com.btcpayapp.data.api.dto.RefundTriggerData
import com.btcpayapp.data.api.dto.RefundVariant
import com.btcpayapp.data.api.endpoints.invoice
import com.btcpayapp.data.api.endpoints.refundInvoice
import com.btcpayapp.data.api.endpoints.refundTrigger
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Refunds in BTCPay are not a push. The server creates a **pull payment** — a
 * claimable link the customer redeems with their own wallet — which is why this
 * screen ends by navigating to the pull payment rather than showing a
 * transaction.
 */
data class RefundState(
    val invoice: InvoiceData? = null,
    val trigger: RefundTriggerData? = null,
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
    val error: ApiException? = null,
)

class RefundViewModel(
    private val graph: AppGraph,
    private val invoiceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(RefundState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Waits for the store instead of silently giving up.
     *
     * `activeStore` starts null and only becomes non-null once `/stores`
     * returns. Opening this screen from a payment notification or a launcher
     * shortcut on a cold start would otherwise find no store, return from
     * `load()` without clearing `loading`, and spin forever with no retry.
     */
    private suspend fun awaitStoreId(): String =
        graph.session.activeStore.filterNotNull().first().id

    private fun load() {
        viewModelScope.launch {
            val storeId = awaitStoreId()
            runCatching {
                graph.session.requireApi().invoice(storeId, invoiceId, includePaymentMethods = true)
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
        _state.update { it.copy(selectedMethodId = methodId, trigger = null) }
        loadTrigger(methodId)
    }

    private fun loadTrigger(methodId: String) {
        val storeId = graph.session.activeStore.value?.id ?: return
        viewModelScope.launch {
            runCatching {
                graph.session.requireApi().refundTrigger(storeId, invoiceId, methodId)
            }.onSuccess { data -> _state.update { it.copy(trigger = data) } }
            // A failure here only costs the preview figures, so it is not
            // surfaced as a blocking error.
        }
    }

    fun update(transform: (RefundState) -> RefundState) = _state.update(transform)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun submit(onCreated: (String) -> Unit) {
        val snapshot = _state.value
        // A refund creates a claimable pull payment against the store's funds,
        // so a double tap is a second one the customer can also redeem. Like
        // every other write path in the app, it re-checks its in-flight flag
        // here rather than relying on the button's `enabled`, which lags the
        // click by a recomposition.
        if (snapshot.submitting) return
        val storeId = graph.session.activeStore.value?.id ?: return
        val methodId = snapshot.selectedMethodId ?: return

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching {
                graph.session.requireApi().refundInvoice(
                    storeId = storeId,
                    invoiceId = invoiceId,
                    request = RefundInvoiceRequest(
                        name = snapshot.name.takeIf { it.isNotBlank() },
                        description = snapshot.description.takeIf { it.isNotBlank() },
                        payoutMethods = listOf(methodId.toPayoutMethodId()),
                        refundVariant = snapshot.variant,
                        // `Amounts.parse`, not `toBigDecimalOrNull`: the field
                        // uses KeyboardType.Decimal, so on a de/fr/cs/it locale
                        // the keyboard emits a comma. `toBigDecimalOrNull`
                        // parses "2,5" to null and silently drops the
                        // withholding — refunding the customer the full amount
                        // with no error shown.
                        subtractPercentage = Amounts.parse(snapshot.subtractPercentage),
                        customAmount = Amounts.parse(snapshot.customAmount)
                            ?.takeIf { snapshot.variant == RefundVariant.Custom },
                        customCurrency = snapshot.customCurrency
                            .takeIf { snapshot.variant == RefundVariant.Custom },
                    ),
                )
            }.onSuccess {
                // Cleared on success too. Left true, a no-op navigation (the
                // screen already popped) would leave the spinner spinning
                // forever and the button disabled.
                _state.update { current -> current.copy(submitting = false) }
                onCreated(it.id)
            }
                .onFailure { failure ->
                    _state.update { it.copy(submitting = false, error = failure.asApiException()) }
                }
        }
    }
}


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

    AppScreen(title = "Refund", onBack = onBack) { padding ->
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
                ErrorBanner(state.error, onDismiss = viewModel::dismissError)

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

                val trigger = state.trigger
                val options = listOf(
                    RefundVariant.CurrentRate to ("Value at today's rate" to trigger?.paymentAmountNow),
                    RefundVariant.RateThen to ("Value at the rate when paid" to trigger?.paymentAmountThen),
                    RefundVariant.Fiat to ("The invoice amount" to trigger?.invoiceAmount),
                    RefundVariant.OverpaidAmount to ("Only the overpayment" to trigger?.overpaidPaymentAmount),
                    RefundVariant.Custom to ("A custom amount" to null),
                )

                options.forEach { (variant, labelAndAmount) ->
                    val (label, amount) = labelAndAmount
                    val enabled = variant == RefundVariant.Custom || amount != null
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
                            if (amount != null && trigger != null) {
                                Text(
                                    text = "${Amounts.trim(amount, trigger.paymentCurrencyDivisibility)} " +
                                        trigger.paymentCurrency,
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

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.submit(onCreated) },
                    enabled = !state.submitting && state.selectedMethodId != null &&
                        (state.variant != RefundVariant.Custom || Amounts.parse(state.customAmount)
                            ?.let { it > BigDecimal.ZERO } == true),
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
}
