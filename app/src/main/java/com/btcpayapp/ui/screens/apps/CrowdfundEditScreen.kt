package com.btcpayapp.ui.screens.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Dates
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.CrowdfundAppRequest
import com.btcpayapp.data.api.endpoints.createCrowdfundApp
import com.btcpayapp.data.api.endpoints.crowdfundApp
import com.btcpayapp.data.api.endpoints.updateCrowdfundApp
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

/** The values BTCPay accepts for `resetEvery`. */
private val RESET_PERIODS = listOf("Never", "Hour", "Day", "Month", "Year")

/**
 * What the body of the screen is showing. A discriminator rather than the
 * state, so typing into a field does not re-animate the form around it.
 */
private enum class CrowdfundEditPhase { Loading, Error, Form }

data class CrowdfundEditState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loadError: ApiException? = null,
    val error: ApiException? = null,

    val appName: String = "",
    val title: String = "",
    val description: String = "",
    val tagline: String = "",
    val enabled: Boolean = false,
    val enforceTargetAmount: Boolean = false,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val targetCurrency: String = "",
    val targetAmount: String = "",
    val mainImageUrl: String = "",
    val notificationUrl: String = "",
    val soundsEnabled: Boolean = false,
    val animationsEnabled: Boolean = false,
    val resetEveryAmount: String = "",
    val resetEvery: String = "Never",
    val displayPerksValue: Boolean = false,
    val displayPerksRanking: Boolean = false,
    val sortPerksByPopularity: Boolean = true,
    val perksTemplate: String = "",

    val nameError: String? = null,
    val perksError: String? = null,
    val finished: Boolean = false,
)

