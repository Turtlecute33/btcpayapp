package com.btcpayapp.ui.screens.lightning
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.btcpayapp.data.api.dto.CreateLightningInvoiceRequest
import com.btcpayapp.data.api.dto.LightningInvoiceData
import com.btcpayapp.data.api.dto.LightningInvoiceStatus
import com.btcpayapp.data.api.endpoints.LightningScope
import com.btcpayapp.data.api.endpoints.createLightningInvoice
import com.btcpayapp.data.api.endpoints.lightningInvoice
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.ui.LocalIsLocked
import com.btcpayapp.ui.LocalSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.FormProblem
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.ExpandableSection
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.KeepScreenOn
import com.btcpayapp.ui.components.PulsingDot
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.copyToClipboard
import com.btcpayapp.ui.components.NoStoreSelectedState
import com.btcpayapp.ui.screens.send.amountProblem
import com.btcpayapp.ui.screens.wallet.parseAmountToBtc
import com.btcpayapp.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Three seconds is a compromise: fast enough that a customer's payment lands on
 * screen while they are still holding the phone, slow enough not to hammer a
 * node that may be behind Tor.
 */
private const val POLL_INTERVAL_MS = 3_000L

/** Ceiling for the failure backoff: a node that is down is asked twice a minute, not twenty times. */
private const val MAX_POLL_INTERVAL_MS = 30_000L

/**
 * Failed checks in a row before the screen stops saying it is watching for the
 * payment. One dropped poll on a Tor circuit is routine; three in a row (about
 * 20 s with the backoff) is a node or a link that is down.
 */
private const val FAILURES_BEFORE_NOTICE = 3

data class LightningReceiveState(
    val amount: String = "",
    val description: String = "",
    val expiryMinutes: String = "60",
    val privateRouteHints: Boolean = false,
    val creating: Boolean = false,
    val invoice: LightningInvoiceData? = null,
    val error: ApiException? = null,
    val formError: String? = null,
    val noStore: Boolean = false,
    /** Status checks that failed in a row; see [FAILURES_BEFORE_NOTICE]. */
    val pollFailures: Int = 0,
)

class LightningReceiveViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    private val node = LightningBinding(graph.session, serverNode)

    /**
     * Shown in the title bar: an operator with several stores must see which
     * store receives before showing the code.
     */
    val storeName: String? get() = node.storeName

    private val _state = MutableStateFlow(LightningReceiveState(noStore = node.scope == null))
    val state = _state.asStateFlow()

    init {
        node.retryWhenKnown(viewModelScope) { _state.update { it.copy(noStore = false) } }
    }

    fun setAmount(value: String) = _state.update { it.copy(amount = value, formError = null) }

    fun setDescription(value: String) = _state.update { it.copy(description = value, formError = null) }

    fun setExpiry(value: String) = _state.update { it.copy(expiryMinutes = value, formError = null) }

    fun setPrivateRouteHints(value: Boolean) = _state.update { it.copy(privateRouteHints = value) }

    fun reset() = _state.update { LightningReceiveState(noStore = node.scope == null) }

    fun create(unit: BitcoinUnit) {
        val snapshot = _state.value
        // `enabled` is one recomposition behind the click, so a double tap
        // would otherwise make two invoices.
        if (snapshot.creating) return
        // Zero is refused, not sent: a node reads 0 msat as "any amount", so
        // the payer would choose it. Nothing is rounded to 0 sat either.
        val btc = parseAmountToBtc(snapshot.amount, unit)?.takeIf { it.signum() > 0 }
        if (btc == null) {
            val problem = amountProblem(snapshot.amount, unit) ?: "Enter an amount greater than zero."
            _state.update { it.copy(formError = problem) }
            return
        }
        val minutes = snapshot.expiryMinutes.trim().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(formError = "Expiry must be a number of minutes.") }
            return
        }

        val scope = node.scope ?: run {
            _state.update { it.copy(noStore = true) }
            return
        }

        // The wire format is millisatoshi; the field is in whatever unit the
        // user reads the rest of the app in. Exact, not rounded:
        // parseAmountToBtc refuses more than 8 decimals, so this is whole sat.
        val msat = Amounts.satsToMsat(Amounts.btcToSats(btc))

        _state.update { it.copy(creating = true, error = null, formError = null) }
        viewModelScope.launch {
            runCatching {
                graph.session.requireApi().createLightningInvoice(
                    scope = scope,
                    request = CreateLightningInvoiceRequest(
                        amount = msat,
                        description = snapshot.description.trim().ifBlank { null },
                        expiry = minutes * 60,
                        privateRouteHints = snapshot.privateRouteHints,
                    ),
                    cryptoCode = cryptoCode,
                )
            }.onSuccess { invoice ->
                _state.update { it.copy(creating = false, invoice = invoice, error = null, pollFailures = 0) }
            }.onFailure { failure ->
                _state.update { it.copy(creating = false, error = failure.asApiException()) }
            }
        }
    }

    /**
     * Checks the invoice until it is no longer unpaid, then returns.
     *
     * Each check is awaited, so a slow node on Tor cannot have several in
     * flight at once, and failures back off to [MAX_POLL_INTERVAL_MS]. The
     * screen runs this in `repeatOnLifecycle(RESUMED)`, which cancels it in
     * the background and starts it again on return. A cancelled check applies
     * nothing, so an older answer cannot land after a newer one, and
     * [withFresh] keeps Paid final.
     */
    suspend fun poll() {
        val scope = node.scope ?: return
        var backoff = POLL_INTERVAL_MS
        var failures = 0
        while (true) {
            delay(backoff)
            val shown = _state.value.invoice ?: return
            if (shown.status != LightningInvoiceStatus.Unpaid) return
            val ok = checkStatus(scope, shown.id)
            failures = if (ok) 0 else failures + 1
            backoff = if (ok) POLL_INTERVAL_MS else (backoff * 2).coerceAtMost(MAX_POLL_INTERVAL_MS)
            _state.update { it.copy(pollFailures = failures) }
        }
    }

    /** One status check; true when the node answered. */
    private suspend fun checkStatus(scope: LightningScope, id: String): Boolean {
        return try {
            val fresh = graph.session.requireApi().lightningInvoice(scope, id, cryptoCode)
            _state.update { it.withFresh(fresh) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * [fresh] applied to the invoice on screen, if it is still that invoice. Paid
 * is final: no later answer moves the screen back to the QR code.
 */
private fun LightningReceiveState.withFresh(fresh: LightningInvoiceData): LightningReceiveState {
    val shown = invoice ?: return this
    if (shown.id != fresh.id || shown.status == LightningInvoiceStatus.Paid) return this
    return copy(invoice = fresh)
}

/**
 * What the screen is showing.
 *
 * A discriminator rather than the invoice itself, because this screen re-reads
 * that invoice every three seconds while it waits to be paid. Handing the
 * object to `AnimatedContent` would replay the transition on every poll, and
 * the merchant would watch the QR code they are holding out blink at them.
 */
private enum class ReceivePhase { NoStore, Paid, Invoice, Form }

@Composable
fun LightningReceiveScreen(
    cryptoCode: String,
    serverNode: Boolean,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "ln-receive:$cryptoCode:$serverNode") {
        LightningReceiveViewModel(it, cryptoCode, serverNode)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val locked = LocalIsLocked.current
    val context = LocalContext.current

    var optionsOpen by rememberSaveable { mutableStateOf(false) }

    val invoice = state.invoice

    // Polling is deliberately bounded: it runs only while this screen is
    // resumed, only while the app is unlocked, and only while the invoice can
    // still change (`poll` returns once it cannot). `repeatOnLifecycle` is what
    // stops it in the background. Composition survives onStop, so a bare
    // LaunchedEffect kept asking the node every three seconds from the
    // user's pocket for the whole life of a 60-minute invoice.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(invoice?.id, locked, lifecycleOwner) {
        if (invoice == null || locked) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.poll() }
    }

    AppScreen(
        title = "Receive",
        subtitle = chainSubtitle(cryptoCode, prefix = if (serverNode) "Server node" else viewModel.storeName),
        onBack = onBack,
    ) { padding ->
        val phase = when {
            state.noStore -> ReceivePhase.NoStore
            invoice == null -> ReceivePhase.Form
            invoice.status == LightningInvoiceStatus.Paid -> ReceivePhase.Paid
            else -> ReceivePhase.Invoice
        }

        AnimatedSwap(phase, Modifier.fillMaxSize(), label = "receive") { shown ->
            when (shown) {
                ReceivePhase.NoStore -> NoStoreSelectedState()

                // Read through `?.let` rather than `!!`. A branch on its way
                // out of a swap stays composed while it leaves, and "New
                // invoice" has already cleared the invoice it was built from.
                ReceivePhase.Paid -> state.invoice?.let { paid ->
                    PaidState(
                        invoice = paid,
                        unit = settings.bitcoinUnit,
                        onAgain = viewModel::reset,
                        onDone = onBack,
                        modifier = Modifier.padding(padding),
                    )
                }

                ReceivePhase.Invoice -> state.invoice?.let { unpaid ->
                    InvoiceState(
                        invoice = unpaid,
                        unit = settings.bitcoinUnit,
                        reconnecting = state.pollFailures >= FAILURES_BEFORE_NOTICE,
                        // No snackbar: `copyToClipboard` says "Copied" below
                        // Android 13 and the system does from 13.
                        onCopy = { copyToClipboard(context, "Lightning invoice", unpaid.BOLT11) },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "lightning:${unpaid.BOLT11}")
                            }
                            context.safeStartActivity(Intent.createChooser(send, "Share invoice"))
                        },
                        onAgain = viewModel::reset,
                        modifier = Modifier.padding(padding),
                    )
                }

                ReceivePhase.Form -> CreateForm(
                    state = state,
                    unit = settings.bitcoinUnit,
                    onAmount = viewModel::setAmount,
                    onDescription = viewModel::setDescription,
                    onExpiry = viewModel::setExpiry,
                    onPrivateRouteHints = viewModel::setPrivateRouteHints,
                    onCreate = { viewModel.create(settings.bitcoinUnit) },
                    optionsOpen = optionsOpen,
                    onToggleOptions = { optionsOpen = !optionsOpen },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun CreateForm(
    state: LightningReceiveState,
    unit: BitcoinUnit,
    onAmount: (String) -> Unit,
    onDescription: (String) -> Unit,
    onExpiry: (String) -> Unit,
    onPrivateRouteHints: (Boolean) -> Unit,
    onCreate: () -> Unit,
    optionsOpen: Boolean,
    onToggleOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        FormField(
            label = if (unit == BitcoinUnit.Btc) "Amount (BTC)" else "Amount (sat)",
            value = state.amount,
            onValueChange = onAmount,
            enabled = !state.creating,
            keyboardType = KeyboardType.Decimal,
        )

        FormField(
            label = "Description",
            value = state.description,
            onValueChange = onDescription,
            placeholder = "What is this for?",
            supportingText = "Shown to whoever pays the invoice.",
            enabled = !state.creating,
        )

        // An hour suits a counter and an emailed invoice equally well, and
        // route hints are a node-configuration question rather than a per-sale
        // one. Both stay reachable, neither is in the way.
        ExpandableSection(
            title = "Invoice options",
            summary = receiveSummary(state),
            expanded = optionsOpen,
            onToggle = onToggleOptions,
        ) {
            FormField(
                label = "Expires in (minutes)",
                value = state.expiryMinutes,
                onValueChange = onExpiry,
                enabled = !state.creating,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            )

            FormSwitch(
                title = "Private route hints",
                checked = state.privateRouteHints,
                onCheckedChange = onPrivateRouteHints,
                description = "Include unannounced channels so a private node can still be paid.",
                enabled = !state.creating,
            )
        }

        FormProblem(state.formError ?: state.error?.userMessage)

        Button(
            onClick = onCreate,
            enabled = !state.creating,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            AnimatedSwap(state.creating, label = "createInvoice") { creating ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text("Create invoice")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** The collapsed row states the expiry, because a wrong one is a lost sale. */
private fun receiveSummary(state: LightningReceiveState): String {
    val minutes = state.expiryMinutes.trim().toIntOrNull()
    val expiry = when {
        minutes == null -> "Expiry not set"
        minutes % 60 == 0 && minutes >= 60 -> "Expires in ${minutes / 60} h"
        else -> "Expires in $minutes min"
    }
    return if (state.privateRouteHints) "$expiry · private route hints" else expiry
}

/**
 * The code a customer scans, and whether it can still be paid.
 *
 * Nothing here is masked in privacy mode: the customer has to read the amount.
 *
 * Once the invoice has expired the code is replaced, not left on screen: a
 * payer's wallet refuses an expired BOLT11 invoice, and a code that still
 * looks payable invites a scan that can only fail at the counter.
 */
@Composable
private fun InvoiceState(
    invoice: LightningInvoiceData,
    unit: BitcoinUnit,
    reconnecting: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expired = invoice.status == LightningInvoiceStatus.Expired || isPast(invoice.expiresAt)
    val waiting = !expired && invoice.status == LightningInvoiceStatus.Unpaid

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A customer is reading this code, so the display stays on. As a
        // customer screen the idle lock waits 15 minutes without a touch, so
        // the app does not lock while the customer pays. Once the invoice
        // cannot be paid, the normal screen timeout applies again.
        if (waiting) KeepScreenOn(customerFacing = true)

        Spacer(Modifier.height(16.dp))
        // The code itself has its own entrance — see `QrCode` — so the rest of
        // the screen falls in behind it rather than starting with it.
        AnimatedSwap(expired, label = "expiry") { gone ->
            if (gone) {
                ExpiredNotice(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            } else {
                QrCode(
                    content = "lightning:${invoice.BOLT11}",
                    modifier = Modifier.fillMaxWidth(0.82f),
                    contentDescription = "Lightning invoice as a QR code",
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = msatLabel(invoice.amount, unit),
            modifier = Modifier.arrive(1),
            style = MaterialTheme.typography.headlineMedium,
        )

        if (waiting) {
            Spacer(Modifier.height(4.dp))
            Countdown(expiresAt = invoice.expiresAt, checking = !reconnecting, modifier = Modifier.arrive(2))
            if (reconnecting) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Not checking right now. Reconnecting…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // A dead invoice is not worth copying or sending on either.
        if (!expired) {
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 16.dp).arrive(4)) {
                CopyableField(label = "BOLT11", value = invoice.BOLT11, truncate = true)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAgain, modifier = Modifier.arrive(5)) { Text("New invoice") }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * True once [expiresAt] (epoch seconds) has passed.
 *
 * One timer that fires at the expiry, not a clock: the screen around it
 * recomposes once, when the code has to go, and not every second.
 */
@Composable
private fun isPast(expiresAt: Long): Boolean {
    val past by produceState(System.currentTimeMillis() / 1000 >= expiresAt, expiresAt) {
        // Clamped, so an absurd value from the server cannot overflow the millis.
        val remainingMs = expiresAt.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 - System.currentTimeMillis()
        if (remainingMs > 0) delay(remainingMs)
        value = true
    }
    return past
}

/**
 * The expiry line and its one-second clock, in their own composable.
 *
 * A ticking time in [InvoiceState] recomposed the QR code, the buttons and the
 * BOLT11 field once a second for the life of the invoice; here only this line
 * does. It is composed only while the invoice is unpaid, so the clock stops
 * with it.
 *
 * [checking] draws the pulse that says the node is being asked. It goes when
 * the checks keep failing: a pulse on a screen that no longer hears from the
 * node tells the merchant that a payment would show, and it would not.
 */
@Composable
private fun Countdown(expiresAt: Long, checking: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val remaining = Dates.countdown(expiresAt, now) ?: return

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (checking) {
            PulsingDot(color = AppTheme.statusColors.incoming)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "Expires in $remaining",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What stands where the code was. */
@Composable
private fun ExpiredNotice(modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.TimerOff,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This invoice has expired",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "It can no longer be paid. Make a new invoice.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PaidState(
    invoice: LightningInvoiceData,
    unit: BitcoinUnit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        // A tick that draws itself rather than one that was simply always
        // there. This is the moment the merchant is watching for from across a
        // counter, and it has to be believed at a glance.
        SuccessCheck(size = 72.dp, color = AppTheme.statusColors.incoming)
        Spacer(Modifier.height(16.dp))
        Text("Paid", modifier = Modifier.arrive(1), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = msatLabel(invoice.amountReceived ?: invoice.amount, unit),
            modifier = Modifier.arrive(2),
            style = MaterialTheme.typography.headlineMedium,
        )
        invoice.paidAt?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = Dates.full(it),
                modifier = Modifier.arrive(3),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(32.dp))
        // Last, and by a longer beat than the lines above: the buttons are the
        // one thing here that can be pressed by mistake.
        Row(
            Modifier.fillMaxWidth().arrive(6),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text("New invoice") }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
        Spacer(Modifier.height(32.dp))
    }
}
