package com.btcpayapp.ui.screens.onboarding

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.pairing.Pairing
import com.btcpayapp.core.util.safeStartActivity
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.Endpoint
import com.btcpayapp.data.api.dto.ApplicationUserData
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.endpoints.currentApiKey
import com.btcpayapp.data.api.endpoints.requireSupportedServer
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.Credential
import com.btcpayapp.data.session.Permissions
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.clearClipboardIfHolds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/** What a successful check found, so saving needs no second round trip. */
data class VerifiedKey(
    val key: String,
    /** Null when the key may not read the profile; see [currentUserIfAllowed]. */
    val user: ApplicationUserData?,
    val stores: List<StoreData>,
    /** What the key reports for itself. Empty when the server would not say. */
    val permissions: List<String>,
    /** What `server/info` reported, stored with the account. Null when blank. */
    val serverVersion: String? = null,
)

data class ManualKeyState(
    val apiKey: String = "",
    /** Builds the approval link, and is what the verified key is compared with. */
    val access: PermissionSet = PermissionSet.TakePayments,
    val serverAdmin: Boolean = false,
    val busy: Boolean = false,
    val error: ApiException? = null,
    val verified: VerifiedKey? = null,
    val paired: Boolean = false,
    /** Saved but not made active, because a payment result is still open. */
    val addedInactive: Boolean = false,
)

