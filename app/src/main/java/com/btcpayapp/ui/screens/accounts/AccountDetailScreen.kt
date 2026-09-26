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
import com.btcpayapp.core.net.Tls
import com.btcpayapp.core.util.Dates
import com.btcpayapp.core.util.Text as TextUtil
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.endpoints.revokeCurrentApiKey
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.AccountProxy
import com.btcpayapp.data.session.toEndpoint
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
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
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val error: ApiException? = null,
    val message: String? = null,
    val removed: Boolean = false,
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
            combine(graph.session.activeAccount, graph.session.serverInfo) { active, info ->
                info?.version?.takeIf { active?.id == accountId }
            }.collect { version -> _state.update { it.copy(liveServerVersion = version) } }
        }
    }

    fun setLabel(value: String) = _state.update { it.copy(label = value, dirty = true) }

    fun setProxyEnabled(value: Boolean) = _state.update { it.copy(proxyEnabled = value, dirty = true) }

    fun setProxyHost(value: String) = _state.update { it.copy(proxyHost = value, dirty = true) }

    fun setProxyPort(value: String) =
        _state.update { it.copy(proxyPort = value.filter(Char::isDigit), dirty = true, portError = null) }

    fun setProxySocks(value: Boolean) = _state.update { it.copy(proxySocks = value, dirty = true) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun save() {
        val account = _state.value.account ?: return
        val snapshot = _state.value
        val port = snapshot.proxyPort.toIntOrNull()

        if (snapshot.proxyEnabled && (port == null || port !in 1..65535)) {
            _state.update { it.copy(portError = "Enter a port between 1 and 65535.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, portError = null) }
            runCatching { graph.accounts.update(account.id) { stored ->
                stored.copy(
                    label = snapshot.label.trim().ifBlank { stored.host },
                    proxy = if (snapshot.proxyEnabled) {
                        AccountProxy(
                            host = snapshot.proxyHost.trim().ifBlank { "127.0.0.1" },
                            port = port ?: 9050,
                            socks = snapshot.proxySocks,
                        )
                    } else {
                        null
                    },
                )
            }
            }.onSuccess {
                _state.update { it.copy(busy = false, dirty = false, message = "Saved") }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
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
            runCatching {
                BtcPayApi(graph.client, account.toEndpoint()).revokeCurrentApiKey()
                graph.accounts.remove(account.id)
            }
                .onSuccess {
                    _state.update { it.copy(busy = false, removed = true) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            busy = false,
                            error = failure as? ApiException
                                ?: ApiException.Transport(
                                    failure.message ?: "Could not revoke the key on the server.",
                                ),
                        )
                    }
                }
        }
    }

    fun removeWithoutRevoking() {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { graph.accounts.remove(account.id) }.onSuccess {
                _state.update { it.copy(busy = false, removed = true) }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _state.update { it.copy(busy = false, error = ApiException.Transport("Could not remove the account. Check device storage and try again.")) }
            }
        }
    }
}

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
                    enabled = !state.busy,
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
                    enabled = !state.busy,
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
                viewModel.revokeAndRemove()
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
                viewModel.removeWithoutRevoking()
            },
            onDismiss = { confirmRemove = false },
        )
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
