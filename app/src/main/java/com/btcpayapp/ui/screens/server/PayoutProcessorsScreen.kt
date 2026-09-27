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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.btcpayapp.ui.components.maskedIfPrivate
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.screens.wallet.cryptoCodeOf
import com.btcpayapp.ui.theme.Motion
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * The threshold to send: blank keeps [loaded] (0 for a new processor), and
 * anything else must be 0 or more with at most 8 decimals; null when it is
 * not. Always sent: the server replaces the whole settings blob, and an
 * absent threshold becomes 0, which sweeps every approved payout on each run.
 */
internal fun thresholdToSend(text: String, loaded: BigDecimal): BigDecimal? =
    if (text.isBlank()) {
        loaded
    } else {
        Amounts.parse(text)?.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 8 }
    }

/** As [thresholdToSend], for the fee target: blank keeps [loaded], else whole blocks from 1. */
internal fun feeTargetToSend(text: String, loaded: Int): Int? =
    if (text.isBlank()) loaded else text.toIntOrNull()?.takeIf { it > 0 }

/** A checked editor: what the confirmation shows, and exactly what is then saved. */
data class ProcessorValues(
    val seconds: Int,
    val instant: Boolean,
    val feeTarget: Int,
    val threshold: BigDecimal,
)

/**
 * [values] as the confirmation lists them, so what is turned on is read
 * before it runs. The fee target and threshold only apply on-chain. The
 * threshold is not masked in privacy mode: this is where it is checked.
 */
internal fun processorSummary(kind: ProcessorKind, payoutMethodId: String, values: ProcessorValues): String =
    buildList {
        add("Runs every ${formatMinutes(values.seconds)}.")
        add(if (values.instant) "New payouts are sent at once." else "New payouts wait for the next run.")
        if (kind == ProcessorKind.OnChain) {
            add("Fee target: ${values.feeTarget} blocks.")
            add("Threshold: ${Amounts.trim(values.threshold, 8)} ${cryptoCodeOf(payoutMethodId)}.")
        }
    }.joinToString("\n")

