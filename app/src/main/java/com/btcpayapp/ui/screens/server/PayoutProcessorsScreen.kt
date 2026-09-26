package com.btcpayapp.ui.screens.server
import kotlinx.coroutines.async

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.btcpayapp.data.api.dto.LightningPayoutProcessorSettings
import com.btcpayapp.data.api.dto.OnChainPayoutProcessorSettings
import com.btcpayapp.data.api.dto.PayoutProcessorData
import com.btcpayapp.data.api.dto.UpdateLightningPayoutProcessorSettings
import com.btcpayapp.data.api.dto.UpdateOnChainPayoutProcessorSettings
import com.btcpayapp.data.api.endpoints.LIGHTNING_PAYOUT_PROCESSOR
import com.btcpayapp.data.api.endpoints.ONCHAIN_PAYOUT_PROCESSOR
import com.btcpayapp.data.api.endpoints.deletePayoutProcessor
import com.btcpayapp.data.api.endpoints.lightningPayoutProcessors
import com.btcpayapp.data.api.endpoints.onChainPayoutProcessors
import com.btcpayapp.data.api.endpoints.payoutProcessors
import com.btcpayapp.data.api.endpoints.updateLightningPayoutProcessor
import com.btcpayapp.data.api.endpoints.updateOnChainPayoutProcessor
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A payout processor is unattended spending. Once one is configured, the server
 * pays out every *approved* payout on its own schedule — no confirmation, no
 * second pair of eyes, and on-chain it signs with the store's hot wallet. It
 * exists so a shop does not have to press "send" a hundred times a day, and it
 * is the single most expensive setting on this screen to get wrong. Hence the
 * warning banner, and hence approval being left as a deliberate manual act.
 *
 * These settings are store-scoped even though the menu files them under the
 * server: the processors run in the server process, but each store configures
 * its own.
 */
private const val LIGHTNING_FACTORY = LIGHTNING_PAYOUT_PROCESSOR
private const val ONCHAIN_FACTORY = ONCHAIN_PAYOUT_PROCESSOR

enum class ProcessorKind { Lightning, OnChain }

