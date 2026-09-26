package com.btcpayapp.ui.screens.onboarding

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.btcpayapp.data.api.endpoints.currentUser
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.Credential
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/** What a successful check found, so saving needs no second round trip. */
data class VerifiedKey(
    val key: String,
    val user: ApplicationUserData,
    val stores: List<StoreData>,
    /** What the key reports for itself. Empty when the server would not say. */
    val permissions: List<String>,
)

data class ManualKeyState(
    val apiKey: String = "",
    val busy: Boolean = false,
    val error: ApiException? = null,
    val verified: VerifiedKey? = null,
    val paired: Boolean = false,
)

class ManualKeyViewModel(
    private val graph: AppGraph,
    private val baseUrl: String,
    private val pins: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualKeyState())
    val state = _state.asStateFlow()

    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value.trim(), error = null, verified = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun verify() {
        val key = _state.value.apiKey.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            verifyKey(key)
        }
    }

    fun save() {
        val verified = _state.value.verified ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val preferred = verified.stores.firstOrNull()
            runCatching {
                graph.accounts.add(
                    Account(
                        id = "",
                        label = verified.stores.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                            ?: baseUrl.serverHost(),
                        baseUrl = baseUrl,
                        credential = Credential.ApiKey(verified.key),
                        userId = verified.user.id.takeIf { it.isNotBlank() },
                        userEmail = verified.user.email.takeIf { it.isNotBlank() },
                        userName = verified.user.name,
                        permissions = verified.permissions,
                        certificatePins = pins,
                        activeStoreId = preferred?.id,
                    ),
                )
            }
                .onSuccess { _state.update { it.copy(busy = false, paired = true) } }
                .onFailure { failure -> fail(failure) }
        }
    }

    private suspend fun verifyKey(key: String) {
        val api = BtcPayApi(graph.client, endpointFor(Credential.ApiKey(key)))

        runCatching { api.currentUser() }
            .onSuccess { user ->
                val stores = runCatching { api.stores() }.getOrDefault(emptyList())
                // `/api-keys/current` is API-key-only and absent on older
                // instances. An empty list means "unknown", which the session
                // treats as "let the server decide" rather than "denied".
                val permissions = runCatching { api.currentApiKey().permissions }
                    .getOrDefault(emptyList())

                _state.update {
                    it.copy(
                        busy = false,
                        verified = VerifiedKey(key, user, stores, permissions),
                    )
                }
            }
            .onFailure { failure -> fail(failure) }
    }

    private fun fail(failure: Throwable) = _state.update {
        it.copy(
            busy = false,
            verified = null,
            error = failure as? ApiException
                ?: ApiException.Transport(failure.message ?: "Could not reach the server."),
        )
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
 * consent page with every permission already selected, and BTCPay shows the
 * finished key to copy. Telling the user to "create one under Account → API
 * keys" instead would leave them on a page with an empty form and twenty-odd
 * permission checkboxes to reproduce from memory on a phone.
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
) {
    val viewModel = appViewModel { ManualKeyViewModel(it, baseUrl, pins) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.paired) {
        if (state.paired) onPaired()
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

            Step(1, "Open the approval page below. Every permission this app needs is already ticked — untick anything you would rather not grant.", Modifier.arrive(1))
            Step(2, "Sign in if your server asks, then approve the request.", Modifier.arrive(2))
            Step(3, "Your key appears in a banner at the top of the API Keys page. It is shown once, so copy it now.", Modifier.arrive(3))
            Step(4, "Come back here and paste it in.", Modifier.arrive(4))

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    context.safeStartActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Pairing.manualAuthorizeUrl(baseUrl, Pairing.DEFAULT_PERMISSIONS)),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(5),
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open the approval page")
            }

            TextButton(
                onClick = {
                    context.safeStartActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(Pairing.manualApiKeyUrl(baseUrl))),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(6),
            ) {
                Text("Or create one by hand")
            }

            SectionHeader("The key", modifier = Modifier.arrive(7))

            SecretField(
                label = "API key",
                value = state.apiKey,
                onValueChange = viewModel::setApiKey,
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
                            ?.let(viewModel::setApiKey)
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
                            DetailRow(label = "User", value = verified.user.displayName)
                            if (verified.user.email.isNotBlank() && verified.user.name != null) {
                                DetailRow(label = "Email", value = verified.user.email)
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
                                        text = permission.substringBefore(':').removePrefix("btcpay."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = viewModel::save,
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
}

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

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
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
