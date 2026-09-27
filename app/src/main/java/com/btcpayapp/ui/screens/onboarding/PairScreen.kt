package com.btcpayapp.ui.screens.onboarding

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.pairing.ApiKeyGrant
import com.btcpayapp.core.pairing.Pairing
import com.btcpayapp.core.pairing.PairingReceiver
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.util.safeStartActivity
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.Endpoint
import com.btcpayapp.data.api.dto.ApplicationUserData
import com.btcpayapp.data.api.endpoints.currentUser
import com.btcpayapp.data.api.endpoints.requireSupportedServer
import com.btcpayapp.data.api.endpoints.revokeCurrentApiKey
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.Credential
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the key may do, in the order offered. The first is the default and the
 * least a till needs: the phone at the counter is the
 * one most likely to be lost or picked up unlocked, so by default its key can
 * neither move funds nor change where the store is paid.
 *
 * The names travel in `ManualKeyRoute.access`, so a rename breaks a saved back
 * stack; [permissionSetNamed] then falls back to the least access.
 */
enum class PermissionSet(val label: String, val description: String) {
    TakePayments(
        label = "Take payments",
        description = "Creates and shows invoices, including Lightning, and can see store settings, " +
            "reports and payouts. This key cannot send funds or change the store.",
    ),
    Full(
        label = "Full access",
        description = "Can send funds from the store's hot wallet and Lightning node, approve payouts, " +
            "and change store settings, payment methods and webhooks.",
    ),
    ReadOnly(
        label = "Watch only",
        description = "Sees invoices, payments, payouts and balances. Cannot create or change anything.",
    ),
}

/**
 * What to ask the server for. Pure, so a test can hold every set to its promise.
 *
 * Server management comes only with [PermissionSet.Full]. It lets the key pay
 * from the server's Lightning node and create administrators, so added to the
 * other two it would break what their cards promise. [serverAdmin] is ignored
 * for them, and the switch shows only under Full.
 */
internal fun permissionsFor(set: PermissionSet, serverAdmin: Boolean): List<String> {
    val base = when (set) {
        PermissionSet.TakePayments -> Pairing.POINT_OF_SALE_PERMISSIONS
        PermissionSet.Full -> Pairing.DEFAULT_PERMISSIONS
        PermissionSet.ReadOnly -> Pairing.READ_ONLY_PERMISSIONS
    }
    return if (serverAdmin && set == PermissionSet.Full) base + Pairing.SERVER_ADMIN_PERMISSIONS else base
}

/** The set a route names. An unknown name gets the least access, never more. */
internal fun permissionSetNamed(name: String): PermissionSet =
    PermissionSet.entries.firstOrNull { it.name == name } ?: PermissionSet.TakePayments

data class PairState(
    val permissionSet: PermissionSet = PermissionSet.TakePayments,
    val serverAdmin: Boolean = false,
    val waiting: Boolean = false,
    val saving: Boolean = false,
    val error: ApiException? = null,
    val paired: Boolean = false,
    /** Saved but not made active, because a payment result is still open. */
    val addedInactive: Boolean = false,
    /** A URL the composable should hand to the browser, then clear. */
    val launchUrl: String? = null,
)

