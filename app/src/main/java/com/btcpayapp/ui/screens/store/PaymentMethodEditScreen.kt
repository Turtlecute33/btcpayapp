package com.btcpayapp.ui.screens.store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.GenerateWalletRequest
import com.btcpayapp.data.api.dto.LightningPaymentMethodConfig
import com.btcpayapp.data.api.dto.LnurlPaymentMethodConfig
import com.btcpayapp.data.api.dto.OnChainPaymentMethodConfig
import com.btcpayapp.data.api.dto.UpdatePaymentMethodRequest
import com.btcpayapp.data.api.dto.WalletPreviewAddress
import com.btcpayapp.data.api.endpoints.deletePaymentMethod
import com.btcpayapp.data.api.endpoints.generateWallet
import com.btcpayapp.data.api.endpoints.paymentMethod
import com.btcpayapp.data.api.endpoints.previewWallet
import com.btcpayapp.data.api.endpoints.updatePaymentMethod
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.MonospaceStyle
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Editor for one payment method's configuration.
 *
 * Reading the config needs `canmodifystoresettings` — a key scoped only to
 * `canviewstoresettings` gets a 403 here even though the list screen worked, so
 * that case is called out rather than shown as a generic failure.
 */
private const val INTERNAL_NODE = "Internal Node"

sealed interface PaymentMethodForm {
    data class OnChain(
        val derivationScheme: String,
        val label: String,
        val accountKeyPath: String,
    ) : PaymentMethodForm

    data class Lightning(val connectionString: String) : PaymentMethodForm

    data class Lnurl(
        val useBech32Scheme: Boolean,
        val lud12Enabled: Boolean,
        val lud21Enabled: Boolean,
    ) : PaymentMethodForm

    /** A kind this build does not know how to edit; shown read-only. */
    data class Raw(val text: String) : PaymentMethodForm
}

data class WalletGeneratorForm(
    val label: String = "",
    val wordCount: Int = 12,
    val scriptPubKeyType: String = "Segwit",
    val passphrase: String = "",
    val savePrivateKeys: Boolean = false,
)

data class PaymentMethodEditState(
    val enabled: Boolean = false,
    val form: PaymentMethodForm? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: ApiException? = null,
    val message: String? = null,
    val done: Boolean = false,
    val confirmRemove: Boolean = false,
    val generator: WalletGeneratorForm? = null,
    val generating: Boolean = false,
    val mnemonic: String? = null,
    val previewing: Boolean = false,
    val preview: List<WalletPreviewAddress> = emptyList(),
)

class PaymentMethodEditViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PaymentMethodEditState())
    val state = _state.asStateFlow()

    val kind = paymentMethodKind(paymentMethodId)

    init {
        load()
    }

    fun load() {
        val storeId = graph.session.activeStore.value?.id
        if (storeId == null) {
            _state.update { it.copy(loading = false, error = ApiException.NotFound("No store is selected.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = it.form == null, error = null) }
            runCatching { graph.session.requireApi().paymentMethod(storeId, paymentMethodId, includeConfig = true) }
                .onSuccess { method ->
                    _state.update {
                        it.copy(
                            enabled = method.enabled,
                            form = toForm(method.config),
                            loading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.asApiException()) }
                }
        }
    }

    fun setEnabled(enabled: Boolean) = _state.update { it.copy(enabled = enabled) }

    fun editOnChain(transform: (PaymentMethodForm.OnChain) -> PaymentMethodForm.OnChain) =
        _state.update { s -> s.copy(form = (s.form as? PaymentMethodForm.OnChain)?.let(transform) ?: s.form) }

    fun setConnectionString(value: String) =
        _state.update { it.copy(form = PaymentMethodForm.Lightning(value)) }

    fun editLnurl(transform: (PaymentMethodForm.Lnurl) -> PaymentMethodForm.Lnurl) =
        _state.update { s -> s.copy(form = (s.form as? PaymentMethodForm.Lnurl)?.let(transform) ?: s.form) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun askRemove(ask: Boolean) = _state.update { it.copy(confirmRemove = ask) }

    fun save() {
        val storeId = graph.session.activeStore.value?.id ?: return
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                graph.session.requireApi().updatePaymentMethod(
                    storeId = storeId,
                    paymentMethodId = paymentMethodId,
                    request = UpdatePaymentMethodRequest(
                        enabled = current.enabled,
                        config = current.form.toConfigJson(),
                    ),
                )
            }.onSuccess { updated ->
                graph.session.refreshPaymentMethods(storeId)
                _state.update {
                    it.copy(
                        saving = false,
                        enabled = updated.enabled,
                        form = toForm(updated.config) ?: it.form,
                        message = "Payment method saved.",
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
            }
        }
    }

    fun remove() {
        val storeId = graph.session.activeStore.value?.id ?: return
        _state.update { it.copy(confirmRemove = false) }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { graph.session.requireApi().deletePaymentMethod(storeId, paymentMethodId) }
                .onSuccess {
                    graph.session.refreshPaymentMethods(storeId)
                    _state.update { it.copy(saving = false, done = true) }
                }
                .onFailure { failure -> _state.update { it.copy(saving = false, error = failure.asApiException()) } }
        }
    }

    // --- Wallet generation --------------------------------------------------

    fun openGenerator(open: Boolean) =
        _state.update { it.copy(generator = if (open) WalletGeneratorForm() else null) }

    fun editGenerator(transform: (WalletGeneratorForm) -> WalletGeneratorForm) =
        _state.update { s -> s.copy(generator = s.generator?.let(transform)) }

    fun generate() {
        val storeId = graph.session.activeStore.value?.id ?: return
        val form = _state.value.generator ?: return
        viewModelScope.launch {
            _state.update { it.copy(generating = true, error = null) }
            runCatching {
                graph.session.requireApi().generateWallet(
                    storeId = storeId,
                    paymentMethodId = paymentMethodId,
                    request = GenerateWalletRequest(
                        label = form.label.takeIf { it.isNotBlank() },
                        passphrase = form.passphrase.takeIf { it.isNotBlank() },
                        savePrivateKeys = form.savePrivateKeys,
                        wordCount = form.wordCount,
                        scriptPubKeyType = form.scriptPubKeyType,
                    ),
                )
            }.onSuccess { response ->
                graph.session.refreshPaymentMethods(storeId)
                _state.update {
                    it.copy(
                        generating = false,
                        generator = null,
                        enabled = response.enabled,
                        form = response.config?.let { config ->
                            PaymentMethodForm.OnChain(
                                derivationScheme = config.derivationScheme,
                                label = config.label.orEmpty(),
                                accountKeyPath = config.accountKeyPath.orEmpty(),
                            )
                        } ?: it.form,
                        // Held in memory only, and dropped as soon as the user
                        // closes the dialog. It is never written to disk.
                        mnemonic = response.mnemonic,
                        preview = emptyList(),
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(generating = false, error = failure.asApiException()) }
            }
        }
    }

    fun forgetMnemonic() = _state.update { it.copy(mnemonic = null) }

    fun preview() {
        val storeId = graph.session.activeStore.value?.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(previewing = true, error = null) }
            runCatching { graph.session.requireApi().previewWallet(storeId, paymentMethodId, offset = 0, count = 5) }
                .onSuccess { response ->
                    _state.update { it.copy(previewing = false, preview = response.addresses) }
                }
                .onFailure { failure -> _state.update { it.copy(previewing = false, error = failure.asApiException()) } }
        }
    }

    // --- Config marshalling -------------------------------------------------

    private fun toForm(config: JsonObject?): PaymentMethodForm? {
        val json = graph.client.json
        if (config == null) {
            return when (kind) {
                PaymentMethodKind.OnChain -> PaymentMethodForm.OnChain("", "", "")
                PaymentMethodKind.Lightning -> PaymentMethodForm.Lightning("")
                PaymentMethodKind.Lnurl -> PaymentMethodForm.Lnurl(true, false, false)
                PaymentMethodKind.Other -> null
            }
        }
        return runCatching {
            when (kind) {
                PaymentMethodKind.OnChain ->
                    json.decodeFromJsonElement(OnChainPaymentMethodConfig.serializer(), config).let {
                        PaymentMethodForm.OnChain(
                            derivationScheme = it.derivationScheme,
                            label = it.label.orEmpty(),
                            accountKeyPath = it.accountKeyPath.orEmpty(),
                        )
                    }

                PaymentMethodKind.Lightning ->
                    PaymentMethodForm.Lightning(
                        json.decodeFromJsonElement(
                            LightningPaymentMethodConfig.serializer(),
                            config,
                        ).connectionString,
                    )

                PaymentMethodKind.Lnurl ->
                    json.decodeFromJsonElement(LnurlPaymentMethodConfig.serializer(), config).let {
                        PaymentMethodForm.Lnurl(it.useBech32Scheme, it.lud12Enabled, it.lud21Enabled)
                    }

                PaymentMethodKind.Other -> PaymentMethodForm.Raw(config.toString())
            }
        }.getOrElse { PaymentMethodForm.Raw(config.toString()) }
    }

    /**
     * Built key by key rather than by serialising the config DTO: the shared
     * `Json` omits values equal to their default, which would quietly drop
     * `useBech32Scheme = true` and leave the server to guess.
     */
    private fun PaymentMethodForm?.toConfigJson(): JsonObject? = when (this) {
        is PaymentMethodForm.OnChain -> buildJsonObject {
            put("derivationScheme", derivationScheme.trim())
            // An absent key clears the stored value, which is what an emptied
            // field should do — the server replaces the config wholesale.
            label.trim().takeIf { it.isNotEmpty() }?.let { put("label", it) }
            accountKeyPath.trim().takeIf { it.isNotEmpty() }?.let { put("accountKeyPath", it) }
        }.takeIf { derivationScheme.isNotBlank() }

        is PaymentMethodForm.Lightning -> buildJsonObject {
            put("connectionString", connectionString.trim())
        }.takeIf { connectionString.isNotBlank() }

        is PaymentMethodForm.Lnurl -> buildJsonObject {
            put("useBech32Scheme", useBech32Scheme)
            put("lud12Enabled", lud12Enabled)
            put("lud21Enabled", lud21Enabled)
        }

        // Null leaves the stored config untouched, which is what an unknown
        // kind and a not-yet-configured wallet both want.
        is PaymentMethodForm.Raw, null -> null
    }

}

/** What the body is showing; a cheap discriminator, not the form itself. */
private enum class MethodEditPhase { Loading, Error, Content }

/** The three things the app bar's trailing slot can be. */
private enum class MethodSaveAction { Busy, Ready, None }

@Composable
fun PaymentMethodEditScreen(
    paymentMethodId: String,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = paymentMethodId) { PaymentMethodEditViewModel(it, paymentMethodId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.done) {
        if (state.done) onBack()
    }

    AppScreen(
        title = paymentMethodId,
        subtitle = viewModel.kind.title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            val action = when {
                state.saving -> MethodSaveAction.Busy
                state.form != null -> MethodSaveAction.Ready
                else -> MethodSaveAction.None
            }
            AnimatedSwap(action, label = "save") { shown ->
                when (shown) {
                    MethodSaveAction.Busy ->
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)

                    MethodSaveAction.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.askRemove(true) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                        }
                        TextButton(onClick = viewModel::save) { Text("Save") }
                    }

                    MethodSaveAction.None -> Unit
                }
            }
        },
    ) { padding ->
        val form = state.form
        val phase = when {
            state.loading -> MethodEditPhase.Loading
            form == null -> MethodEditPhase.Error
            else -> MethodEditPhase.Content
        }

        AnimatedSwap(phase, label = "methodEdit") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: one record behind a form.
                MethodEditPhase.Loading -> LoadingState(Modifier.padding(padding))

                MethodEditPhase.Error -> Column(Modifier.fillMaxSize().padding(padding)) {
                    ErrorState(
                        error = state.error,
                        modifier = Modifier.weight(1f),
                        onRetry = viewModel::load,
                    )
                    // The permissions note explains one failure out of several,
                    // so it arrives under the error rather than replacing it.
                    AnimatedVisibility(
                        visible = state.error is ApiException.Forbidden,
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        Text(
                            text = "Reading a payment method's configuration needs the " +
                                "canmodifystoresettings permission. The list screen only needs " +
                                "canviewstoresettings, which is why it worked. Pair this app again " +
                                "with a key that can modify store settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                        )
                    }
                }

                MethodEditPhase.Content -> {
                    // The outgoing half of a swap outlives the state that chose
                    // it, so this can still be composed after the form has gone.
                    val current = form ?: return@AnimatedSwap

                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                        FormSwitch(
                            title = "Enabled",
                            description = "Offer this method to buyers at checkout.",
                            checked = state.enabled,
                            onCheckedChange = viewModel::setEnabled,
                            modifier = Modifier.arrive(0),
                        )
                        ThinDivider()

                        // Not swapped: which branch applies is fixed by the
                        // payment method this screen was opened for.
                        Column(Modifier.arrive(1)) {
                            when (current) {
                                is PaymentMethodForm.OnChain -> OnChainConfigSection(current, state, viewModel)
                                is PaymentMethodForm.Lightning -> LightningConfigSection(current, viewModel)
                                is PaymentMethodForm.Lnurl -> LnurlConfigSection(current, viewModel)
                                is PaymentMethodForm.Raw -> RawConfigSection(current)
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    if (state.confirmRemove) {
        ConfirmDialog(
            title = "Remove $paymentMethodId?",
            message = "The configuration is deleted from the store. For an on-chain method that " +
                "includes the derivation scheme, so keep your recovery phrase before continuing.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = viewModel::remove,
            onDismiss = { viewModel.askRemove(false) },
        )
    }

    state.mnemonic?.let { mnemonic ->
        MnemonicDialog(mnemonic = mnemonic, onDismiss = viewModel::forgetMnemonic)
    }
}

// ---------------------------------------------------------------------------
// On-chain
// ---------------------------------------------------------------------------

@Composable
private fun OnChainConfigSection(
    form: PaymentMethodForm.OnChain,
    state: PaymentMethodEditState,
    viewModel: PaymentMethodEditViewModel,
) {
    FormSection("Wallet") {
        FormField(
            label = "Derivation scheme",
            value = form.derivationScheme,
            onValueChange = { value -> viewModel.editOnChain { it.copy(derivationScheme = value) } },
            singleLine = false,
            supportingText = "An output descriptor or an extended public key. Paste a watch-only " +
                "key here to keep the private keys off the server.",
        )
        FormField(
            label = "Label",
            value = form.label,
            onValueChange = { value -> viewModel.editOnChain { it.copy(label = value) } },
        )
        FormField(
            label = "Account key path",
            value = form.accountKeyPath,
            onValueChange = { value -> viewModel.editOnChain { it.copy(accountKeyPath = value) } },
            placeholder = "abcd1234/84'/0'/0'",
            supportingText = "The fingerprint and path of the signing device, used when a PSBT " +
                "is exported.",
        )
    }

    ThinDivider()

    FormSection("Addresses") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = viewModel::preview,
                enabled = !state.previewing && form.derivationScheme.isNotBlank(),
            ) {
                Text("Preview the first addresses")
            }
            // Sideways: the spinner sits beside the button, and opening
            // downward would shift the addresses below it.
            AnimatedVisibility(
                visible = state.previewing,
                enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        AnimatedVisibility(
            visible = state.preview.isNotEmpty(),
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            Column {
                Text(
                    text = "These come from the wallet saved on the server. Check the first one " +
                        "against your signing device before taking a payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                state.preview.forEachIndexed { index, address ->
                    DetailRow(
                        label = address.keyPath,
                        value = address.address,
                        modifier = Modifier.arrive(index),
                        monospace = true,
                    )
                }
            }
        }
    }

    ThinDivider()

    FormSection("Generate a new wallet") {
        val generator = state.generator
        // One button becomes six fields. Swapping them on the frame drops the
        // rest of the screen half a page without anything having moved, which
        // is the moment a reader loses their place.
        AnimatedSwap(generator == null, label = "generator") { closed ->
            if (closed) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Creates a fresh seed on the server and replaces the derivation " +
                            "scheme above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.openGenerator(true) }) { Text("Generate a wallet") }
                }
            } else {
                // Not null when this branch was chosen, but the outgoing half
                // of a swap outlives the state that chose it.
                generator?.let {
                    Column {
                        WalletGeneratorFields(form = it, busy = state.generating, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletGeneratorFields(
    form: WalletGeneratorForm,
    busy: Boolean,
    viewModel: PaymentMethodEditViewModel,
) {
    FormField(
        label = "Label",
        value = form.label,
        onValueChange = { value -> viewModel.editGenerator { it.copy(label = value) } },
        enabled = !busy,
    )
    FormDropdown(
        label = "Recovery phrase length",
        options = listOf(12, 15, 18, 21, 24),
        selected = form.wordCount,
        onSelect = { value -> viewModel.editGenerator { it.copy(wordCount = value) } },
        enabled = !busy,
        optionLabel = { "$it words" },
    )
    FormDropdown(
        label = "Address type",
        options = listOf("Segwit", "SegwitP2SH", "Legacy", "TaprootBIP86"),
        selected = form.scriptPubKeyType,
        onSelect = { value -> viewModel.editGenerator { it.copy(scriptPubKeyType = value) } },
        enabled = !busy,
        optionLabel = { scriptTypeLabel(it) },
    )
    SecretField(
        label = "Passphrase",
        value = form.passphrase,
        onValueChange = { value -> viewModel.editGenerator { it.copy(passphrase = value) } },
        supportingText = "Optional BIP 39 passphrase. Lose it and the recovery phrase alone will " +
            "not restore the wallet.",
    )
    FormSwitch(
        title = "Hot wallet",
        description = "Keeps the private keys on the server so payouts can be signed there. " +
            "Anyone who reaches the server can then spend the balance — leave this off for a " +
            "watch-only wallet.",
        checked = form.savePrivateKeys,
        onCheckedChange = { value -> viewModel.editGenerator { it.copy(savePrivateKeys = value) } },
        enabled = !busy,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = viewModel::generate, enabled = !busy) { Text("Generate") }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = { viewModel.openGenerator(false) }, enabled = !busy) { Text("Cancel") }
        AnimatedVisibility(
            visible = busy,
            enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * The seed is returned by the server exactly once and this app keeps no copy of
 * it, so the dialog says so plainly rather than relying on the user to guess.
 */
@Composable
private fun MnemonicDialog(mnemonic: String, onDismiss: () -> Unit) {
    var revealed by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("Write this down now") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "This recovery phrase is shown once and once only. It is not saved on " +
                        "this device and cannot be shown again. Anyone who reads it can spend " +
                        "the wallet, so keep it off screen in public and out of a screenshot.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                AnimatedSwap(revealed, label = "mnemonic") { shown ->
                    if (shown) {
                        Column {
                            AppCard {
                                Column(Modifier.padding(12.dp)) {
                                    Text(text = mnemonic, style = MonospaceStyle)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            CopyableField(label = "Recovery phrase", value = mnemonic, sensitive = true)
                        }
                    } else {
                        OutlinedButton(onClick = { revealed = true }) { Text("Reveal the phrase") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = revealed) { Text("I have written it down") }
        },
    )
}

// ---------------------------------------------------------------------------
// Lightning and LNURL
// ---------------------------------------------------------------------------

@Composable
private fun LightningConfigSection(
    form: PaymentMethodForm.Lightning,
    viewModel: PaymentMethodEditViewModel,
) {
    FormSection("Node connection") {
        SecretField(
            label = "Connection string",
            value = form.connectionString,
            onValueChange = viewModel::setConnectionString,
            // A connection string usually carries a macaroon or a certificate
            // thumbprint, which is why it is masked by default.
            supportingText = "For example type=lnd-rest;server=https://…;macaroon=… . It holds " +
                "credentials for your node, so it is hidden until you reveal it.",
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = { viewModel.setConnectionString(INTERNAL_NODE) }) {
                Text("Use the server's internal node")
            }
        }
    }
}

@Composable
private fun LnurlConfigSection(
    form: PaymentMethodForm.Lnurl,
    viewModel: PaymentMethodEditViewModel,
) {
    FormSection("LNURL") {
        FormSwitch(
            title = "Bech32 encoding",
            description = "Off uses the newer lightning: URI form, which more wallets now read.",
            checked = form.useBech32Scheme,
            onCheckedChange = { value -> viewModel.editLnurl { it.copy(useBech32Scheme = value) } },
        )
        FormSwitch(
            title = "Comments (LUD-12)",
            description = "Lets the payer attach a short message to the payment.",
            checked = form.lud12Enabled,
            onCheckedChange = { value -> viewModel.editLnurl { it.copy(lud12Enabled = value) } },
        )
        FormSwitch(
            title = "Payer data (LUD-21)",
            description = "Asks the payer's wallet for a name or an email address.",
            checked = form.lud21Enabled,
            onCheckedChange = { value -> viewModel.editLnurl { it.copy(lud21Enabled = value) } },
        )
    }
}

@Composable
private fun RawConfigSection(form: PaymentMethodForm.Raw) {
    FormSection("Configuration") {
        Text(
            text = "This app does not know how to edit this kind of payment method. The stored " +
                "configuration is shown as the server sent it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        AppCard {
            Text(text = form.text, style = MonospaceStyle, modifier = Modifier.padding(12.dp))
        }
    }
}

private fun scriptTypeLabel(value: String): String = when (value) {
    "Segwit" -> "Native segwit (bc1…)"
    "SegwitP2SH" -> "Segwit in P2SH (3…)"
    "Legacy" -> "Legacy (1…)"
    "TaprootBIP86" -> "Taproot (bc1p…)"
    else -> value
}
