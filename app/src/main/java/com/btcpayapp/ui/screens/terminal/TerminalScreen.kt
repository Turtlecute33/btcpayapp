package com.btcpayapp.ui.screens.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.CreateInvoiceRequest
import com.btcpayapp.data.api.endpoints.createInvoice
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AnimatedValue
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.KeepScreenOn
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.pressScale
import com.btcpayapp.ui.theme.AmountStyle
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
 *
 * The arithmetic lives here, on the state, and not in the view model, so
 * `TerminalStateTest` runs the code that bills the customer rather than a copy
 * of it.
 */
data class TerminalState(
    val digits: String = "",
    val currency: String = "USD",
    val tipPercent: Int? = null,
    val askingForTip: Boolean = false,
    val creating: Boolean = false,
    val error: ApiException? = null,
    val availableCurrencies: List<String> = emptyList(),
    /**
     * The store this terminal charges into, null until the session reports
     * one. Held here, and never re-read from the session, so a charge cannot
     * land in a store the amount was not typed for.
     */
    val storeId: String? = null,
    /** `"$accountId|$storeId"`, the key of the remembered currency in `AppSettings.terminalCurrencies`. */
    val currencyKey: String? = null,
    /** False when the key certainly cannot create invoices in [storeId]; see `Permissions`. */
    val canCreateInvoice: Boolean = true,
    /** A one-off line for the snackbar, such as a setting that did not save. */
    val notice: String? = null,
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

    /**
     * [digit] appended to the entry. The entry stops at [MAX_DIGITS], so a
     * stuck key cannot produce an absurd invoice, and leading zeros are
     * dropped, so "0005" is the same five cents as "5".
     */
    internal fun typed(digit: Char): TerminalState =
        if (digits.length >= MAX_DIGITS) this else copy(digits = (digits + digit).trimStart('0'), error = null)

    /**
     * The same entry in [code].
     *
     * The digit string is interpreted against the currency's scale, so
     * carrying it across a scale change silently reinterprets the amount:
     * "1234" means 12.34 in USD but 0.00001234 in BTC. It is cleared rather
     * than charge a number the operator never typed.
     */
    internal fun withCurrency(code: String): TerminalState {
        val rescale = Amounts.scaleFor(code) != Amounts.scaleFor(currency)
        return copy(
            currency = code,
            digits = if (rescale) "" else digits,
            tipPercent = if (rescale) null else tipPercent,
            askingForTip = if (rescale) false else askingForTip,
        )
    }

    /** Ready for the next sale: the entry goes, the store and the currency stay. */
    internal fun cleared(): TerminalState =
        copy(digits = "", tipPercent = null, askingForTip = false, creating = false, error = null)

    companion object {
        internal const val MAX_DIGITS = 12
    }
}

class TerminalViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(TerminalState())
    val state = _state.asStateFlow()

    val store = graph.session.activeStore
    val storesLoaded = graph.session.storesLoaded
    val sessionRefreshing = graph.session.refreshing
    val sessionError = graph.session.lastError

    init {
        viewModelScope.launch {
            // The first store the session reports is the one this terminal
            // charges into, for as long as it lives. The shell replaces the
            // terminal when the store changes, so nothing here
            // watches for a switch, and no scale rule is needed for one.
            val bound = graph.session.activeStore.filterNotNull().first()
            val accountId = graph.session.activeAccount.value?.id ?: return@launch
            val key = "$accountId|${bound.id}"
            // Per account and store, not one global value: a currency tapped
            // once in store A must not override store B's default.
            val remembered = graph.settings.settings.value.terminalCurrencies[key]
            _state.update {
                it.copy(
                    storeId = bound.id,
                    currencyKey = key,
                    currency = remembered ?: bound.defaultCurrency,
                    availableCurrencies = listOfNotNull(
                        bound.defaultCurrency,
                        remembered,
                        "USD", "EUR", "GBP", "CHF", "CZK", "SATS", "BTC",
                    ).distinct(),
                    canCreateInvoice = graph.session.canCreateInvoice(bound.id),
                )
            }
        }
    }

    fun press(digit: Char) = _state.update { it.typed(digit) }

    fun backspace() = _state.update { it.copy(digits = it.digits.dropLast(1), error = null) }

    fun clear() = _state.update { it.copy(digits = "", tipPercent = null, askingForTip = false, error = null) }

    fun setCurrency(code: String) {
        val current = _state.value
        val key = current.currencyKey ?: return
        if (code == current.currency) return
        _state.update { it.withCurrency(code) }
        viewModelScope.launch {
            val saved = graph.settings.update {
                it.copy(terminalCurrencies = it.terminalCurrencies + (key to code))
            }
            // The chip stays selected for this sale either way; the operator
            // only needs to know it will not be there next time.
            if (!saved) _state.update { it.copy(notice = "Could not save the setting.") }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun askForTip() = _state.update { it.copy(askingForTip = true) }

    fun setTip(percent: Int?) = _state.update { it.copy(tipPercent = percent, askingForTip = false) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Loads the store list again, for the failure state. */
    fun retry() {
        viewModelScope.launch { graph.session.refresh() }
    }

    fun charge(onCreated: (String) -> Unit) {
        val snapshot = _state.value
        if (!snapshot.canCharge) return
        val storeId = snapshot.storeId ?: run {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
        // Set before the launch, so a second tap in the same frame finds
        // `creating` already true and cannot create a second invoice.
        _state.update { it.copy(creating = true, error = null) }

        viewModelScope.launch {
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
                _state.update { it.cleared() }
                onCreated(invoice.id)
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update { it.copy(creating = false, error = error) }
            }
        }
    }
}

/**
 * What the Terminal tab is showing.
 *
 * A discriminator rather than the store itself: a session refresh hands back
 * an equal-but-new store, and a swap keyed on that object would cross-fade the
 * whole keypad — mid-sale — each time one arrived.
 */
private enum class TerminalPhase { Loading, Failed, NoStore, Ready }

@Composable
fun TerminalScreen(onCheckout: (String) -> Unit) {
    val viewModel = appViewModel { TerminalViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val storesLoaded by viewModel.storesLoaded.collectAsStateWithLifecycle()
    val refreshing by viewModel.sessionRefreshing.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.noticeShown()
    }

    // The store list takes a while on a cold start, longer over Tor. Until it
    // is here this says so, and a failure offers a retry, instead of a "No
    // store" that is not true and has no way out. A retry shows
    // as loading, so "Try again" visibly does something.
    val phase = when {
        store != null -> if (state.storeId != null) TerminalPhase.Ready else TerminalPhase.Loading
        refreshing -> TerminalPhase.Loading
        sessionError != null -> TerminalPhase.Failed
        !storesLoaded -> TerminalPhase.Loading
        else -> TerminalPhase.NoStore
    }

    AppScreen(title = "Terminal", subtitle = store?.name, snackbarHostState = snackbar) { padding ->
        AnimatedSwap(
            targetState = phase,
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "terminal",
        ) { shown ->
            when (shown) {
                TerminalPhase.Loading -> LoadingState()

                TerminalPhase.Failed -> ErrorState(error = sessionError, onRetry = viewModel::retry)

                TerminalPhase.NoStore -> EmptyState(
                    title = "No store",
                    description = "This key cannot see a store. Create one on the server, or pair again.",
                )

                TerminalPhase.Ready -> TerminalBody(state = state, viewModel = viewModel, onCheckout = onCheckout)
            }
        }
    }
}

/** No pane of the terminal is wider than this. Wider keys only move the digits apart. */
private val MAX_PANE_WIDTH = 600.dp

/** From this width the amount and the keypad sit side by side, as they do in landscape. */
private val TWO_PANE_WIDTH = 600.dp

/** The amount area never gets less than this, so the figure being charged is always on screen. */
private val MIN_AMOUNT_HEIGHT = 96.dp

/**
 * The keypad, the amount and the Charge button, fitted to the space there is.
 *
 * Nothing here scrolls, so every part is sized from what is left rather than
 * from its own width. Before, the keys took their height from their width and
 * four rows of them needed about 1000dp on a tablet in landscape: the Column
 * then gave the bottom keys, the Charge button and the amount no height at
 * all. Now the amount is measured before the keypad and keeps
 * [MIN_AMOUNT_HEIGHT], the Charge row is measured before the keypad too, and
 * the keypad shrinks into what remains.
 *
 * In landscape, or from [TWO_PANE_WIDTH], the amount side and the keypad side
 * sit next to each other, because four rows of keys under an amount do not fit
 * a screen that is wider than it is tall.
 */
@Composable
private fun TerminalBody(state: TerminalState, viewModel: TerminalViewModel, onCheckout: (String) -> Unit) {
    val settings = LocalSettings.current
    val haptics = LocalHapticFeedback.current

    val entry: @Composable ColumnScope.() -> Unit = {
        AmountEntry(
            state = state,
            tipPercentages = settings.terminalTipPercentages,
            onCurrency = viewModel::setCurrency,
            onTip = viewModel::setTip,
        )
    }
    val pad: @Composable () -> Unit = {
        ChargePad(
            state = state,
            offerTip = settings.terminalAskForTip,
            onDigit = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.press(it)
            },
            onBackspace = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.backspace()
            },
            onClear = viewModel::clear,
            onTip = viewModel::askForTip,
            onCharge = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.charge(onCheckout)
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val twoPane = maxWidth > maxHeight || maxWidth >= TWO_PANE_WIDTH

        // A terminal that sleeps mid-sale is useless. This also arms the idle
        // lock, so a counter phone that never sleeps still locks when left.
        if (settings.terminalKeepScreenOn) KeepScreenOn()

        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = if (twoPane) MAX_PANE_WIDTH * 2 else MAX_PANE_WIDTH)
                .fillMaxWidth(),
        ) {
            ErrorBanner(error = state.error, onDismiss = viewModel::dismissError)

            if (twoPane) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight(), content = entry)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { pad() }
                }
            } else {
                entry()
                pad()
            }
        }
    }
}

