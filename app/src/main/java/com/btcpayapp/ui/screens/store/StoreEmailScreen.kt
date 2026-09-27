package com.btcpayapp.ui.screens.store

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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.EmailSettingsData
import com.btcpayapp.data.api.dto.SendEmailRequest
import com.btcpayapp.data.api.dto.UpdateEmailSettingsRequest
import com.btcpayapp.data.api.endpoints.sendStoreEmail
import com.btcpayapp.data.api.endpoints.storeEmailSettings
import com.btcpayapp.data.api.endpoints.updateStoreEmailSettings
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.confirmDiscardChanges
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.theme.Motion
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SMTP settings for this store's own email.
 *
 * The server never returns the stored password — only [EmailSettingsData.passwordSet]
 * says whether there is one — so a blank password field means "keep what you
 * have" and is sent as a null rather than an empty string, which would clear it.
 */
data class StoreEmailForm(
    val from: String = "",
    val server: String = "",
    val port: String = "587",
    val login: String = "",
    val password: String = "",
    val disableCertificateCheck: Boolean = false,
    val passwordSet: Boolean = false,
)

private fun StoreEmailForm.route() = SmtpRoute.of(server, port, login, disableCertificateCheck)

data class StoreEmailState(
    val form: StoreEmailForm? = null,
    /** The route the server holds, to tell a new one from it. */
    val saved: SmtpRoute = SmtpRoute(),
    /** A new route waiting for the user's yes; see [SmtpReviewDialog]. */
    val review: SmtpRoute? = null,
    val passwordError: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val sending: Boolean = false,
    val showTest: Boolean = false,
    val dirty: Boolean = false,
    val error: ApiException? = null,
    val message: String? = null,
)

class StoreEmailViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(StoreEmailState())
    val state = _state.asStateFlow()

    /**
     * The store this screen was opened in (see [StoreBinding]). The shell
     * closes this screen when the store changes, so a save always goes to the
     * store whose settings were loaded, never to whichever store is active by
     * the time the user taps Save.
     */
    private val bound = StoreBinding(graph.session)
    private val storeId: String? get() = bound.id

    /** Pre-fills the test recipient; the signed-in user is the only address we know. */
    val ownEmail: String get() = graph.session.user.value?.email.orEmpty()

    init {
        load()
        bound.retryWhenKnown(viewModelScope) { load() }
    }

    fun load() {
        val store = storeId
        if (store == null) {
            _state.update { it.copy(loading = false, error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = it.form == null, error = null) }
            runCatching { graph.session.requireApi().storeEmailSettings(store) }
                .onSuccess { settings ->
                    val form = settings.toForm()
                    _state.update {
                        it.copy(form = form, saved = form.route(), dirty = false, loading = false, error = null)
                    }
                }
                .onFailure { failure -> _state.update { it.copy(loading = false, error = failure.asApiException()) } }
        }
    }

    fun edit(transform: (StoreEmailForm) -> StoreEmailForm) = _state.update { current ->
        current.copy(form = current.form?.let(transform), dirty = current.form != null, passwordError = null)
    }

    fun dismissReview() = _state.update { it.copy(review = null) }

    fun showTest(show: Boolean) = _state.update { it.copy(showTest = show) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * [reviewed] is the route the user confirmed in [SmtpReviewDialog] and the
     * spend gate. The save goes ahead only for that exact route.
     */
    fun save(reviewed: SmtpRoute? = null) {
        val store = storeId ?: return
        val snapshot = _state.value
        val form = snapshot.form ?: return
        val route = form.route()
        when (smtpSave(snapshot.saved, route, passwordTyped = form.password.isNotBlank(), passwordSet = form.passwordSet)) {
            SmtpSave.RetypePassword -> {
                // Also as a snackbar: Save sits far from the password field,
                // which the keyboard often hides.
                _state.update { it.copy(passwordError = SMTP_RETYPE_PASSWORD, message = SMTP_RETYPE_PASSWORD) }
                return
            }
            SmtpSave.Review -> if (route != reviewed) {
                _state.update { it.copy(review = route) }
                return
            }
            SmtpSave.Go -> Unit
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                graph.session.requireApi().updateStoreEmailSettings(
                    storeId = store,
                    request = UpdateEmailSettingsRequest(
                        from = form.from.trim().ifBlank { null },
                        server = form.server.trim().ifBlank { null },
                        port = form.port.toIntOrNull(),
                        login = form.login.trim().ifBlank { null },
                        // Null, not "": an empty string would erase the stored password.
                        password = form.password.ifBlank { null },
                        disableCertificateCheck = form.disableCertificateCheck,
                    ),
                )
            }.onSuccess { settings ->
                _state.update {
                    // Edits typed while the request ran stay in the form, unsaved.
                    val unchanged = it.form == form
                    val stored = settings.toForm()
                    it.copy(
                        form = if (unchanged) stored else it.form,
                        saved = stored.route(),
                        dirty = !unchanged,
                        saving = false,
                        message = "Email settings saved.",
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
            }
        }
    }

    fun sendTest(to: String, subject: String, body: String) {
        val store = storeId ?: return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            runCatching {
                graph.session.requireApi().sendStoreEmail(
                    storeId = store,
                    request = SendEmailRequest(email = to.trim(), subject = subject, body = body),
                )
            }.onSuccess {
                _state.update { it.copy(sending = false, showTest = false, message = "Test email sent to $to.") }
            }.onFailure { failure ->
                _state.update { it.copy(sending = false, error = failure.asApiException()) }
            }
        }
    }

    private fun EmailSettingsData.toForm() = StoreEmailForm(
        from = from.orEmpty(),
        server = server.orEmpty(),
        port = port?.toString() ?: "587",
        login = login.orEmpty(),
        password = "",
        disableCertificateCheck = disableCertificateCheck,
        passwordSet = passwordSet,
    )

}

