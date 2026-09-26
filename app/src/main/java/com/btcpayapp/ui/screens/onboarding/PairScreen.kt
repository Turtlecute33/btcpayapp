package com.btcpayapp.ui.screens.onboarding

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInBrowser
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.pairing.ApiKeyGrant
import com.btcpayapp.core.pairing.Pairing
import com.btcpayapp.core.pairing.PairingReceiver
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.Endpoint
import com.btcpayapp.data.api.endpoints.currentUser
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PermissionSet(val label: String, val description: String) {
    Full(
        label = "Full access",
        description = "Create and settle invoices, move payouts, and use the wallet and " +
            "Lightning node. Server settings and user management are not included.",
    ),
    ReadOnly(
        label = "Read only",
        description = "Watch invoices, payouts and balances. The key cannot spend, refund " +
            "or change anything.",
    ),
}

data class PairState(
    val permissionSet: PermissionSet = PermissionSet.Full,
    val serverAdmin: Boolean = false,
    val waiting: Boolean = false,
    val saving: Boolean = false,
    val error: ApiException? = null,
    val paired: Boolean = false,
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

    private var receiver: PairingReceiver? = null
    private var pairingJob: Job? = null

    fun setPermissionSet(value: PermissionSet) = _state.update { it.copy(permissionSet = value) }

    fun setServerAdmin(value: Boolean) = _state.update { it.copy(serverAdmin = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeLaunchUrl() = _state.update { it.copy(launchUrl = null) }

    fun permissions(): List<String> {
        val base = when (_state.value.permissionSet) {
            PermissionSet.Full -> Pairing.DEFAULT_PERMISSIONS
            PermissionSet.ReadOnly -> Pairing.READ_ONLY_PERMISSIONS
        }
        return if (_state.value.serverAdmin) base + Pairing.SERVER_ADMIN_PERMISSIONS else base
    }

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
            val user = api.currentUser()
            val stores = api.stores()

            // Store-scoped permissions carry the store id, so the store the
            // user actually ticked on the consent screen is known without
            // asking them to pick again.
            val preferred = stores.firstOrNull { it.id in grant.scopedStoreIds }
                ?: stores.firstOrNull()

            graph.accounts.add(
                Account(
                    id = "",
                    // An account is a server, so the host is the honest label
                    // — unless there is exactly one store, where its name is
                    // more recognisable.
                    label = stores.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                        ?: baseUrl.hostOrSelf(),
                    baseUrl = baseUrl,
                    credential = credential,
                    userId = grant.userId.takeIf { it.isNotBlank() },
                    userEmail = user.email.takeIf { it.isNotBlank() },
                    userName = user.name,
                    permissions = grant.permissions,
                    certificatePins = pins,
                    activeStoreId = preferred?.id,
                ),
            )
            _state.update { it.copy(saving = false, paired = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
    onManualKey: () -> Unit,
    onPaired: () -> Unit,
) {
    val viewModel = appViewModel { PairViewModel(it, baseUrl, pins) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var advanced by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.paired) {
        if (state.paired) onPaired()
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
        if (!opened) {
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
                onRetry = viewModel::authorise,
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
                        onManualKey = {
                            // Free the bound port before leaving; the paste route is
                            // a different screen and nothing would close it later.
                            viewModel.cancel()
                            onManualKey()
                        },
                    )
                } else {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Your server's own page will ask you to approve this app. Sign in there " +
                                "as usual — your password and any two-factor code stay in the browser, and " +
                                "the app only ever receives the key that comes back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp).arrive(0),
                        )

                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = viewModel::authorise,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(1),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Authorise in browser")
                        }

                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onManualKey,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(2),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Having trouble? Paste a key instead")
                        }

                        // Collapsed by default. Full access with no server administration
                        // is what a merchant terminal wants; someone who needs to narrow
                        // or widen that knows to look here, and everyone else is spared a
                        // decision they cannot yet make anything of.
                        Spacer(Modifier.height(16.dp))
                        TextButton(
                            onClick = { advanced = !advanced },
                            modifier = Modifier.padding(horizontal = 8.dp).arrive(3),
                        ) {
                            Icon(
                                imageVector = if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Advanced")
                        }

                        AnimatedVisibility(
                            visible = advanced,
                            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                        ) {
                            Column {
                                SectionHeader("What to ask for")

                                PermissionSet.entries.forEach { option ->
                                    PermissionOption(
                                        option = option,
                                        selected = state.permissionSet == option,
                                        onSelect = { viewModel.setPermissionSet(option) },
                                    )
                                }

                                FormSwitch(
                                    title = "Also manage this server",
                                    checked = state.serverAdmin,
                                    onCheckedChange = viewModel::setServerAdmin,
                                    description = "Adds server settings, user management and the internal " +
                                        "Lightning node. Only useful if you are an administrator.",
                                )

                                val permissionCount = remember(state.permissionSet, state.serverAdmin) {
                                    viewModel.permissions().size
                                }
                                Text(
                                    text = "$permissionCount permissions will be requested. " +
                                        "The server is asked to grant exactly these and nothing broader, and the " +
                                        "consent screen lets you narrow the key to individual stores.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingSection(saving: Boolean, onCancel: () -> Unit, onManualKey: () -> Unit) {
    // The browser hands the key back by POSTing it to a loopback listener. That
    // is what RFC 8252 prescribes and it is the only way to receive a POST body,
    // but it depends on the browser being willing to submit a form from an https
    // page to http://127.0.0.1 — and some are not. When that is refused the key
    // has usually already been created server-side, so the worst outcome is a
    // spinner that never resolves while the key sits on the server unclaimed.
    //
    // So: after a grace period, stop pretending and offer the way out. The
    // paste screen re-opens the same consent page, where BTCPay recognises the
    // key it already made for this application and shows it to copy.
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
                        text = "Approved it already? To hand the key back, the browser has to open a " +
                            "connection to this device, and privacy-hardened browsers block that. " +
                            "Your server created the key regardless — collect it here instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onManualKey) { Text("Get the key manually") }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel, modifier = Modifier.arrive(2)) { Text("Cancel") }
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

private fun String.hostOrSelf(): String =
    runCatching { URI(this).host }.getOrNull() ?: this