/**
 * The figure, the currency chips and the tip choice.
 *
 * The figure is measured before anything below it and never gets less than
 * [MIN_AMOUNT_HEIGHT]. The two spacers share whatever height the keypad
 * leaves, so the figure sits in the middle of it.
 */
@Composable
private fun ColumnScope.AmountEntry(
    state: TerminalState,
    tipPercentages: List<Int>,
    onCurrency: (String) -> Unit,
    onTip: (Int?) -> Unit,
) {
    Spacer(Modifier.weight(1f))
    AmountDisplay(state = state)
    Spacer(Modifier.weight(1f))

    CurrencyRow(selected = state.currency, options = state.availableCurrencies, onSelect = onCurrency)

    AnimatedVisibility(
        visible = state.askingForTip,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        TipRow(percentages = tipPercentages, selected = state.tipPercent, onSelect = onTip)
    }
}

/**
 * The keypad over the Charge row.
 *
 * The keypad is weighted, so this column measures the Charge row first and
 * the keys only get the height the row leaves: the button is always there to
 * tap. `fill = false` lets the keypad stay at its natural size when there is
 * more room than it needs.
 */
@Composable
private fun ChargePad(
    state: TerminalState,
    offerTip: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onTip: () -> Unit,
    onCharge: () -> Unit,
) {
    Column {
        Keypad(
            onDigit = onDigit,
            onBackspace = onBackspace,
            onClear = onClear,
            modifier = Modifier.weight(1f, fill = false),
        )
        ChargeRow(state = state, offerTip = offerTip, onTip = onTip, onCharge = onCharge)
    }
}

