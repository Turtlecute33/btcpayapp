package com.btcpayapp.ui.screens.store

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.dto.GenerateWalletRequest
import com.btcpayapp.data.api.dto.LightningPaymentMethodConfig
import com.btcpayapp.data.api.dto.LnurlPaymentMethodConfig
import com.btcpayapp.data.api.dto.OnChainWalletConfig
import com.btcpayapp.data.api.dto.PaymentMethodData
import com.btcpayapp.data.api.dto.UpdatePaymentMethodRequest
import com.btcpayapp.data.api.dto.WalletPreviewAddress
import com.btcpayapp.data.api.endpoints.deletePaymentMethod
import com.btcpayapp.data.api.endpoints.generateWallet
import com.btcpayapp.data.api.endpoints.paymentMethod
import com.btcpayapp.data.api.endpoints.previewProposedWallet
import com.btcpayapp.data.api.endpoints.previewWallet
import com.btcpayapp.data.api.endpoints.updatePaymentMethod
import com.btcpayapp.data.api.overlaid
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
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
import com.btcpayapp.ui.components.confirmDiscardChanges
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.theme.MonospaceStyle
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Locale

/**
 * Editor for one payment method.
 *
 * Reading the config needs `canmodifystoresettings` — a key scoped only to
 * `canviewstoresettings` gets a 403 here even though the list screen worked, so
 * that case is called out rather than shown as a generic failure.
 *
 * What the server stores is shown read-only, decoded from the GET shape (for
 * on-chain, `DerivationSchemeSettings`; the PUT shape has other keys). The
 * switches send no wallet config and save at once, except turning a method
 * on: that goes through a review of where it pays and the spend gate
 * ([enableReview]). A change of where the store
 * is paid (a new wallet, a new node, a first setup, a generated wallet) is a
 * draft of its own: preview, a review that names the store and shows the
 * current and the new first address (or node), the spend gate, then the PUT. The server
 * builds a new wallet from that PUT and keeps nothing of the old one, so the
 * review also says when a hot-wallet flag or multisig signers go.
 */
private const val INTERNAL_NODE = "Internal Node"
private const val SERVER_NODE = "Server's internal node"

/** The prompt title after [EnableReviewDialog], in the editor and the list. */
internal const val ENABLE_PROMPT = "Confirm payment method"

/** The config the server has, decoded for display. */
sealed interface MethodConfig {
    data class OnChain(val wallet: OnChainWalletConfig) : MethodConfig

    /**
     * [summary] is the node type, host and wallet id (see [connectionSummary]),
     * or the server's internal node. The connection string holds credentials
     * and is never shown.
     */
    data class Lightning(val summary: String) : MethodConfig

    data class Lnurl(
        val useBech32Scheme: Boolean,
        val lud12Enabled: Boolean,
        val lud21Enabled: Boolean,
    ) : MethodConfig

    /** A kind this build does not know how to show; the JSON as sent. */
    data class Raw(val text: String) : MethodConfig
}

/** A wallet or node that is not saved yet. */
sealed interface MethodDraft {
    data class Wallet(
        val derivationScheme: String = "",
        val accountKeyPath: String = "",
        val label: String = "",
    ) : MethodDraft

    data class Node(val connectionString: String = "", val internalNode: Boolean = false) : MethodDraft
}

/**
 * What the review shows before a draft is sent: [config] is exactly what goes
 * out. [current] and [next] say where the store is paid now and after: the
 * first address of each wallet, or the [connectionSummary] of each node.
 * [nodeUnverified]: the node lines cannot tell this string from another one.
 */
data class DraftReview(
    val config: JsonObject,
    val current: String? = null,
    val next: String? = null,
    val nodeUnverified: Boolean = false,
)

/**
 * A method about to be turned on, from [enableReview]. [destination] is
 * where it pays, or null for a kind this app cannot read. [nodeUnverified]:
 * the node line reads the same for another account, as in [DraftReview].
 */
data class EnableReview(
    val paymentMethodId: String,
    val destination: String?,
    val nodeUnverified: Boolean = false,
)

/** An action waiting for its confirmation dialog. */
sealed interface MethodConfirm {
    data object Remove : MethodConfirm
    data object Generate : MethodConfirm
    data class Apply(val review: DraftReview) : MethodConfirm
    data class Enable(val review: EnableReview) : MethodConfirm
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
    /** The config as the server sent it, kept whole so an LNURL edit writes back keys this app does not know. */
    val raw: JsonObject? = null,
    val config: MethodConfig? = null,
    /** The server has no config for this method (404). */
    val notSetUp: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: ApiException? = null,
    val message: String? = null,
    val done: Boolean = false,
    val confirm: MethodConfirm? = null,
    val draft: MethodDraft? = null,
    /** The draft as opened, so an untouched one leaves without a prompt. */
    val draftOrigin: MethodDraft? = null,
    val draftPreview: List<WalletPreviewAddress> = emptyList(),
    val draftPreviewing: Boolean = false,
    val reviewing: Boolean = false,
    val generator: WalletGeneratorForm? = null,
    val generating: Boolean = false,
    val mnemonic: String? = null,
    /** A wallet may exist whose recovery phrase never reached the user. */
    val phraseLost: Boolean = false,
    val previewing: Boolean = false,
    val preview: List<WalletPreviewAddress> = emptyList(),
) {
    val dirty: Boolean get() = draft != draftOrigin || (generator != null && generator != WalletGeneratorForm())
    val busy: Boolean get() = saving || generating || reviewing
}

private const val LOST_PHRASE = "The recovery phrase may not have reached you. If a wallet shows here and " +
    "you did not write down its phrase, remove the wallet and create a new one before you take payments."

/**
 * [phraseLost]: the screen was showing a recovery phrase, or waiting for one,
 * when the process died. The phrase went with the old view model.
 */