private fun displayInterval(seconds: Int): String = java.math.BigDecimal(seconds)
    .divide(java.math.BigDecimal(60), 9, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

data class ProcessorEditor(
    val kind: ProcessorKind,
    val payoutMethodId: String,
    val existing: Boolean,
    val minutes: String = "60",
    val originalIntervalSeconds: Int? = null,
    val processInstantly: Boolean = false,
    val cancelAfterFailures: String = "",
    val feeTargetBlock: String = "1",
    val threshold: String = "0",
    val minutesError: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

data class PayoutProcessorsState(
    val available: List<PayoutProcessorData> = emptyList(),
    val lightning: List<LightningPayoutProcessorSettings> = emptyList(),
    val onChain: List<OnChainPayoutProcessorSettings> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val editor: ProcessorEditor? = null,
    val pendingRemoval: Pair<ProcessorKind, String>? = null,
    val message: String? = null,
)

class PayoutProcessorsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(PayoutProcessorsState())
    val state = _state.asStateFlow()

    private val storeId get() = graph.session.activeStore.value?.id

    init {
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest { if (it != null) load(refreshing = false) }
        }
    }

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun closeEditor() = _state.update { it.copy(editor = null) }

    fun editLightning(settings: LightningPayoutProcessorSettings) = _state.update {
        it.copy(
            editor = ProcessorEditor(
                kind = ProcessorKind.Lightning,
                payoutMethodId = settings.payoutMethodId,
                existing = true,
                minutes = displayInterval(settings.intervalSeconds),
                originalIntervalSeconds = settings.intervalSeconds,
                processInstantly = settings.processNewPayoutsInstantly,
                cancelAfterFailures = settings.cancelPayoutAfterFailures?.toString().orEmpty(),
            ),
        )
    }

    fun editOnChain(settings: OnChainPayoutProcessorSettings) = _state.update {
        it.copy(
            editor = ProcessorEditor(
                kind = ProcessorKind.OnChain,
                payoutMethodId = settings.payoutMethodId,
                existing = true,
                minutes = displayInterval(settings.intervalSeconds),
                originalIntervalSeconds = settings.intervalSeconds,
                processInstantly = settings.processNewPayoutsInstantly,
                feeTargetBlock = settings.feeTargetBlock.toString(),
                threshold = Amounts.trim(settings.threshold, 8),
            ),
        )
    }

    fun addProcessor(kind: ProcessorKind, payoutMethodId: String) = _state.update {
        it.copy(editor = ProcessorEditor(kind = kind, payoutMethodId = payoutMethodId, existing = false))
    }

    fun setMinutes(value: String) = mutateEditor { it.copy(minutes = value.filter(Char::isDigit), minutesError = null) }

    fun setProcessInstantly(value: Boolean) = mutateEditor { it.copy(processInstantly = value) }

    fun setCancelAfterFailures(value: String) =
        mutateEditor { it.copy(cancelAfterFailures = value.filter(Char::isDigit)) }

    fun setFeeTargetBlock(value: String) = mutateEditor { it.copy(feeTargetBlock = value.filter(Char::isDigit)) }

    fun setThreshold(value: String) = mutateEditor { it.copy(threshold = value) }

    fun askRemove(kind: ProcessorKind, payoutMethodId: String) =
        _state.update { it.copy(pendingRemoval = kind to payoutMethodId) }

    fun dismissRemove() = _state.update { it.copy(pendingRemoval = null) }

    fun confirmRemove() {
        val store = storeId ?: return
        val (kind, payoutMethodId) = _state.value.pendingRemoval ?: return
        _state.update { it.copy(pendingRemoval = null) }
        val factory = if (kind == ProcessorKind.Lightning) lightningFactoryName() else onChainFactoryName()
        viewModelScope.launch {
            runCatching { graph.session.requireApi().deletePayoutProcessor(store, factory, payoutMethodId) }
                .onSuccess {
                    _state.update { it.copy(message = "Automatic sending switched off.") }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    _state.update { it.copy(message = failure.asApiException().userMessage) }
                }
        }
    }

    fun save() {
        val store = storeId ?: return
        val editor = _state.value.editor ?: return
        val seconds = if (editor.originalIntervalSeconds?.let(::displayInterval) == editor.minutes) {
            editor.originalIntervalSeconds
        } else runCatching { Amounts.parse(editor.minutes)?.multiply(java.math.BigDecimal(60))?.intValueExact() }.getOrNull()
        if (seconds == null || seconds < 1) {
            mutateEditor { it.copy(minutesError = "Enter a positive interval that converts to whole seconds.") }
            return
        }
        if ((editor.cancelAfterFailures.isNotBlank() && editor.cancelAfterFailures.toIntOrNull()?.let { it >= 0 } != true) ||
            (editor.feeTargetBlock.isNotBlank() && editor.feeTargetBlock.toIntOrNull()?.let { it > 0 } != true) ||
            (editor.threshold.isNotBlank() && Amounts.parse(editor.threshold)?.let { it.signum() >= 0 } != true)) {
            mutateEditor { it.copy(error = "Check the failure count, fee target and minimum amount.") }
            return
        }

        viewModelScope.launch {
            mutateEditor { it.copy(saving = true, error = null) }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                mutateEditor { it.copy(saving = false, error = failure.asApiException().userMessage) }
                return@launch
            }

            val outcome = runCatching {
                when (editor.kind) {
                    ProcessorKind.Lightning -> api.updateLightningPayoutProcessor(
                        storeId = store,
                        payoutMethodId = editor.payoutMethodId,
                        settings = UpdateLightningPayoutProcessorSettings(
                            intervalSeconds = seconds,
                            cancelPayoutAfterFailures = editor.cancelAfterFailures.toIntOrNull(),
                            processNewPayoutsInstantly = editor.processInstantly,
                        ),
                    )

                    ProcessorKind.OnChain -> api.updateOnChainPayoutProcessor(
                        storeId = store,
                        paymentMethodId = editor.payoutMethodId,
                        settings = UpdateOnChainPayoutProcessorSettings(
                            feeTargetBlock = editor.feeTargetBlock.toIntOrNull(),
                            intervalSeconds = seconds,
                            // Blank means "leave unchanged", not zero. Parsed
                            // through `Amounts.parse` so a comma decimal
                            // separator is accepted — `toBigDecimalOrNull`
                            // returns null for "2,5", and a `?: ZERO` fallback
                            // would then set the minimum to zero, making the
                            // processor sweep every payout on every run.
                            threshold = Amounts.parse(editor.threshold),
                            processNewPayoutsInstantly = editor.processInstantly,
                        ),
                    )
                }
            }

            outcome
                .onSuccess {
                    _state.update { it.copy(editor = null, message = "Processor saved.") }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    mutateEditor { it.copy(saving = false, error = failure.asApiException().userMessage) }
                }
        }
    }

    fun load(refreshing: Boolean = false) {
        val store = storeId ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.available.isEmpty(), refreshing = refreshing, error = null)
            }

            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(loading = false, refreshing = false, error = failure.asApiException()) }
                return@launch
            }

            val availableCall = async { runCatching { api.payoutProcessors(store) } }
            val lightningCall = async { runCatching { api.lightningPayoutProcessors(store) } }
            val onChainCall = async { runCatching { api.onChainPayoutProcessors(store) } }
            val available = availableCall.await()
            val lightning = lightningCall.await()
            val onChain = onChainCall.await()

            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    available = available.getOrNull() ?: emptyList(),
                    lightning = lightning.getOrNull() ?: emptyList(),
                    onChain = onChain.getOrNull() ?: emptyList(),
                    error = available.exceptionOrNull()?.asApiException(),
                )
            }
        }
    }

    /**
     * The delete route needs the factory name the server registered. Prefer what
     * the instance reported; the constants are only a fallback, and the on-chain
     * one differs between the read and write routes in BTCPay itself.
     */
    private fun lightningFactoryName(): String =
        _state.value.available.firstOrNull { it.name.contains("Lightning", ignoreCase = true) }?.name
            ?: LIGHTNING_FACTORY

    private fun onChainFactoryName(): String =
        _state.value.available.firstOrNull { it.name.contains("Chain", ignoreCase = true) }?.name
            ?: ONCHAIN_FACTORY

    private fun mutateEditor(block: (ProcessorEditor) -> ProcessorEditor) =
        _state.update { current -> current.editor?.let { current.copy(editor = block(it)) } ?: current }

}