@Composable
private fun ChargeRow(state: TerminalState, offerTip: Boolean, onTip: () -> Unit, onCharge: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.canCreateInvoice) {
            // In place of a Charge button the server would refuse with a 403
            // after the amount was typed.
            Text(
                text = "This key cannot create invoices. Pair again with “Take payments” or “Full access”.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            if (offerTip && !state.askingForTip) {
                FilledTonalButton(
                    onClick = onTip,
                    enabled = state.baseAmount.signum() > 0,
                    modifier = Modifier.height(56.dp),
                ) {
                    Icon(Icons.Rounded.Percent, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Tip")
                }
            }
            Button(
                onClick = onCharge,
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

@Composable
private fun AmountDisplay(state: TerminalState) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = MIN_AMOUNT_HEIGHT).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The total is deliberately not animated. A digit lands every
            // 150ms or so during entry and a spring takes twice that to
            // settle, so a rolling figure would spend the whole of a sale
            // mid-slide — unreadable at exactly the moment the operator and
            // the customer are both checking it.
            //
            // Never masked: the customer reads it to pay. It shrinks rather
            // than wraps or cuts, so twelve digits of BTC stay one readable
            // line, and it is a polite live region, so a screen reader speaks
            // the total after each key.
            Text(
                text = Amounts.format(state.total, state.currency),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = AmountStyle,
                textAlign = TextAlign.Center,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 16.sp,
                    maxFontSize = AmountStyle.fontSize,
                    stepSize = 1.sp,
                ),
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
                    value = "includes ${Amounts.format(state.tipAmount, state.currency)} tip",
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
        contentPadding = PaddingValues(horizontal = 16.dp),
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

private val KEYS = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', 'C', '0', '<')
private const val KEY_COLUMNS = 3
private const val KEY_ROWS = 4

/** A key's width over its height, when there is room for it. */
private const val KEY_RATIO = 1.6f

/** The space around each key, inside its cell. */
private val KEY_INSET = 4.dp

/**
 * The twelve keys, sized to the space they are given.
 *
 * A key is a third of the pad wide and 1/[KEY_RATIO] of that high, unless four
 * rows of that do not fit; then the rows share the height there is. Measured
 * here rather than with `aspectRatio`, which cannot give way: it made the pad
 * taller than the screen on a landscape phone or a tablet. The pad
 * is never taller than its keys, so spare height goes to the amount above.
 *
 * Always left to right. A phone keypad reads 1-2-3 in every language; mirrored
 * on a right-to-left system it read 3-2-1.
 */
@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Layout(
            modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
            content = {
                KEYS.forEach { key ->
                    KeypadKey(
                        key = key,
                        onClick = {
                            when (key) {
                                'C' -> onClear()
                                '<' -> onBackspace()
                                else -> onDigit(key)
                            }
                        },
                    )
                }
            },
        ) { measurables, constraints ->
            val cellWidth = constraints.maxWidth / KEY_COLUMNS
            val inset = KEY_INSET.roundToPx() * 2
            val natural = ((cellWidth - inset).coerceAtLeast(0) / KEY_RATIO).roundToInt() + inset
            val cellHeight = if (constraints.hasBoundedHeight) {
                minOf(natural, constraints.maxHeight / KEY_ROWS)
            } else {
                natural
            }
            val cell = Constraints.fixed(cellWidth, cellHeight)
            val placeables = measurables.map { it.measure(cell) }
            layout(constraints.maxWidth, cellHeight * KEY_ROWS) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative((index % KEY_COLUMNS) * cellWidth, (index / KEY_COLUMNS) * cellHeight)
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(key: Char, onClick: () -> Unit) {
    // The key itself answers the touch, since the amount above deliberately
    // does not. Without that the only confirmation a digit registered is the
    // haptic tick and a number that may not visibly change — pressing 0 on an
    // empty entry, for instance — and an operator taking a payment at a
    // counter needs to see the key go down.
    val interactions = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .padding(KEY_INSET)
            .pressScale(interactions),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interactions,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (key) {
                '<' -> Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete")
                // Read as "Clear", not as the letter C.
                'C' -> Text(
                    text = "C",
                    modifier = Modifier.clearAndSetSemantics { contentDescription = "Clear" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                else -> Text(
                    text = key.toString(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
