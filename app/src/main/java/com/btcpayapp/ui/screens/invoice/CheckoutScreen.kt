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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.dto.InvoiceAdditionalStatus
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoicePaymentMethodData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.InvoiceType
import com.btcpayapp.data.api.endpoints.activatePaymentMethod
import com.btcpayapp.data.api.endpoints.invoiceWithPaymentMethods
import com.btcpayapp.ui.LocalIsLocked
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.KeepScreenOn
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.components.rememberLast
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
private const val POLL_NEW_MS = 3_000L

/**
 * A Processing invoice is paid and only waits for a block, which takes
 * minutes. Every three seconds would be about 1,200 requests for one on-chain
 * sale.
 */
private const val POLL_PROCESSING_MS = 15_000L

/** An expired invoice can still be paid late, until the server stops watching it. */
private const val POLL_EXPIRED_MS = 60_000L

/** Ceiling for the failure backoff, unless the phase's own interval is longer. */
private const val MAX_POLL_INTERVAL_MS = 30_000L

data class CheckoutState(
    val invoice: InvoiceData? = null,
    val selectedMethodId: String? = null,
    val loading: Boolean = true,
    /** The last read failed; the screen keeps the invoice it has and says so. */
    val error: ApiException? = null,
    val activating: Boolean = false,
    /**
     * Why the last activation failed. Apart from [error], so that the next
     * successful poll does not clear it before anyone has read it; it goes on
     * the user's next action instead.
     */
    val activationError: ApiException? = null,
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

    /**
     * The store this checkout was opened in, taken once.
     *
     * The shell closes every store screen when the store changes, so a later
     * read of `activeStore` could only find a store this invoice is not in,
     * and every poll would then 404 in silence. On a cold start
     * the store list may not be here yet, so this waits for the first store.
     */
    private val storeId = viewModelScope.async { graph.session.activeStore.filterNotNull().first().id }

    /**
     * `sequence` discards a response that started before one already applied.
     * Without it, overlapping requests on a slow Tor circuit resolve in
     * whatever order they land, and an older "New" response can overwrite a
     * settled invoice and flip the merchant's paid screen back to awaiting
     * payment.
     *
     * Declared above `init`: the first fetch starts inside it, and a counter
     * initialised after that would be reset to 0 under the running fetch.
     */
    private var sequence = 0L
    private var applied = 0L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    /**
     * One fetch. Suspends until it is done, so the poll loop can await it.
     * Returns true when the invoice was updated.
     */
    private suspend fun fetch(): Boolean {
        val ticket = ++sequence
        return try {
            // With the payment methods on every 2.x: servers before 2.4.1
            // ignore `includePaymentMethods`, and without the list there is
            // no QR to show.
            val data = graph.session.requireApi().invoiceWithPaymentMethods(storeId.await(), invoiceId)
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
        _state.update { it.copy(selectedMethodId = methodId, activationError = null) }
        val method = _state.value.methods.firstOrNull { it.paymentMethodId == methodId }
        // Lazy payment methods have no address until they are asked for. Doing
        // it on selection is what keeps a store from reserving an on-chain
        // address for every invoice that is only ever paid over Lightning.
        if (method != null && !method.activated) activate(methodId)
    }

    private fun activate(methodId: String) {
        // One at a time: each tap used to start another activation.
        if (_state.value.activating) return
        _state.update { it.copy(activating = true, activationError = null) }
        viewModelScope.launch {
            val failure = attempt {
                graph.session.requireApi().activatePaymentMethod(storeId.await(), invoiceId, methodId)
            }.exceptionOrNull()
            // Shown under the button until the next tap. A 403 from a key
            // without the store scope otherwise turns the spinner off, reloads
            // the same unactivated method and says nothing, so "Activate"
            // looks like a button that simply does not work.
            _state.update { it.copy(activating = false, activationError = failure?.asApiException()) }
            fetch()
        }
    }

    /**
     * Polls while the invoice can still change, then stops on its own.
     *
     * The interval follows the invoice ([pollIntervalMs]). A failure doubles
     * the wait, up to [MAX_POLL_INTERVAL_MS] or the phase's own interval,
     * rather than hammering an unreachable node for the life of the checkout.
     */
    suspend fun poll() {
        var failures = 0
        while (true) {
            val interval = pollIntervalMs(_state.value.invoice, System.currentTimeMillis() / 1000) ?: return
            val wait = if (failures == 0) {
                interval
            } else {
                (interval shl failures.coerceAtMost(4)).coerceAtMost(maxOf(MAX_POLL_INTERVAL_MS, interval))
            }
            delay(wait)
            // Awaited, so the next tick cannot start before this one finishes.
            failures = if (fetch()) 0 else failures + 1
        }
    }
}

/**
 * How long to wait before reading the invoice again, or null once it cannot
 * change. Null invoice means the first read has not landed yet.
 *
 * An expired invoice is read until `monitoringExpiration`, because BTCPay
 * still accepts a late payment until then, and that payment (PaidLate) is
 * what the merchant has to see. Settled and invalid are final.
 */
internal fun pollIntervalMs(invoice: InvoiceData?, nowSeconds: Long): Long? = when (invoice?.status) {
    null, InvoiceStatus.New -> POLL_NEW_MS
    InvoiceStatus.Processing -> POLL_PROCESSING_MS
    InvoiceStatus.Expired -> POLL_EXPIRED_MS.takeIf { nowSeconds < invoice.monitoringExpiration }
    InvoiceStatus.Settled, InvoiceStatus.Invalid, InvoiceStatus.Unknown -> null
}

/**
 * What the screen is showing, and the only thing its transition depends on.
 *
 * A discriminator rather than the state itself because this screen re-reads the
 * invoice every few seconds. Handing `AnimatedContent` the state object would
 * restart the transition on each poll, so the merchant would watch the checkout
 * blink at them for the whole time they were waiting to be paid.
 *
 * One phase per invoice status. Before, everything but Settled was "Paying":
 * an invoice paid on-chain and waiting for its block (Processing) kept the
 * title "Awaiting payment", the QR and "$0.00 still due", and turned red with
 * "This invoice has expired" after fifteen minutes, while an expired or
 * invalid invoice kept a code a customer could still scan and pay.
 */
internal enum class CheckoutPhase { Loading, Error, Paying, Received, Paid, Closed }

internal fun phaseOf(state: CheckoutState): CheckoutPhase {
    val invoice = state.invoice ?: return if (state.loading) CheckoutPhase.Loading else CheckoutPhase.Error
    return when (invoice.status) {
        InvoiceStatus.New -> CheckoutPhase.Paying
        InvoiceStatus.Processing -> CheckoutPhase.Received
        InvoiceStatus.Settled -> CheckoutPhase.Paid
        // A status this app does not know is not one to show a QR for.
        InvoiceStatus.Expired, InvoiceStatus.Invalid, InvoiceStatus.Unknown -> CheckoutPhase.Closed
    }
}

internal fun titleOf(phase: CheckoutPhase, invoice: InvoiceData?): String = when (phase) {
    CheckoutPhase.Loading, CheckoutPhase.Error -> "Checkout"
    CheckoutPhase.Paying -> "Awaiting payment"
    // "Detected", as in the notification: an unconfirmed payment can still be
    // replaced, so "received" is kept for a settled invoice.
    CheckoutPhase.Received -> "Payment detected"
    CheckoutPhase.Paid -> "Paid"
    CheckoutPhase.Closed -> when (invoice?.status) {
        InvoiceStatus.Expired -> "Invoice expired"
        InvoiceStatus.Invalid -> "Invoice invalid"
        else -> "Invoice"
    }
}

/** The one line that says where the payment stands; see [StatusLine]. */
internal fun statusOf(phase: CheckoutPhase, invoice: InvoiceData?): String? = when (phase) {
    CheckoutPhase.Loading, CheckoutPhase.Error -> null
    CheckoutPhase.Paying -> "Scan to pay"
    CheckoutPhase.Received -> "Waiting for confirmation"
    CheckoutPhase.Paid ->
        if (invoice?.additionalStatus == InvoiceAdditionalStatus.Marked) "Marked as paid" else "Payment received"
    CheckoutPhase.Closed -> when (invoice?.status) {
        InvoiceStatus.Expired -> "This invoice expired"
        InvoiceStatus.Invalid -> "This invoice is invalid"
        else -> "Open the invoice details to see its status."
    }
}

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
    // so the poll would carry on in the user's pocket regardless.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(locked, lifecycleOwner) {
        if (locked) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.poll() }
    }

    val settled = state.invoice?.status == InvoiceStatus.Settled
    LaunchedEffect(settled) {
        if (settled && settings.terminalVibrateOnPaid) context.celebrate()
    }

    val invoice = state.invoice
    val phase = phaseOf(state)
    val live = phase == CheckoutPhase.Paying || phase == CheckoutPhase.Received

    AppScreen(
        title = titleOf(phase, invoice),
        onBack = onBack,
        actions = {
            invoice?.checkoutLink?.let { link ->
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionProblem(error = state.error.takeIf { live })
            StatusLine(text = statusOf(phase, invoice))

            // The whole body is replaced when the status changes, and that is
            // the one moment on this screen worth animating: a layout that is
            // simply gone the next frame looks like a navigation, not like a
            // payment arriving.
            AnimatedSwap(phase, modifier = Modifier.weight(1f), label = "checkout") { shown ->
                when (shown) {
                    CheckoutPhase.Loading -> LoadingState()

                    CheckoutPhase.Error -> ErrorState(error = state.error, onRetry = viewModel::load)

                    CheckoutPhase.Paying -> PayingState(
                        state = state,
                        onSelect = viewModel::select,
                        onOpenInvoice = { onOpenInvoice(invoiceId) },
                    )

                    CheckoutPhase.Received -> invoice?.let {
                        OutcomeState(
                            mark = { OutcomeIcon(Icons.Rounded.HourglassTop) },
                            amount = Amounts.format(it.paidAmount, it.currency),
                            note = null,
                            onDone = onBack,
                            onOpenInvoice = { onOpenInvoice(invoiceId) },
                        )
                    }

                    CheckoutPhase.Paid -> invoice?.let {
                        PaidState(it, onDone = onBack, onOpenInvoice = { onOpenInvoice(invoiceId) })
                    }

                    CheckoutPhase.Closed -> invoice?.let {
                        OutcomeState(
                            mark = {
                                val expired = it.status == InvoiceStatus.Expired
                                OutcomeIcon(if (expired) Icons.Rounded.TimerOff else Icons.Rounded.Block)
                            },
                            amount = null,
                            note = lateOrPartial(it),
                            onDone = onBack,
                            onOpenInvoice = { onOpenInvoice(invoiceId) },
                        )
                    }
                }
            }
        }
    }
}