class PaymentMethodEditViewModel(
    private val graph: AppGraph,
    private val paymentMethodId: String,
    phraseLost: Boolean = false,
) : ViewModel() {

    private val bound = StoreBinding(graph.session)
    private val _state = MutableStateFlow(PaymentMethodEditState(phraseLost = phraseLost))
    val state = _state.asStateFlow()

    val kind = paymentMethodKind(paymentMethodId)

    /** For every dialog and prompt: a change of wallet must say which store it hits. */
    val storeName: String get() = bound.name

    init {
        load()
        bound.retryWhenKnown(viewModelScope) { load() }
    }

    fun load() {
        val storeId = bound.id
        if (storeId == null) {
            _state.update { it.copy(loading = false, error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = it.config == null && !it.notSetUp, error = null) }
            runCatching { graph.session.requireApi().paymentMethod(storeId, paymentMethodId, includeConfig = true) }
                .onSuccess { method ->
                    _state.update {
                        it.copy(
                            enabled = method.enabled,
                            raw = method.config,
                            config = decode(method.config),
                            notSetUp = false,
                            loading = false,
                        )
                    }
                }
                .onFailure { failure ->
                    val error = failure.asApiException()
                    // Only a configured method has a config to read, so for a
                    // kind this screen can set up, 404 means "not set up".
                    if (error is ApiException.NotFound && kind != PaymentMethodKind.Other) {
                        _state.update { it.copy(loading = false, notSetUp = true, raw = null, config = null) }
                    } else {
                        _state.update { it.copy(loading = false, error = error) }
                    }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun ask(confirm: MethodConfirm?) = _state.update { it.copy(confirm = confirm) }

    /**
     * Sends `enabled` alone: a config-less PUT leaves the wallet as it is.
     * Turning off saves at once. Turning on sends payments to the saved wallet
     * or node, which may be an old one, so it first opens a review of where
     * the method pays; [enable] runs after the review and the spend gate.
     * LNURL too, and its first setup: it pays to the node of the Lightning
     * method, which can be an old one while that method is off.
     */
    fun setEnabled(enabled: Boolean) {
        if (!enabled) return put(UpdatePaymentMethodRequest(enabled = enabled))
        val storeId = bound.id ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(reviewing = true, error = null) }
            runCatching { graph.session.requireApi().enableReview(storeId, paymentMethodId, graph.client.json) }
                .onSuccess { review -> _state.update { it.copy(reviewing = false, confirm = MethodConfirm.Enable(review)) } }
                .onFailure { failure -> _state.update { it.copy(reviewing = false, error = failure.asReadFailure()) } }
        }
    }

    /**
     * Call only after the review and the spend gate. An LNURL method that is
     * not set up yet has no config to turn on, so it goes out with its flags.
     */
    fun enable() {
        _state.update { it.copy(confirm = null) }
        if (kind != PaymentMethodKind.Lnurl || !_state.value.notSetUp) return put(UpdatePaymentMethodRequest(enabled = true))
        put(
            UpdatePaymentMethodRequest(enabled = true, config = lnurlJson(MethodConfig.Lnurl(true, false, false))),
            reload = true,
        ) { s, _ -> s.copy(message = "LNURL set up.") }
    }

    /** The three LNURL flags over the config the server sent. They move no funds, so each saves at once. */
    fun setLnurl(transform: (MethodConfig.Lnurl) -> MethodConfig.Lnurl) {
        val current = _state.value.config as? MethodConfig.Lnurl ?: return
        val next = transform(current)
        val body = (_state.value.raw ?: JsonObject(emptyMap())).overlaid(lnurlJson(next))
        put(UpdatePaymentMethodRequest(config = body)) { s, updated ->
            val raw = updated.config ?: body
            s.copy(raw = raw, config = decode(raw))
        }
    }

    /** Call only after the spend gate. */
    fun remove() {
        val storeId = bound.id ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { graph.session.spending { graph.session.requireApi().deletePaymentMethod(storeId, paymentMethodId) } }
                .onSuccess {
                    graph.session.refreshPaymentMethods(storeId)
                    _state.update { it.copy(saving = false, done = true) }
                }
                .onFailure { failure -> _state.update { it.copy(saving = false, error = failure.asApiException()) } }
        }
    }

    fun preview() {
        val storeId = bound.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(previewing = true, error = null) }
            runCatching { graph.session.requireApi().previewWallet(storeId, paymentMethodId, offset = 0, count = 5) }
                .onSuccess { response -> _state.update { it.copy(previewing = false, preview = response.addresses) } }
                .onFailure { failure -> _state.update { it.copy(previewing = false, error = failure.asApiException()) } }
        }
    }

    // --- Draft: replace or set up a wallet or node ---------------------------

    fun openDraft() {
        val origin = when (kind) {
            // The label is kept by default; the key path belongs to the old wallet.
            PaymentMethodKind.OnChain ->
                MethodDraft.Wallet(label = (_state.value.config as? MethodConfig.OnChain)?.wallet?.label.orEmpty())
            PaymentMethodKind.Lightning -> MethodDraft.Node()
            PaymentMethodKind.Lnurl, PaymentMethodKind.Other -> return
        }
        _state.update { it.copy(draft = origin, draftOrigin = origin, draftPreview = emptyList()) }
    }

    fun closeDraft() = _state.update { it.copy(draft = null, draftOrigin = null, draftPreview = emptyList()) }

    /** Any edit drops the preview: addresses on screen must be the ones of the text on screen. */
    fun editWallet(transform: (MethodDraft.Wallet) -> MethodDraft.Wallet) = _state.update { s ->
        (s.draft as? MethodDraft.Wallet)?.let { s.copy(draft = transform(it), draftPreview = emptyList()) } ?: s
    }

    fun editNode(transform: (MethodDraft.Node) -> MethodDraft.Node) = _state.update { s ->
        (s.draft as? MethodDraft.Node)?.let { s.copy(draft = transform(it)) } ?: s
    }

    /** The first addresses of the pasted wallet, before it is saved, to check against the signing device. */
    fun previewDraft() {
        val storeId = bound.id ?: return
        val config = _state.value.draft?.toConfigJson() ?: return
        viewModelScope.launch {
            _state.update { it.copy(draftPreviewing = true, error = null) }
            runCatching { graph.session.requireApi().previewProposedWallet(storeId, paymentMethodId, config, count = 5) }
                .onSuccess { response ->
                    // Only if the draft is still the one previewed.
                    _state.update {
                        if (it.draft?.toConfigJson() == config) {
                            it.copy(draftPreviewing = false, draftPreview = response.addresses)
                        } else {
                            it.copy(draftPreviewing = false)
                        }
                    }
                }
                .onFailure { failure -> _state.update { it.copy(draftPreviewing = false, error = failure.asReadFailure()) } }
        }
    }

    /**
     * Opens the review. For a wallet it first asks the server for the first
     * address of the saved wallet and of the draft, so the user compares the
     * two addresses the store will really use. For a node it names the saved
     * node and the new one: the pasted string is masked, so a swapped
     * clipboard would not show on the form.
     */
    fun review() {
        val storeId = bound.id ?: return
        val draft = _state.value.draft ?: return
        val config = draft.toConfigJson() ?: return
        if (draft is MethodDraft.Node) {
            val current = (_state.value.config as? MethodConfig.Lightning)?.summary
            val next = if (draft.internalNode) SERVER_NODE else connectionSummary(draft.connectionString)
            val review = DraftReview(
                config = config,
                current = current,
                next = next,
                // A line that names no account (see connectionNamesAccount), on
                // first setup too, or one equal to the saved line: either way a
                // swapped string reads the same.
                nodeUnverified = next != SERVER_NODE && (next == current || !connectionNamesAccount(draft.connectionString)),
            )
            _state.update { it.copy(confirm = MethodConfirm.Apply(review)) }
            return
        }
        val replacing = _state.value.config != null
        viewModelScope.launch {
            _state.update { it.copy(reviewing = true, error = null) }
            runCatching {
                val api = graph.session.requireApi()
                coroutineScope {
                    val proposed = async { api.previewProposedWallet(storeId, paymentMethodId, config, count = 1) }
                    // Best effort: a saved wallet the server cannot derive from
                    // must not block its own replacement. The new address must come.
                    val now = if (replacing) {
                        async {
                            attempt { api.previewWallet(storeId, paymentMethodId, offset = 0, count = 1) }.getOrNull()
                        }
                    } else {
                        null
                    }
                    DraftReview(
                        config = config,
                        current = now?.await()?.addresses?.firstOrNull()?.address,
                        next = proposed.await().addresses.firstOrNull()?.address,
                    )
                }
            }.onSuccess { review ->
                _state.update {
                    if (review.next == null) {
                        val problem = "The server gave no address for this wallet. Check the derivation scheme."
                        it.copy(reviewing = false, error = ApiException.Transport(problem))
                    } else {
                        it.copy(reviewing = false, confirm = MethodConfirm.Apply(review))
                    }
                }
            }.onFailure { failure ->
                _state.update { it.copy(reviewing = false, error = failure.asReadFailure()) }
            }
        }
    }

    /** Call only after the review and the spend gate. Sends the reviewed config, not the draft as it is now. */
    fun applyDraft(review: DraftReview) {
        val setUp = _state.value.config == null
        val what = if (kind == PaymentMethodKind.OnChain) "Wallet" else "Node"
        _state.update { it.copy(confirm = null) }
        put(
            UpdatePaymentMethodRequest(enabled = if (setUp) true else null, config = review.config),
            reload = true,
        ) { s, updated ->
            s.copy(
                // From the answer, so a failed reload cannot leave the old
                // wallet on screen as current. A shape this app cannot read
                // shows nothing until the reload.
                raw = updated.config,
                config = updated.config?.let { decode(it) }?.takeUnless { it is MethodConfig.Raw },
                notSetUp = false,
                draft = null,
                draftOrigin = null,
                draftPreview = emptyList(),
                preview = emptyList(),
                message = if (setUp) "$what set up." else "$what replaced.",
            )
        }
    }

    // --- Wallet generation (a method with no wallet only) -------------------

    fun openGenerator(open: Boolean) =
        _state.update { it.copy(generator = if (open) WalletGeneratorForm() else null) }

    fun editGenerator(transform: (WalletGeneratorForm) -> WalletGeneratorForm) =
        _state.update { s -> s.copy(generator = s.generator?.let(transform)) }

    /**
     * Call only after the spend gate: the new wallet receives every future payment.
     *
     * The request and the phrase dialog count as a payment in flight. A store
     * or account switch clears this screen, and the only copy of the phrase
     * with it, so until the user has written the phrase down a switch is
     * refused, by hand or from a notification.
     */
    fun generate() {
        val storeId = bound.id ?: return
        val form = _state.value.generator ?: return
        if (_state.value.generating) return
        _state.update { it.copy(confirm = null, generating = true, error = null) }
        viewModelScope.launch {
            graph.session.spending {
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
                    val phrase = response.mnemonic?.takeIf { it.isNotBlank() }
                    _state.update {
                        it.copy(
                            generating = false,
                            generator = null,
                            // The store has a wallet now, whatever the reload
                            // says, so the generator is not offered again.
                            notSetUp = false,
                            // Held in memory only, and dropped as soon as the user
                            // closes the dialog. It is never written to disk.
                            mnemonic = phrase,
                            phraseLost = phrase == null,
                        )
                    }
                    load()
                    graph.session.refreshPaymentMethods(storeId)
                    _state.first { it.mnemonic == null }
                }.onFailure { failure ->
                    val error = failure.asApiException()
                    // No answer, or one this app could not read: the wallet may
                    // exist with a phrase nobody saw. Payments to it would be
                    // lost, so the screen reloads to show it, and says so.
                    val lost = error is ApiException.OutcomeUnknown || error is ApiException.Decoding
                    _state.update { it.copy(generating = false, error = error.takeUnless { lost }, phraseLost = lost) }
                    if (lost) load()
                }
            }
        }
    }

    fun forgetMnemonic() = _state.update { it.copy(mnemonic = null) }

    fun dismissPhraseLost() = _state.update { it.copy(phraseLost = false) }

    // --- Plumbing -------------------------------------------------------------

    /**
     * One PUT at a time. [then] folds the answer in; [reload] reads the config back in the GET shape.
     *
     * Marked as a payment in flight, as [remove] is: a store switch clears this
     * screen and would cancel a change of where the store is paid after the
     * server may have applied it.
     */
    private fun put(
        request: UpdatePaymentMethodRequest,
        reload: Boolean = false,
        then: (PaymentMethodEditState, PaymentMethodData) -> PaymentMethodEditState = { s, _ -> s },
    ) {
        val storeId = bound.id ?: return
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                graph.session.spending { graph.session.requireApi().updatePaymentMethod(storeId, paymentMethodId, request) }
            }
                .onSuccess { updated ->
                    // The answer first, so Back is free and the result shows as
                    // soon as the change is applied. The session's list refresh
                    // runs in the app's scope, so leaving does not cancel it.
                    _state.update { then(it.copy(saving = false, enabled = updated.enabled), updated) }
                    graph.scope.launch { graph.session.refreshPaymentMethods(storeId) }
                    if (reload) load()
                }
                .onFailure { failure -> _state.update { it.copy(saving = false, error = failure.asApiException()) } }
        }
    }

    private fun decode(config: JsonObject?): MethodConfig {
        val raw = config ?: JsonObject(emptyMap())
        val json = graph.client.json
        return runCatching {
            when (kind) {
                PaymentMethodKind.OnChain ->
                    json.decodeFromJsonElement(OnChainWalletConfig.serializer(), raw)
                        .takeIf { it.accountDerivation.isNotBlank() }
                        ?.let { MethodConfig.OnChain(it) }

                PaymentMethodKind.Lightning ->
                    json.decodeFromJsonElement(LightningPaymentMethodConfig.serializer(), raw).let { ln ->
                        val internal = !ln.internalNodeRef.isNullOrBlank()
                        MethodConfig.Lightning(if (internal) SERVER_NODE else connectionSummary(ln.connectionString))
                    }

                PaymentMethodKind.Lnurl ->
                    json.decodeFromJsonElement(LnurlPaymentMethodConfig.serializer(), raw).let {
                        MethodConfig.Lnurl(it.useBech32Scheme, it.lud12Enabled, it.lud21Enabled)
                    }

                PaymentMethodKind.Other -> null
            }
        }.getOrNull() ?: MethodConfig.Raw(raw.toString())
    }
}

