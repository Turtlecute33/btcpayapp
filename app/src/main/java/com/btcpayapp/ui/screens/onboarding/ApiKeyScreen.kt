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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.pairing.Pairing
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.util.safeStartActivity
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.Endpoint
import com.btcpayapp.data.api.dto.ApplicationUserData
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.endpoints.currentApiKey
import com.btcpayapp.data.api.endpoints.currentUser
import com.btcpayapp.data.api.endpoints.requireSupportedServer
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.Credential
import com.btcpayapp.data.session.Permissions
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.clearClipboardIfHolds
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/**
 * What the key may do, in the order offered. The first is the default and the
 * least a till needs: the phone at the counter is the one most likely to be
 * lost or picked up unlocked, so by default its key can neither move funds nor
 * change where the store is paid.
 */
enum class PermissionSet(val label: String, val description: String) {
    TakePayments(
        label = "Take payments",
        description = "Create and view invoices. Cannot send funds or change the store.",
    ),
    Full(
        label = "Full access",
        description = "Also send funds, manage payouts and change store settings.",
    ),
    ReadOnly(
        label = "Watch only",
        description = "View invoices, payouts and balances. Cannot change anything.",
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

data class ApiKeyState(
    val apiKey: String = "",
    /** Builds the approval link, and is what the checked key is compared with. */
    val access: PermissionSet = PermissionSet.TakePayments,
    val serverAdmin: Boolean = false,
    val busy: Boolean = false,
    val error: ApiException? = null,
    /**
     * A checked key that does not do what was chosen. It waits here until the
     * user has read why and saves it anyway. A key that matches is saved at once.
     */
    val review: VerifiedKey? = null,
    val paired: Boolean = false,
    /** Saved but not made active, because a payment result is still open. */
    val addedInactive: Boolean = false,
)

class ApiKeyViewModel(
    private val graph: AppGraph,
    private val baseUrl: String,
    private val pins: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(ApiKeyState())
    val state = _state.asStateFlow()

    /** Asked just before the first account is saved; see [AppLockOffer]. */
    internal val lockOffer = AppLockOffer(graph)

    // Each change sends the key back through the check, so a note on screen
    // is always about the key and the access shown with it.
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value.trim(), error = null, review = null) }

    fun setAccess(value: PermissionSet) = _state.update { it.copy(access = value, review = null) }