class ManualKeyViewModel(
    private val graph: AppGraph,
    private val baseUrl: String,
    private val pins: List<String>,
    access: PermissionSet = PermissionSet.TakePayments,
    serverAdmin: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualKeyState(access = access, serverAdmin = serverAdmin))
    val state = _state.asStateFlow()

    /** Asked just before the first account is saved; see [AppLockOffer]. */
    internal val lockOffer = AppLockOffer(graph)

    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value.trim(), error = null, verified = null) }

    fun setAccess(value: PermissionSet) = _state.update { it.copy(access = value) }

    fun setServerAdmin(value: Boolean) = _state.update { it.copy(serverAdmin = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** The consent page for the access chosen now, with nothing to redirect to. */
    fun approvalUrl(): String =
        Pairing.manualAuthorizeUrl(baseUrl, permissionsFor(_state.value.access, _state.value.serverAdmin))

    fun verify() {
        val key = _state.value.apiKey.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            verifyKey(key)
        }
    }

    fun save() {
        val verified = _state.value.verified ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val preferred = verified.stores.firstOrNull()
            // One try around the write too: a vault failure must become a
            // message, not an exception through `viewModelScope`.
            try {
                lockOffer.ask()
                // See PairViewModel: never switch away from an open payment result.
                val activate = !graph.session.busy.value
                graph.accounts.add(
                    makeActive = activate,
                    account = Account(
                        id = "",
                        label = verified.stores.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                            ?: baseUrl.serverHost(),
                        baseUrl = baseUrl,
                        credential = Credential.ApiKey(verified.key),
                        userId = verified.user?.id?.takeIf { it.isNotBlank() },
                        userEmail = verified.user?.email?.takeIf { it.isNotBlank() },
                        userName = verified.user?.name,
                        permissions = verified.permissions,
                        certificatePins = pins,
                        activeStoreId = preferred?.id,
                        serverVersion = verified.serverVersion,
                    ),
                )
                _state.update { it.copy(busy = false, paired = true, addedInactive = !activate) }
            } catch (e: Exception) {
                fail(e, fallback = "Could not save the connection. Try again.")
            }
        }
    }

    private suspend fun verifyKey(key: String) {
        val api = BtcPayApi(graph.client, endpointFor(Credential.ApiKey(key)))

        try {
            val user = api.currentUserIfAllowed()
            // Checked here, before anything can be saved: an old server is
            // refused with its cause named instead of failing screen by
            // screen later.
            val info = api.requireSupportedServer()
            val stores = runCatching { api.stores() }.getOrDefault(emptyList())
            // `/api-keys/current` is API-key-only and absent on older
            // instances. An empty list means "unknown", which the session
            // treats as "let the server decide" rather than "denied".
            val permissions = runCatching { api.currentApiKey().permissions }
                .getOrDefault(emptyList())

            _state.update {
                it.copy(
                    busy = false,
                    verified = VerifiedKey(key, user, stores, permissions, info.version.takeIf(String::isNotBlank)),
                )
            }
        } catch (e: Exception) {
            fail(e, fallback = "Could not reach the server.")
        }
    }

    /** Rethrows a cancellation, which is control flow and no failure to show. */
    private fun fail(failure: Throwable, fallback: String) {
        if (failure is CancellationException) throw failure
        _state.update {
            it.copy(
                busy = false,
                verified = null,
                error = failure as? ApiException ?: ApiException.Transport(fallback),
            )
        }
    }

    private fun endpointFor(credential: Credential): Endpoint {
        val onion = baseUrl.serverHost().endsWith(".onion", ignoreCase = true)
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
 * The fallback when the browser hand-back does not work.
 *
 * One path with the work done for you: the button opens the server's own
 * consent page with the access chosen on the pairing screen already ticked,
 * and BTCPay shows the finished key to copy. Telling the user to
 * "create one under Account → API keys" instead would leave them on a page
 * with an empty form and twenty-odd permission checkboxes to reproduce from
 * memory on a phone.
 *
 * The key can still differ from the choice: the user may untick or tick boxes,
 * or paste a key made by hand. So after the check the key's own permissions
 * are compared with the choice, the way the server reads them
 * ([permissionDiff]), and any difference is said before saving.
 *
 * There is no email-and-password sign-in. BTCPay disables Basic auth per user
 * by default and refuses it outright when two-factor is on, so it would fail
 * for most people who tried it.
 */
@Composable
fun ManualKeyScreen(
    baseUrl: String,
    pins: List<String>,
    onBack: () -> Unit,
    onPaired: () -> Unit,
    access: String = "TakePayments",
    serverAdmin: Boolean = false,
) {
    val viewModel = appViewModel { ManualKeyViewModel(it, baseUrl, pins, permissionSetNamed(access), serverAdmin) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // The field text as last entered, spaces and all. A paste, by the button
    // below or by the system menu, leaves exactly the clip here, while the
    // view model keeps only the trimmed key, so this is the text to clear.
    // In memory only: a key never goes into saved state.
    var entered by remember { mutableStateOf<String?>(null) }
    val enterKey: (String) -> Unit = { text ->
        entered = text
        viewModel.setApiKey(text)
    }

    // For a pinned server a page opens only after the warning.
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val openPage: (String) -> Unit = { url ->
        if (pins.isEmpty()) {
            context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } else {
            pendingUrl = url
        }
    }

    LaunchedEffect(state.paired) {
        if (!state.paired) return@LaunchedEffect
        if (state.addedInactive) Toast.makeText(context, ADDED_INACTIVE, Toast.LENGTH_LONG).show()
        onPaired()
    }

    AppScreen(
        title = "Paste a key",
        subtitle = baseUrl.serverHost(),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ErrorBanner(error = state.error, onDismiss = viewModel::dismissError)

            Spacer(Modifier.height(8.dp))

            // This screen is a recipe, so it assembles in the order it is
            // followed: the explanation, then step one, and so on down to the
            // button that starts it.
            Note(
                "Use this when the browser will not hand the key back by itself. That is normal " +
                    "on privacy-hardened browsers, which block a web page from connecting to the " +
                    "device it is running on — the same protection that makes the automatic " +
                    "handover possible everywhere else.",
                modifier = Modifier.arrive(0),
            )

            AccessChoice(
                selected = state.access,
                serverAdmin = state.serverAdmin,
                onSelect = viewModel::setAccess,
                onServerAdminChange = viewModel::setServerAdmin,
                modifier = Modifier.arrive(1),
            )

            Step(
                1,
                "Open the approval page below. The permissions you chose are ticked. Untick anything " +
                    "you would rather not grant.",
                Modifier.arrive(2),
            )
            Step(2, "Sign in if your server asks, then approve the request.", Modifier.arrive(3))
            Step(3, "Your key appears in a banner at the top of the API Keys page. It is shown once, so copy it now.", Modifier.arrive(4))
            Step(4, "Come back here and paste it in.", Modifier.arrive(5))

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { openPage(viewModel.approvalUrl()) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(6),
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open the approval page")
            }

            TextButton(
                onClick = { openPage(Pairing.manualApiKeyUrl(baseUrl)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(7),
            ) {
                Text("Or create one by hand")
            }

            SectionHeader("The key", modifier = Modifier.arrive(8))

            SecretField(
                label = "API key",
                value = state.apiKey,
                onValueChange = enterKey,
                placeholder = "Paste it here",
                supportingText = "Stored in the encrypted vault. You can revoke it on the server at any time.",
                imeAction = ImeAction.Done,
            )

            // Only on an explicit tap. Reading the clipboard unprompted would
            // trip Android's paste notification and is exactly the kind of thing
            // this app does not do quietly.
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.getClipEntry()
                            ?.clipData?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(enterKey)
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste from clipboard")
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::verify,
                enabled = !state.busy && state.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                BusyLabel(busy = state.busy, label = "Check the key")
            }

            // Everything the check found arrives as one block, because it all
            // appeared at the same moment and for the same reason. A `Column`
            // purely to carry that entrance; it changes nothing about the
            // layout.
            state.verified?.let { verified ->
                Column(Modifier.arrive()) {
                    SectionHeader("This key belongs to")
                    AppCard {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            DetailRow(label = "User", value = verified.user?.displayName ?: "Not shared with this key")
                            verified.user?.takeIf { it.email.isNotBlank() && it.name != null }?.let { user ->
                                DetailRow(label = "Email", value = user.email)
                            }
                            DetailRow(
                                label = "Stores",
                                value = when (verified.stores.size) {
                                    0 -> "None visible to this key"
                                    1 -> verified.stores.first().name
                                    else -> "${verified.stores.size} stores"
                                },
                            )
                        }
                    }

                    SectionHeader("Permissions")
                    val (missing, extra) = remember(verified.permissions, state.access, state.serverAdmin) {
                        permissionDiff(permissionsFor(state.access, state.serverAdmin), verified.permissions)
                    }
                    if (extra.isNotEmpty()) {
                        Note(
                            "This key has more permissions than you chose: " +
                                "${extra.joinToString(", ") { it.permissionLabel() }}.",
                            warning = true,
                        )
                    }
                    if (missing.isNotEmpty()) {
                        Note(
                            "This key lacks: ${missing.joinToString(", ") { it.permissionLabel() }}. " +
                                "Some screens will not work.",
                        )
                    }
                    if (verified.permissions.isEmpty()) {
                        Note(
                            "This server did not report what the key can do. The app will show every " +
                                "screen and let the server refuse anything the key is not allowed.",
                        )
                    } else {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            verified.permissions.forEach { permission ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = permission.permissionLabel(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // At the tap, not after the save: a saved account
                            // becomes the active one, which replaces this whole
                            // screen at once. The key is in the field for a
                            // retry, so nothing needs the clip any more.
                            clearClipboardIfHolds(context, verified.key)
                            entered?.let { clearClipboardIfHolds(context, it) }
                            viewModel.save()
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        BusyLabel(busy = state.busy, label = "Save this connection")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    pendingUrl?.let { url ->
        BrowserWarningDialog(
            onOpen = {
                pendingUrl = null
                context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            onCancel = { pendingUrl = null },
        )
    }
    AppLockOfferDialog(viewModel.lockOffer)
}

/**
 * What the key lacks and what it has beyond [requested], as (missing, extra),
 * each a list of policy names.
 *
 * Both sides go through [Permissions.covers], because a policy grants the
 * ones below it: BTCPay 2.3 drops the 2.4 wallet policies from a Full request,
 * yet its store-settings grant covers them, so a name-by-name compare would
 * report a loss that is not one. Store scopes are ignored: which stores the
 * key reaches is picked on the consent page, not here.
 */
internal fun permissionDiff(requested: List<String>, granted: List<String>): Pair<List<String>, List<String>> {
    val asked = requested.map { it.substringBefore(':') }.distinct()
    val got = granted.map { it.substringBefore(':') }.distinct()
    val missing = asked.filterNot { Permissions.covers(got, it) }
    val extra = got.filterNot { Permissions.covers(asked, it) }
    return missing to extra
}

/** `btcpay.store.canviewinvoices:store` as `store.canviewinvoices`. */
private fun String.permissionLabel(): String = substringBefore(':').removePrefix("btcpay.")

@Composable
private fun Step(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusyLabel(busy: Boolean, label: String) {
    // The button keeps its size and its place; only what is inside it changes.
    // Replacing the label with a spinner on the frame reads as the button
    // having been swapped for a different control at the moment it was tapped.
    AnimatedSwap(busy, label = "busy") { working ->
        if (working) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Working…")
            }
        } else {
            Text(label)
        }
    }
}

/** An aside; [warning] marks one that asks the user to think before going on. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    AppCard(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (warning) Icons.Rounded.Warning else Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun String.serverHost(): String =
    runCatching { URI(this).host }.getOrNull() ?: this