/**
 * The write shape: BTCPay's alternative config. Built key by key rather than
 * by serialising a DTO, so an emptied optional field is left out, which clears
 * it: the server replaces the config wholesale.
 */
private fun MethodDraft.toConfigJson(): JsonObject? = when (this) {
    is MethodDraft.Wallet -> derivationScheme.trim().takeIf { it.isNotEmpty() }?.let { scheme ->
        buildJsonObject {
            put("derivationScheme", scheme)
            label.trim().takeIf { it.isNotEmpty() }?.let { put("label", it) }
            accountKeyPath.trim().takeIf { it.isNotEmpty() }?.let { put("accountKeyPath", it) }
        }
    }

    is MethodDraft.Node -> (if (internalNode) INTERNAL_NODE else connectionString.trim())
        .takeIf { it.isNotEmpty() }
        ?.let { buildJsonObject { put("connectionString", it) } }
}

/** Every key, so the server never falls back to a default of its own. */
private fun lnurlJson(value: MethodConfig.Lnurl): JsonObject = buildJsonObject {
    put("useBech32Scheme", value.useBech32Scheme)
    put("lud12Enabled", value.lud12Enabled)
    put("lud21Enabled", value.lud21Enabled)
}

/**
 * The node type and host of a connection string, and its wallet id when it
 * has one, or the server's internal node. The string itself carries the
 * node's credentials (a macaroon, an API token, a password in the URL), so it
 * is never shown, and the host is read without its user-info part.
 *
 * A wallet id (Blink, for example) names an account on a shared host and is
 * no credential. A new key for the same wallet keeps it; a string for another
 * wallet on that host does not, so the review can show the change.
 */
