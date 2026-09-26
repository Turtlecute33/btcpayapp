package com.btcpayapp.ui.screens.payout

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.core.scan.ScannedPayload
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.CreatePayoutRequest
import com.btcpayapp.data.api.endpoints.createPayout
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class PayoutCreateState(
    val destination: String = "",
    val amount: String = "",
    val methods: List<String> = emptyList(),
    val selectedMethod: String? = null,
    val approveImmediately: Boolean = false,
    val pullPaymentId: String = "",
    val submitting: Boolean = false,
    val created: Boolean = false,
    val destinationError: String? = null,
    val amountError: String? = null,
    val scanNote: String? = null,
    val message: String? = null,
)

class PayoutCreateViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PayoutCreateState())
    val state = _state.asStateFlow()

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

    fun selectMethod(id: String) = _state.update { it.copy(selectedMethod = id) }

    fun setApproveImmediately(value: Boolean) = _state.update { it.copy(approveImmediately = value) }

    fun setPullPaymentId(value: String) = _state.update { it.copy(pullPaymentId = value) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Applies a scan. The rail is decided by what was scanned rather than left
     * to the user, because a BOLT11 invoice on the on-chain method is rejected
     * only after a round trip.
     */
    fun applyScan(raw: String) {
        when (val payload = ScanParser.parse(raw)) {
            is ScannedPayload.BitcoinAddress -> useDestination(payload.address, lightning = false)

            is ScannedPayload.Bip21 -> useDestination(
                value = payload.address,
                lightning = false,
                // An on-chain payout is denominated in BTC, which is exactly
                // what BIP21 carries, so the amount transfers unchanged.
                amount = payload.amountBtc?.let { Amounts.trim(it, 8) },
            )

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

    private fun useDestination(value: String, lightning: Boolean, amount: String? = null) {
        val suffix = if (lightning) "-LN" else "-CHAIN"
        val match = _state.value.methods.firstOrNull { it.endsWith(suffix, ignoreCase = true) }
        val article = if (lightning) "a Lightning" else "an on-chain"
        val rail = if (lightning) "Lightning" else "on-chain"

        _state.update {
            it.copy(
                destination = value,
                amount = amount ?: it.amount,
                selectedMethod = match ?: it.selectedMethod,
                destinationError = null,
                scanNote = if (match != null) {
                    "Recognised $article destination — ${payoutMethodLabel(match)} selected."
                } else {
                    "Recognised $article destination, but this store does not offer $rail payouts."
                },
            )
        }
    }

    fun submit() {
        val snapshot = _state.value
        // Re-entrancy guard. The composable's `enabled` is one
        // recomposition behind the click, so two taps in the same
        // frame would both get through and create two of whatever this is.
        if (snapshot.submitting) return
        val destination = snapshot.destination.trim()
        if (destination.isEmpty()) {
            _state.update { it.copy(destinationError = "Enter or scan a destination.") }
            return
        }

        var amount: BigDecimal? = null
        if (snapshot.amount.isNotBlank()) {
            amount = Amounts.parse(snapshot.amount)
            if (amount == null || amount.signum() <= 0) {
                _state.update { it.copy(amountError = "Enter an amount greater than zero.") }
                return
            }
        }

        val method = snapshot.selectedMethod
        if (method == null) {
            _state.update { it.copy(message = "Choose a payout method.") }
            return
        }
        val store = graph.session.activeStore.value?.id
        if (store == null) {
            _state.update { it.copy(message = "Select a store first.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            runCatching {
                graph.session.requireApi().createPayout(
                    storeId = store,
                    request = CreatePayoutRequest(
                        destination = destination,
                        amount = amount,
                        payoutMethodId = method,
                        pullPaymentId = snapshot.pullPaymentId.trim().ifBlank { null },
                        approved = snapshot.approveImmediately,
                    ),
                )
            }
                .onSuccess { _state.update { it.copy(submitting = false, created = true) } }
                .onFailure { failure -> _state.update { it.withFailure(failure.asApiException()) } }
        }
    }
}

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

    LaunchedEffect(scanResult) { scanResult?.let(viewModel::applyScan) }

    LaunchedEffect(state.created) { if (state.created) onBack() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = "New payout",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            AnimatedSwap(state.submitting, label = "submit") { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp))
                } else {
                    IconButton(onClick = viewModel::submit) {
                        Icon(Icons.Rounded.Check, contentDescription = "Create payout")
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
                FormField(
                    label = "Amount",
                    value = state.amount,
                    onValueChange = viewModel::setAmount,
                    keyboardType = KeyboardType.Decimal,
                    supportingText = "Leave empty to use the amount in the invoice.",
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
                    description = "Locks in a rate now and skips the review step.",
                )
                FormField(
                    label = "Pull payment id",
                    value = state.pullPaymentId,
                    onValueChange = viewModel::setPullPaymentId,
                    supportingText = "Optional. Charges this payout against an existing pull payment.",
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

