package com.btcpayapp.ui.screens.store

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.NetworkFeeMode
import com.btcpayapp.data.api.dto.ReceiptOptions
import com.btcpayapp.data.api.dto.SpeedPolicy
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.endpoints.deleteStore
import com.btcpayapp.data.api.endpoints.store
import com.btcpayapp.data.api.endpoints.updateStore
import com.btcpayapp.ui.LocalAppGraph
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import com.btcpayapp.ui.nav.AppsRoute
import com.btcpayapp.ui.nav.PaymentMethodsRoute
import com.btcpayapp.ui.nav.StoreEmailRoute
import com.btcpayapp.ui.nav.StoreListRoute
import com.btcpayapp.ui.nav.StoreRatesRoute
import com.btcpayapp.ui.nav.StoreUsersRoute
import com.btcpayapp.ui.nav.WebhooksRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * Editing surface for every field of a store, plus the doorway to the store's
 * sub-settings.
 *
 * `PUT /stores/{id}` is a whole-object replace, not a patch. The screen
 * therefore fetches the live [StoreData] and edits a *copy* of it; building a
 * fresh `StoreData` from the controls on screen would silently reset every
 * setting this app does not render — payment method criteria, logo and CSS
 * URLs, additional tracked rates — the next time anyone pressed save.
 */

/** The three durations the API keeps in seconds are shown in friendlier units. */
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

data class StoreSettingsDraft(
    val store: StoreData,
    val invoiceExpirationMinutes: String,
    val displayExpirationSeconds: String,
    val monitoringExpirationHours: String,
    val paymentTolerancePercent: String,
    val recommendedFeeBlockTarget: String,
    val refundExpirationDays: String,
) {
    /** Folds the free-text numeric fields back into the object that goes on the wire. */
    fun toStore(): StoreData = store.copy(
        invoiceExpiration = durationSeconds(invoiceExpirationMinutes, store.invoiceExpiration, SECONDS_PER_MINUTE),
        displayExpirationTimer = nonNegativeInt(displayExpirationSeconds),
        monitoringExpiration = durationSeconds(monitoringExpirationHours, store.monitoringExpiration, SECONDS_PER_HOUR),
        paymentTolerance = paymentTolerancePercent.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: throw IllegalArgumentException("Payment tolerance must be between 0 and 100."),
        recommendedFeeBlockTarget = nonNegativeInt(recommendedFeeBlockTarget).also { require(it > 0) { "Fee target must be positive." } },
        refundBOLT11Expiration = nonNegativeInt(refundExpirationDays),
    )

    companion object {
        fun of(store: StoreData) = StoreSettingsDraft(
            store = store,
            invoiceExpirationMinutes = displayDuration(store.invoiceExpiration, SECONDS_PER_MINUTE),
            displayExpirationSeconds = store.displayExpirationTimer.toString(),
            monitoringExpirationHours = displayDuration(store.monitoringExpiration, SECONDS_PER_HOUR),
            paymentTolerancePercent = Amounts.trim(BigDecimal.valueOf(store.paymentTolerance), 4),
            recommendedFeeBlockTarget = store.recommendedFeeBlockTarget.toString(),
            refundExpirationDays = store.refundBOLT11Expiration.toString(),
        )
    }
}