internal fun connectionSummary(connectionString: String): String {
    if (connectionString.trim().equals(INTERNAL_NODE, ignoreCase = true)) return SERVER_NODE
    val name = nodeName(connectionString)
    return listOfNotNull(name.type, name.host, name.wallet?.let { "wallet $it" }).joinToString(" · ")
        .ifEmpty { "External node" }
}

/** Types whose one host serves many accounts, told apart only by the key; see [connectionNamesAccount]. */
private val SHARED_HOST_TYPES = setOf("lnbits", "lndhub", "blink", "lnbank")

/**
 * False when the [connectionSummary] reads the same for another account: a
 * line with no host and no wallet id (Strike, NWC: a type and a key), or a
 * shared host (LNbits, LNDhub) with no wallet id.
 */
internal fun connectionNamesAccount(connectionString: String): Boolean =
    connectionString.trim().equals(INTERNAL_NODE, ignoreCase = true) ||
        nodeName(connectionString).let {
            it.wallet != null || (it.host != null && it.type.orEmpty().lowercase(Locale.ROOT) !in SHARED_HOST_TYPES)
        }

/** The first sentence of the node warning in both reviews: [DraftReview] and [EnableReview]. */
private const val NODE_UNVERIFIED = "This line does not show the key and may not show the account."