/** What the body is showing; a cheap discriminator, not the processor lists. */
private enum class ProcessorsPhase { Loading, Error, Empty, Content }

@Composable
fun PayoutProcessorsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { PayoutProcessorsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    state.pendingRemoval?.let { (_, payoutMethodId) ->
        ConfirmDialog(
            title = "Stop sending automatically?",
            message = "Approved $payoutMethodId payouts will wait for someone to send them by hand.",
            confirmLabel = "Stop",
            destructive = true,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::dismissRemove,
        )
    }

    state.editor?.let { editor ->
        ProcessorSheet(editor = editor, viewModel = viewModel, onDismiss = viewModel::closeEditor)
    }

    // Payout methods a processor exists for but which are not yet configured.
    val addable = remember(state.available, state.lightning, state.onChain) {
        state.available.flatMap { processor ->
            val kind = if (processor.name.contains("Lightning", ignoreCase = true)) {
                ProcessorKind.Lightning
            } else {
                ProcessorKind.OnChain
            }
            processor.payoutMethods.map { kind to it }
        }.filterNot { (kind, methodId) ->
            when (kind) {
                ProcessorKind.Lightning -> state.lightning.any { it.payoutMethodId == methodId }
                ProcessorKind.OnChain -> state.onChain.any { it.payoutMethodId == methodId }
            }
        }.distinct()
    }

    AppScreen(
        title = "Payout processors",
        subtitle = "Automatic sending for this store",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        val phase = when {
            state.loading -> ProcessorsPhase.Loading
            state.error != null && state.available.isEmpty() -> ProcessorsPhase.Error
            state.available.isEmpty() -> ProcessorsPhase.Empty
            else -> ProcessorsPhase.Content
        }

        AnimatedSwap(phase, label = "processors") { shown ->
            when (shown) {
                ProcessorsPhase.Loading -> SkeletonList(Modifier.padding(padding), rows = 3)

                ProcessorsPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::refresh,
                )

                ProcessorsPhase.Empty -> EmptyState(
                    title = "No processors available",
                    description = "This server offers no automated payout senders for the current store.",
                    icon = Icons.Rounded.Autorenew,
                    modifier = Modifier.padding(padding),
                )

                ProcessorsPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(
                        error = state.error,
                        onDismiss = viewModel::dismissError,
                        onRetry = viewModel::refresh,
                    )

                    SpendingWarning(Modifier.arrive(0))

                    // Turning the last processor off empties this whole group,
                    // and turning one on fills it — both while the page is
                    // being looked at.
                    AnimatedVisibility(
                        visible = state.lightning.isNotEmpty() || state.onChain.isNotEmpty(),
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        Column {
                            SectionHeader("Running")

                            state.lightning.forEachIndexed { index, settings ->
                                AppCard(modifier = Modifier.arrive(index + 1)) {
                                    Column(Modifier.padding(vertical = 8.dp)) {
                                        SectionHeader("Lightning · ${settings.payoutMethodId}")
                                        DetailRow("Runs every", formatMinutes(settings.intervalSeconds))
                                        DetailRow(
                                            "New payouts",
                                            if (settings.processNewPayoutsInstantly) {
                                                "Sent at once"
                                            } else {
                                                "Wait for the next run"
                                            },
                                        )
                                        DetailRow(
                                            "Cancel after failures",
                                            settings.cancelPayoutAfterFailures?.toString() ?: "Never",
                                        )
                                        ProcessorActions(
                                            onEdit = { viewModel.editLightning(settings) },
                                            onRemove = {
                                                viewModel.askRemove(
                                                    ProcessorKind.Lightning,
                                                    settings.payoutMethodId,
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            state.onChain.forEachIndexed { index, settings ->
                                AppCard(modifier = Modifier.arrive(state.lightning.size + index + 1)) {
                                    Column(Modifier.padding(vertical = 8.dp)) {
                                        SectionHeader("On-chain · ${settings.payoutMethodId}")
                                        DetailRow("Runs every", formatMinutes(settings.intervalSeconds))
                                        DetailRow(
                                            "New payouts",
                                            if (settings.processNewPayoutsInstantly) {
                                                "Sent at once"
                                            } else {
                                                "Wait for the next run"
                                            },
                                        )
                                        DetailRow("Fee target", "${settings.feeTargetBlock} blocks")
                                        DetailRow("Threshold", Amounts.trim(settings.threshold, 8))
                                        ProcessorActions(
                                            onEdit = { viewModel.editOnChain(settings) },
                                            onRemove = {
                                                viewModel.askRemove(
                                                    ProcessorKind.OnChain,
                                                    settings.payoutMethodId,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // A payout method moves between the two groups as it is
                    // turned on and off, so this one empties as the one above
                    // fills.
                    AnimatedVisibility(
                        visible = addable.isNotEmpty(),
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        Column {
                            SectionHeader("Available")
                            addable.forEachIndexed { index, (kind, methodId) ->
                                AppCard(
                                    modifier = Modifier.arrive(index),
                                    onClick = { viewModel.addProcessor(kind, methodId) },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(methodId, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                text = if (kind == ProcessorKind.Lightning) {
                                                    "Lightning automated sender"
                                                } else {
                                                    "On-chain automated sender"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.Rounded.Add, contentDescription = "Configure $methodId")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ProcessorActions(onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onRemove) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

@Composable
private fun SpendingWarning(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "A processor sends approved payouts without asking again. The server spends " +
                    "from the store wallet on the schedule below, so approval becomes the only " +
                    "point at which a human sees the payment.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessorSheet(
    editor: ProcessorEditor,
    viewModel: PayoutProcessorsViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // Material animates the sheet itself; the contents only need to be
            // staggered so the form assembles rather than appearing whole.
            Text(
                text = if (editor.kind == ProcessorKind.Lightning) {
                    "Lightning · ${editor.payoutMethodId}"
                } else {
                    "On-chain · ${editor.payoutMethodId}"
                },
                modifier = Modifier.padding(horizontal = 16.dp).arrive(0),
                style = MaterialTheme.typography.titleMedium,
            )

            FormField(
                label = "Run every (minutes)",
                value = editor.minutes,
                onValueChange = viewModel::setMinutes,
                modifier = Modifier.arrive(1),
                error = editor.minutesError,
                supportingText = "The API stores this in seconds; minutes are easier to reason about.",
                keyboardType = KeyboardType.Number,
            )

            FormSwitch(
                title = "Process new payouts instantly",
                checked = editor.processInstantly,
                onCheckedChange = viewModel::setProcessInstantly,
                modifier = Modifier.arrive(2),
                description = "Send the moment a payout is approved, rather than on the next run.",
            )

            // Not swapped: which branch applies is fixed by the processor the
            // sheet was opened for.
            Column(Modifier.arrive(3)) {
                when (editor.kind) {
                    ProcessorKind.Lightning -> FormField(
                        label = "Cancel after failures",
                        value = editor.cancelAfterFailures,
                        onValueChange = viewModel::setCancelAfterFailures,
                        placeholder = "Leave blank to keep retrying",
                        supportingText = "Give up on a payout after this many failed attempts.",
                        keyboardType = KeyboardType.Number,
                    )

                    ProcessorKind.OnChain -> {
                        FormField(
                            label = "Fee target (blocks)",
                            value = editor.feeTargetBlock,
                            onValueChange = viewModel::setFeeTargetBlock,
                            supportingText = "Confirmation target used to pick a fee rate. Lower costs more.",
                            keyboardType = KeyboardType.Number,
                        )
                        FormField(
                            label = "Threshold",
                            value = editor.threshold,
                            onValueChange = viewModel::setThreshold,
                            supportingText = "Wait until the pending total reaches this amount before sending. " +
                                "Zero sends every run.",
                            keyboardType = KeyboardType.Decimal,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = editor.error != null,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Text(
                    text = editor.error.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(4),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = editor.saving,
                    enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                    exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(16.dp))
                    }
                }
                Button(onClick = viewModel::save, enabled = !editor.saving) {
                    Text(if (editor.existing) "Save" else "Turn on")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatMinutes(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "$seconds s"
        minutes == 1 -> "1 min"
        minutes % 60 == 0 && minutes >= 60 -> "${minutes / 60} h"
        else -> "$minutes min"
    }
}
