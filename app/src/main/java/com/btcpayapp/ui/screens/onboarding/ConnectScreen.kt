package com.btcpayapp.ui.screens.onboarding

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.net.CertificateProbe
import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.Tls
import com.btcpayapp.core.net.TlsProblem
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.scan.ScanParser
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.Endpoint
import com.btcpayapp.data.api.endpoints.health
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CertificateCheckDialog
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

data class ConnectState(
    val address: String = "",
    val probing: Boolean = false,
    val error: ApiException? = null,
    /**
     * Set only when the system refused the certificate for its issuer alone
     * and a probe read it back, so the user can check it against the server.
     */
    val certificate: CertificateProbe? = null,
    val pins: List<String> = emptyList(),
    /** Non-null once the health probe succeeded; consumed by the composable. */
    val connected: String? = null,
)

class ConnectViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(ConnectState())
    val state = _state.asStateFlow()

    private var probeJob: kotlinx.coroutines.Job? = null

    fun setAddress(value: String) {
        probeJob?.cancel()
        _state.update { it.copy(address = value, error = null, pins = emptyList(), certificate = null, connected = null, probing = false) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun rejectCertificate() = _state.update { it.copy(certificate = null) }

    /**
     * Pins [pin] for this account, then retries the health call.
     * [CertificateCheckDialog] passes it only once the user typed a matching
     * fingerprint; a call after the dialog closed does nothing.
     */
    fun acceptCertificate(pin: String) {
        if (_state.value.certificate == null) return
        _state.update { it.copy(certificate = null, pins = (it.pins + pin).distinct()) }
        connect()
    }

    fun connect() {
        val normalised = ScanParser.normaliseServerUrl(_state.value.address)
        if (normalised == null) {
            _state.update {
                it.copy(error = ApiException.Transport("That does not look like a server address."))
            }
            return
        }

        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            _state.update { it.copy(probing = true, error = null) }
            val pins = _state.value.pins

            attempt { apiFor(normalised, pins).health() }
                .onSuccess {
                    _state.update { current -> current.copy(probing = false, connected = normalised) }
                }
                .onFailure { failure ->
                    val error = failure as? ApiException
                        ?: ApiException.Transport(failure.message ?: "Could not reach the server.")

                    // Trust-on-first-use is offered only for the very first
                    // handshake, and only when the issuer is the one fault. A
                    // wrong name or an expired certificate is what an
                    // interception looks like, and pinning repairs neither; once
                    // a pin is held, a rejection means the key changed.
                    val offerTrust = error is ApiException.Tls &&
                        error.problem == TlsProblem.UntrustedIssuer &&
                        pins.isEmpty() &&
                        !normalised.isOnion()
                    val probe = if (offerTrust) {
                        withContext(Dispatchers.IO) {
                            Tls.probeCertificate(normalised.hostOrBlank(), normalised.effectivePort())
                        }
                    } else {
                        null
                    }

                    _state.update { current ->
                        current.copy(probing = false, error = error, certificate = probe)
                    }
                }
        }
    }

    fun consumeConnected() = _state.update { it.copy(connected = null) }

    /**
     * The account does not exist yet, so the endpoint is assembled by hand
     * rather than derived from a stored [com.btcpayapp.data.model.Account].
     */
    private fun apiFor(baseUrl: String, pins: List<String>): BtcPayApi {
        val onion = baseUrl.isOnion()
        return BtcPayApi(
            graph.client,
            Endpoint(
                baseUrl = baseUrl,
                credential = null,
                transport = TransportOptions(
                    pinnedSpki = pins.toSet(),
                    connectTimeoutMs = if (onion) 45_000 else 15_000,
                    readTimeoutMs = if (onion) 60_000 else 30_000,
                    proxy = if (onion) ProxySpec.ORBOT else null,
                ),
            ),
        )
    }
}

@Composable
fun ConnectScreen(
    onBack: () -> Unit,
    onConnected: (baseUrl: String, pins: List<String>) -> Unit,
) {
    val viewModel = appViewModel { ConnectViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.connected) {
        val baseUrl = state.connected ?: return@LaunchedEffect
        viewModel.consumeConnected()
        onConnected(baseUrl, state.pins)
    }

    val normalised = ScanParser.normaliseServerUrl(state.address)
    val onion = normalised?.isOnion() == true
    // For an http:// onion address, the hop to Orbot's port carries the API
    // key in clear text.
    val cleartext = normalised?.startsWith("http://") == true

    AppScreen(
        title = "Connect a server",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            FormField(
                label = "Server address",
                value = state.address,
                onValueChange = viewModel::setAddress,
                placeholder = "btcpay.example.com",
                supportingText = normalised
                    ?.takeIf { it != state.address }
                    ?.let { "Will connect to $it" }
                    ?: "https:// is assumed unless the address ends in .onion.",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onImeAction = viewModel::connect,
                enabled = !state.probing,
                leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                modifier = Modifier.arrive(0),
            )

            // Both hints appear in response to something the user just did —
            // typing an .onion address, accepting a certificate — so they push
            // the button below them down rather than materialising above it.
            AnimatedVisibility(
                visible = onion,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Hint(
                    icon = Icons.Rounded.Shield,
                    text = "Onion addresses are routed through Orbot on 127.0.0.1:9050. " +
                        "Start Orbot and let it finish bootstrapping before you connect. " +
                        "The first request over a fresh circuit can take half a minute. " +
                        (if (cleartext) "While Orbot is off, another app can take that port and read the API key. " else "") +
                        "Background checks use Orbot too. Keep it running, or turn them off in Settings.",
                )
            }

            AnimatedVisibility(
                visible = state.pins.isNotEmpty(),
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Hint(
                    icon = Icons.Rounded.Shield,
                    text = "A certificate has been accepted for this connection. It applies to " +
                        "this account alone.",
                )
            }

            // Also under the certificate dialog: it names why the system
            // refused the certificate, which the dialog does not repeat.
            ErrorBanner(
                error = state.error,
                onDismiss = viewModel::dismissError,
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::connect,
                enabled = !state.probing && state.address.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(1),
            ) {
                AnimatedSwap(state.probing, label = "connect") { probing ->
                    if (probing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Checking…")
                        }
                    } else {
                        Text("Connect")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Only an unauthenticated health check is sent at this point. Nothing is " +
                    "saved until you authorise the app on the next screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).arrive(2),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    state.certificate?.let { probe ->
        CertificateCheckDialog(
            host = normalised?.hostOrBlank().orEmpty(),
            port = normalised?.effectivePort() ?: 443,
            probe = probe,
            onTrust = viewModel::acceptCertificate,
            onCancel = viewModel::rejectCertificate,
        )
    }
}

@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
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

private fun String.hostOrBlank(): String =
    runCatching { URI(this).host }.getOrNull().orEmpty()

private fun String.isOnion(): Boolean = hostOrBlank().endsWith(".onion", ignoreCase = true)

private fun String.effectivePort(): Int {
    val uri = runCatching { URI(this) }.getOrNull() ?: return 443
    if (uri.port > 0) return uri.port
    return if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443
}