private fun displayDuration(seconds: Int, divisor: Int): String =
    BigDecimal(seconds).divide(BigDecimal(divisor), 9, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private fun durationSeconds(input: String, original: Int, divisor: Int): Int {
    // Preserve seconds exactly when the displayed decimal is recurring.
    if (input == displayDuration(original, divisor)) return original
    return try {
        input.toBigDecimal().multiply(BigDecimal(divisor)).intValueExact().also { require(it >= 0) }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Enter a non-negative duration in whole seconds.")
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Duration is too large or contains a fraction of a second.")
    }
}

private fun nonNegativeInt(input: String): Int = input.toIntOrNull()?.takeIf { it >= 0 }
    ?: throw IllegalArgumentException("Enter a non-negative whole number in each numeric field.")

enum class StoreSettingsConfirm { Archive, Restore, Delete }

/** What the body is showing, so a save does not cross-fade the form with itself. */
private enum class StoreSettingsPhase { Loading, Error, Content }

/** The three things the app bar's trailing slot can be. */
private enum class StoreSaveAction { Busy, Ready, None }

data class StoreSettingsState(
    val draft: StoreSettingsDraft? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: ApiException? = null,
    val message: String? = null,
    val confirm: StoreSettingsConfirm? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)

class StoreSettingsViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(StoreSettingsState())
    val state = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            graph.session.activeStore
                .map { it?.id }
                .distinctUntilChanged()
                .collectLatest {
                    loadJob?.cancel()
                    _state.value = StoreSettingsState()
                    load()
                }
        }
    }

    fun load() {
        val storeId = graph.session.activeStore.value?.id
        if (storeId == null) {
            _state.update { it.copy(loading = false, error = ApiException.NotFound("No store is selected.")) }
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.draft == null, error = null) }
            runCatching { graph.session.requireApi().store(storeId) }
                .onSuccess { store ->
                    _state.update { it.copy(draft = if (it.dirty) it.draft else StoreSettingsDraft.of(store), loading = false, error = null) }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.asApiException()) }
                }
        }
    }

    fun edit(transform: (StoreData) -> StoreData) = _state.update { current ->
        current.copy(draft = current.draft?.let { it.copy(store = transform(it.store)) }, dirty = true)
    }

    fun editDraft(transform: (StoreSettingsDraft) -> StoreSettingsDraft) = _state.update { current ->
        current.copy(draft = current.draft?.let(transform), dirty = true)
    }

    fun ask(confirm: StoreSettingsConfirm?) = _state.update { it.copy(confirm = confirm) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun save() = saveDraft(null)

    fun setArchived(archived: Boolean) = saveDraft(archived)

    private fun saveDraft(archived: Boolean?) {
        runCatching {
            _state.value.draft?.toStore()?.let { if (archived == null) it else it.copy(archived = archived) }
        }.onSuccess {
            put(it, when (archived) { true -> "Store archived."; false -> "Store restored."; null -> "Store settings saved." })
        }.onFailure { failure ->
            _state.update { it.copy(error = ApiException.Transport(failure.message ?: "Check the numeric fields.")) }
        }
    }

    fun delete() {
        val storeId = graph.session.activeStore.value?.id ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { graph.session.requireApi().deleteStore(storeId) }
                .onSuccess {
                    graph.session.refresh()
                    _state.update { it.copy(saving = false, deleted = true) }
                }
                .onFailure { failure -> _state.update { it.copy(saving = false, error = failure.asApiException()) } }
        }
    }

    private fun put(store: StoreData?, message: String) {
        if (store == null || _state.value.saving) return
        val storeId = graph.session.activeStore.value?.id ?: return
        if (store.id != storeId) return
        val submitted = _state.value.draft
        loadJob?.cancel()
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { graph.session.requireApi().updateStore(storeId, store) }
                .onSuccess { saved ->
                    // The rest of the app reads the store from the session, so it
                    // has to be told the name or currency just changed.
                    graph.session.refresh()
                    _state.update {
                        if (it.draft == submitted) it.copy(draft = StoreSettingsDraft.of(saved), dirty = false, saving = false, message = message)
                        else it.copy(saving = false, message = message)
                    }
                }
                .onFailure { failure -> _state.update { it.copy(saving = false, error = failure.asApiException()) } }
        }
    }

}

