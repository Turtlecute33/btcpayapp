package com.btcpayapp.ui.screens.wallet
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.WalletAddressData
import com.btcpayapp.data.api.endpoints.walletAddress
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.screens.send.amountProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** What the screen is showing, kept cheap so a refresh does not replay it. */
private enum class ReceivePhase { Loading, Error, Content }

data class WalletReceiveState(
    val address: WalletAddressData? = null,
    val amountInput: String = "",
    val loading: Boolean = false,
    val generating: Boolean = false,
    val error: ApiException? = null,
)

class WalletReceiveViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
) : ViewModel() {

    /**
     * The store this screen was opened in (see [StoreBinding]). The shell
     * drops the screen on a store switch, so "New address" can never reserve
     * one in a store the code on screen does not belong to.
     */
    private val store = StoreBinding(graph.session)
    private val storeId: String? get() = store.id

    /**
     * Shown in the title bar: an operator with several stores must see which
     * store's wallet the customer pays before showing the code.
     */
    val storeName: String? get() = store.store?.name?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(WalletReceiveState())
    val state = _state.asStateFlow()

    init {
        load(forceGenerate = false)
        store.retryWhenKnown(viewModelScope) { load(forceGenerate = false) }
    }

    fun setAmount(value: String) = _state.update { it.copy(amountInput = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * [forceGenerate] asks the server for a brand-new address instead of
     * re-serving the one it has already reserved and not yet seen paid.
     */
    fun load(forceGenerate: Boolean) {
        val storeId = storeId ?: run {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.address == null,
                    generating = forceGenerate,
                    error = null,
                )
            }
            runCatching {
                graph.session.requireApi().walletAddress(
                    storeId = storeId,
                    paymentMethodId = paymentMethodId,
                    forceGenerate = forceGenerate,
                )
            }.onSuccess { address ->
                _state.update {
                    it.copy(address = address, loading = false, generating = false, error = null)
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        loading = false,
                        generating = false,
                        error = failure as? ApiException
                            ?: ApiException.Transport(failure.message ?: "Unexpected failure"),
                    )
                }
            }
        }
    }
}