/** The parts of a connection string that name a node; see [connectionSummary]. */
private class NodeName(val type: String?, val host: String?, val wallet: String?)

private fun nodeName(connectionString: String): NodeName {
    val parts = connectionString.split(';').associate {
        it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=', "").trim()
    }
    val host = parts["server"]?.let { server ->
        runCatching { URI(server) }.getOrNull()?.let { uri ->
            // Java gives no host for a name it will not read as one (with an
            // '_', for example), which the server can still connect to.
            uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.takeIf { it.isNotBlank() }
        }
    }
    return NodeName(
        type = parts["type"]?.takeIf { it.isNotBlank() },
        host = host,
        wallet = parts["wallet-id"]?.takeIf { it.isNotBlank() },
    )
}

/** The Lightning method whose node an LNURL method pays to: `BTC-LNURL` → `BTC-LN`. */
private fun lnurlNodeId(paymentMethodId: String): String = paymentMethodId.dropLast("URL".length)

/**
 * The review before a method is turned on: where it pays. That is the
 * first address of the wallet, or the node's [connectionSummary]. LNURL has
 * no node of its own and pays to the node saved for [lnurlNodeId], so that
 * node is shown. No destination for a kind this app cannot read. The editor
 * and the list both read it here, from the server, so they show the same thing.
 */
internal suspend fun BtcPayApi.enableReview(storeId: String, paymentMethodId: String, json: Json): EnableReview =
    when (val kind = paymentMethodKind(paymentMethodId)) {
        PaymentMethodKind.OnChain -> EnableReview(
            paymentMethodId,
            previewWallet(storeId, paymentMethodId, offset = 0, count = 1).addresses.firstOrNull()?.address
                ?: throw ApiException.Transport("The server gave no address for this wallet."),
        )

        PaymentMethodKind.Lightning, PaymentMethodKind.Lnurl -> {
            val nodeId = if (kind == PaymentMethodKind.Lnurl) lnurlNodeId(paymentMethodId) else paymentMethodId
            val method = try {
                paymentMethod(storeId, nodeId, includeConfig = true)
            } catch (e: ApiException.NotFound) {
                throw if (nodeId == paymentMethodId) e else ApiException.NotFound("Set up $nodeId first. LNURL pays to its node.")
            }
            val node = json.decodeFromJsonElement(LightningPaymentMethodConfig.serializer(), method.config ?: JsonObject(emptyMap()))
            if (!node.internalNodeRef.isNullOrBlank()) {
                EnableReview(paymentMethodId, SERVER_NODE)
            } else {
                EnableReview(
                    paymentMethodId,
                    connectionSummary(node.connectionString),
                    nodeUnverified = !connectionNamesAccount(node.connectionString),
                )
            }
        }

        PaymentMethodKind.Other -> EnableReview(paymentMethodId, null)
    }

/** What the body is showing; a cheap discriminator, not the config itself. */
private enum class MethodEditPhase { Loading, Error, NotSetUp, Content }

/** The three things the app bar's trailing slot can be. */
private enum class MethodBarAction { Busy, Remove, None }