class CrowdfundEditViewModel(
    private val graph: AppGraph,
    private val appId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(CrowdfundEditState())
    val state = _state.asStateFlow()

    private val storeId get() = graph.session.activeStore.value?.id

    init {
        if (appId != null) load()
    }

    fun setAppName(value: String) = _state.update { it.copy(appName = value, nameError = null) }
    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }
    fun setTagline(value: String) = _state.update { it.copy(tagline = value) }
    fun setEnabled(value: Boolean) = _state.update { it.copy(enabled = value) }
    fun setEnforceTargetAmount(value: Boolean) = _state.update { it.copy(enforceTargetAmount = value) }
    fun setStartDate(value: Long?) = _state.update { it.copy(startDate = value) }
    fun setEndDate(value: Long?) = _state.update { it.copy(endDate = value) }
    fun setTargetCurrency(value: String) = _state.update { it.copy(targetCurrency = value.uppercase()) }
    fun setTargetAmount(value: String) = _state.update { it.copy(targetAmount = value) }
    fun setMainImageUrl(value: String) = _state.update { it.copy(mainImageUrl = value) }
    fun setNotificationUrl(value: String) = _state.update { it.copy(notificationUrl = value) }
    fun setSoundsEnabled(value: Boolean) = _state.update { it.copy(soundsEnabled = value) }
    fun setAnimationsEnabled(value: Boolean) = _state.update { it.copy(animationsEnabled = value) }
    fun setResetEveryAmount(value: String) = _state.update { it.copy(resetEveryAmount = value.filter(Char::isDigit)) }
    fun setResetEvery(value: String) = _state.update { it.copy(resetEvery = value) }
    fun setDisplayPerksValue(value: Boolean) = _state.update { it.copy(displayPerksValue = value) }
    fun setDisplayPerksRanking(value: Boolean) = _state.update { it.copy(displayPerksRanking = value) }
    fun setSortPerksByPopularity(value: Boolean) = _state.update { it.copy(sortPerksByPopularity = value) }
    fun setPerksTemplate(value: String) = _state.update { it.copy(perksTemplate = value, perksError = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun load() {
        val id = appId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching { graph.session.requireApi().crowdfundApp(id) }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            loading = false,
                            appName = data.appName,
                            title = data.title.orEmpty(),
                            description = data.description.orEmpty(),
                            tagline = data.tagline.orEmpty(),
                            enabled = data.enabled,
                            enforceTargetAmount = data.enforceTargetAmount,
                            startDate = data.startDate,
                            endDate = data.endDate,
                            targetCurrency = data.targetCurrency.orEmpty(),
                            targetAmount = data.targetAmount?.toPlainString().orEmpty(),
                            mainImageUrl = data.mainImageUrl.orEmpty(),
                            notificationUrl = data.notificationUrl.orEmpty(),
                            soundsEnabled = data.soundsEnabled,
                            animationsEnabled = data.animationsEnabled,
                            resetEveryAmount = data.resetEveryAmount?.toString().orEmpty(),
                            resetEvery = data.resetEvery?.takeIf { period -> period in RESET_PERIODS } ?: "Never",
                            displayPerksValue = data.displayPerksValue,
                            displayPerksRanking = data.displayPerksRanking,
                            sortPerksByPopularity = data.sortPerksByPopularity,
                            // The read side hands back a parsed array; the write side
                            // wants it re-encoded into a string, so keep the text here.
                            perksTemplate = data.perks?.toString().orEmpty(),
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

        if (snapshot.appName.isBlank()) {
            _state.update { it.copy(nameError = "Give this app a name so you can find it again.") }
            return
        }

        val perks = snapshot.perksTemplate.trim()
        if (perks.isNotEmpty()) {
            // Parsed with the same Json the API layer uses, so what passes here is
            // exactly what the request would carry.
            val element = runCatching { graph.client.json.parseToJsonElement(perks) }.getOrNull()
            if (element !is JsonArray) {
                _state.update { it.copy(perksError = "Perks must be a JSON array, for example [] or [{…}].") }
                return
            }
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
                return@launch
            }

            val request = CrowdfundAppRequest(
                appName = snapshot.appName.trim(),
                title = snapshot.title.trim().takeIf { it.isNotBlank() },
                description = snapshot.description.takeIf { it.isNotBlank() },
                enabled = snapshot.enabled,
                enforceTargetAmount = snapshot.enforceTargetAmount,
                startDate = snapshot.startDate,
                endDate = snapshot.endDate,
                targetCurrency = snapshot.targetCurrency.trim().takeIf { it.isNotBlank() },
                // `Amounts.parse`, not `toDoubleOrNull`: this is money, and it
                // must accept a comma decimal separator like every other amount
                // field. The DTO is `BigDecimal` because binary floating point
                // can write a crowdfund goal of 1234.56 back as
                // 1234.5600000000001.
                targetAmount = Amounts.parse(snapshot.targetAmount),
                mainImageUrl = snapshot.mainImageUrl.trim().takeIf { it.isNotBlank() },
                notificationUrl = snapshot.notificationUrl.trim().takeIf { it.isNotBlank() },
                tagline = snapshot.tagline.takeIf { it.isNotBlank() },
                soundsEnabled = snapshot.soundsEnabled,
                animationsEnabled = snapshot.animationsEnabled,
                resetEveryAmount = snapshot.resetEveryAmount.toIntOrNull(),
                resetEvery = snapshot.resetEvery,
                displayPerksValue = snapshot.displayPerksValue,
                displayPerksRanking = snapshot.displayPerksRanking,
                sortPerksByPopularity = snapshot.sortPerksByPopularity,
                perksTemplate = perks.takeIf { it.isNotEmpty() },
            )

            runCatching {
                if (appId == null) api.createCrowdfundApp(store, request) else api.updateCrowdfundApp(appId, request)
            }.onSuccess {
                _state.update { it.copy(saving = false, finished = true) }
            }.onFailure { failure ->
                _state.update { it.copy(saving = false, error = failure.asApiException()) }
            }
        }
    }

}