    fun setServerAdmin(value: Boolean) = _state.update { it.copy(serverAdmin = value, review = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun requested(): List<String> = permissionsFor(_state.value.access, _state.value.serverAdmin)

    /** The consent page for the access chosen now, with nothing to redirect to. */
    fun approvalUrl(): String = Pairing.authorizeUrl(baseUrl, requested())

    /**
     * Checks the key and saves it when it does what was chosen. Otherwise it
     * stops at [ApiKeyState.review], and the next call saves that key as it is.
     */
    fun connect() {
        val current = _state.value
        if (current.busy) return
        val key = current.apiKey.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val reviewed = current.review
            if (reviewed != null) {
                save(reviewed)
                return@launch
            }
            val verified = verify(key) ?: return@launch
            if (keyMatches(requested(), verified.permissions)) {
                save(verified)
            } else {
                _state.update { it.copy(busy = false, review = verified) }
            }
        }
    }

    /** Null after a failure, which [fail] has put on screen. */
    private suspend fun verify(key: String): VerifiedKey? {
        val api = BtcPayApi(graph.client, endpointFor(Credential.ApiKey(key)))

        return try {
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
            VerifiedKey(key, user, stores, permissions, info.version.takeIf(String::isNotBlank))
        } catch (e: Exception) {
            fail(e, fallback = "Could not reach the server.")
            null
        }
    }

    private suspend fun save(verified: VerifiedKey) {
        // One try around the write too: a vault failure must become a
        // message, not an exception through `viewModelScope`.
        try {
            lockOffer.ask()
            // Switching away while a payment result is open would clear that
            // account's screens, and the result with them.
            val activate = !graph.session.busy.value
            graph.accounts.add(
                makeActive = activate,
                account = Account(
                    id = "",
                    // An account is a server, so the host is the honest label,
                    // unless there is exactly one store, whose name is easier
                    // to recognise.
                    label = verified.stores.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                        ?: baseUrl.serverHost(),
                    baseUrl = baseUrl,
                    credential = Credential.ApiKey(verified.key),
                    userId = verified.user?.id?.takeIf { it.isNotBlank() },
                    userEmail = verified.user?.email?.takeIf { it.isNotBlank() },
                    userName = verified.user?.name,
                    permissions = verified.permissions,
                    certificatePins = pins,
                    activeStoreId = verified.stores.firstOrNull()?.id,
                    serverVersion = verified.serverVersion,
                ),
            )
            _state.update { it.copy(busy = false, paired = true, addedInactive = !activate) }
        } catch (e: Exception) {
            fail(e, fallback = "Could not save the connection. Try again.")
        }
    }

    /** Rethrows a cancellation, which is control flow and no failure to show. */
    private fun fail(failure: Throwable, fallback: String) {
        if (failure is CancellationException) throw failure
        _state.update {
            it.copy(
                busy = false,
                review = null,
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
 * The key's user, or null when the key may not read it. "View your profile" is
 * a box the consent page lets the user untick, and the profile only labels the
 * account, so its absence must not fail the check.
 */
private suspend fun BtcPayApi.currentUserIfAllowed(): ApplicationUserData? =
    try {
        currentUser()
    } catch (_: ApiException.Forbidden) {
        null
    }

/**
 * The login: the user makes a key on their own server and pastes it here.
 *
 * The button opens the server's consent page with the chosen access already
 * ticked. The link has no redirect, so BTCPay shows the new key on its API
 * keys page, ready to copy. Nothing on the phone waits for a reply, and the
 * password and any two-factor code stay in the browser.
 *
 * The key can still differ from the choice: the user may untick or tick boxes,
 * or paste a key made by hand. So the check compares the key's own permissions
 * with the choice, the way the server reads them ([permissionDiff]). A key that
 * matches is saved at once; any difference is said before saving.
 *
 * There is no email-and-password sign-in. BTCPay disables Basic auth per user
 * by default and refuses it outright when two-factor is on, so it would fail
 * for most people who tried it.
 */
@Composable
fun ApiKeyScreen(
    baseUrl: String,
    pins: List<String>,
    onBack: () -> Unit,
    onPaired: () -> Unit,
) {
    val viewModel = appViewModel { ApiKeyViewModel(it, baseUrl, pins) }
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

    // For a pinned server the page opens only after the warning.
    var warnBrowser by rememberSaveable { mutableStateOf(false) }
    val openApproval = {
        context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.approvalUrl())))
    }

    LaunchedEffect(state.paired) {
        if (!state.paired) return@LaunchedEffect
        if (state.addedInactive) Toast.makeText(context, ADDED_INACTIVE, Toast.LENGTH_LONG).show()
        onPaired()
    }

    AppScreen(
        title = "Add an API key",
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

            // A recipe, so it assembles in the order it is followed.
            Step(1, "Choose the access", modifier = Modifier.arrive(0))
            AccessChoice(
                selected = state.access,
                serverAdmin = state.serverAdmin,
                onSelect = viewModel::setAccess,
                onServerAdminChange = viewModel::setServerAdmin,
                modifier = Modifier.arrive(0),
            )

            Step(
                2,
                "Create the key",
                detail = "Approve the request on your server. Then copy the key at the top of the " +
                    "page. It shows only once.",
                modifier = Modifier.arrive(1),
            )
            FilledTonalButton(
                onClick = { if (pins.isEmpty()) openApproval() else warnBrowser = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).arrive(1),
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open your server")
            }

            Step(3, "Paste the key", modifier = Modifier.arrive(2))
            SecretField(
                label = "API key",
                value = state.apiKey,
                onValueChange = enterKey,
                supportingText = "Stored encrypted on this phone.",
                imeAction = ImeAction.Done,
                modifier = Modifier.arrive(2),
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
                modifier = Modifier.padding(horizontal = 8.dp).arrive(2),
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste")
            }

            state.review?.let { review ->
                ReviewNotes(
                    granted = review.permissions,
                    requested = permissionsFor(state.access, state.serverAdmin),
                    modifier = Modifier.arrive(),
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // At the tap, not after the save: a saved account becomes
                    // the active one, which replaces this whole screen at once.
                    // The key is in the field for a retry, so nothing needs the
                    // clip any more.
                    clearClipboardIfHolds(context, state.apiKey)
                    entered?.let { clearClipboardIfHolds(context, it) }
                    viewModel.connect()
                },
                enabled = !state.busy && state.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(3),
            ) {
                BusyLabel(busy = state.busy, label = if (state.review != null) "Save anyway" else "Connect")
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (warnBrowser) {
        BrowserWarningDialog(
            onOpen = {
                warnBrowser = false
                openApproval()
            },
            onCancel = { warnBrowser = false },
        )
    }
    AppLockOfferDialog(viewModel.lockOffer)
}

/**
 * Whether [granted] does what [requested] asked for, so the key can be saved
 * without a question. A key that did not report its permissions never
 * matches: then nothing says what it can do.
 */
internal fun keyMatches(requested: List<String>, granted: List<String>): Boolean {
    if (granted.isEmpty()) return false
    val (missing, extra) = permissionDiff(requested, granted)
    return missing.isEmpty() && extra.isEmpty()
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

/** Why a checked key was not saved at once; see [keyMatches]. */
@Composable
private fun ReviewNotes(granted: List<String>, requested: List<String>, modifier: Modifier = Modifier) {
    val (missing, extra) = remember(granted, requested) { permissionDiff(requested, granted) }
    Column(modifier) {
        if (extra.isNotEmpty()) {
            Note(
                "This key has more permissions than you chose: " +
                    "${extra.joinToString(", ") { it.permissionLabel() }}.",
                warning = true,
            )
        }
        if (missing.isNotEmpty()) {
            Note(
                "This key does not have: ${missing.joinToString(", ") { it.permissionLabel() }}. " +
                    "Some screens will not work.",
            )
        }
        if (granted.isEmpty()) {
            Note("The server did not say what this key can do. Some screens can fail.")
        }
    }
}

/**
 * The access choice. It builds the approval link, and the checked key is
 * compared with it.
 *
 * On the screen, not under "Advanced": the default is the least a till needs,
 * and whoever wants more should read what "more" can do before asking for it.
 */
@Composable
private fun AccessChoice(
    selected: PermissionSet,
    serverAdmin: Boolean,
    onSelect: (PermissionSet) -> Unit,
    onServerAdminChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
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
                description = "Server settings, users and the server's Lightning node. This key " +
                    "can then create admins. For server admins only.",
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
 * authenticated. A key made on the server's own computer skips that leg, so
 * the dialog says so.
 */
@Composable
private fun BrowserWarningDialog(onOpen: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text("Check the browser warning") },
        text = {
            Text(
                "Your phone does not trust this server's certificate. The browser will warn you and " +
                    "cannot check the certificate this app accepted. Continue only on a network you " +
                    "trust, or create the key on the server's own computer.",
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Open browser") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** A numbered heading of the recipe, with an optional line under it. */
@Composable
private fun Step(number: Int, title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                Text("Connecting…")
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

/**
 * "Lock the app?", asked once, just before the first account is saved.
 * App lock is off by default, and the first key is the moment
 * the app starts to hold something worth locking.
 *
 * Before the save, not after it: saving an account makes it the active one,
 * which replaces the whole onboarding tree, so a question asked afterwards
 * would never be seen. [ask] holds the save until [answer].
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
private fun AppLockOfferDialog(offer: AppLockOffer) {
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

private fun String.serverHost(): String =
    runCatching { URI(this).host }.getOrNull() ?: this

/** Shown when a new account is saved while a payment result is still open (see [SessionManager.busy]). */
private const val ADDED_INACTIVE =
    "Account added. Close the open payment result, then switch to it in Accounts."