@Composable
fun PaymentMethodEditScreen(
    paymentMethodId: String,
    onBack: () -> Unit,
) {
    // The phrase lives only in the view model, which dies with the process.
    // This flag is saved with the screen, so a screen that comes back without
    // the phrase it was showing, or waiting for, says so. It stays set while
    // that warning is open, so a second process death does not lose it.
    var phraseOpen by rememberSaveable { mutableStateOf(false) }
    val viewModel = appViewModel(key = paymentMethodId) {
        PaymentMethodEditViewModel(it, paymentMethodId, phraseLost = phraseOpen)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phraseLive = state.generating || state.mnemonic != null || state.phraseLost
    LaunchedEffect(phraseLive) { phraseOpen = phraseLive }
    val snackbarHostState = remember { SnackbarHostState() }
    val guardedBack = confirmDiscardChanges(state.dirty, onBack)
    // Leaving clears the view model. While a wallet is made that loses the
    // phrase on its way; while a change saves it cancels a request the server
    // may already have applied, and "Discard changes?" would not be true.
    val pinned = state.generating || state.saving
    BackHandler(enabled = pinned) {}
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()
    val subject = "$paymentMethodId · ${viewModel.storeName}"

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
        onBack = { if (!pinned) guardedBack() },
        snackbarHostState = snackbarHostState,
        actions = {
            val action = when {
                state.busy -> MethodBarAction.Busy
                state.config != null -> MethodBarAction.Remove
                else -> MethodBarAction.None
            }
            AnimatedSwap(action, label = "methodBar") { shown ->
                when (shown) {
                    MethodBarAction.Busy ->
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)

                    MethodBarAction.Remove -> IconButton(onClick = { viewModel.ask(MethodConfirm.Remove) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                    }

                    MethodBarAction.None -> Unit
                }
            }
        },
    ) { padding ->
        val config = state.config
        val phase = when {
            state.loading -> MethodEditPhase.Loading
            state.notSetUp -> MethodEditPhase.NotSetUp
            config == null -> MethodEditPhase.Error
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
                        onRetry = { viewModel.load() },
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

                MethodEditPhase.NotSetUp -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)
                    Text(
                        text = "$paymentMethodId is not set up for ${viewModel.storeName}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).arrive(0),
                    )
                    Column(Modifier.arrive(1)) {
                        when (viewModel.kind) {
                            PaymentMethodKind.OnChain -> {
                                FormSection("Existing wallet") { DraftSection(state, viewModel, "Use an existing wallet") }
                                ThinDivider()
                                GeneratorSection(state, viewModel)
                            }

                            PaymentMethodKind.Lightning ->
                                FormSection("Lightning node") { DraftSection(state, viewModel, "Connect a node") }

                            PaymentMethodKind.Lnurl -> LnurlSetUp(busy = state.busy, onSetUp = { viewModel.setEnabled(true) })

                            PaymentMethodKind.Other -> Unit
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }

                MethodEditPhase.Content -> {
                    // The outgoing half of a swap outlives the state that chose
                    // it, so this can still be composed after the config has gone.
                    val current = config ?: return@AnimatedSwap

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
                            enabled = !state.busy,
                        )
                        ThinDivider()

                        // Not swapped: which branch applies is fixed by the
                        // payment method this screen was opened for.
                        Column(Modifier.arrive(1)) {
                            when (current) {
                                is MethodConfig.OnChain -> {
                                    OnChainConfigSection(current, state, viewModel)
                                    ThinDivider()
                                    FormSection("Change wallet") { DraftSection(state, viewModel, "Replace wallet…") }
                                }

                                is MethodConfig.Lightning -> {
                                    LightningConfigSection(current)
                                    ThinDivider()
                                    FormSection("Change node") { DraftSection(state, viewModel, "Replace node…") }
                                }

                                is MethodConfig.Lnurl -> LnurlConfigSection(current, busy = state.busy, viewModel = viewModel)
                                is MethodConfig.Raw -> RawConfigSection(current)
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    when (val confirm = state.confirm) {
        MethodConfirm.Remove -> ConfirmDialog(
            title = "Remove $paymentMethodId from ${viewModel.storeName}?",
            message = "The configuration is deleted from the store. For an on-chain method that " +
                "includes the derivation scheme, so keep your recovery phrase before continuing.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                viewModel.ask(null)
                scope.afterSpendGate(gate, "Confirm removal", subject, { snackbarHostState.showSnackbar(it) }) { viewModel.remove() }
            },
            onDismiss = { viewModel.ask(null) },
        )

        MethodConfirm.Generate -> ConfirmDialog(
            title = "Create a wallet for ${viewModel.storeName}?",
            message = "Future payments to ${viewModel.storeName} go to the new wallet. Its recovery " +
                "phrase is shown once, next.",
            confirmLabel = "Create",
            onConfirm = {
                viewModel.ask(null)
                scope.afterSpendGate(gate, "Confirm new wallet", subject, { snackbarHostState.showSnackbar(it) }) { viewModel.generate() }
            },
            onDismiss = { viewModel.ask(null) },
        )

        is MethodConfirm.Apply -> DraftReviewDialog(
            review = confirm.review,
            state = state,
            onChain = viewModel.kind == PaymentMethodKind.OnChain,
            storeName = viewModel.storeName,
            onConfirm = {
                viewModel.ask(null)
                val title = if (viewModel.kind == PaymentMethodKind.OnChain) "Confirm wallet change" else "Confirm node change"
                scope.afterSpendGate(gate, title, subject, { snackbarHostState.showSnackbar(it) }) { viewModel.applyDraft(confirm.review) }
            },
            onDismiss = { viewModel.ask(null) },
        )

        is MethodConfirm.Enable -> EnableReviewDialog(
            review = confirm.review,
            storeName = viewModel.storeName,
            onConfirm = {
                viewModel.ask(null)
                scope.afterSpendGate(gate, ENABLE_PROMPT, subject, { snackbarHostState.showSnackbar(it) }) { viewModel.enable() }
            },
            onDismiss = { viewModel.ask(null) },
        )

        null -> Unit
    }

    state.mnemonic?.let { mnemonic ->
        MnemonicDialog(mnemonic = mnemonic, onDismiss = viewModel::forgetMnemonic)
    }

    if (state.phraseLost) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text("Check the new wallet") },
            text = { Text(LOST_PHRASE) },
            confirmButton = { TextButton(onClick = viewModel::dismissPhraseLost) { Text("OK") } },
        )
    }
}

// ---------------------------------------------------------------------------
// On-chain
// ---------------------------------------------------------------------------

@Composable
private fun OnChainConfigSection(
    config: MethodConfig.OnChain,
    state: PaymentMethodEditState,
    viewModel: PaymentMethodEditViewModel,
) {
    val wallet = config.wallet
    val key = wallet.accountKeySettings.firstOrNull()
    FormSection("Wallet") {
        DetailRow(label = "Derivation scheme", value = wallet.accountDerivation, monospace = true)
        wallet.label?.takeIf { it.isNotBlank() }?.let { DetailRow(label = "Label", value = it) }
        key?.rootFingerprint?.takeIf { it.isNotBlank() }?.let { DetailRow(label = "Fingerprint", value = it, monospace = true) }
        key?.accountKeyPath?.takeIf { it.isNotBlank() }?.let { DetailRow(label = "Key path", value = it, monospace = true) }
        DetailRow(
            label = "Keys",
            value = listOfNotNull(
                if (wallet.isHotWallet) "Hot wallet" else "Watch-only",
                wallet.accountKeySettings.size.takeIf { it > 1 }?.let { "Multisig: $it keys" },
            ).joinToString(" · "),
        )
        wallet.source?.takeIf { it.isNotBlank() }?.let { DetailRow(label = "Source", value = it) }
    }

    ThinDivider()

    FormSection("Addresses") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::preview, enabled = !state.previewing) {
                Text("Preview the first addresses")
            }
            BusyBeside(state.previewing)
        }
        AddressList(
            addresses = state.preview,
            caption = "These come from the wallet saved on the server. Check the first one " +
                "against your signing device before taking a payment.",
        )
    }
}