/** What the body is showing; a cheap discriminator, not the form itself. */
private enum class StoreEmailPhase { Loading, Error, Content }

/** The three things the app bar's trailing slot can be. */
private enum class EmailSaveAction { Busy, Ready, None }

@Composable
fun StoreEmailScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { StoreEmailViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val guardedBack = confirmDiscardChanges(state.dirty, onBack)
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()

    state.review?.let { route ->
        SmtpReviewDialog(
            route = route,
            mail = "this store's emails",
            onConfirm = {
                viewModel.dismissReview()
                scope.afterSpendGate(gate, "Confirm email server", route.host, { snackbarHostState.showSnackbar(it) }) {
                    viewModel.save(reviewed = route)
                }
            },
            onDismiss = viewModel::dismissReview,
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = "Email",
        onBack = guardedBack,
        snackbarHostState = snackbarHostState,
        actions = {
            val action = when {
                state.saving -> EmailSaveAction.Busy
                state.form != null -> EmailSaveAction.Ready
                else -> EmailSaveAction.None
            }
            AnimatedSwap(action, label = "save") { shown ->
                when (shown) {
                    EmailSaveAction.Busy ->
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    EmailSaveAction.Ready -> TextButton(onClick = { viewModel.save() }) { Text("Save") }
                    EmailSaveAction.None -> Unit
                }
            }
        },
    ) { padding ->
        val form = state.form
        val phase = when {
            state.loading -> StoreEmailPhase.Loading
            form == null -> StoreEmailPhase.Error
            else -> StoreEmailPhase.Content
        }

        AnimatedSwap(phase, label = "storeEmail") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: one record behind a form.
                StoreEmailPhase.Loading -> LoadingState(Modifier.padding(padding))

                StoreEmailPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::load,
                )

                StoreEmailPhase.Content -> {
                    // The outgoing half of a swap outlives the state that chose
                    // it, so this can still be composed after the form has gone.
                    val current = form ?: return@AnimatedSwap

                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                        FormSection("Sender", Modifier.arrive(0)) {
                            FormField(
                                label = "From",
                                value = current.from,
                                onValueChange = { value -> viewModel.edit { it.copy(from = value) } },
                                placeholder = "Your shop <shop@example.com>",
                                keyboardType = KeyboardType.Email,
                                supportingText = "The address buyers see. Most providers reject a from " +
                                    "address that is not the account's own.",
                            )
                        }

                        ThinDivider()

                        FormSection("SMTP server", Modifier.arrive(1)) {
                            FormField(
                                label = "Server",
                                value = current.server,
                                onValueChange = { value -> viewModel.edit { it.copy(server = value) } },
                                placeholder = "smtp.example.com",
                                keyboardType = KeyboardType.Uri,
                            )
                            FormField(
                                label = "Port",
                                value = current.port,
                                onValueChange = { value ->
                                    viewModel.edit { it.copy(port = value.filter { c -> c.isDigit() }) }
                                },
                                keyboardType = KeyboardType.Number,
                                supportingText = "587 for STARTTLS, 465 for implicit TLS.",
                            )
                            FormField(
                                label = "Login",
                                value = current.login,
                                onValueChange = { value -> viewModel.edit { it.copy(login = value) } },
                                keyboardType = KeyboardType.Email,
                            )
                            SecretField(
                                label = "Password",
                                value = current.password,
                                onValueChange = { value -> viewModel.edit { it.copy(password = value) } },
                                supportingText = if (current.passwordSet) {
                                    "A password is already stored. Leave this blank to keep it."
                                } else {
                                    "No password is stored for this store yet."
                                },
                                error = state.passwordError,
                            )
                        }

                        ThinDivider()

                        FormSection("Security", Modifier.arrive(2)) {
                            FormSwitch(
                                title = "Skip the certificate check",
                                description = "Accepts any certificate the SMTP server presents.",
                                checked = current.disableCertificateCheck,
                                onCheckedChange = { value ->
                                    viewModel.edit { it.copy(disableCertificateCheck = value) }
                                },
                            )
                            // The warning is the consequence of the switch above, so it
                            // has to be seen arriving. Appearing fully formed on the
                            // next frame reads as part of the page the reader already
                            // scrolled past.
                            AnimatedVisibility(
                                visible = current.disableCertificateCheck,
                                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                            ) {
                                CertificateWarning()
                            }
                        }

                        ThinDivider()

                        FormSection("Test", Modifier.arrive(3)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.showTest(true) },
                                    enabled = !state.sending,
                                ) {
                                    Text("Send a test email")
                                }
                                // Sideways: the spinner sits beside the button, and
                                // opening downward would shift the text under it.
                                AnimatedVisibility(
                                    visible = state.sending,
                                    enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                                    exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.width(12.dp))
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                            Text(
                                text = "Uses the settings already saved on the server, so save first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    if (state.showTest) {
        TestEmailDialog(
            defaultRecipient = viewModel.ownEmail,
            busy = state.sending,
            onSend = viewModel::sendTest,
            onDismiss = { viewModel.showTest(false) },
        )
    }
}

@Composable
private fun CertificateWarning() {
    AppCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "With the check off, nothing proves the server you connect to is the one " +
                    "you named. Anyone able to redirect the connection can read every message — " +
                    "including the password used to log in — and no warning is shown. Use it only " +
                    "against a mail server on your own machine with a self-signed certificate.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TestEmailDialog(
    defaultRecipient: String,
    busy: Boolean,
    onSend: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var recipient by rememberSaveable { mutableStateOf(defaultRecipient) }
    var subject by rememberSaveable { mutableStateOf("BTCPay test email") }
    var body by rememberSaveable {
        mutableStateOf("This is a test message sent from the store's email settings.")
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Send a test email") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FormField(
                    label = "To",
                    value = recipient,
                    onValueChange = { recipient = it },
                    enabled = !busy,
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    label = "Subject",
                    value = subject,
                    onValueChange = { subject = it },
                    enabled = !busy,
                )
                FormField(
                    label = "Message",
                    value = body,
                    onValueChange = { body = it },
                    enabled = !busy,
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(recipient, subject, body) },
                enabled = !busy && recipient.isNotBlank(),
            ) {
                AnimatedSwap(busy, label = "send") { working ->
                    if (working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Send")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * Where SMTP settings send mail: the host, port and login, and whether the
 * host must prove who it is. Whoever runs the host or holds the login can read
 * every message sent that way, and the server's own mail carries
 * password-reset links. So a new route is confirmed like a new webhook
 * target. Build it with [of].
 */
data class SmtpRoute(
    val host: String = "",
    val port: String = "",
    val login: String = "",
    val skipCertificateCheck: Boolean = false,
) {
    companion object {
        /** Trimmed, with the host in lower case, so typing the same values again is no change. */
        fun of(host: String, port: String, login: String, skipCertificateCheck: Boolean) =
            SmtpRoute(host.trim().lowercase(Locale.ROOT), port.trim(), login.trim(), skipCertificateCheck)
    }
}

/** What a save of SMTP settings needs first. */
internal enum class SmtpSave { Go, RetypePassword, Review }

/**
 * Compares the [route] in the form with the [saved] one.
 *
 * BTCPay keeps the stored password when a login comes without one, whatever
 * the host. So a new host would sign in with the old host's password, unless
 * the user types a password for it.
 */
internal fun smtpSave(saved: SmtpRoute, route: SmtpRoute, passwordTyped: Boolean, passwordSet: Boolean): SmtpSave =
    when {
        // No host sends no mail, so clearing the settings gives nobody anything.
        route.host.isEmpty() -> SmtpSave.Go
        route.host != saved.host && route.login.isNotEmpty() && passwordSet && !passwordTyped -> SmtpSave.RetypePassword
        route.host != saved.host || route.port != saved.port || route.login != saved.login -> SmtpSave.Review
        // With the check off, anyone on the network path can read the mail and the password.
        route.skipCertificateCheck && !saved.skipCertificateCheck -> SmtpSave.Review
        else -> SmtpSave.Go
    }

internal const val SMTP_RETYPE_PASSWORD = "Type the password for this host. The stored one is not sent to a new host."

/**
 * Asks before mail goes a new way. The caller runs the spend gate on a yes,
 * so someone with the unlocked phone cannot move the mail to a host or an
 * account of their own.
 */
@Composable
internal fun SmtpReviewDialog(route: SmtpRoute, mail: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val account = if (route.login.isEmpty()) "" else ", signed in as ${route.login}"
    val readers = if (route.login.isEmpty()) "Whoever runs it" else "Whoever runs it or holds that login"
    val unchecked = if (route.skipCertificateCheck) {
        " Certificate checks are off, so anyone on the network path can read them too, and the SMTP password."
    } else {
        ""
    }
    ConfirmDialog(
        title = "Send mail through ${route.host}?",
        message = "From now on $mail go through this host$account. $readers can read them.$unchecked",
        confirmLabel = "Save",
        destructive = route.skipCertificateCheck,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
