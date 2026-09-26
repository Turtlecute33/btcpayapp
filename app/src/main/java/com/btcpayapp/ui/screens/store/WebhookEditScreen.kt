package com.btcpayapp.ui.screens.store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.btcpayapp.data.api.dto.WebhookAuthorizedEvents
import com.btcpayapp.data.api.dto.WebhookEventTypes
import com.btcpayapp.data.api.dto.WebhookRequest
import com.btcpayapp.data.api.endpoints.createWebhook
import com.btcpayapp.data.api.endpoints.updateWebhook
import com.btcpayapp.data.api.endpoints.webhook
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Headings come from `groupOf` so a new event type lands in the right place. */
private val EVENT_GROUPS: List<Pair<String, List<String>>> = listOf(
    WebhookEventTypes.invoice,
    WebhookEventTypes.payout,
    WebhookEventTypes.paymentRequest,
).map { group -> WebhookEventTypes.groupOf(group.first()) to group }

data class WebhookEditState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loadError: ApiException? = null,
    val error: ApiException? = null,
    val url: String = "",
    val enabled: Boolean = true,
    val automaticRedelivery: Boolean = true,
    val everything: Boolean = true,
    val selectedEvents: Set<String> = emptySet(),
    val secretInput: String = "",
    val urlError: String? = null,
    /** Returned by the create call only, and never again. */
    val revealedSecret: String? = null,
    val finished: Boolean = false,
)

class WebhookEditViewModel(
    private val graph: AppGraph,
    private val webhookId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(WebhookEditState())
    val state = _state.asStateFlow()

    private val storeId get() = graph.session.activeStore.value?.id

    init {
        if (webhookId != null) load()
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, urlError = null) }

    fun setEnabled(value: Boolean) = _state.update { it.copy(enabled = value) }

    fun setAutomaticRedelivery(value: Boolean) = _state.update { it.copy(automaticRedelivery = value) }

    fun setEverything(value: Boolean) = _state.update { it.copy(everything = value) }

    fun setSecret(value: String) = _state.update { it.copy(secretInput = value) }

    fun toggleEvent(event: String) = _state.update { current ->
        val next = if (event in current.selectedEvents) {
            current.selectedEvents - event
        } else {
            current.selectedEvents + event
        }
        current.copy(selectedEvents = next)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun load() {
        val store = storeId ?: return
        val id = webhookId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching { graph.session.requireApi().webhook(store, id) }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            loading = false,
                            url = data.url,
                            enabled = data.enabled,
                            automaticRedelivery = data.automaticRedelivery,
                            everything = data.authorizedEvents.everything,
                            selectedEvents = data.authorizedEvents.specificEvents.toSet(),
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, loadError = failure.asApiException()) }
                }
        }
    }

    fun save() {
        val store = storeId ?: return
        val snapshot = _state.value

        val url = snapshot.url.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            _state.update { it.copy(urlError = "Enter a full URL starting with https://") }
            return
        }
        if (!snapshot.everything && snapshot.selectedEvents.isEmpty()) {
            _state.update {
                it.copy(
                    error = ApiException.Transport("Choose at least one event, or switch “Every event” back on."),
                )
            }
            return
        }

        val request = WebhookRequest(
            url = url,
            enabled = snapshot.enabled,
            automaticRedelivery = snapshot.automaticRedelivery,
            authorizedEvents = WebhookAuthorizedEvents(
                everything = snapshot.everything,
                specificEvents = if (snapshot.everything) emptyList() else snapshot.selectedEvents.toList(),
            ),
            // Blank means "server, choose one" on create and "leave it alone" on update.
            secret = snapshot.secretInput.takeIf { it.isNotBlank() },
        )

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
                return@launch
            }
            runCatching {
                if (webhookId == null) {
                    api.createWebhook(store, request)
                } else {
                    api.updateWebhook(store, webhookId, request)
                }
            }.onSuccess { data ->
                val secret = data.secret?.takeIf { it.isNotBlank() }
                _state.update {
                    it.copy(saving = false, revealedSecret = secret, finished = secret == null)
                }
            }.onFailure { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
            }
        }
    }

}

/** What the body is showing; a cheap discriminator, not the form itself. */
private enum class WebhookEditPhase { Loading, Error, Secret, Form }