@Composable
fun CrowdfundEditScreen(
    appId: String?,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "crowdfund-$appId") { CrowdfundEditViewModel(it, appId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    AppScreen(
        title = if (appId == null) "New crowdfund" else "Edit crowdfund",
        onBack = onBack,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The spinner pushes the Save button aside rather than
                    // appearing over it, so the bar reads as one control that
                    // has become busy.
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
            state.loading -> CrowdfundEditPhase.Loading
            state.loadError != null -> CrowdfundEditPhase.Error
            else -> CrowdfundEditPhase.Form
        }

        AnimatedSwap(phase, label = "crowdfundEdit") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is coming is one app's
                // settings behind a form, not a list of rows.
                CrowdfundEditPhase.Loading -> LoadingState(Modifier.padding(padding))

                CrowdfundEditPhase.Error -> state.loadError?.let {
                    ErrorState(
                        error = it,
                        modifier = Modifier.padding(padding),
                        onRetry = viewModel::load,
                    )
                }

                CrowdfundEditPhase.Form ->
                    CrowdfundForm(state = state, viewModel = viewModel, modifier = Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun CrowdfundForm(
    state: CrowdfundEditState,
    viewModel: CrowdfundEditViewModel,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        ErrorBanner(state.error, onDismiss = viewModel::dismissError)

        FormSection(title = "Basics", modifier = Modifier.arrive(0)) {
            FormField(
                label = "App name",
                value = state.appName,
                onValueChange = viewModel::setAppName,
                error = state.nameError,
                supportingText = "Internal — only you see this.",
            )
            FormField(label = "Title", value = state.title, onValueChange = viewModel::setTitle)
            FormField(
                label = "Tagline",
                value = state.tagline,
                onValueChange = viewModel::setTagline,
                supportingText = "One line under the title.",
            )
            FormField(
                label = "Description",
                value = state.description,
                onValueChange = viewModel::setDescription,
                singleLine = false,
            )
            FormField(
                label = "Main image URL",
                value = state.mainImageUrl,
                onValueChange = viewModel::setMainImageUrl,
                keyboardType = KeyboardType.Uri,
            )
            FormSwitch(
                title = "Enabled",
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                description = "Off keeps the page private while you prepare it.",
            )
        }

        FormSection(title = "Target", modifier = Modifier.arrive(1)) {
            FormField(
                label = "Target currency",
                value = state.targetCurrency,
                onValueChange = viewModel::setTargetCurrency,
                placeholder = "EUR",
            )
            FormField(
                label = "Target amount",
                value = state.targetAmount,
                onValueChange = viewModel::setTargetAmount,
                keyboardType = KeyboardType.Decimal,
            )
            FormSwitch(
                title = "Enforce the target",
                checked = state.enforceTargetAmount,
                onCheckedChange = viewModel::setEnforceTargetAmount,
                description = "Refuse contributions once the target is reached.",
            )
        }

        FormSection(title = "Dates", modifier = Modifier.arrive(2)) {
            DateField(
                label = "Starts",
                epochSeconds = state.startDate,
                onPick = viewModel::setStartDate,
                supportingText = "Before this, the page shows a countdown.",
            )
            DateField(
                label = "Ends",
                epochSeconds = state.endDate,
                onPick = viewModel::setEndDate,
                supportingText = "After this, contributions close.",
            )
            FormField(
                label = "Reset every",
                value = state.resetEveryAmount,
                onValueChange = viewModel::setResetEveryAmount,
                supportingText = "How many periods between resets. Blank means never.",
                keyboardType = KeyboardType.Number,
            )
            FormDropdown(
                label = "Period",
                options = RESET_PERIODS,
                selected = state.resetEvery,
                onSelect = viewModel::setResetEvery,
            )
        }

        FormSection(title = "Perks", modifier = Modifier.arrive(3)) {
            FormField(
                label = "Perks (JSON array)",
                value = state.perksTemplate,
                onValueChange = viewModel::setPerksTemplate,
                error = state.perksError,
                supportingText = "The API takes this as a JSON string. It is checked before saving.",
                singleLine = false,
                imeAction = ImeAction.Default,
            )
            FormSwitch(
                title = "Show perk value",
                checked = state.displayPerksValue,
                onCheckedChange = viewModel::setDisplayPerksValue,
            )
            FormSwitch(
                title = "Show perk ranking",
                checked = state.displayPerksRanking,
                onCheckedChange = viewModel::setDisplayPerksRanking,
            )
            FormSwitch(
                title = "Sort perks by popularity",
                checked = state.sortPerksByPopularity,
                onCheckedChange = viewModel::setSortPerksByPopularity,
            )
        }

        FormSection(title = "Presentation", modifier = Modifier.arrive(4)) {
            FormSwitch(
                title = "Sounds",
                checked = state.soundsEnabled,
                onCheckedChange = viewModel::setSoundsEnabled,
                description = "Plays a chime on the page when a contribution lands.",
            )
            FormSwitch(
                title = "Animations",
                checked = state.animationsEnabled,
                onCheckedChange = viewModel::setAnimationsEnabled,
            )
        }

        FormSection(title = "After payment", modifier = Modifier.arrive(5)) {
            FormField(
                label = "Notification URL",
                value = state.notificationUrl,
                onValueChange = viewModel::setNotificationUrl,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    epochSeconds: Long?,
    onPick: (Long?) -> Unit,
    supportingText: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    if (open) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = epochSeconds?.let { it * 1000 },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // BTCPay stores these as unix seconds, not milliseconds.
                        onPick(pickerState.selectedDateMillis?.let { it / 1000 })
                        open = false
                    },
                ) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.DateRange,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = epochSeconds?.let(Dates::date) ?: "Not set",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The clear button only exists once a date has been set, and it turns
        // up right where the finger that set it already is.
        AnimatedVisibility(
            visible = epochSeconds != null,
            enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
            exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
        ) {
            IconButton(onClick = { onPick(null) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear $label")
            }
        }
    }
}
