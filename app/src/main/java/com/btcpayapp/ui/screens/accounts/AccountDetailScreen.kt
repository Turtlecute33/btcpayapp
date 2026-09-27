package com.btcpayapp.ui.screens.accounts

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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.CertificateProbe
import com.btcpayapp.core.net.Tls
import com.btcpayapp.core.net.TlsProblem
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.isOnThisPhone
import com.btcpayapp.data.api.endpoints.revokeCurrentApiKey
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.AccountProxy
import com.btcpayapp.data.session.toEndpoint
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CertificateCheckDialog
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.screens.settings.rememberOwnerCheck
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

data class AccountDetailState(
    val account: Account? = null,
    val loaded: Boolean = false,
    val label: String = "",
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: String = "9050",
    val proxySocks: Boolean = true,
    /** Only known while this account is the active one. */
    val liveServerVersion: String? = null,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val portError: String? = null,
    val hostError: String? = null,
    val error: ApiException? = null,
    val message: String? = null,
    val removed: Boolean = false,
    /** A call from this screen met a key the account has not accepted. */
    val keyChanged: Boolean = false,
    /** The session's last call for this account did; only known while it is the active one. */
    val sessionKeyChanged: Boolean = false,
    val probing: Boolean = false,
    /** The server's current certificate, while the user checks it. */
    val probe: CertificateProbe? = null,
)