@Composable
fun WebhookEditScreen(
    webhookId: String?,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "webhook-$webhookId") { WebhookEditViewModel(it, webhookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    AppScreen(
        title = if (webhookId == null) "New webhook" else "Edit webhook",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        bottomBar = {
            // Saving is over once the secret is on screen, and the bar leaves
            // downward rather than vanishing so it is clear the screen has
            // moved on rather than lost a control.
            AnimatedVisibility(
                visible = state.revealedSecret == null,
                enter = slideInVertically(Motion.spatialOffset) { it } + fadeIn(Motion.effects),
                exit = slideOutVertically(Motion.spatialOffset) { it } + fadeOut(Motion.effectsFast),
            ) {
                SaveBar(saving = state.saving, onSave = viewModel::save)
            }
        },
    ) { padding ->
        val phase = when {
            state.loading -> WebhookEditPhase.Loading
            state.loadError != null -> WebhookEditPhase.Error
            state.revealedSecret != null -> WebhookEditPhase.Secret
            else -> WebhookEditPhase.Form
        }

        AnimatedSwap(phase, label = "webhookEdit") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: one webhook behind a form.
                WebhookEditPhase.Loading -> LoadingState(Modifier.padding(padding))

                WebhookEditPhase.Error -> ErrorState(
                    error = state.loadError,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::load,
                )

                WebhookEditPhase.Secret -> RevealedSecret(
                    secret = state.revealedSecret.orEmpty(),
                    modifier = Modifier.padding(padding),
                    onDone = onBack,
                )

                WebhookEditPhase.Form -> WebhookForm(
                    state = state,
                    isNew = webhookId == null,
                    modifier = Modifier.padding(padding),
                    viewModel = viewModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebhookForm(
    state: WebhookEditState,
    isNew: Boolean,
    modifier: Modifier,
    viewModel: WebhookEditViewModel,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        ErrorBanner(state.error, onDismiss = viewModel::dismissError)

        FormSection(title = "Endpoint", modifier = Modifier.arrive(0)) {
            FormField(
                label = "Payload URL",
                value = state.url,
                onValueChange = viewModel::setUrl,
                placeholder = "https://example.com/btcpay-events",
                error = state.urlError,
                supportingText = "The server posts a JSON body to this URL for every authorised event.",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            )
            FormSwitch(
                title = "Enabled",
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                description = "Turn off to stop deliveries without losing the configuration.",
            )
            FormSwitch(
                title = "Automatic redelivery",
                checked = state.automaticRedelivery,
                onCheckedChange = viewModel::setAutomaticRedelivery,
                description = "Retry a failed delivery on a backoff instead of dropping it.",
            )
        }

        FormSection(title = "Events", modifier = Modifier.arrive(1)) {
            FormSwitch(
                title = "Every event",
                checked = state.everything,
                onCheckedChange = viewModel::setEverything,
                description = "Includes event types added by future server versions.",
            )

            // Three headings and thirty chips appear the instant that switch is
            // turned off. Dropping them in fully formed pushes the rest of the
            // form off the bottom of the screen with nothing to say it moved.
            AnimatedVisibility(
                visible = !state.everything,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column {
                    EVENT_GROUPS.forEachIndexed { index, (heading, events) ->
                        Column(Modifier.arrive(index)) {
                            SectionHeader(heading)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                events.forEach { event ->
                                    FilterChip(
                                        selected = event in state.selectedEvents,
                                        onClick = { viewModel.toggleEvent(event) },
                                        label = { Text(event) },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        FormSection(title = "Signing secret", modifier = Modifier.arrive(2)) {
            SecretField(
                label = "Secret",
                value = state.secretInput,
                onValueChange = viewModel::setSecret,
                supportingText = if (isNew) {
                    "Leave blank and the server will generate one. It is shown once, after saving."
                } else {
                    "Leave blank to keep the existing secret. Anything you type replaces it."
                },
            )
            Text(
                text = "The secret is the HMAC key behind the BTCPay-Sig: sha256=… header. " +
                    "Verify that signature at your endpoint before trusting a payload.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RevealedSecret(secret: String, modifier: Modifier, onDone: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Surface(
            modifier = Modifier.arrive(0),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Copy this now. The server returns the signing secret exactly once — " +
                        "reopening the webhook will not show it again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        CopyableField(
            label = "Signing secret",
            value = secret,
            modifier = Modifier.arrive(1),
            sensitive = true,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your endpoint receives it as BTCPay-Sig: sha256=<hmac>, computed over the raw " +
                "request body. Compare the two in constant time and reject anything that does not match.",
            modifier = Modifier.arrive(2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().arrive(3),
        ) { Text("I have copied it") }
    }
}

@Composable
private fun SaveBar(saving: Boolean, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sideways: the button keeps its place and the spinner opens a gap
            // beside it, rather than the bar changing height mid-save.
            AnimatedVisibility(
                visible = saving,
                enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                }
            }
            Button(onClick = onSave, enabled = !saving) { Text("Save") }
        }
    }
}