/**
 * The replace or setup flow. Closed, it is one button; open, the fields of a
 * new wallet or node, a preview for a wallet, and the button to the review.
 */
@Composable
private fun DraftSection(state: PaymentMethodEditState, viewModel: PaymentMethodEditViewModel, openLabel: String) {
    val draft = state.draft
    // One button becomes several fields. Swapping them on the frame drops the
    // rest of the screen without anything having moved.
    AnimatedSwap(draft == null, label = "draft") { closed ->
        if (closed) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = viewModel::openDraft, enabled = !state.busy) { Text(openLabel) }
            }
        } else {
            // Not null when this branch was chosen, but the outgoing half of a
            // swap outlives the state that chose it.
            Column {
                when (draft) {
                    is MethodDraft.Wallet -> WalletDraftFields(draft, state, viewModel)
                    is MethodDraft.Node -> NodeDraftFields(draft, viewModel)
                    null -> Unit
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = viewModel::review,
                        enabled = !state.busy && draft?.toConfigJson() != null,
                    ) {
                        Text(if (state.config == null) "Review and save" else "Review and replace")
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = viewModel::closeDraft, enabled = !state.busy) { Text("Cancel") }
                    BusyBeside(state.reviewing)
                }
            }
        }
    }
}

@Composable
private fun WalletDraftFields(draft: MethodDraft.Wallet, state: PaymentMethodEditState, viewModel: PaymentMethodEditViewModel) {
    FormField(
        label = "Derivation scheme",
        value = draft.derivationScheme,
        onValueChange = { value -> viewModel.editWallet { it.copy(derivationScheme = value) } },
        singleLine = false,
        supportingText = "An output descriptor or an extended public key. Paste a watch-only " +
            "key here to keep the private keys off the server.",
    )
    FormField(
        label = "Account key path",
        value = draft.accountKeyPath,
        onValueChange = { value -> viewModel.editWallet { it.copy(accountKeyPath = value) } },
        placeholder = "abcd1234/84'/0'/0'",
        supportingText = "Optional. The fingerprint and path of the signing device, used when a " +
            "PSBT is exported.",
    )
    FormField(
        label = "Label",
        value = draft.label,
        onValueChange = { value -> viewModel.editWallet { it.copy(label = value) } },
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = viewModel::previewDraft,
            enabled = !state.draftPreviewing && draft.derivationScheme.isNotBlank(),
        ) {
            Text("Preview")
        }
        BusyBeside(state.draftPreviewing)
    }
    AddressList(
        addresses = state.draftPreview,
        caption = "The first addresses of this wallet. Check them against your signing device.",
    )
}

@Composable
private fun NodeDraftFields(draft: MethodDraft.Node, viewModel: PaymentMethodEditViewModel) {
    AnimatedSwap(draft.internalNode, label = "internalNode") { internal ->
        Column {
            if (internal) {
                Text(
                    text = "Uses the server's internal Lightning node.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = { viewModel.editNode { it.copy(internalNode = false) } },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Use a connection string instead")
                }
            } else {
                SecretField(
                    label = "Connection string",
                    value = draft.connectionString,
                    onValueChange = { value -> viewModel.editNode { it.copy(connectionString = value) } },
                    // A connection string usually carries a macaroon or a
                    // certificate thumbprint, which is why it is masked.
                    supportingText = "For example type=lnd-rest;server=https://…;macaroon=… . It holds " +
                        "credentials for your node, so it is hidden until you reveal it.",
                )
                OutlinedButton(
                    onClick = { viewModel.editNode { it.copy(internalNode = true) } },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Use the server's internal node")
                }
            }
        }
    }
}

/**
 * The last step before a change of where the store is paid. The addresses, or
 * the node hosts, are monospace and on their own lines, because comparing
 * them is the point of the dialog.
 */
