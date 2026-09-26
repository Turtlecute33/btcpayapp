package com.btcpayapp.ui.screens.server

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.UpdateEmailSettingsRequest
import com.btcpayapp.data.api.endpoints.serverEmailSettings
import com.btcpayapp.data.api.endpoints.updateServerEmailSettings
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerEmailState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loadError: ApiException? = null,
    val error: ApiException? = null,
    val from: String = "",
    val server: String = "",
    val port: String = "",
    val login: String = "",
    val password: String = "",
    val passwordSet: Boolean = false,
    val disableCertificateCheck: Boolean = false,
    val shareWithStores: Boolean = false,
    val portError: String? = null,
    val message: String? = null,
)

class ServerEmailViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(ServerEmailState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun setFrom(value: String) = _state.update { it.copy(from = value) }

    fun setServer(value: String) = _state.update { it.copy(server = value) }

    fun setPort(value: String) = _state.update { it.copy(port = value.filter(Char::isDigit), portError = null) }

    fun setLogin(value: String) = _state.update { it.copy(login = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun setDisableCertificateCheck(value: Boolean) = _state.update { it.copy(disableCertificateCheck = value) }

    fun setShareWithStores(value: Boolean) = _state.update { it.copy(shareWithStores = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching { graph.session.requireApi().serverEmailSettings() }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            loading = false,
                            from = data.from.orEmpty(),
                            server = data.server.orEmpty(),
                            port = data.port?.toString().orEmpty(),
                            login = data.login.orEmpty(),
                            password = "",
                            passwordSet = data.passwordSet,
                            disableCertificateCheck = data.disableCertificateCheck,
                            shareWithStores = data.enableStoresToUseServerEmailSettings == true,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, loadError = failure.asApiException()) }
                }
        }
    }

    fun save() {
        val snapshot = _state.value
        val port = snapshot.port.takeIf { it.isNotBlank() }?.toIntOrNull()
        if (snapshot.port.isNotBlank() && (port == null || port !in 1..65535)) {
            _state.update { it.copy(portError = "A port is a number between 1 and 65535.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                graph.session.requireApi().updateServerEmailSettings(
                    UpdateEmailSettingsRequest(
                        from = snapshot.from.trim().takeIf { it.isNotBlank() },
                        server = snapshot.server.trim().takeIf { it.isNotBlank() },
                        port = port,
                        login = snapshot.login.trim().takeIf { it.isNotBlank() },
                        // A blank box means "keep what is stored". An empty string
                        // would be taken as a new, empty password; null is ignored.
                        password = snapshot.password.takeIf { it.isNotBlank() },
                        disableCertificateCheck = snapshot.disableCertificateCheck,
                        enableStoresToUseServerEmailSettings = snapshot.shareWithStores,
                    ),
                )
            }.onSuccess { data ->
                _state.update {
                    it.copy(
                        saving = false,
                        password = "",
                        passwordSet = data.passwordSet,
                        message = "Email settings saved.",
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
            }
        }
    }

}

/** What the body is showing; a cheap discriminator, not the form itself. */
private enum class ServerEmailPhase { Loading, Error, Content }

@Composable
fun ServerEmailScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { ServerEmailViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    AppScreen(
        title = "Server email",
        subtitle = "SMTP used for invitations and notices",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Sideways: the button keeps its place and the spinner
                    // opens a gap beside it, rather than the bar changing
                    // height mid-save.
                    AnimatedVisibility(
                        visible = state.saving,
                        enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(16.dp))
                        }
                    }
                    Button(onClick = viewModel::save, enabled = !state.saving) { Text("Save") }
                }
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> ServerEmailPhase.Loading
            state.loadError != null -> ServerEmailPhase.Error
            else -> ServerEmailPhase.Content
        }

        AnimatedSwap(phase, label = "serverEmail") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: one record behind a form.
                ServerEmailPhase.Loading -> LoadingState(Modifier.padding(padding))

                ServerEmailPhase.Error -> ErrorState(
                    error = state.loadError,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::load,
                )

                ServerEmailPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    FormSection(title = "Sender", modifier = Modifier.arrive(0)) {
                        FormField(
                            label = "From address",
                            value = state.from,
                            onValueChange = viewModel::setFrom,
                            placeholder = "BTCPay <noreply@example.com>",
                            supportingText = "Shown as the sender. A plain address works too.",
                            keyboardType = KeyboardType.Email,
                        )
                    }

                    FormSection(title = "SMTP server", modifier = Modifier.arrive(1)) {
                        FormField(
                            label = "Host",
                            value = state.server,
                            onValueChange = viewModel::setServer,
                            placeholder = "smtp.example.com",
                            keyboardType = KeyboardType.Uri,
                        )
                        FormField(
                            label = "Port",
                            value = state.port,
                            onValueChange = viewModel::setPort,
                            placeholder = "587",
                            error = state.portError,
                            supportingText = "587 for STARTTLS, 465 for implicit TLS.",
                            keyboardType = KeyboardType.Number,
                        )
                        FormField(
                            label = "Username",
                            value = state.login,
                            onValueChange = viewModel::setLogin,
                        )
                        SecretField(
                            label = "Password",
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            supportingText = if (state.passwordSet) {
                                "A password is stored. Leave this blank to keep it — nothing is sent " +
                                    "unless you type a replacement."
                            } else {
                                "No password is stored for this server yet."
                            },
                            imeAction = ImeAction.Done,
                        )
                    }

                    FormSection(title = "Options", modifier = Modifier.arrive(2)) {
                        FormSwitch(
                            title = "Let stores use these settings",
                            checked = state.shareWithStores,
                            onCheckedChange = viewModel::setShareWithStores,
                            description = "Each store may then send its email through this server " +
                                "instead of configuring its own.",
                        )
                        FormSwitch(
                            title = "Skip certificate checks",
                            checked = state.disableCertificateCheck,
                            onCheckedChange = viewModel::setDisableCertificateCheck,
                            description = "Accepts any TLS certificate the mail server offers.",
                        )
                        // The warning is the consequence of the switch above it, so
                        // it has to be seen arriving rather than simply be there.
                        AnimatedVisibility(
                            visible = state.disableCertificateCheck,
                            enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                            exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                        ) {
                            CertificateWarning()
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun CertificateWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "With this on, the connection to the mail server is encrypted but not " +
                    "authenticated: anyone able to intercept it can present their own certificate " +
                    "and read the SMTP password and every message. Use it only against a host on " +
                    "your own network with a self-signed certificate.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
