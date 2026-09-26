package com.btcpayapp.ui.screens.invoice
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoicePaymentMethodData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.endpoints.activatePaymentMethod
import com.btcpayapp.data.api.endpoints.invoice
import com.btcpayapp.ui.LocalIsLocked
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.StatusChip
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The customer-facing payment screen: a QR, a countdown, and a live status.
 *
 * Progress is polled rather than pushed. BTCPay's only push mechanism is a
 * webhook to a public URL, which a phone does not have, and the alternative —
 * routing payment events through a third-party push service — would tell that
 * service when and how much a merchant is paid. A three-second poll while the
 * screen is open costs a few hundred bytes and leaks nothing.
 */
private const val POLL_INTERVAL_MS = 3_000L

/** Ceiling for the failure backoff. */
private const val MAX_POLL_INTERVAL_MS = 30_000L

data class CheckoutState(
    val invoice: InvoiceData? = null,
    val selectedMethodId: String? = null,
    val loading: Boolean = true,
    val error: ApiException? = null,
    val activating: Boolean = false,
) {
    val methods: List<InvoicePaymentMethodData> get() = invoice?.paymentMethods.orEmpty()
    val selected: InvoicePaymentMethodData?
        get() = methods.firstOrNull { it.paymentMethodId == selectedMethodId } ?: methods.firstOrNull()
}

class CheckoutViewModel(
    private val graph: AppGraph,
    private val invoiceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckoutState())
    val state = _state.asStateFlow()

    val baseUrl: String? get() = graph.session.activeAccount.value?.baseUrl

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    /**
     * One fetch. Suspends until it is done, so the poll loop can await it.
     *
     * Returns true when the invoice was updated. `sequence` discards a response
     * that started before one already applied. Without it, overlapping requests
     * on a slow Tor circuit resolve in whatever order they land, and an older
     * "New" response can overwrite a settled invoice and flip the merchant's
     * paid screen back to awaiting payment.
     */
    private var sequence = 0L
    private var applied = 0L

    private suspend fun fetch(): Boolean {
        val storeId = graph.session.activeStore.value?.id ?: return false
        val ticket = ++sequence
        return try {
            val data = graph.session.requireApi()
                .invoice(storeId, invoiceId, includePaymentMethods = true)
            if (ticket < applied) return false
            applied = ticket
            _state.update {
                it.copy(
                    invoice = data,
                    loading = false,
                    error = null,
                    selectedMethodId = it.selectedMethodId
                        ?: data.paymentMethods?.firstOrNull { m -> m.activated }?.paymentMethodId,
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.asApiException()) }
            false
        }
    }

    fun select(methodId: String) {
        _state.update { it.copy(selectedMethodId = methodId) }
        val method = _state.value.methods.firstOrNull { it.paymentMethodId == methodId }
        // Lazy payment methods have no address until they are asked for. Doing
        // it on selection is what keeps a store from reserving an on-chain
        // address for every invoice that is only ever paid over Lightning.
        if (method != null && !method.activated) activate(methodId)
    }

    private fun activate(methodId: String) {
        val storeId = graph.session.activeStore.value?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(activating = true) }
            val failure = runCatching {
                graph.session.requireApi().activatePaymentMethod(storeId, invoiceId, methodId)
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            // The failure is surfaced, not discarded: a 403 from a key without
            // the store scope otherwise turns the spinner off, reloads the same
            // unactivated method and says nothing, so "Activate" looks like a
            // button that simply does not work.
            _state.update { it.copy(activating = false, error = failure?.asApiException()) }
            fetch()
        }
    }

    /** Polls while the invoice can still change, then stops on its own. */
    suspend fun poll() {
        var backoff = POLL_INTERVAL_MS
        while (true) {
            delay(backoff)
            val status = _state.value.invoice?.status
            if (status != null && status != InvoiceStatus.New && status != InvoiceStatus.Processing) return
            // Awaited, so the next tick cannot start before this one finishes.
            val ok = fetch()
            // Back off on failure rather than hammering an unreachable node
            // every three seconds for the life of the checkout.
            backoff = if (ok) POLL_INTERVAL_MS else (backoff * 2).coerceAtMost(MAX_POLL_INTERVAL_MS)
        }
    }
}