@Composable
fun StoreSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
) {
    val viewModel = appViewModel { StoreSettingsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val guardedBack = com.btcpayapp.ui.components.confirmDiscardChanges(state.dirty, onBack)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    AppScreen(
        title = "Store settings",
        subtitle = state.draft?.store?.name,
        onBack = guardedBack,
        snackbarHostState = snackbarHostState,
        actions = {
            val action = when {
                state.saving -> StoreSaveAction.Busy
                state.draft != null -> StoreSaveAction.Ready
                else -> StoreSaveAction.None
            }
            AnimatedSwap(action, label = "save") { shown ->
                when (shown) {
                    StoreSaveAction.Busy ->
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    StoreSaveAction.Ready -> TextButton(onClick = viewModel::save) { Text("Save") }
                    StoreSaveAction.None -> Unit
                }
            }
        },
    ) { padding ->
        val draft = state.draft
        val phase = when {
            state.loading -> StoreSettingsPhase.Loading
            draft == null -> StoreSettingsPhase.Error
            else -> StoreSettingsPhase.Content
        }

        AnimatedSwap(phase, label = "storeSettings") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is being waited for is
                // one record behind a long form, not a list of rows, so there
                // is no shape to hold open.
                StoreSettingsPhase.Loading -> LoadingState(Modifier.padding(padding))

                StoreSettingsPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::load,
                )

                StoreSettingsPhase.Content -> {
                    // The outgoing half of a swap outlives the state that chose
                    // it, so this branch can still be composed a frame or two
                    // after the draft has been cleared.
                    val current = draft ?: return@AnimatedSwap

                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                        StoreGeneralSection(current, viewModel, Modifier.arrive(0))
                        ThinDivider()
                        StoreCheckoutSection(current, viewModel, Modifier.arrive(1))
                        ThinDivider()
                        StoreLightningSection(current, viewModel, Modifier.arrive(2))
                        ThinDivider()
                        StoreReceiptSection(current, viewModel, Modifier.arrive(3))
                        ThinDivider()
                        StoreMenuSection(onNavigate, Modifier.arrive(4))
                        ThinDivider()
                        StoreDangerSection(current, viewModel, Modifier.arrive(5))

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    when (state.confirm) {
        StoreSettingsConfirm.Archive -> ConfirmDialog(
            title = "Archive this store?",
            message = "An archived store is hidden from the store list and stops accepting new " +
                "invoices. Existing invoices and payouts are kept, and you can restore it later.",
            confirmLabel = "Archive",
            destructive = true,
            onConfirm = { viewModel.setArchived(true) },
            onDismiss = { viewModel.ask(null) },
        )

        StoreSettingsConfirm.Restore -> ConfirmDialog(
            title = "Restore this store?",
            message = "The store becomes visible again and can accept invoices.",
            confirmLabel = "Restore",
            onConfirm = { viewModel.setArchived(false) },
            onDismiss = { viewModel.ask(null) },
        )

        StoreSettingsConfirm.Delete -> ConfirmDialog(
            title = "Delete “${state.draft?.store?.name.orEmpty()}”?",
            message = "This cannot be undone. The store, its invoices, its payment method " +
                "configuration and its wallet settings are removed from the server. If you only " +
                "want to stop using it, archive it instead.",
            confirmLabel = "Delete for ever",
            destructive = true,
            onConfirm = viewModel::delete,
            onDismiss = { viewModel.ask(null) },
        )

        null -> Unit
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun StoreGeneralSection(
    draft: StoreSettingsDraft,
    viewModel: StoreSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val store = draft.store
    FormSection("General", modifier) {
        FormField(
            label = "Name",
            value = store.name,
            onValueChange = { value -> viewModel.edit { it.copy(name = value) } },
        )
        FormField(
            label = "Website",
            value = store.website.orEmpty(),
            onValueChange = { value -> viewModel.edit { it.copy(website = value.ifBlank { null }) } },
            placeholder = "https://example.com",
            keyboardType = KeyboardType.Uri,
        )
        FormField(
            label = "Support URL",
            value = store.supportUrl.orEmpty(),
            onValueChange = { value -> viewModel.edit { it.copy(supportUrl = value.ifBlank { null }) } },
            supportingText = "Shown to a buyer when a payment goes wrong. {OrderId} and " +
                "{InvoiceId} are substituted.",
            keyboardType = KeyboardType.Uri,
        )
        FormField(
            label = "Default currency",
            value = store.defaultCurrency,
            onValueChange = { value -> viewModel.edit { it.copy(defaultCurrency = value.uppercase()) } },
            supportingText = "The currency new invoices are priced in, for example EUR or SATS.",
        )
        FormField(
            label = "Brand colour",
            value = store.brandColor.orEmpty(),
            onValueChange = { value -> viewModel.edit { it.copy(brandColor = value.ifBlank { null }) } },
            placeholder = "#0f3b82",
            leadingIcon = {
                // The swatch is the only preview of what is being typed, and a
                // hex code is edited one character at a time — so it crossfades
                // between the half-finished colours rather than flicking
                // through them.
                val swatch = parseHexColour(store.brandColor)
                val shown by animateColorAsState(
                    targetValue = swatch ?: MaterialTheme.colorScheme.surfaceVariant,
                    animationSpec = Motion.color,
                    label = "brandColour",
                )
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(shown),
                )
            },
        )
        FormSwitch(
            title = "Apply the brand colour to the back office",
            checked = store.applyBrandColorToBackend,
            onCheckedChange = { value -> viewModel.edit { it.copy(applyBrandColorToBackend = value) } },
        )
        FormField(
            label = "HTML title",
            value = store.htmlTitle.orEmpty(),
            onValueChange = { value -> viewModel.edit { it.copy(htmlTitle = value.ifBlank { null }) } },
            supportingText = "The browser tab title on the checkout page. Defaults to the store name.",
        )
        FormField(
            label = "Default language",
            value = store.defaultLang,
            onValueChange = { value -> viewModel.edit { it.copy(defaultLang = value) } },
            supportingText = "A checkout language code, for example en, fr or de-DE.",
            enabled = !store.autoDetectLanguage,
        )
        FormSwitch(
            title = "Auto-detect the language",
            description = "Use the buyer's browser language instead of the default.",
            checked = store.autoDetectLanguage,
            onCheckedChange = { value -> viewModel.edit { it.copy(autoDetectLanguage = value) } },
        )
    }
}

@Composable
private fun StoreCheckoutSection(
    draft: StoreSettingsDraft,
    viewModel: StoreSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val store = draft.store
    FormSection("Checkout", modifier) {
        FormDropdown(
            label = "Consider an invoice paid after",
            options = SpeedPolicy.entries.filter { it != SpeedPolicy.Unknown },
            selected = store.speedPolicy,
            onSelect = { value -> viewModel.edit { it.copy(speedPolicy = value) } },
            optionLabel = { it.speedLabel() },
            supportingText = "How many on-chain confirmations settle an invoice.",
        )
        FormField(
            label = "Invoice expires after (minutes)",
            value = draft.invoiceExpirationMinutes,
            onValueChange = { value -> viewModel.editDraft { it.copy(invoiceExpirationMinutes = value.digits()) } },
            keyboardType = KeyboardType.Number,
            supportingText = "The server stores this in seconds; it is shown here in minutes.",
        )
        FormField(
            label = "Show the countdown for the last (seconds)",
            value = draft.displayExpirationSeconds,
            onValueChange = { value -> viewModel.editDraft { it.copy(displayExpirationSeconds = value.digits()) } },
            keyboardType = KeyboardType.Number,
            supportingText = "The timer stays hidden until this much of the invoice life is left.",
        )
        FormField(
            label = "Keep watching for payment for (hours)",
            value = draft.monitoringExpirationHours,
            onValueChange = { value -> viewModel.editDraft { it.copy(monitoringExpirationHours = value.digits()) } },
            keyboardType = KeyboardType.Number,
            supportingText = "A late payment arriving inside this window still marks the invoice paid.",
        )
        FormField(
            label = "Payment tolerance (%)",
            value = draft.paymentTolerancePercent,
            onValueChange = { value -> viewModel.editDraft { it.copy(paymentTolerancePercent = value) } },
            keyboardType = KeyboardType.Decimal,
            // Unlike almost every other amount in the API this one goes on the
            // wire as a plain JSON number rather than a decimal string.
            supportingText = "An underpayment inside this margin is still accepted. 0 means exact.",
        )
        FormDropdown(
            label = "Network fee charged to the buyer",
            options = NetworkFeeMode.entries.filter { it != NetworkFeeMode.Unknown },
            selected = store.networkFeeMode,
            onSelect = { value -> viewModel.edit { it.copy(networkFeeMode = value) } },
            optionLabel = { it.feeModeLabel() },
        )
        FormDropdown(
            label = "Default payment method",
            options = listOf("") + paymentMethodIds(),
            selected = store.defaultPaymentMethod.orEmpty(),
            onSelect = { value -> viewModel.edit { it.copy(defaultPaymentMethod = value.ifBlank { null }) } },
            optionLabel = { if (it.isBlank()) "No preference" else it },
            supportingText = "Pre-selected on the checkout page.",
        )
        FormSwitch(
            title = "Lazy payment methods",
            description = "Only generate an address or a Lightning invoice once the buyer picks " +
                "that method. Saves address space on a busy store.",
            checked = store.lazyPaymentMethods,
            onCheckedChange = { value -> viewModel.edit { it.copy(lazyPaymentMethods = value) } },
        )
        FormSwitch(
            title = "Anyone can create an invoice",
            description = "Lets an unauthenticated caller create invoices on this store. Leave " +
                "off unless a public app needs it.",
            checked = store.anyoneCanCreateInvoice,
            onCheckedChange = { value -> viewModel.edit { it.copy(anyoneCanCreateInvoice = value) } },
        )
        FormSwitch(
            title = "Redirect automatically after payment",
            checked = store.redirectAutomatically,
            onCheckedChange = { value -> viewModel.edit { it.copy(redirectAutomatically = value) } },
        )
        FormSwitch(
            title = "Show the recommended fee",
            checked = store.showRecommendedFee,
            onCheckedChange = { value -> viewModel.edit { it.copy(showRecommendedFee = value) } },
        )
        FormField(
            label = "Recommended fee block target",
            value = draft.recommendedFeeBlockTarget,
            onValueChange = { value -> viewModel.editDraft { it.copy(recommendedFeeBlockTarget = value.digits()) } },
            keyboardType = KeyboardType.Number,
            enabled = store.showRecommendedFee,
            supportingText = "Blocks to confirm within, used to quote a fee rate.",
        )
        FormSwitch(
            title = "Celebrate a payment",
            description = "Confetti on the checkout page once the invoice settles.",
            checked = store.celebratePayment,
            onCheckedChange = { value -> viewModel.edit { it.copy(celebratePayment = value) } },
        )
        FormSwitch(
            title = "Play a sound on payment",
            checked = store.playSoundOnPayment,
            onCheckedChange = { value -> viewModel.edit { it.copy(playSoundOnPayment = value) } },
        )
        FormSwitch(
            title = "Show the pay-in-wallet button",
            checked = store.showPayInWalletButton,
            onCheckedChange = { value -> viewModel.edit { it.copy(showPayInWalletButton = value) } },
        )
        FormSwitch(
            title = "Show the store header",
            checked = store.showStoreHeader,
            onCheckedChange = { value -> viewModel.edit { it.copy(showStoreHeader = value) } },
        )
        FormSwitch(
            title = "Payjoin",
            description = "Offer a payjoin endpoint to buyers whose wallet supports BIP 78.",
            checked = store.payJoinEnabled,
            onCheckedChange = { value -> viewModel.edit { it.copy(payJoinEnabled = value) } },
        )
        FormField(
            label = "Refund link expires after (days)",
            value = draft.refundExpirationDays,
            onValueChange = { value -> viewModel.editDraft { it.copy(refundExpirationDays = value.digits()) } },
            keyboardType = KeyboardType.Number,
            supportingText = "How long a BOLT11 refund claim stays valid.",
        )
    }
}

@Composable
private fun StoreLightningSection(
    draft: StoreSettingsDraft,
    viewModel: StoreSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val store = draft.store
    FormSection("Lightning", modifier) {
        FormSwitch(
            title = "Show amounts in satoshi",
            checked = store.lightningAmountInSatoshi,
            onCheckedChange = { value -> viewModel.edit { it.copy(lightningAmountInSatoshi = value) } },
        )
        FormSwitch(
            title = "Include private route hints",
            description = "Needed for payments to an unannounced channel; it also reveals a " +
                "little of your channel topology to the payer.",
            checked = store.lightningPrivateRouteHints,
            onCheckedChange = { value -> viewModel.edit { it.copy(lightningPrivateRouteHints = value) } },
        )
        FormSwitch(
            title = "Unified QR with an on-chain fallback",
            description = "Put a BOLT11 invoice inside the on-chain BIP 21 code so one scan " +
                "works for either.",
            checked = store.onChainWithLnInvoiceFallback,
            onCheckedChange = { value -> viewModel.edit { it.copy(onChainWithLnInvoiceFallback = value) } },
        )
        FormSwitch(
            title = "Allow zero-amount invoices",
            description = "Lets the payer choose the amount. Useful for tips, risky for a till.",
            checked = store.allowZeroAmountInvoices,
            onCheckedChange = { value -> viewModel.edit { it.copy(allowZeroAmountInvoices = value) } },
        )
        FormField(
            label = "Lightning invoice description",
            value = store.lightningDescriptionTemplate.orEmpty(),
            onValueChange = { value ->
                viewModel.edit { it.copy(lightningDescriptionTemplate = value.ifBlank { null }) }
            },
            singleLine = false,
            supportingText = "Placeholders: {StoreName}, {ItemDescription}, {OrderId}.",
        )
    }
}

@Composable
private fun StoreReceiptSection(
    draft: StoreSettingsDraft,
    viewModel: StoreSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    // A null flag means "inherit the server default", which is a third state and
    // not the same as off — so these are dropdowns rather than switches.
    val receipt = draft.store.receipt
    fun set(transform: (ReceiptOptions) -> ReceiptOptions) =
        viewModel.edit { it.copy(receipt = transform(it.receipt ?: ReceiptOptions())) }

    FormSection("Receipts", modifier) {
        FormDropdown(
            label = "Show a receipt after payment",
            options = StoreInheritable.entries,
            selected = StoreInheritable.of(receipt?.enabled),
            onSelect = { choice -> set { it.copy(enabled = choice.value) } },
            optionLabel = { it.label },
        )
        FormDropdown(
            label = "Show the QR code on the receipt",
            options = StoreInheritable.entries,
            selected = StoreInheritable.of(receipt?.showQR),
            onSelect = { choice -> set { it.copy(showQR = choice.value) } },
            optionLabel = { it.label },
        )
        FormDropdown(
            label = "List the payments on the receipt",
            options = StoreInheritable.entries,
            selected = StoreInheritable.of(receipt?.showPayments),
            onSelect = { choice -> set { it.copy(showPayments = choice.value) } },
            optionLabel = { it.label },
        )
    }
}

@Composable
private fun StoreMenuSection(onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    FormSection("More settings", modifier) {
        StoreMenuRow(
            title = "Payment methods",
            description = "Wallets, Lightning node, LNURL",
            icon = Icons.Rounded.Payments,
            onClick = { onNavigate(PaymentMethodsRoute) },
        )
        StoreMenuRow(
            title = "Users and roles",
            description = "Who can see and manage this store",
            icon = Icons.Rounded.Group,
            onClick = { onNavigate(StoreUsersRoute) },
        )
        StoreMenuRow(
            title = "Rates",
            description = "Exchange rate source and spread",
            icon = Icons.Rounded.CurrencyExchange,
            onClick = { onNavigate(StoreRatesRoute) },
        )
        StoreMenuRow(
            title = "Email",
            description = "SMTP server used for store notifications",
            icon = Icons.Rounded.Email,
            onClick = { onNavigate(StoreEmailRoute) },
        )
        StoreMenuRow(
            title = "Webhooks",
            description = "Push invoice events to another system",
            icon = Icons.Rounded.Webhook,
            onClick = { onNavigate(WebhooksRoute) },
        )
        StoreMenuRow(
            title = "Apps",
            description = "Point of sale and crowdfunding",
            icon = Icons.Rounded.Apps,
            onClick = { onNavigate(AppsRoute) },
        )
        StoreMenuRow(
            title = "Switch store",
            description = "Choose or create another store",
            icon = Icons.Rounded.Store,
            onClick = { onNavigate(StoreListRoute) },
        )
    }
}

@Composable
private fun StoreDangerSection(
    draft: StoreSettingsDraft,
    viewModel: StoreSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    FormSection("Danger zone", modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Archiving replaces one control with a warning and a different
            // control, and the save it rides on takes a round trip — so the two
            // halves are swapped rather than exchanged on the frame the
            // response lands.
            AnimatedSwap(draft.store.archived, label = "archived") { archived ->
                Column {
                    if (archived) {
                        Text(
                            text = "This store is archived. It is hidden from the store list and does " +
                                "not accept new invoices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.ask(StoreSettingsConfirm.Restore) }) { Text("Restore store") }
                    } else {
                        OutlinedButton(onClick = { viewModel.ask(StoreSettingsConfirm.Archive) }) {
                            Text("Archive store")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.ask(StoreSettingsConfirm.Delete) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Delete store")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun StoreMenuRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tri-state for the receipt flags, where a null is "inherit", not "off". */
enum class StoreInheritable(val label: String, val value: Boolean?) {
    Inherit("Use the server default", null),
    On("On", true),
    Off("Off", false),
    ;

    companion object {
        fun of(value: Boolean?): StoreInheritable = entries.first { it.value == value }
    }
}

@Composable
private fun paymentMethodIds(): List<String> {
    val graph = LocalAppGraph.current
    val methods by graph.session.paymentMethods.collectAsStateWithLifecycle()
    return methods.map { it.paymentMethodId }
}

private fun SpeedPolicy.speedLabel(): String {
    val plural = if (confirmations == 1) "confirmation" else "confirmations"
    return "${TextUtil.sentenceCase(name)} · $confirmations $plural"
}

private fun NetworkFeeMode.feeModeLabel(): String = when (this) {
    NetworkFeeMode.MultiplePaymentsOnly -> "Only on a second payment"
    NetworkFeeMode.Always -> "Always"
    NetworkFeeMode.Never -> "Never"
    NetworkFeeMode.Unknown -> "Unknown"
}

private fun String.digits(): String = filter { it.isDigit() }

private fun parseHexColour(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#")?.takeIf { it.length == 6 || it.length == 8 } ?: return null
    val parsed = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) Color(0xFF000000L or parsed) else Color(parsed)
}