@Composable
private fun DraftReviewDialog(
    review: DraftReview,
    state: PaymentMethodEditState,
    onChain: Boolean,
    storeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val replacing = state.config != null
    val wallet = (state.config as? MethodConfig.OnChain)?.wallet
    val warnings = listOfNotNull(
        "This drops the hot-wallet setting; the server will no longer sign or run automatic payouts."
            .takeIf { wallet?.isHotWallet == true },
        "This drops the other signers of this multisig wallet."
            .takeIf { (wallet?.accountKeySettings?.size ?: 0) > 1 },
        "$NODE_UNVERIFIED Check that the string is yours.".takeIf { review.nodeUnverified },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    onChain && replacing -> "Replace the wallet?"
                    onChain -> "Use this wallet?"
                    replacing -> "Replace the node?"
                    else -> "Use this node?"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (onChain) {
                        "Future payments to $storeName go to the new wallet."
                    } else {
                        "Future Lightning payments to $storeName go to the new node."
                    },
                )
                review.current?.let { ReviewAddress(if (onChain) "First address now" else "Node now", it) }
                review.next?.let {
                    val label = when {
                        !onChain -> "New node"
                        replacing -> "First new address"
                        else -> "First address"
                    }
                    ReviewAddress(label, it)
                }
                warnings.forEach { warning ->
                    Spacer(Modifier.height(12.dp))
                    Text(warning, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (replacing) "Replace" else "Save",
                    color = if (replacing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The review before a method is turned on (or LNURL is set up), in the editor
 * and the list. The caller runs the spend gate after [onConfirm], from its own scope.
 */
@Composable
internal fun EnableReviewDialog(
    review: EnableReview,
    storeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val id = review.paymentMethodId
    val destination = review.destination
    val where = when {
        destination == null -> "Buyers can then pay with it, to the wallet or node saved for it."
        paymentMethodKind(id) == PaymentMethodKind.OnChain ->
            "Buyers can then pay with it, to the saved wallet. Its first address:\n\n$destination\n\n" +
                "Check it against your signing device."
        paymentMethodKind(id) == PaymentMethodKind.Lnurl ->
            "Buyers can then pay with it, to the node saved for ${lnurlNodeId(id)}:\n\n$destination"
        else -> "Buyers can then pay with it, to the saved node:\n\n$destination"
    }
    ConfirmDialog(
        title = "Turn on $id for $storeName?",
        // The saved string is never shown, so the way out is a new node, not a check.
        message = if (review.nodeUnverified) {
            // LNURL pays through the node of its Lightning method, so that is the one to replace.
            val replace = if (paymentMethodKind(id) == PaymentMethodKind.Lnurl) "the node of ${lnurlNodeId(id)}" else "it"
            "$where\n\n$NODE_UNVERIFIED If you are not sure that the node is yours, replace $replace first."
        } else {
            where
        },
        confirmLabel = "Turn on",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ReviewAddress(label: String, address: String) {
    Spacer(Modifier.height(12.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(address, style = MonospaceStyle)
}

@Composable
private fun AddressList(addresses: List<WalletPreviewAddress>, caption: String) {
    AnimatedVisibility(
        visible = addresses.isNotEmpty(),
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Column {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            addresses.forEachIndexed { index, address ->
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

/** Sideways: the spinner sits beside its button, and opening downward would shift what is below. */
@Composable
private fun BusyBeside(busy: Boolean) {
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

@Composable
private fun GeneratorSection(state: PaymentMethodEditState, viewModel: PaymentMethodEditViewModel) {
    FormSection("New wallet") {
        val generator = state.generator
        AnimatedSwap(generator == null, label = "generator") { closed ->
            if (closed) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Creates a new wallet on the server for this store.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.openGenerator(true) }, enabled = !state.busy) {
                        Text("Generate a wallet")
                    }
                }
            } else {
                // Not null when this branch was chosen, but the outgoing half
                // of a swap outlives the state that chose it.
                generator?.let {
                    Column {
                        WalletGeneratorFields(form = it, busy = state.busy, viewModel = viewModel)
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
        Button(onClick = { viewModel.ask(MethodConfirm.Generate) }, enabled = !busy) { Text("Generate") }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = { viewModel.openGenerator(false) }, enabled = !busy) { Text("Cancel") }
        BusyBeside(busy)
    }
}

/**
 * The seed is returned by the server exactly once and this app keeps no copy of
 * it, so the dialog says so plainly rather than relying on the user to guess.
 *
 * With no private keys on the server it is the only key to every future
 * payment, so it never goes to the clipboard (below Android 13 any app, the
 * keyboard or a cloud clipboard can read it there), and the dialog window is
 * always secure, whatever the "Block screenshots" setting says.
 */
@Composable
private fun MnemonicDialog(mnemonic: String, onDismiss: () -> Unit) {
    var revealed by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("Write this down now") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "This recovery phrase is shown once and once only. It is not saved on " +
                        "this device and cannot be shown again. Anyone who reads it can spend " +
                        "the wallet, so write it on paper and keep it off screen in public.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                AnimatedSwap(revealed, label = "mnemonic") { shown ->
                    if (shown) {
                        AppCard {
                            Column(Modifier.padding(12.dp)) {
                                Text(text = mnemonic, style = MonospaceStyle)
                            }
                        }
                    } else {
                        OutlinedButton(onClick = { revealed = true }) { Text("Reveal the phrase") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = revealed) { Text("I wrote down these words") }
        },
    )
}

// ---------------------------------------------------------------------------
// Lightning and LNURL
// ---------------------------------------------------------------------------

@Composable
private fun LightningConfigSection(config: MethodConfig.Lightning) {
    FormSection("Node connection") {
        DetailRow(label = "Node", value = config.summary)
    }
}

@Composable
private fun LnurlConfigSection(
    config: MethodConfig.Lnurl,
    busy: Boolean,
    viewModel: PaymentMethodEditViewModel,
) {
    FormSection("LNURL") {
        FormSwitch(
            title = "Bech32 encoding",
            description = "Off uses the newer lightning: URI form, which more wallets now read.",
            checked = config.useBech32Scheme,
            onCheckedChange = { value -> viewModel.setLnurl { it.copy(useBech32Scheme = value) } },
            enabled = !busy,
        )
        FormSwitch(
            title = "Comments (LUD-12)",
            description = "Lets the payer attach a short message to the payment.",
            checked = config.lud12Enabled,
            onCheckedChange = { value -> viewModel.setLnurl { it.copy(lud12Enabled = value) } },
            enabled = !busy,
        )
        FormSwitch(
            title = "Payment verification (LUD-21)",
            description = "Adds a public link where anyone with the payment hash can check that it was paid.",
            checked = config.lud21Enabled,
            onCheckedChange = { value -> viewModel.setLnurl { it.copy(lud21Enabled = value) } },
            enabled = !busy,
        )
    }
}

@Composable
private fun LnurlSetUp(busy: Boolean, onSetUp: () -> Unit) {
    FormSection("LNURL") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Lets wallets pay this store through LNURL. Payments go to the store's " +
                    "Lightning node, so set that up first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSetUp, enabled = !busy) { Text("Set up LNURL") }
        }
    }
}

@Composable
private fun RawConfigSection(config: MethodConfig.Raw) {
    FormSection("Configuration") {
        Text(
            text = "This app does not know how to show this kind of payment method. The stored " +
                "configuration is shown as the server sent it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        AppCard {
            Text(text = config.text, style = MonospaceStyle, modifier = Modifier.padding(12.dp))
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