/** What an expired or invalid invoice still took, or null when it took nothing. */
private fun lateOrPartial(invoice: InvoiceData): String? {
    val paid = Amounts.format(invoice.paidAmount, invoice.currency)
    return when (invoice.additionalStatus) {
        InvoiceAdditionalStatus.PaidPartial -> "Partly paid: $paid"
        InvoiceAdditionalStatus.PaidLate -> "Paid after it expired: $paid"
        else -> null
    }
}

/**
 * Where the payment stands, in one line above the body.
 *
 * Outside the swap on purpose. A screen reader speaks a live region when its
 * text changes, not when a new one appears, and the swap builds each phase
 * from new nodes. Kept out here, this is the same node from "Scan to pay" to
 * "Payment received", so the change is spoken to an operator who is not
 * looking at the screen.
 */
@Composable
private fun StatusLine(text: String?) {
    if (text == null) return
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
}

/**
 * One line while a read fails and the last good invoice stays on screen.
 *
 * The poll already backs off and tries again; without this the merchant could
 * not tell a screen that stopped updating from one that has not been paid
 * yet, and might ask a customer who has paid to pay again.
 */
@Composable
private fun ConnectionProblem(error: ApiException?) {
    // Held, so the line keeps its wording while it collapses.
    val shown = rememberLast(error)
    AnimatedVisibility(
        visible = error != null,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(
                text = when (shown) {
                    is ApiException.Transport, is ApiException.Timeout, is ApiException.Tls ->
                        "Connection lost – retrying"
                    // Not "connection lost" for a refusal or a missing invoice:
                    // that would send the merchant to check the wrong thing.
                    else -> "Could not check the payment – retrying"
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
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
 *
 * Only ever shown for a New invoice: the server expires only those, and a paid
 * invoice past its time is not expired.
 *
 * It also keeps the screen on, for as long as it counts down: see below.
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

    // Awake while the customer can pay: the Terminal's keep-on ends when it
    // hands over to this screen, and a QR that sleeps in the customer's hand
    // pauses the poll with it. The idle lock is the customer-facing one: it
    // waits at least 15 minutes without a touch (see KeepScreenOn), so the app
    // does not lock while a customer pays, and a checkout left on the counter
    // still locks. The keep-on ends at the expiry by this phone's clock, also
    // when no poll gets through to report it, and then the screen sleeps and
    // the app locks as usual. A received payment has no code to show, so it
    // gets no keep-on.
    if (LocalSettings.current.terminalKeepScreenOn) KeepScreenOn(customerFacing = true)

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

/**
 * The customer's half: the amount, the code and the time left.
 *
 * Never masked. The customer reads these figures to pay.
 */
@Composable
private fun PayingState(
    state: CheckoutState,
    onSelect: (String) -> Unit,
    onOpenInvoice: () -> Unit,
) {
    val invoice = state.invoice ?: return
    val method = state.selected
    val unit = LocalSettings.current.bitcoinUnit
    val scroll = rememberScrollState()

    // A top-up invoice lets the payer choose, and the server reports its
    // amount as 0 until something arrives. "$0.00" and "0 BTC due" read as
    // "nothing to pay" to the customer.
    val anyAmount = invoice.type == InvoiceType.TopUp && invoice.paidAmount.signum() == 0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        // Remembered: `Amounts.format` does an ICU currency lookup and
        // BigDecimal scaling, which there is no reason to repeat whenever this
        // screen recomposes.
        val amountText = remember(invoice.amount, invoice.currency, anyAmount) {
            if (anyAmount) "Any amount" else Amounts.format(invoice.amount, invoice.currency)
        }
        Text(text = amountText, style = MaterialTheme.typography.displaySmall)

        // This line appears mid-wait, when an underpayment lands and the poll
        // brings it back. Expanding rather than being conjured into place means
        // the figure above it is not pushed up between two frames while the
        // merchant is reading it.
        AnimatedVisibility(
            visible = !anyAmount && invoice.paidAmount.signum() > 0,
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
                Button(
                    onClick = { onSelect(method.paymentMethodId) },
                    enabled = !state.activating,
                ) {
                    if (state.activating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Activate")
                }
                FormProblem(message = state.activationError?.userMessage)
            }

            else -> {
                QrCode(
                    content = method.paymentLink ?: method.destination,
                    modifier = Modifier.widthIn(max = 320.dp),
                    contentDescription = "Payment code",
                )
                Spacer(Modifier.height(16.dp))
                if (!anyAmount) {
                    Text(
                        text = "${methodAmountText(method.due, method.currency, unit)} due",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }
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
        OutlinedButton(onClick = onOpenInvoice) { Text("Invoice details") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PaidState(invoice: InvoiceData, onDone: () -> Unit, onOpenInvoice: () -> Unit) {
    // A manual mark says nothing about what arrived, which may be nothing: the
    // figure is the invoice's, and the status line says it was marked.
    val marked = invoice.additionalStatus == InvoiceAdditionalStatus.Marked
    OutcomeState(
        // A tick that draws itself, rather than a tick that was always there.
        // A static icon in an `AnimatedVisibility(visible = true)` cannot do
        // this: it is already visible on the frame it is first composed, so the
        // entrance has nothing to animate from and the mark simply appears. The
        // arrival of the whole block is the swap's job; this is the
        // confirmation itself, and it is the one thing on the screen the
        // merchant crossed the counter to see.
        mark = { SuccessCheck(size = 96.dp, color = AppTheme.statusColors.incoming) },
        amount = Amounts.format(if (marked) invoice.amount else invoice.paidAmount, invoice.currency),
        note = if (invoice.additionalStatus == InvoiceAdditionalStatus.PaidOver) "Paid more than the invoice" else null,
        onDone = onDone,
        onOpenInvoice = onOpenInvoice,
    )
}

/** The mark of a received or closed invoice. Decorative: the status line above says what it means. */
@Composable
private fun OutcomeIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The body once the invoice wants no more money: received, paid, or closed.
 *
 * None of them has a QR. A code left on screen after the money arrived, or
 * after the invoice expired, is a code a customer can still pay.
 *
 * Centred when it fits, and it scrolls when it does not: on a phone in
 * landscape the mark, the amount and both buttons are taller than the body.
 */
@Composable
private fun OutcomeState(
    mark: @Composable () -> Unit,
    amount: String?,
    note: String?,
    onDone: () -> Unit,
    onOpenInvoice: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        mark()
        if (amount != null) {
            Spacer(Modifier.height(24.dp))
            Text(text = amount, style = MaterialTheme.typography.displaySmall)
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.height(52.dp)) { Text("Done") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenInvoice) { Text("Invoice details") }
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