class PairViewModel(
    private val graph: AppGraph,
    private val baseUrl: String,
    private val pins: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(PairState())
    val state = _state.asStateFlow()

    /** Asked just before the first account is saved; see [AppLockOffer]. */
    internal val lockOffer = AppLockOffer(graph)

    private var receiver: PairingReceiver? = null
    private var pairingJob: Job? = null

    fun setPermissionSet(value: PermissionSet) = _state.update { it.copy(permissionSet = value) }

    fun setServerAdmin(value: Boolean) = _state.update { it.copy(serverAdmin = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeLaunchUrl() = _state.update { it.copy(launchUrl = null) }

    fun permissions(): List<String> = permissionsFor(_state.value.permissionSet, _state.value.serverAdmin)

    fun authorise() {
        if (_state.value.waiting) return

        pairingJob = viewModelScope.launch {
            val listener = runCatching { withContext(Dispatchers.IO) { PairingReceiver.open() } }
                .getOrElse { failure ->
                    _state.update {
                        it.copy(
                            error = ApiException.Transport(
                                "Could not open a local listener for the reply: ${failure.message}",
                            ),
                        )
                    }
                    return@launch
                }

            receiver = listener
            try {
                _state.update {
                    it.copy(
                        waiting = true,
                        error = null,
                        launchUrl = Pairing.authorizeUrl(
                            baseUrl = baseUrl,
                            redirectUri = listener.redirectUri,
                            permissions = permissions(),
                        ),
                    )
                }

                val grant = listener.awaitGrant()
                if (grant == null) {
                    // `cancel()` already cleared `waiting`, so a null grant there
                    // is the user walking away — not a failure to report.
                    if (_state.value.waiting) {
                        _state.update {
                            it.copy(
                                error = ApiException.Transport(
                                    "The authorisation was not completed. Try again, or paste a key instead.",
                                ),
                            )
                        }
                    }
                    return@launch
                }
                save(grant)
            } finally {
                // The listener holds a bound socket; leaving it open would keep a
                // port reachable from other processes on the device.
                listener.close()
                receiver = null
                _state.update { it.copy(waiting = false) }
            }
        }
    }

    fun cancel() {
        // Closing the socket is what unblocks the accept() the receiver is
        // parked in; cancelling the job alone would leave that thread waiting
        // for the five-minute timeout.
        receiver?.close()
        pairingJob?.cancel()
        _state.update { it.copy(waiting = false, launchUrl = null) }
    }

    override fun onCleared() {
        receiver?.close()
        super.onCleared()
    }

    private suspend fun save(grant: ApiKeyGrant) {
        _state.update { it.copy(saving = true, error = null) }

        val credential = Credential.ApiKey(grant.apiKey)
        val api = BtcPayApi(graph.client, endpointFor(credential))

        // A single try/catch, not `runCatching { … }.onSuccess { … }`.
        // In `onSuccess` the `accounts.add` below would sit *outside* the
        // caught region: a vault write failure (disk full, rename failure, or a
        // Keystore key invalidated by a biometric re-enrolment) would throw
        // straight through `viewModelScope` and kill the process. BTCPay returns
        // the cleartext API key exactly once, so that crash would lose it for
        // good and leave the user to revoke a key they can no longer see.
        try {
            // First, so an old server is refused with its cause named instead
            // of being saved and then failing screen by screen.
            val info = api.requireSupportedServer()
            val user = api.currentUserIfAllowed()
            val stores = api.stores()

            // Store-scoped permissions carry the store id, so the store the
            // user actually ticked on the consent screen is known without
            // asking them to pick again.
            val preferred = stores.firstOrNull { it.id in grant.scopedStoreIds }
                ?: stores.firstOrNull()

            val account = Account(
                id = "",
                // An account is a server, so the host is the honest label
                // — unless there is exactly one store, where its name is
                // more recognisable.
                label = stores.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                    ?: baseUrl.hostOrSelf(),
                baseUrl = baseUrl,
                credential = credential,
                userId = grant.userId.takeIf { it.isNotBlank() },
                userEmail = user?.email?.takeIf { it.isNotBlank() },
                userName = user?.name,
                permissions = grant.permissions,
                certificatePins = pins,
                activeStoreId = preferred?.id,
                serverVersion = info.version.takeIf { it.isNotBlank() },
            )
            // The key waits in memory for this one question, and only on the
            // first pairing.
            lockOffer.ask()
            // Switching away while a payment result is open would clear that
            // account's screens, and the result with them.
            val activate = !graph.session.busy.value
            graph.accounts.add(account, makeActive = activate)
            _state.update { it.copy(saving = false, paired = true, addedInactive = !activate) }
        } catch (e: CancellationException) {
            // Not revoked: a cancelled vault write still finishes
            // (EncryptedJsonFile.update), so the account may hold this key.
            throw e
        } catch (e: Exception) {
            // The app never uses this key after a failure: Retry asks the
            // server for a new one. Left alone, it would stay live on the
            // server with nobody knowing.
            api.revokeQuietly()
            _state.update {
                it.copy(
                    saving = false,
                    error = e as? ApiException
                        ?: ApiException.Transport(e.message ?: "Could not use the new key."),
                )
            }
        }
    }

    private fun endpointFor(credential: Credential): Endpoint {
        val onion = baseUrl.hostOrSelf().endsWith(".onion", ignoreCase = true)
        return Endpoint(
            baseUrl = baseUrl,
            credential = credential,
            transport = TransportOptions(
                pinnedSpki = pins.toSet(),
                connectTimeoutMs = if (onion) 45_000 else 15_000,
                readTimeoutMs = if (onion) 60_000 else 30_000,
                proxy = if (onion) ProxySpec.ORBOT else null,
            ),
        )
    }
}

/**
 * Revokes the key this client authenticates with. Best effort: the failure
 * that led here is what the user must see, and when the revoke fails too, the
 * lost-key note says where to remove the key by hand.
 */
private suspend fun BtcPayApi.revokeQuietly() {
    try {
        revokeCurrentApiKey()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Ignored; the KDoc says why.
    }
}

/**
 * The key's user, or null when the key may not read it. "View your profile" is
 * a box the consent page lets the user untick, and the profile only labels the
 * account, so its absence must not fail the pairing or the paste check.
 */
internal suspend fun BtcPayApi.currentUserIfAllowed(): ApplicationUserData? =
    try {
        currentUser()
    } catch (_: ApiException.Forbidden) {
        null
    }

/**
 * The browser hand-off.
 *
 * BTCPay's `/api-keys/authorize` page does not redirect when the user approves:
 * it answers with an auto-submitting HTML form that **POSTs** the key, the user
 * id and the granted permissions to whatever `redirect` was asked for. A custom
 * `btcpayapp://` scheme cannot receive that — Android delivers only the URI to
 * the intent and the body is discarded, so the key would be lost — and Chrome
 * blocks script-initiated navigation to external schemes without a user gesture
 * anyway. A loopback listener on 127.0.0.1 is the pattern RFC 8252 prescribes
 * for exactly this case, and it receives the full body.
 */
@Composable
fun PairScreen(
    baseUrl: String,
    pins: List<String>,
    onBack: () -> Unit,
    onManualKey: (access: String, serverAdmin: Boolean) -> Unit,
    onPaired: () -> Unit,
) {
    val viewModel = appViewModel { PairViewModel(it, baseUrl, pins) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Set once the browser has the consent page, and saved with the screen.
    // After process death the new view model has no listener and no memory of
    // the attempt, so this is the only trace that a key may now exist on the
    // server. It stays set: any attempt that did not end in a
    // saved account (cancelled, timed out, refused) can leave that key.
    var pairingStarted by rememberSaveable { mutableStateOf(false) }

    // For a pinned server the browser opens only after the warning.
    var pendingBrowser by remember { mutableStateOf<(() -> Unit)?>(null) }
    val openBrowser: (() -> Unit) -> Unit = { open ->
        if (pins.isEmpty()) {
            open()
        } else {
            pendingBrowser = open
        }
    }
    val pasteKey = {
        // Free the bound port before leaving; the paste route is a different
        // screen and nothing would close it later.
        viewModel.cancel()
        onManualKey(state.permissionSet.name, state.serverAdmin)
    }
    val openApiKeys = {
        openBrowser {
            context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Pairing.manualApiKeyUrl(baseUrl))))
        }
    }

    LaunchedEffect(state.paired) {
        if (!state.paired) return@LaunchedEffect
        if (state.addedInactive) Toast.makeText(context, ADDED_INACTIVE, Toast.LENGTH_LONG).show()
        onPaired()
    }

    // The snackbar runs on the composition scope, not inside the effect:
    // consuming the URL flips the effect's key and would cancel a suspended
    // `showSnackbar` before it appeared.
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.launchUrl) {
        val url = state.launchUrl ?: return@LaunchedEffect
        viewModel.consumeLaunchUrl()
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (opened) {
            pairingStarted = true
        } else {
            viewModel.cancel()
            scope.launch {
                snackbarHostState.showSnackbar("No browser is available to open that page.")
            }
        }
    }

    AppScreen(
        title = "Authorise the app",
        subtitle = baseUrl.hostOrSelf(),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ErrorBanner(
                error = state.error,
                onDismiss = viewModel::dismissError,
                onRetry = { openBrowser(viewModel::authorise) },
            )

            // A boolean, not the state object: this swap carries the whole
            // screen, and keying it on anything else that moves during the
            // wait — an error clearing, a permission toggled — would restart
            // the transition underneath the user.
            AnimatedSwap(state.waiting || state.saving, label = "pair") { busy ->
                if (busy) {
                    WaitingSection(
                        saving = state.saving,
                        onCancel = viewModel::cancel,
                        onManualKey = pasteKey,
                        onOpenKeys = openApiKeys,
                    )
                } else {
                    Column {
                        if (pairingStarted) LostKeyNote(onOpenKeys = openApiKeys, modifier = Modifier.arrive(0))

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Your server's own page will ask you to approve this app. Sign in there " +
                                "as usual — your password and any two-factor code stay in the browser, and " +
                                "the app only ever receives the key that comes back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp).arrive(0),
                        )

                        AccessChoice(
                            selected = state.permissionSet,
                            serverAdmin = state.serverAdmin,
                            onSelect = viewModel::setPermissionSet,
                            onServerAdminChange = viewModel::setServerAdmin,
                            modifier = Modifier.arrive(1),
                        )
                        Text(
                            text = "Your server's page lists each permission. You can untick any of them " +
                                "and choose which stores the key can use.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).arrive(1),
                        )

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = { openBrowser(viewModel::authorise) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(2),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Authorise in browser")
                        }

                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = pasteKey,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(3),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Having trouble? Paste a key instead")
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    pendingBrowser?.let { open ->
        BrowserWarningDialog(
            onOpen = {
                pendingBrowser = null
                open()
            },
            onCancel = { pendingBrowser = null },
            onPasteKey = {
                pendingBrowser = null
                pasteKey()
            },
        )
    }
    AppLockOfferDialog(viewModel.lockOffer)
}

@Composable
private fun WaitingSection(
    saving: Boolean,
    onCancel: () -> Unit,
    onManualKey: () -> Unit,
    onOpenKeys: () -> Unit,
) {
    // The browser hands the key back by POSTing it to a loopback listener. That
    // is what RFC 8252 prescribes and it is the only way to receive a POST body,
    // but it depends on the browser being willing to submit a form from an https
    // page to http://127.0.0.1 — and some are not. When that is refused the key
    // has usually already been created server-side, so the worst outcome is a
    // spinner that never resolves while the key sits on the server unclaimed.
    //
    // So: after a grace period, stop pretending and offer the way out. That key
    // cannot be collected later: BTCPay shows an earlier key again only for the
    // same redirect, and every pairing has a new port and nonce. So
    // the note says to remove it, and the paste screen makes a new one.
    var stalled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saving) {
        stalled = false
        if (!saving) {
            delay(20_000)
            stalled = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A spinner rather than a skeleton, and it stays. What is being waited
        // for is a person in another app, so there is no shape to stand in for
        // and no honest sense of progress to show — only "still listening".
        CircularProgressIndicator(Modifier.arrive(0))
        Spacer(Modifier.height(24.dp))

        // Waiting and saving are two different waits, not one wait with a new
        // caption. Swapping them says the browser has answered and the app has
        // moved on, which is the one piece of progress there is to report.
        AnimatedSwap(saving, modifier = Modifier.arrive(1), label = "waiting") { storing ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (storing) "Saving the connection…" else "Waiting for the browser",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (storing) {
                        "Checking the new key and reading your stores."
                    } else {
                        "Approve the request in the tab that just opened, then come back. " +
                            "The reply arrives on a listener bound to this device only, which " +
                            "closes as soon as it has the key."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (!saving) {
            // Twenty seconds in, the way out opens rather than appears: the
            // user has been staring at an unchanging screen, and something
            // that simply materialises under their thumb is easy to miss and
            // easy to hit by accident.
            AnimatedVisibility(
                visible = stalled,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Approved it already? Some browsers block the page from handing the key " +
                            "back to this app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    LostKeyNote(onOpenKeys = onOpenKeys)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onManualKey) { Text("Get the key manually") }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel, modifier = Modifier.arrive(2)) { Text("Cancel") }
        }
    }
}

/**
 * The key a pairing may have left on the server.
 *
 * BTCPay makes the key the moment the user approves, then posts it to this
 * app. When the post never arrives — a browser that blocks it, the five-minute
 * timeout, a cancel, the app killed while the browser was in front — that key
 * stays live and the app cannot get it back. A key that arrives but cannot be
 * saved is revoked, and stays live only when that revoke fails too. So the
 * note says "may" and where to remove it.
 */
@Composable
private fun LostKeyNote(onOpenKeys: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "If you approved in the browser, your server may hold a key this app is " +
                        "not using. Remove it under API keys on the server, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            TextButton(onClick = onOpenKeys, modifier = Modifier.align(Alignment.End)) { Text("Open API keys") }
        }
    }
}

/**
 * The access choice, on this screen and on the paste screen, where it also
 * builds the approval link and is compared with what the key really got.
 *
 * On the main screen, not under "Advanced": the default is the
 * least a till needs, and whoever wants more should read what "more" can do
 * before asking for it.
 */
@Composable
internal fun AccessChoice(
    selected: PermissionSet,
    serverAdmin: Boolean,
    onSelect: (PermissionSet) -> Unit,
    onServerAdminChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("What this key can do")
        Column(Modifier.selectableGroup()) {
            PermissionSet.entries.forEach { option ->
                PermissionOption(
                    option = option,
                    selected = selected == option,
                    onSelect = { onSelect(option) },
                )
            }
        }
        // Only under Full access: see [permissionsFor].
        AnimatedVisibility(
            visible = selected == PermissionSet.Full,
            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            FormSwitch(
                title = "Also manage this server",
                checked = serverAdmin,
                onCheckedChange = onServerAdminChange,
                description = "Adds server settings, user management and the server's Lightning " +
                    "node, so this key can also create administrators. Only for server administrators.",
            )
        }
    }
}

@Composable
private fun PermissionOption(
    option: PermissionSet,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shown before the browser opens a server whose key this app pinned.
 *
 * The pin guards the app's own requests only. The browser checks the
 * certificate against the phone's authorities, fails, and shows its own
 * warning, so the sign-in and the new key cross a connection nothing has
 * authenticated. A key made on the server's own computer and pasted here skips
 * that leg, so the dialog offers it; [onPasteKey] is null on the paste screen,
 * which is already that path.
 */
@Composable
internal fun BrowserWarningDialog(
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onPasteKey: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("Check the browser warning") },
        text = {
            Text(
                "This server's certificate is not trusted by your phone. Your browser will warn " +
                    "about it and cannot check the key this app pinned. Continue only on a network " +
                    "you trust, or get a key on the server's own computer and paste it here.",
            )
        },
        // Stacked: three labels this long do not fit side by side on a phone.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onOpen) { Text("Open browser") }
                if (onPasteKey != null) TextButton(onClick = onPasteKey) { Text("Paste a key instead") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/**
 * "Lock the app?", asked once, just before the first account is saved.
 * App lock is off by default, and the first key is the moment
 * the app starts to hold something worth locking.
 *
 * Before the save, not after it: saving an account makes it the active one,
 * which replaces the whole onboarding tree, so a question asked afterwards
 * would never be seen. [ask] holds the save until [answer]. Shared by the
 * pairing and paste screens, which both end in that save.
 */
internal class AppLockOffer(private val graph: AppGraph) {

    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    private var pending: CompletableDeferred<Boolean>? = null

    /** [turnOn] only once a prompt has passed, so the new lock is known to open. */
    fun answer(turnOn: Boolean) {
        pending?.complete(turnOn)
    }

    /** Returns at once unless this is the first account and the lock is off. */
    suspend fun ask() {
        if (graph.accounts.vault.value.accounts.isNotEmpty()) return
        if (graph.settings.settings.value.appLock != AppLockMode.Off) return
        val answered = CompletableDeferred<Boolean>().also { pending = it }
        _showing.value = true
        try {
            // A failed write leaves the lock off, where it was. The account is
            // what must not be lost here; Settings can turn the lock on later.
            if (answered.await()) graph.settings.update { it.copy(appLock = AppLockMode.Biometric) }
        } finally {
            pending = null
            _showing.value = false
        }
    }
}

/**
 * The dialog for [AppLockOffer]. With no screen lock nothing could open the
 * app again, so there is nothing to offer and the save goes on at once.
 */
@Composable
internal fun AppLockOfferDialog(offer: AppLockOffer) {
    val showing by offer.showing.collectAsStateWithLifecycle()
    if (!showing) return

    val context = LocalContext.current
    if (!Biometrics.hasScreenLock(context)) {
        LaunchedEffect(Unit) { offer.answer(false) }
        return
    }
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var prompting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!prompting) offer.answer(false) },
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("Lock the app?") },
        text = { Text("Ask for your fingerprint, face or PIN when you open the app.") },
        confirmButton = {
            TextButton(
                enabled = activity != null && !prompting,
                onClick = {
                    if (activity != null) {
                        prompting = true
                        scope.launch {
                            // The same rule as in Settings: the lock goes on
                            // only after a prompt has worked. A cancelled or
                            // failed prompt leaves the question open.
                            try {
                                if (Biometrics.authenticate(activity, "Lock the app")) offer.answer(true)
                            } finally {
                                prompting = false
                            }
                        }
                    }
                },
            ) { Text("Turn on") }
        },
        dismissButton = {
            TextButton(enabled = !prompting, onClick = { offer.answer(false) }) { Text("Not now") }
        },
    )
}

private fun String.hostOrSelf(): String =
    runCatching { URI(this).host }.getOrNull() ?: this

/** Shown when a new account is saved while a payment result is still open (see [SessionManager.busy]). */
internal const val ADDED_INACTIVE =
    "Account added. Close the open payment result, then switch to it in Accounts."