/**
 * What the screen is showing, and the only thing its transition depends on.
 *
 * A discriminator rather than the state itself because this screen re-reads the
 * invoice every three seconds. Handing `AnimatedContent` the state object would
 * restart the transition on each poll, so the merchant would watch the checkout
 * blink at them for the whole time they were waiting to be paid.
 */
private enum class CheckoutPhase { Loading, Error, Paying, Paid }

@Composable
fun CheckoutScreen(
    invoiceId: String,
    onBack: () -> Unit,
    onOpenInvoice: (String) -> Unit,
) {
    val viewModel = appViewModel(key = "checkout-$invoiceId") { CheckoutViewModel(it, invoiceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val locked = LocalIsLocked.current
    val context = LocalContext.current

    // Suspending the poll while the lock screen is up keeps the app from
    // chattering to the server for a phone sitting in a pocket — and
    // `repeatOnLifecycle` does the same when the app is merely backgrounded,
    // which a bare `LaunchedEffect` would not: composition survives a stop,
    // so the 3-second poll would carry on in the user's pocket regardless.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(locked, lifecycleOwner) {
        if (locked) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.poll() }
    }

    val settled = state.invoice?.status == InvoiceStatus.Settled
    LaunchedEffect(settled) {
        if (settled && settings.terminalVibrateOnPaid) context.celebrate()
    }

    AppScreen(
        title = if (settled) "Paid" else "Awaiting payment",
        onBack = onBack,
        actions = {
            state.invoice?.checkoutLink?.let { link ->
                IconButton(
                    onClick = {
                        context.safeStartActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                },
                                "Share the payment link",
                            ),
                        )
                    },
                ) { Icon(Icons.Rounded.Share, contentDescription = "Share") }

                IconButton(
                    onClick = { context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) },
                ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in a browser") }
            }
        },
    ) { padding ->
        val invoice = state.invoice

        val phase = when {
            state.loading && invoice == null -> CheckoutPhase.Loading
            invoice == null -> CheckoutPhase.Error
            settled -> CheckoutPhase.Paid
            else -> CheckoutPhase.Paying
        }

        // The whole screen is replaced when the money lands, and that is the
        // one moment on this screen worth animating: an awaiting-payment layout
        // that is simply gone the next frame looks like a navigation, not like
        // a payment arriving.
        AnimatedSwap(phase, label = "checkout") { shown ->
            when (shown) {
                CheckoutPhase.Loading -> LoadingState(Modifier.padding(padding))

                CheckoutPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::load,
                )

                CheckoutPhase.Paid -> invoice?.let {
                    PaidState(
                        invoice = it,
                        modifier = Modifier.padding(padding),
                        onDone = onBack,
                        onOpenInvoice = { onOpenInvoice(it.id) },
                    )
                }

                CheckoutPhase.Paying -> PayingState(
                    state = state,
                    modifier = Modifier.padding(padding),
                    onSelect = viewModel::select,
                    onOpenInvoice = { invoice?.let { onOpenInvoice(it.id) } },
                )
            }
        }
    }
}

/**
 * The expiry bar and its one-second clock, isolated in their own composable.
 *
 * A ticking `now` in `PayingState` would recompose the whole checkout — the QR,
 * the payment-method chips, the copyable address and the currency formats —
 * once a second for the entire life of the invoice, on the screen the merchant
 * is looking at. Owning the state here confines the per-second invalidation to
 * this subtree.
 */
