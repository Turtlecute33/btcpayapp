package com.btcpayapp.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.CreateInvoiceRequest
import com.btcpayapp.data.api.endpoints.createInvoice
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AnimatedValue
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.pressScale
import com.btcpayapp.ui.theme.AmountStyle
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The payment terminal: type an amount, charge it, show a QR.
 *
 * This is the screen a merchant actually uses, so it is built for the counter
 * rather than for a settings menu — big targets, one decision per step, haptics
 * on every key, and the screen kept awake while it is open.
 *
 * Digits are accumulated as an integer of minor units and only turned into a
 * decimal at the end. Typing into a `BigDecimal` string is where terminal apps
 * usually acquire their rounding bugs.
 */
data class TerminalState(
    val digits: String = "",
    val currency: String = "USD",
    val tipPercent: Int? = null,
    val askingForTip: Boolean = false,
    val creating: Boolean = false,
    val error: ApiException? = null,
    val availableCurrencies: List<String> = emptyList(),
) {
    /**
     * Minor-unit digits for the selected currency.
     *
     * Not a fixed 2 for everything but BTC: the currency chips offer SATS and
     * the store's own default. At a fixed 2, typing 1-0-0-0 with SATS selected
     * gives `1000.movePointLeft(2)` = 10.00, so the terminal would charge
     * **10 sat for a 1000 sat sale** — a 100x underbill, and the same for a
     * JPY/KRW/ISK store (0 digits) or a KWD/BHD/JOD one (3).
     */
    private val scale: Int get() = Amounts.scaleFor(currency)

    val baseAmount: BigDecimal
        get() = if (digits.isEmpty()) {
            BigDecimal.ZERO
        } else {
            BigDecimal(digits).movePointLeft(scale).setScale(scale, RoundingMode.DOWN)
        }

    val tipAmount: BigDecimal
        get() = tipPercent
            ?.let { baseAmount.multiply(BigDecimal(it)).divide(BigDecimal(100), scale, RoundingMode.HALF_UP) }
            ?: BigDecimal.ZERO

    val total: BigDecimal get() = baseAmount.add(tipAmount)

    val canCharge: Boolean get() = total.signum() > 0 && !creating
}

class TerminalViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state = _state.asStateFlow()

    val store = graph.session.activeStore

    init {
        viewModelScope.launch {
            graph.session.activeStore.collect { current ->
                val configured = graph.settings.settings.value.terminalCurrency
                _state.update {
                    it.copy(
                        currency = configured ?: current?.defaultCurrency ?: "USD",
                        availableCurrencies = listOfNotNull(
                            current?.defaultCurrency,
                            "USD", "EUR", "GBP", "CHF", "CZK", "SATS", "BTC",
                        ).distinct(),
                    )
                }
            }
        }
    }

    fun press(digit: Char) = _state.update { current ->
        // Cap the entry so a stuck key cannot produce an absurd invoice.
        if (current.digits.length >= 12) current else {
            val next = (current.digits + digit).trimStart('0')
            current.copy(digits = next, error = null)
        }
    }

    fun backspace() = _state.update { it.copy(digits = it.digits.dropLast(1), error = null) }

    fun clear() = _state.update { it.copy(digits = "", tipPercent = null, askingForTip = false, error = null) }

    fun setCurrency(code: String) {
        _state.update { current ->
            // The digit string is interpreted against the currency's scale, so
            // carrying it across a scale change silently reinterprets the
            // amount: "1234" means 12.34 in USD but 0.00001234 in BTC. Clear it
            // rather than charge a number the operator never typed.
            val rescale = Amounts.scaleFor(code) != Amounts.scaleFor(current.currency)
            current.copy(
                currency = code,
                digits = if (rescale) "" else current.digits,
                tipPercent = if (rescale) null else current.tipPercent,
                askingForTip = if (rescale) false else current.askingForTip,
            )
        }
        viewModelScope.launch {
            runCatching { graph.settings.update { it.copy(terminalCurrency = code) } }
        }
    }

    fun askForTip() = _state.update { it.copy(askingForTip = true) }

    fun setTip(percent: Int?) = _state.update { it.copy(tipPercent = percent, askingForTip = false) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun charge(onCreated: (String) -> Unit) {
        val snapshot = _state.value
        val storeId = runCatching { graph.session.requireStoreId() }.getOrElse {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
        if (!snapshot.canCharge) return

        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null) }

            runCatching {
                graph.session.requireApi().createInvoice(
                    storeId = storeId,
                    request = CreateInvoiceRequest(
                        amount = snapshot.total,
                        currency = snapshot.currency,
                        metadata = buildJsonObject {
                            put("itemDesc", JsonPrimitive("Terminal"))
                            // Recorded so the operator can reconcile a tip later;
                            // BTCPay keeps arbitrary metadata on the invoice.
                            snapshot.tipPercent?.let { put("tipPercent", JsonPrimitive(it)) }
                            if (snapshot.tipAmount.signum() > 0) {
                                put("tipAmount", JsonPrimitive(snapshot.tipAmount.toPlainString()))
                            }
                        },
                    ),
                )
            }.onSuccess { invoice ->
                _state.update { TerminalState(currency = snapshot.currency, availableCurrencies = snapshot.availableCurrencies) }
                onCreated(invoice.id)
            }.onFailure { failure ->
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
fun TerminalScreen(onCheckout: (String) -> Unit) {
    val viewModel = appViewModel { TerminalViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    // A terminal that sleeps mid-sale is useless.
    if (settings.terminalKeepScreenOn) {
        DisposableEffect(Unit) {
            val window = (context as? android.app.Activity)?.window
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    AppScreen(title = "Terminal", subtitle = store?.name) { padding ->
        // A plain boolean rather than the store itself: a session refresh hands
        // back an equal-but-new store, and a swap keyed on that object would
        // cross-fade the whole keypad — mid-sale — each time one arrived.
        AnimatedSwap(
            targetState = store != null,
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "terminal",
        ) { ready ->
            if (!ready) {
                EmptyState(
                    title = "No store selected",
                    description = "Choose a store before taking payments.",
                )
            } else {
                Column(Modifier.fillMaxSize()) {

                    ErrorBanner(error = state.error, onDismiss = viewModel::dismissError)

                    AmountDisplay(state = state, modifier = Modifier.weight(1f))

                    CurrencyRow(
                        selected = state.currency,
                        options = state.availableCurrencies,
                        onSelect = viewModel::setCurrency,
                    )

                    AnimatedVisibility(
                        visible = state.askingForTip,
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        TipRow(
                            percentages = settings.terminalTipPercentages,
                            selected = state.tipPercent,
                            onSelect = viewModel::setTip,
                        )
                    }

                    Keypad(
                        onDigit = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.press(it)
                        },
                        onBackspace = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.backspace()
                        },
                        onClear = viewModel::clear,
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (settings.terminalAskForTip && !state.askingForTip) {
                            FilledTonalButton(
                                onClick = viewModel::askForTip,
                                enabled = state.baseAmount.signum() > 0,
                                modifier = Modifier.height(56.dp),
                            ) {
                                Icon(Icons.Rounded.Percent, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Tip")
                            }
                        }
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.charge(onCheckout)
                            },
                            enabled = state.canCharge,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            AnimatedSwap(state.creating, label = "charge") { creating ->
                                if (creating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text("Charge", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountDisplay(state: TerminalState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The total is deliberately not animated. A digit lands every
            // 150ms or so during entry and a spring takes twice that to
            // settle, so a rolling figure would spend the whole of a sale
            // mid-slide — unreadable at exactly the moment the operator and
            // the customer are both checking it.
            Text(
                text = com.btcpayapp.core.util.Amounts.format(state.total, state.currency),
                style = AmountStyle,
                textAlign = TextAlign.Center,
            )
            if (state.tipAmount.signum() > 0) {
                Spacer(Modifier.height(6.dp))

                // The tip line does move, unlike the total above it. It is
                // settled by tapping a percentage rather than by typing, so in
                // practice it changes once a sale — and when it does, the
                // direction is worth seeing: 15% to 20% and 20% to 15% should
                // not look the same.
                var previous by remember { mutableStateOf(state.tipAmount) }
                val rising = state.tipAmount >= previous
                SideEffect { previous = state.tipAmount }

                AnimatedValue(
                    value = "includes ${com.btcpayapp.core.util.Amounts.format(state.tipAmount, state.currency)} tip",
                    upward = rising,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CurrencyRow(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { code ->
            FilterChip(
                selected = code == selected,
                onClick = { onSelect(code) },
                label = { Text(code) },
            )
        }
    }
}

@Composable
private fun TipRow(percentages: List<Int>, selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        percentages.forEach { percent ->
            FilterChip(
                selected = selected == percent,
                onClick = { onSelect(percent) },
                label = { Text("$percent%") },
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = { onSelect(null) }) { Text("None") }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = remember {
        listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf('C', '0', '<'),
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    KeypadKey(
                        key = key,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                'C' -> onClear()
                                '<' -> onBackspace()
                                else -> onDigit(key)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(key: Char, modifier: Modifier, onClick: () -> Unit) {
    // The key itself answers the touch, since the amount above deliberately
    // does not. Without that the only confirmation a digit registered is the
    // haptic tick and a number that may not visibly change — pressing 0 on an
    // empty entry, for instance — and an operator taking a payment at a
    // counter needs to see the key go down.
    val interactions = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .padding(4.dp)
            .aspectRatio(1.6f)
            .pressScale(interactions),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interactions,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (key) {
                '<' -> Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete")
                'C' -> Text("C", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                else -> Text(
                    text = key.toString(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
