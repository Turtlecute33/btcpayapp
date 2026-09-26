package com.btcpayapp.ui.screens.lightning
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.CreateLightningInvoiceRequest
import com.btcpayapp.data.api.dto.LightningInvoiceData
import com.btcpayapp.data.api.dto.LightningInvoiceStatus
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
import com.btcpayapp.ui.components.PulsingDot
import com.btcpayapp.ui.components.QrCode
import com.btcpayapp.ui.components.SuccessCheck
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.copyToClipboard
import com.btcpayapp.ui.theme.AppTheme
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
)

class LightningReceiveViewModel(
    private val graph: AppGraph,
    private val cryptoCode: String,
    private val serverNode: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(LightningReceiveState())
    val state = _state.asStateFlow()

    fun setAmount(value: String) = _state.update { it.copy(amount = value, formError = null) }

    fun setDescription(value: String) = _state.update { it.copy(description = value, formError = null) }

    fun setExpiry(value: String) = _state.update { it.copy(expiryMinutes = value, formError = null) }

    fun setPrivateRouteHints(value: Boolean) = _state.update { it.copy(privateRouteHints = value) }

    fun reset() = _state.update { LightningReceiveState() }

    fun create(unit: BitcoinUnit) {
        val snapshot = _state.value
        val entered = Amounts.parse(snapshot.amount)
        if (entered == null || entered.signum() < 0) {
            _state.update { it.copy(formError = "Enter an amount.") }
            return
        }
        val minutes = snapshot.expiryMinutes.trim().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(formError = "Expiry must be a number of minutes.") }
            return
        }

        val scope = graph.lightningScopeOrNull(serverNode) ?: run {
            _state.update { it.copy(noStore = true) }
            return
        }

        // The wire format is millisatoshi; the field is in whatever unit the
        // user reads the rest of the app in.
        val sats = if (unit == BitcoinUnit.Btc) Amounts.btcToSats(entered) else entered
        val msat = Amounts.satsToMsat(sats)

        viewModelScope.launch {
            _state.update { it.copy(creating = true, error = null, formError = null) }
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
                _state.update { it.copy(creating = false, invoice = invoice, error = null) }
            }.onFailure { failure ->
                _state.update { it.copy(creating = false, error = failure.asApiException()) }
            }
        }
    }

    /** One status check. Failures are ignored: a dropped poll is not worth a banner. */
    fun pollOnce() {
        val id = _state.value.invoice?.id ?: return
        val scope = graph.lightningScopeOrNull(serverNode) ?: return
        viewModelScope.launch {
            runCatching { graph.session.requireApi().lightningInvoice(scope, id, cryptoCode) }
                .onSuccess { fresh ->
                    _state.update { if (it.invoice?.id == fresh.id) it.copy(invoice = fresh) else it }
                }
        }
    }
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
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val context = LocalContext.current

    var optionsOpen by rememberSaveable { mutableStateOf(false) }

    val invoice = state.invoice
    val status = invoice?.status

    // Polling is deliberately bounded: it runs only while this screen is
    // composed, only while the app is unlocked, and only while the invoice can
    // still change. An expired or paid invoice will never move again, and a
    // locked app has no business talking to the server on a timer.
    LaunchedEffect(invoice?.id, locked, status) {
        if (invoice == null || locked || status != LightningInvoiceStatus.Unpaid) return@LaunchedEffect
        while (true) {
            delay(POLL_INTERVAL_MS)
            viewModel.pollOnce()
        }
    }

    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(invoice?.id) {
        if (invoice == null) return@LaunchedEffect
        while (true) {
            nowSeconds = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    AppScreen(
        title = "Receive",
        subtitle = chainSubtitle(cryptoCode, prefix = "Server node".takeIf { serverNode }),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
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
                        nowSeconds = nowSeconds,
                        onCopy = {
                            copyToClipboard(context, "Lightning invoice", unpaid.BOLT11)
                            uiScope.launch { snackbarHostState.showSnackbar("Invoice copied") }
                        },
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

@Composable
private fun InvoiceState(
    invoice: LightningInvoiceData,
    unit: BitcoinUnit,
    nowSeconds: Long,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val countdown = Dates.countdown(invoice.expiresAt, nowSeconds)
    // The node is still being asked about this one every three seconds. The
    // countdown cannot say so on its own: it would run down exactly the same
    // way on a screen that had quietly stopped asking.
    val waiting = countdown != null && invoice.status == LightningInvoiceStatus.Unpaid

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        // The code itself has its own entrance — see `QrCode` — so the rest of
        // the screen falls in behind it rather than starting with it.
        QrCode(
            content = "lightning:${invoice.BOLT11}",
            modifier = Modifier.fillMaxWidth(0.82f),
            contentDescription = "Lightning invoice as a QR code",
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = msatLabel(invoice.amount, unit),
            modifier = Modifier.arrive(1),
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(Modifier.height(4.dp))
        Row(Modifier.arrive(2), verticalAlignment = Alignment.CenterVertically) {
            if (waiting) {
                PulsingDot(color = AppTheme.statusColors.incoming)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = countdown?.let { "Expires in $it" } ?: "This invoice has expired",
                style = MaterialTheme.typography.bodyMedium,
                color = if (countdown == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

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

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAgain, modifier = Modifier.arrive(5)) { Text("New invoice") }
        Spacer(Modifier.height(32.dp))
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