@Composable
private fun ExpiryCountdown(expirationTime: Long, createdTime: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expirationTime) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    val remaining = Dates.countdown(expirationTime, now)
    if (remaining == null) {
        Text(
            text = "This invoice has expired",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    val total = (expirationTime - createdTime).coerceAtLeast(1)
    val elapsed = (now - createdTime).coerceIn(0, total)
    // No `animateFloatAsState`. On a typical 15-minute invoice the per-second
    // delta is ~0.001, well under the animation's visibility threshold, so the
    // spring would snap immediately while still scheduling animation frames
    // for the whole checkout session.
    val progress = 1f - elapsed.toFloat() / total.toFloat()

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(4.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Expires in $remaining",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PayingState(
    state: CheckoutState,
    modifier: Modifier,
    onSelect: (String) -> Unit,
    onOpenInvoice: () -> Unit,
) {
    val invoice = state.invoice ?: return
    val method = state.selected
    val scroll = rememberScrollState()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        // Remembered: `Amounts.format` does an ICU currency lookup and
        // BigDecimal scaling, which there is no reason to repeat whenever this
        // screen recomposes.
        val amountText = remember(invoice.amount, invoice.currency) {
            Amounts.format(invoice.amount, invoice.currency)
        }
        Text(text = amountText, style = MaterialTheme.typography.displaySmall)

        // This line appears mid-wait, when an underpayment lands and the poll
        // brings it back. Expanding rather than being conjured into place means
        // the figure above it is not pushed up between two frames while the
        // merchant is reading it.
        AnimatedVisibility(
            visible = invoice.paidAmount.signum() > 0,
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            val dueText = remember(invoice.dueAmount, invoice.currency) {
                Amounts.format(invoice.dueAmount, invoice.currency)
            }
            Text(
                text = "$dueText still due",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        ExpiryCountdown(
            expirationTime = invoice.expirationTime,
            createdTime = invoice.createdTime,
        )

        Spacer(Modifier.height(16.dp))

        if (state.methods.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.methods.forEach { candidate ->
                    FilterChip(
                        selected = candidate.paymentMethodId == method?.paymentMethodId,
                        onClick = { onSelect(candidate.paymentMethodId) },
                        label = { Text(candidate.paymentMethodId.friendlyName()) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when {
            method == null -> Text("No payment method is available for this invoice.")

            !method.activated -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "This method has not been activated yet.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSelect(method.paymentMethodId) }) { Text("Activate") }
            }

            else -> {
                QrCode(
                    content = method.paymentLink ?: method.destination,
                    modifier = Modifier.widthIn(max = 320.dp),
                    contentDescription = "Payment code",
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "${Amounts.trim(method.due, 8)} ${method.currency} due",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                CopyableField(
                    label = method.paymentMethodId.friendlyName(),
                    value = method.destination,
                    truncate = true,
                )
                if (method.rate.signum() > 0) {
                    Text(
                        text = "Rate ${Amounts.format(method.rate, invoice.currency)} per ${method.currency}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        StatusChip(status = invoice.status)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenInvoice) { Text("Invoice details") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PaidState(
    invoice: InvoiceData,
    modifier: Modifier,
    onDone: () -> Unit,
    onOpenInvoice: () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A tick that draws itself, rather than a tick that was always
            // there. A static icon in an `AnimatedVisibility(visible = true)`
            // cannot do this: it is already visible on the frame it is first
            // composed, so the entrance has nothing to animate from and the
            // mark simply appears. The arrival of the whole block is the
            // swap's job; this is the confirmation itself, and it is the one
            // thing on the screen the merchant crossed the counter to see.
            SuccessCheck(size = 96.dp, color = AppTheme.statusColors.incoming)
            Spacer(Modifier.height(24.dp))
            Text(
                text = Amounts.format(invoice.paidAmount, invoice.currency),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Payment received",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onDone, modifier = Modifier.height(52.dp)) { Text("Done") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenInvoice) { Text("Invoice details") }
        }
    }
}

/** `BTC-CHAIN` reads badly on a chip; `Bitcoin` does not. */
private fun String.friendlyName(): String = when {
    endsWith("-CHAIN", true) -> substringBefore('-').let { if (it == "BTC") "Bitcoin" else it }
    endsWith("-LNURL", true) -> "LNURL"
    endsWith("-LN", true) -> "Lightning"
    else -> this
}

private fun android.content.Context.celebrate() {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }
    runCatching {
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 120), -1),
        )
    }
}