@Composable
fun WalletReceiveScreen(paymentMethodId: String, onBack: () -> Unit) {
    val viewModel = appViewModel(key = "receive-$paymentMethodId") {
        WalletReceiveViewModel(it, paymentMethodId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val context = LocalContext.current
    val cryptoCode = remember(paymentMethodId) { cryptoCodeOf(paymentMethodId) }
    val bitcoin = cryptoCode.equals("BTC", true)

    val amountBtc: BigDecimal? = remember(state.amountInput, settings.bitcoinUnit, cryptoCode) {
        parseAmountToBtc(state.amountInput, settings.bitcoinUnit, cryptoCode)
    }

    // Not masked in privacy mode, here or in the code: this screen is shown to
    // the customer who pays, and a hidden amount is one they cannot check.
    // The same wording as on Send, for the same field.
    val amountError = remember(state.amountInput, settings.bitcoinUnit, cryptoCode) {
        amountProblem(state.amountInput, settings.bitcoinUnit, cryptoCode)
    }

    // The same value in the other unit, under the field: a slip of three
    // zeros between sat and BTC shows here, before the customer pays it.
    val echo = remember(amountBtc, settings.bitcoinUnit) {
        amountBtc?.takeIf { bitcoin && it.signum() > 0 }?.let { "= ${Amounts.inOtherUnit(it, settings.bitcoinUnit)}" }
    }

    // BIP21 always denominates `amount` in BTC, whatever unit the user reads the
    // app in — a sat value here would be silently interpreted as 100 million
    // times too much by every other wallet.
    val payload: String? = remember(state.address, amountBtc) {
        val address = state.address
        when {
            address == null || address.address.isBlank() -> null
            cryptoCode.equals("BTC", true) && amountBtc != null && amountBtc.signum() > 0 ->
                "bitcoin:${address.address}?amount=${Amounts.trim(amountBtc, 8)}"
            else -> address.paymentLink?.takeIf { it.isNotBlank() } ?: address.address
        }
    }

    AppScreen(
        title = "Receive",
        subtitle = chainSubtitle(cryptoCode, prefix = viewModel.storeName),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    payload?.let { value ->
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, value)
                        }
                        context.safeStartActivity(Intent.createChooser(share, "Share payment request"))
                    }
                },
                enabled = payload != null,
            ) {
                Icon(Icons.Rounded.Share, contentDescription = "Share")
            }
            IconButton(onClick = { viewModel.load(forceGenerate = true) }, enabled = !state.generating) {
                Icon(Icons.Rounded.Autorenew, contentDescription = "New address")
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> ReceivePhase.Loading
            state.address == null && state.error != null -> ReceivePhase.Error
            else -> ReceivePhase.Content
        }

        // Held across the swap out: retry clears `state.error` on the frame the
        // exit begins, and the outgoing branch is composed until it has finished
        // leaving.
        val lastError = remember { mutableStateOf<ApiException?>(null) }
        if (state.error != null) lastError.value = state.error

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "receive") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is coming is a QR code
                // and two fields, and rows in outline would hold space in the
                // wrong shape.
                ReceivePhase.Loading -> LoadingState()

                ReceivePhase.Error -> lastError.value?.let { failure ->
                    ErrorState(error = failure, onRetry = { viewModel.load(forceGenerate = false) })
                }

                ReceivePhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    // The code fades itself in as soon as it is encoded; the
                    // blocks around it follow it down the screen rather than
                    // appearing beside it, so the eye lands on the thing the
                    // camera is being pointed at first.
                    if (payload != null) {
                        QrCode(
                            content = payload,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                            contentDescription = "Payment QR code",
                        )
                    }

                    // No snackbar on copy: the copy itself says so, with a
                    // toast below Android 13 and the system's own line above.
                    state.address?.let { address ->
                        CopyableField(
                            label = "Address",
                            value = address.address,
                            modifier = Modifier.padding(horizontal = 16.dp).arrive(1),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    FormField(
                        label = "Amount (${unitLabel(settings.bitcoinUnit, cryptoCode)})",
                        enabled = bitcoin,
                        value = state.amountInput,
                        onValueChange = viewModel::setAmount,
                        modifier = Modifier.arrive(2),
                        placeholder = "Optional",
                        supportingText = when {
                            echo != null -> echo
                            bitcoin -> "Adding an amount turns the code into a BIP21 payment request."
                            else -> "Use the server's payment link or address for this currency."
                        },
                        error = amountError,
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    )

                    // No "Details" section and no paragraph about address reuse.
                    // A fresh address is reserved per payment automatically, so
                    // an explanation would narrate behaviour the user never has
                    // to choose; the key path stays because it is what you check
                    // against a hardware wallet.
                    state.address?.let { address ->
                        DetailRow(
                            label = "Derivation path",
                            value = address.keyPath.ifBlank { "\u2014" },
                            modifier = Modifier.arrive(3),
                            monospace = true,
                        )
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

internal fun unitLabel(unit: BitcoinUnit, cryptoCode: String = "BTC"): String = if (!cryptoCode.equals("BTC", true)) cryptoCode else when (unit) {
    BitcoinUnit.Btc -> "BTC"
    BitcoinUnit.Sat -> "sat"
}

/** Reads what the user typed in their chosen unit and returns BTC, or null. */
internal fun parseAmountToBtc(input: String, unit: BitcoinUnit, cryptoCode: String = "BTC"): BigDecimal? {
    val value = Amounts.parse(input) ?: return null
    if (value.signum() < 0) return null
    if (!cryptoCode.equals("BTC", true)) return value
    val btc = if (unit == BitcoinUnit.Sat) value.movePointLeft(8) else value
    return btc.takeIf { it.stripTrailingZeros().scale() <= 8 }
}

/**
 * The inverse of [parseAmountToBtc], for pre-filling a field from a scan.
 *
 * Through [Amounts.toInput], so the text always parses back to the same
 * value: 1.125 BTC is written "1.1250", which [Amounts.parse] reads as a
 * decimal, where "1.125" is refused as possibly meaning 1125.
 */
internal fun formatAmountInput(btc: BigDecimal, unit: BitcoinUnit): String = when (unit) {
    BitcoinUnit.Btc -> Amounts.toInput(btc, 8)
    BitcoinUnit.Sat -> Amounts.toInput(Amounts.btcToSats(btc), 0)
}