class AccountDetailViewModel(
    private val graph: AppGraph,
    private val accountId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountDetailState())
    val state = _state.asStateFlow()

    private var seeded = false

    init {
        viewModelScope.launch {
            graph.accounts.vault.collect { vault ->
                val account = vault.accounts.firstOrNull { it.id == accountId }
                _state.update { current ->
                    // The editable fields are seeded once. Re-seeding on every
                    // vault emission would wipe whatever the user is typing the
                    // moment any unrelated account changes.
                    if (account != null && !seeded) {
                        seeded = true
                        current.copy(
                            account = account,
                            loaded = true,
                            label = account.label,
                            proxyEnabled = account.proxy != null,
                            proxyHost = account.proxy?.host ?: "127.0.0.1",
                            proxyPort = (account.proxy?.port ?: 9050).toString(),
                            proxySocks = account.proxy?.socks ?: true,
                        )
                    } else {
                        current.copy(account = account, loaded = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                graph.session.activeAccount,
                graph.session.serverInfo,
                graph.session.lastError,
            ) { active, info, error ->
                val mine = active?.id == accountId
                Pair(info?.version?.takeIf { mine }, mine && error.isKeyChanged())
            }.collect { (version, keyChanged) ->
                _state.update { it.copy(liveServerVersion = version, sessionKeyChanged = keyChanged) }
            }
        }
    }

    fun setLabel(value: String) = _state.update { it.copy(label = value, dirty = true) }

    fun setProxyEnabled(value: Boolean) = _state.update { it.copy(proxyEnabled = value, dirty = true) }

    fun setProxyHost(value: String) = _state.update { it.copy(proxyHost = value, dirty = true, hostError = null) }

    fun setProxyPort(value: String) =
        _state.update { it.copy(proxyPort = value.filter(Char::isDigit), dirty = true, portError = null) }

    fun setProxySocks(value: Boolean) = _state.update { it.copy(proxySocks = value, dirty = true) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun save() {
        val account = _state.value.account ?: return
        val snapshot = _state.value
        val port = snapshot.proxyPort.toIntOrNull()
        val proxyHost = snapshot.proxyHost.trim().ifBlank { "127.0.0.1" }

        val portError = "Enter a port between 1 and 65535."
            .takeIf { snapshot.proxyEnabled && (port == null || port !in 1..65535) }
        // Plain HTTP is only encrypted by Tor itself, so the hop to the proxy
        // carries the key in clear text. It must not leave the phone.
        // The client refuses such a request too; this says so before saving.
        val hostError = "For an unencrypted .onion address the proxy must run on this phone (127.0.0.1)."
            .takeIf { snapshot.proxyEnabled && account.cleartext && !isOnThisPhone(proxyHost) }
        if (portError != null || hostError != null) {
            _state.update { it.copy(portError = portError, hostError = hostError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, portError = null, hostError = null) }
            attempt { graph.accounts.update(account.id) { stored ->
                stored.copy(
                    label = snapshot.label.trim().ifBlank { stored.host },
                    proxy = if (snapshot.proxyEnabled) {
                        AccountProxy(
                            host = proxyHost,
                            port = port ?: 9050,
                            // The HTTP-proxy option is hidden for plain HTTP,
                            // so an old choice of it must not stay in force.
                            socks = snapshot.proxySocks || account.cleartext,
                        )
                    } else {
                        null
                    },
                )
            }
            }.onSuccess {
                _state.update { it.copy(busy = false, dirty = false, message = "Saved") }
            }.onFailure { failure ->
                _state.update { it.copy(busy = false, error = ApiException.Transport("Could not save the account. Check device storage and try again.")) }
            }
        }
    }

    /**
     * Revokes the key server-side, then forgets the account.
     *
     * Deleting the local copy alone leaves the key live on the server for
     * whoever next reads a backup or a disk image — BTCPay has no idea the phone
     * is gone. So the removal is refused when the revocation call fails, and the
     * user is pointed at the "without revoking" action if they mean it.
     */
    fun revokeAndRemove() {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val revoked = runCatching { BtcPayApi(graph.client, account.toEndpoint()).revokeCurrentApiKey() }
            revoked.exceptionOrNull()?.let { failure ->
                val error = failure.asApiException()
                // A changed server key blocks the revocation too. The notice it
                // raises is the way to trust the new key and try again.
                _state.update { it.copy(busy = false, error = error, keyChanged = it.keyChanged || error.isKeyChanged()) }
                return@launch
            }
            remove(account.id, "The key is revoked, but the account could not be removed from this " +
                "phone. Use Remove without revoking.")
        }
    }

    fun removeWithoutRevoking() {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            remove(account.id, "Could not remove the account. Check device storage and try again.")
        }
    }

    /**
     * Through [AppGraph.removeAccount], which also forgets the account's sync
     * state and clears its notifications. The vault alone would
     * leave both behind.
     */
    private suspend fun remove(id: String, failureText: String) {
        attempt { graph.removeAccount(id) }.onSuccess {
            _state.update { it.copy(busy = false, removed = true) }
        }.onFailure { failure ->
            _state.update { it.copy(busy = false, error = ApiException.Transport(failureText)) }
        }
    }

    /**
     * Reads the certificate the server offers now, for [CertificateCheckDialog].
     *
     * Direct connections only. The probe cannot use a proxy, so for an onion
     * or proxied account it would look the name up on the local network and
     * reach the server from this phone's own address, which is what the proxy
     * is there to prevent. The screen does not offer it for those.
     */
    fun reviewCertificate() {
        val account = _state.value.account ?: return
        if (!account.canProbe || _state.value.probing) return
        viewModelScope.launch {
            _state.update { it.copy(probing = true, error = null) }
            val probe = withContext(Dispatchers.IO) { Tls.probeCertificate(account.host, account.httpsPort) }
            _state.update {
                it.copy(
                    probing = false,
                    probe = probe,
                    error = if (probe == null) ApiException.Transport("Could not read the server's certificate. Try again.") else null,
                )
            }
        }
    }

    fun cancelReview() = _state.update { it.copy(probe = null) }

    /**
     * Replaces the pins with [pin], which the user has just matched against
     * the server itself in [CertificateCheckDialog]. Replaced, not added: the
     * old key is the one the server no longer uses, and keeping it would let
     * whoever holds it pass for this server.
     */
    fun trustNewKey(pin: String) {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            _state.update { it.copy(probe = null, busy = true, error = null) }
            attempt { graph.accounts.update(account.id) { it.copy(certificatePins = listOf(pin)) } }
                .onSuccess {
                    _state.update { it.copy(busy = false, keyChanged = false, message = "The new key is trusted.") }
                }.onFailure { failure ->
                        _state.update { it.copy(busy = false, error = ApiException.Transport("Could not save the account. Check device storage and try again.")) }
                }
        }
    }
}

/** Plain HTTP: only a proxy on this phone may carry it. See [AccountDetailViewModel.save]. */
private val Account.cleartext: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