data class ProcessorEditor(
    val kind: ProcessorKind,
    val payoutMethodId: String,
    val existing: Boolean,
    val minutes: String = "60",
    val originalIntervalSeconds: Int? = null,
    val processInstantly: Boolean = false,
    val feeTargetBlock: String = "1",
    /** What a blank fee target keeps: the loaded value, 1 for a new processor. */
    val loadedFeeTarget: Int = 1,
    val threshold: String = "0",
    /** What a blank threshold keeps: the loaded value, 0 for a new processor. */
    val loadedThreshold: BigDecimal = BigDecimal.ZERO,
    val minutesError: String? = null,
    val feeTargetError: String? = null,
    val thresholdError: String? = null,
    /** The values the "automatic payouts" confirmation is showing, while it shows. */
    val confirming: ProcessorValues? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

data class PayoutProcessorsState(
    val available: List<PayoutProcessorData> = emptyList(),
    val lightning: List<LightningPayoutProcessorSettings> = emptyList(),
    val onChain: List<OnChainPayoutProcessorSettings> = emptyList(),
    /**
     * Kinds whose list failed on the last read. Their methods are not offered
     * under "Available": a processor that exists but did not load would look
     * unconfigured, and "Turn on" would replace its settings with the
     * defaults.
     */
    val failedKinds: Set<ProcessorKind> = emptySet(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val editor: ProcessorEditor? = null,
    val pendingRemoval: Pair<ProcessorKind, String>? = null,
    val message: String? = null,
)

class PayoutProcessorsViewModel(private val graph: AppGraph) : ViewModel() {

    /**
     * The store this screen was opened in. An editor opened for one store
     * must save to that store, whatever became active meanwhile (see
     * [StoreBinding]).
     */
    private val bound = StoreBinding(graph.session)

    /** Whose funds a processor sends, for the confirmation. */
    val storeName: String get() = bound.name

    // Loading from the start: the first read waits for the screen to resume,
    // and until then "no processors" would be a claim nobody has checked.
    private val _state = MutableStateFlow(PayoutProcessorsState(loading = bound.id != null))
    val state = _state.asStateFlow()

    init {
        bound.retryWhenKnown(viewModelScope) { load() }
    }

    /** Reads on every return to the screen: a processor changed in the web UI shows as it is now. */
    fun onResume() = load()

    fun refresh() = load(refreshing = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Not while a save runs: its answer lands in the editor, and with the
     * editor gone a refused save was dropped with no word, so the user took
     * the old settings for the new ones.
     */
    fun closeEditor() = _state.update { if (it.editor?.saving == true) it else it.copy(editor = null) }

    fun editLightning(settings: LightningPayoutProcessorSettings) = _state.update {
        it.copy(
            editor = ProcessorEditor(
                kind = ProcessorKind.Lightning,
                payoutMethodId = settings.payoutMethodId,
                existing = true,
                minutes = displayInterval(settings.intervalSeconds),
                originalIntervalSeconds = settings.intervalSeconds,
                processInstantly = settings.processNewPayoutsInstantly,
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
                loadedFeeTarget = settings.feeTargetBlock,
                threshold = Amounts.toInput(settings.threshold, 8),
                loadedThreshold = settings.threshold,
            ),
        )
    }

    fun addProcessor(kind: ProcessorKind, payoutMethodId: String) = _state.update {
        it.copy(editor = ProcessorEditor(kind = kind, payoutMethodId = payoutMethodId, existing = false))
    }

    fun setMinutes(value: String) = mutateEditor { it.copy(minutes = value.filter(Char::isDigit), minutesError = null) }

    fun setProcessInstantly(value: Boolean) = mutateEditor { it.copy(processInstantly = value) }

    fun setFeeTargetBlock(value: String) =
        mutateEditor { it.copy(feeTargetBlock = value.filter(Char::isDigit), feeTargetError = null) }

    fun setThreshold(value: String) = mutateEditor { it.copy(threshold = value, thresholdError = null) }

    fun askRemove(kind: ProcessorKind, payoutMethodId: String) =
        _state.update { it.copy(pendingRemoval = kind to payoutMethodId) }

    fun dismissRemove() = _state.update { it.copy(pendingRemoval = null) }

    fun confirmRemove() {
        val store = bound.id ?: return
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

    /**
     * Checks the editor and asks for the confirmation. Every save runs a
     * processor that spends without asking, so each one is confirmed and then
     * passes the spend prompt before [save].
     */
    fun review() {
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val values = checked(editor) ?: return
        mutateEditor { it.copy(confirming = values, error = null) }
    }

    fun dismissConfirm() = mutateEditor { it.copy(confirming = null) }

    fun reportEditorError(text: String) = mutateEditor { it.copy(error = text) }

    /** The values [editor] saves, or null after marking what is wrong in it. */
    private fun checked(editor: ProcessorEditor): ProcessorValues? {
        val interval = if (editor.originalIntervalSeconds?.let(::displayInterval) == editor.minutes) {
            editor.originalIntervalSeconds
        } else {
            runCatching { Amounts.parse(editor.minutes)?.multiply(BigDecimal(60))?.intValueExact() }.getOrNull()
        }
        val seconds = interval?.takeIf { it >= 1 }
        // A Lightning editor keeps the defaults of these two, which pass.
        val feeTarget = feeTargetToSend(editor.feeTargetBlock, editor.loadedFeeTarget)
        val threshold = thresholdToSend(editor.threshold, editor.loadedThreshold)
        mutateEditor {
            it.copy(
                minutesError = if (seconds == null) "Enter a positive interval that converts to whole seconds." else null,
                feeTargetError = if (feeTarget == null) "Enter a whole number of blocks, 1 or more." else null,
                thresholdError = if (threshold == null) {
                    Amounts.parseProblem(editor.threshold) ?: "Enter 0 or more, with at most 8 decimals."
                } else {
                    null
                },
            )
        }
        return if (seconds != null && feeTarget != null && threshold != null) {
            ProcessorValues(seconds, editor.processInstantly, feeTarget, threshold)
        } else {
            null
        }
    }

    /**
     * Saves [values], the ones the confirmation showed, to the processor the
     * editor is open for. Marked as a payment in flight, since it sets how the
     * server spends: a store switch must not cancel it and lose the answer.
     */
    fun save(values: ProcessorValues) {
        val store = bound.id ?: return
        val editor = _state.value.editor ?: return
        if (editor.saving) return

        viewModelScope.launch {
            mutateEditor { it.copy(saving = true, confirming = null, error = null) }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                mutateEditor { it.copy(saving = false, error = failure.asApiException().userMessage) }
                return@launch
            }

            val outcome = runCatching {
                graph.session.spending {
                    when (editor.kind) {
                        ProcessorKind.Lightning -> api.updateLightningPayoutProcessor(
                            storeId = store,
                            payoutMethodId = editor.payoutMethodId,
                            settings = UpdateLightningPayoutProcessorSettings(
                                intervalSeconds = values.seconds,
                                processNewPayoutsInstantly = values.instant,
                            ),
                        )

                        // Both always sent, from the loaded settings when left
                        // blank: the server replaces the whole blob, so an absent
                        // one becomes 0 (threshold) or 1 (fee target).
                        ProcessorKind.OnChain -> api.updateOnChainPayoutProcessor(
                            storeId = store,
                            paymentMethodId = editor.payoutMethodId,
                            settings = UpdateOnChainPayoutProcessorSettings(
                                feeTargetBlock = values.feeTarget,
                                intervalSeconds = values.seconds,
                                threshold = values.threshold,
                                processNewPayoutsInstantly = values.instant,
                            ),
                        )
                    }
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
        val store = bound.id ?: run {
            _state.update { it.copy(error = ApiException.NoAccount()) }
            return
        }
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
            val error = listOf(available, lightning, onChain)
                .firstNotNullOfOrNull { it.exceptionOrNull() }
                ?.asApiException()

            // Each list keeps what it last read when its own call fails. An
            // empty list in its place said automatic sending was off while the
            // server went on sending.
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    available = available.getOrNull() ?: it.available,
                    lightning = lightning.getOrNull() ?: it.lightning,
                    onChain = onChain.getOrNull() ?: it.onChain,
                    failedKinds = setOfNotNull(
                        ProcessorKind.Lightning.takeIf { lightning.isFailure },
                        ProcessorKind.OnChain.takeIf { onChain.isFailure },
                    ),
                    error = error,
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
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

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

    // A processor pays approved payouts with no one asking again, so turning
    // one on, or changing one that runs, is confirmed with the settings it
    // will run with and then passes the same prompt as a send.
    val editing = state.editor
    val values = editing?.confirming
    if (editing != null && values != null) {
        val source = if (editing.kind == ProcessorKind.OnChain) "the hot wallet" else "the Lightning node"
        ConfirmDialog(
            title = if (editing.existing) "Change automatic payouts?" else "Turn on automatic payouts?",
            message = "The server will send approved payouts from $source of ${viewModel.storeName} without asking.\n\n" +
                processorSummary(editing.kind, editing.payoutMethodId, values),
            confirmLabel = if (editing.existing) "Save" else "Turn on",
            onDismiss = viewModel::dismissConfirm,
            onConfirm = {
                viewModel.dismissConfirm()
                val subtitle = "${viewModel.storeName} · ${editing.payoutMethodId}"
                scope.afterSpendGate(gate, "Confirm automatic payouts", subtitle, viewModel::reportEditorError) {
                    viewModel.save(values)
                }
            },
        )
    }

    // Payout methods a processor exists for but which are not yet configured.
    // A kind whose list did not load is left out: it may well be configured.
    val addable = remember(state.available, state.lightning, state.onChain, state.failedKinds) {
        state.available.flatMap { processor ->
            val kind = if (processor.name.contains("Lightning", ignoreCase = true)) {
                ProcessorKind.Lightning
            } else {
                ProcessorKind.OnChain
            }
            processor.payoutMethods.map { kind to it }
        }.filterNot { (kind, methodId) ->
            kind in state.failedKinds || when (kind) {
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
                                        DetailRow(
                                            "Threshold",
                                            maskedIfPrivate(
                                                "${Amounts.trim(settings.threshold, 8)} " +
                                                    cryptoCodeOf(settings.payoutMethodId),
                                            ),
                                        )
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
    // A save in flight holds the sheet up, so its answer is seen. Swipe and a
    // tap outside are refused here, and Back also by closeEditor.
    val saving by rememberUpdatedState(editor.saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !saving },
    )

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

            // Not swapped: which fields apply is fixed by the processor the
            // sheet was opened for. BTCPay's Lightning processor has no
            // settings beyond the two above.
            if (editor.kind == ProcessorKind.OnChain) {
                Column(Modifier.arrive(3)) {
                    FormField(
                        label = "Fee target (blocks)",
                        value = editor.feeTargetBlock,
                        onValueChange = viewModel::setFeeTargetBlock,
                        error = editor.feeTargetError,
                        supportingText = "Confirmation target used to pick a fee rate. Lower costs more.",
                        keyboardType = KeyboardType.Number,
                    )
                    // In the method's own coin, which is what the server
                    // compares it with; a sat figure typed here was read as
                    // that many BTC.
                    FormField(
                        label = "Threshold (${cryptoCodeOf(editor.payoutMethodId)})",
                        value = editor.threshold,
                        onValueChange = viewModel::setThreshold,
                        error = editor.thresholdError,
                        supportingText = "Payouts wait until they add up to this amount.",
                        keyboardType = KeyboardType.Decimal,
                    )
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
                Button(onClick = viewModel::review, enabled = !editor.saving) {
                    Text(if (editor.existing) "Save" else "Turn on")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * [seconds] exactly: in seconds unless it is whole minutes or hours. Rounded
 * down to minutes, 90 s read "1 min" on the confirmation that is there to
 * show what gets saved.
 */
private fun formatMinutes(seconds: Int): String = when {
    seconds < 60 || seconds % 60 != 0 -> "$seconds s"
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    else -> "${seconds / 60} min"
}