/** Whether [AccountDetailViewModel.reviewCertificate] may connect: a direct HTTPS connection. */
private val Account.canProbe: Boolean get() = proxy == null && !isOnion && !cleartext

private val Account.httpsPort: Int get() = runCatching { URI(baseUrl).port }.getOrNull()?.takeIf { it > 0 } ?: 443

private fun Throwable?.isKeyChanged(): Boolean = (this as? ApiException.Tls)?.problem == TlsProblem.KeyChanged

@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = accountId) { AccountDetailViewModel(it, accountId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val guardedBack = com.btcpayapp.ui.components.confirmDiscardChanges(state.dirty, onBack)

    var confirmRevoke by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(state.removed) {
        if (state.removed) onBack()
    }

    // The snackbar runs on the composition scope rather than inside the effect:
    // consuming the message flips the effect's key, which would cancel a
    // suspended `showSnackbar` before it ever appeared.
    val scope = rememberCoroutineScope()
    val gate = rememberSpendGate()
    // Removing an account does part of what Erase everything does, and
    // revoking its key also stops whatever else uses that key, so both ask
    // for the same proof.
    val owner = rememberOwnerCheck(onFailed = {
        scope.launch { snackbarHostState.showSnackbar("Not confirmed, so nothing was done.") }
    })
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val account = state.account

    AppScreen(
        title = account?.label ?: "Account",
        subtitle = account?.host,
        onBack = guardedBack,
        snackbarHostState = snackbarHostState,
        actions = {
            // The action appears the moment a field is edited, so it slides in
            // from the edge rather than materialising over the title bar.
            AnimatedVisibility(
                visible = state.dirty,
                enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                TextButton(onClick = viewModel::save, enabled = !state.busy) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (account == null) {
                if (state.loaded) {
                    EmptyState(
                        title = "This account is gone",
                        description = "It was removed from this device.",
                    )
                }
                return@Column
            }

            ErrorBanner(state.error, onDismiss = viewModel::dismissError)

            // Grouped rather than staggered element by element: the sections
            // are what the reader is looking for, and a twenty-step cascade of
            // individual rows would take longer to finish than to read.
            Column(Modifier.arrive(0)) {
                FormField(
                    label = "Label",
                    value = state.label,
                    onValueChange = viewModel::setLabel,
                    supportingText = "Only shown on this device.",
                    enabled = !state.busy,
                )

                SectionHeader("Connection")
                DetailRow(label = "Address", value = account.baseUrl, monospace = true)
                DetailRow(
                    label = "Signed in as",
                    value = account.userName?.takeIf { it.isNotBlank() }
                        ?: account.userEmail?.takeIf { it.isNotBlank() }
                        ?: "Unknown",
                )
                account.userEmail?.takeIf { it.isNotBlank() && it != account.userName }?.let {
                    DetailRow(label = "Email", value = it)
                }
                DetailRow(
                    label = "Server version",
                    value = state.liveServerVersion ?: account.serverVersion ?: "Not reported",
                )
                if (account.createdAt > 0) {
                    DetailRow(label = "Added", value = Dates.date(account.createdAt / 1000))
                }
            }

            Column(Modifier.arrive(1)) {
                if (account.usesPinnedCertificate) {
                    SectionHeader("Pinned certificate")
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (state.keyChanged || state.sessionKeyChanged) {
                            KeyChangedNotice(
                                canReview = account.canProbe,
                                probing = state.probing,
                                enabled = !state.busy,
                                onReview = viewModel::reviewCertificate,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        account.certificatePins.forEach { pin ->
                            CopyableField(
                                label = "SHA-256 of the public key",
                                value = Tls.fingerprintForDisplay(pin),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = "This key authenticates the server for this account only. " +
                                "Nothing else on the device trusts it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                PermissionsSection(account.permissions)
            }

            Column(Modifier.arrive(2)) {
                SectionHeader("Proxy")
                FormSwitch(
                    title = "Route through a proxy",
                    checked = state.proxyEnabled,
                    onCheckedChange = viewModel::setProxyEnabled,
                    description = if (account.isOnion) {
                        "Onion addresses already go through Orbot on 127.0.0.1:9050. Override it here " +
                            "if your Tor client listens elsewhere."
                    } else {
                        "Send this account's traffic through Tor or another local proxy."
                    },
                    enabled = !state.busy,
                )
                // Three fields that only exist because the switch above is on,
                // so they open out of it rather than appearing underneath it.
                AnimatedVisibility(
                    visible = state.proxyEnabled,
                    enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                    exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                ) {
                    Column {
                        FormField(
                            label = "Proxy host",
                            value = state.proxyHost,
                            onValueChange = viewModel::setProxyHost,
                            error = state.hostError,
                            enabled = !state.busy,
                        )
                        FormField(
                            label = "Proxy port",
                            value = state.proxyPort,
                            onValueChange = viewModel::setProxyPort,
                            keyboardType = KeyboardType.Number,
                            error = state.portError,
                            enabled = !state.busy,
                        )
                        // Plain HTTP goes through SOCKS on this phone only,
                        // so there is no choice to offer.
                        if (!account.cleartext) {
                            FormSwitch(
                                title = "SOCKS5",
                                checked = state.proxySocks,
                                onCheckedChange = viewModel::setProxySocks,
                                description = "Off sends requests through an HTTP proxy instead. SOCKS5 is " +
                                    "what Orbot and a standalone Tor daemon expose, and it keeps hostname " +
                                    "lookups off the local resolver.",
                                enabled = !state.busy,
                            )
                        }
                    }
                }
            }

            // The save button belongs to the edit that summoned it, so it grows
            // out of the form rather than being dropped in under it.
            AnimatedVisibility(
                visible = state.dirty,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::save,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        AnimatedSwap(state.busy, label = "saveAccount") { busy ->
                            if (busy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text("Saving…")
                                }
                            } else {
                                Text("Save changes")
                            }
                        }
                    }
                }
            }

            Column(Modifier.arrive(3)) {
                Spacer(Modifier.height(16.dp))
                ThinDivider()
                SectionHeader("Disconnect")

                Text(
                    text = "Removing the account here deletes the key from this device. It does not " +
                        "tell the server anything, so the key keeps working until it is revoked — " +
                        "which is why the first option is the one to use unless the server is gone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { confirmRevoke = true },
                    enabled = !state.busy && !owner.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revoke key and remove")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = !state.busy && !owner.busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Remove without revoking")
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmRevoke) {
        ConfirmDialog(
            title = "Revoke and remove?",
            message = "The key will be revoked on ${account?.host.orEmpty()} and then deleted from " +
                "this device. Anything else using this key stops working immediately.",
            confirmLabel = "Revoke and remove",
            destructive = true,
            onConfirm = {
                confirmRevoke = false
                owner.confirm("Revoke and remove", viewModel::revokeAndRemove)
            },
            onDismiss = { confirmRevoke = false },
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove without revoking?",
            message = "The key stays valid on the server. Revoke it yourself under Account → " +
                "API keys, or anyone who recovers it can keep using it.",
            confirmLabel = "Remove anyway",
            destructive = true,
            onConfirm = {
                confirmRemove = false
                owner.confirm("Remove without revoking", viewModel::removeWithoutRevoking)
            },
            onDismiss = { confirmRemove = false },
        )
    }

    val probe = state.probe
    if (probe != null && account != null) {
        CertificateCheckDialog(
            host = account.host,
            port = account.httpsPort,
            probe = probe,
            onTrust = { pin ->
                viewModel.cancelReview()
                // The pin decides who receives this account's key from now on,
                // so a wrong one hands the key to whoever holds it. That is
                // control of the store, and it takes the same confirmation.
                scope.afterSpendGate(gate, "Trust the new key", account.host, { snackbarHostState.showSnackbar(it) }) {
                    viewModel.trustNewKey(pin)
                }
            },
            onCancel = viewModel::cancelReview,
        )
    }
}

/**
 * Shown when a pinned account met a key it has not accepted.
 *
 * Without it the account was stuck: every call failed, the revocation too, so
 * the only way out left the old key live on the server. A key change is also
 * what an interception looks like, which is why the new key is checked
 * against the server itself before anything is trusted.
 */
@Composable
private fun KeyChangedNotice(canReview: Boolean, probing: Boolean, enabled: Boolean, onReview: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("The server's key changed", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (canReview) {
                    "${TlsProblem.KeyChanged.userMessage} If you did not change the server's certificate, " +
                        "someone may be intercepting the connection."
                } else {
                    "${TlsProblem.KeyChanged.userMessage} This account uses a proxy or Tor, so the app " +
                        "cannot check the new key. Revoke the key on the server's website, then remove " +
                        "this account without revoking and connect again."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (canReview) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onReview, enabled = enabled && !probing) {
                    AnimatedSwap(probing, label = "probe") { busy ->
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Review the new certificate")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsSection(permissions: List<String>) {
    SectionHeader("Permissions")

    if (permissions.isEmpty()) {
        Text(
            text = "This server did not report what the key can do, so every screen is offered " +
                "and the server refuses whatever the key is not allowed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }

    val grouped = remember(permissions) {
        permissions
            .map { it.substringBefore(':') }
            .distinct()
            .groupBy(::permissionGroup)
    }
    val scopedStores = remember(permissions) {
        permissions.mapNotNull { it.substringAfter(':', "").takeIf(String::isNotBlank) }.distinct()
    }

    GROUP_ORDER.forEach { group ->
        val entries = grouped[group].orEmpty()
        if (entries.isEmpty()) return@forEach

        Text(
            text = group,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        entries.sorted().forEach { permission ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = humanisePermission(permission),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (scopedStores.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Store permissions are scoped to ${scopedStores.size} " +
                (if (scopedStores.size == 1) "store." else "stores."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------------------
// Permission naming
//
// Greenfield permissions are wire identifiers: `btcpay.store.canviewinvoices`,
// optionally suffixed with `:storeId`. Showing them raw makes the consent this
// key carries unreadable, so the prefix and scope are stripped and the leaf is
// split back into a verb and a noun.
// ---------------------------------------------------------------------------

private val GROUP_ORDER = listOf("Everything", "Store", "Server", "Account", "Other")

private val PERMISSION_VERBS = listOf(
    "view" to "View",
    "modify" to "Modify",
    "create" to "Create",
    "manage" to "Manage",
    "broadcast" to "Broadcast",
    "delete" to "Delete",
    "sign" to "Sign",
    "use" to "Use",
)

/** Longest match first: `lightninginvoice` must not swallow the internal-node form. */
private val PERMISSION_NOUNS = listOf(
    "lightninginvoiceinternalnode" to "internal-node Lightning invoices",
    "internallightningnode" to "the internal Lightning node",
    "notificationsforuser" to "notifications",
    "wallettransactions" to "wallet transactions",
    "lightninginvoice" to "Lightning invoices",
    "paymentrequests" to "payment requests",
    "lightningnode" to "the Lightning node",
    "serversettings" to "server settings",
    "storesettings" to "store settings",
    "pullpayments" to "pull payments",
    "transactions" to "transactions",
    "storeusers" to "store users",
    "webhooks" to "webhooks",
    "invoices" to "invoices",
    "invoice" to "invoices",
    "payouts" to "payouts",
    "profile" to "the profile",
    "wallet" to "the wallet",
    "stores" to "stores",
    "users" to "users",
)

private fun permissionGroup(permission: String): String = when {
    permission.startsWith("unrestricted") -> "Everything"
    else -> when (permission.removePrefix("btcpay.").substringBefore('.')) {
        "store" -> "Store"
        "server" -> "Server"
        "user" -> "Account"
        else -> "Other"
    }
}

private fun humanisePermission(permission: String): String {
    if (permission.startsWith("unrestricted")) {
        return "Unrestricted — everything this user can do"
    }

    val leaf = permission.removePrefix("btcpay.").substringAfterLast('.')
    val action = leaf.removePrefix("can")

    val verb = PERMISSION_VERBS.firstOrNull { action.startsWith(it.first) }
        ?: return TextUtil.sentenceCase(action)

    val remainder = action.removePrefix(verb.first)
    val noun = PERMISSION_NOUNS.firstOrNull { remainder == it.first }?.second ?: remainder
    return "${verb.second} $noun"
}
