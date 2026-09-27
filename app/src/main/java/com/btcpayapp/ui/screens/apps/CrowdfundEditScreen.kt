package com.btcpayapp.ui.screens.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.ServerVersion
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.CrowdfundAppData
import com.btcpayapp.data.api.dto.CrowdfundAppRequest
import com.btcpayapp.data.api.endpoints.createCrowdfundApp
import com.btcpayapp.data.api.endpoints.crowdfundAppJson
import com.btcpayapp.data.api.endpoints.updateCrowdfundApp
import com.btcpayapp.data.api.overlaid
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.ActionBar
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DateRow
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.confirmDiscardChanges
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The values BTCPay accepts for `resetEvery`. */
private val RESET_PERIODS = listOf("Never", "Hour", "Day", "Month", "Year")

/**
 * The keys a crowdfund update may clear: the fields this form can empty. An
 * absent key keeps the server's value, so the contributor form, the sounds,
 * the animation colours and the HTML settings, none of them on this form,
 * survive a save.
 */
private val CROWDFUND_CLEARABLE = setOf(
    "title", "description", "tagline", "mainImageUrl", "targetCurrency", "targetAmount",
    "startDate", "endDate", "notificationUrl",
)

/** Read-only keys of the GET, and `perks`, which the PUT takes as the `perksTemplate` string. */
private val CROWDFUND_DROP = setOf("id", "storeId", "created", "appType", "archived", "perks")

private const val CROWDFUND_EDIT_TOO_OLD =
    "Editing crowdfunds from the app needs BTCPay Server 2.3.7 or later."

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
    val targetError: String? = null,
    val perksError: String? = null,
    /**
     * False for an existing crowdfund on a server older than 2.3.7, which has
     * no update route: the form is shown, but cannot be changed or saved.
     */
    val editable: Boolean = true,
    val dirty: Boolean = false,
    val finished: Boolean = false,
)

class CrowdfundEditViewModel(
    private val graph: AppGraph,
    private val appId: String?,
) : ViewModel() {

    /**
     * The store this screen was opened for, and the only one it creates in
     * (see [StoreBinding]). A store switch closes the screen (the shell's
     * reset), so it never changes. Read when the app is created, so a store
     * that loads after a cold start is still found.
     */
    private val bound = StoreBinding(graph.session)
    private val storeId: String? get() = bound.id

    private val _state = MutableStateFlow(
        CrowdfundEditState(editable = appId == null || graph.session.serverAtLeast(ServerVersion.CROWDFUND_EDIT)),
    )
    val state = _state.asStateFlow()

    /**
     * The crowdfund as the server sent it. The update replaces the whole app,
     * so it starts from this and changes only what this form edits; a typed
     * round trip reset the form, sounds, colours and HTML settings.
     */
    private var raw: JsonObject? = null

    init {
        if (appId != null) load()
    }

    fun setAppName(value: String) = edit { it.copy(appName = value, nameError = null) }
    fun setTitle(value: String) = edit { it.copy(title = value) }
    fun setDescription(value: String) = edit { it.copy(description = value) }
    fun setTagline(value: String) = edit { it.copy(tagline = value) }
    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }
    fun setEnforceTargetAmount(value: Boolean) = edit { it.copy(enforceTargetAmount = value) }
    fun setStartDate(value: Long?) = edit { it.copy(startDate = value) }
    fun setEndDate(value: Long?) = edit { it.copy(endDate = value) }
    fun setTargetCurrency(value: String) = edit { it.copy(targetCurrency = value.uppercase()) }
    fun setTargetAmount(value: String) = edit { it.copy(targetAmount = value, targetError = null) }
    fun setMainImageUrl(value: String) = edit { it.copy(mainImageUrl = value) }
    fun setNotificationUrl(value: String) = edit { it.copy(notificationUrl = value) }
    fun setSoundsEnabled(value: Boolean) = edit { it.copy(soundsEnabled = value) }
    fun setAnimationsEnabled(value: Boolean) = edit { it.copy(animationsEnabled = value) }
    fun setResetEveryAmount(value: String) = edit { it.copy(resetEveryAmount = value.filter(Char::isDigit)) }
    fun setResetEvery(value: String) = edit { it.copy(resetEvery = value) }
    fun setDisplayPerksValue(value: Boolean) = edit { it.copy(displayPerksValue = value) }
    fun setDisplayPerksRanking(value: Boolean) = edit { it.copy(displayPerksRanking = value) }
    fun setSortPerksByPopularity(value: Boolean) = edit { it.copy(sortPerksByPopularity = value) }
    fun setPerksTemplate(value: String) = edit { it.copy(perksTemplate = value, perksError = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** A change the user made, so leaving asks first. */
    private fun edit(transform: (CrowdfundEditState) -> CrowdfundEditState) =
        _state.update { transform(it).copy(dirty = true) }

    fun load() {
        val id = appId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching {
                val json = graph.session.requireApi().crowdfundAppJson(id)
                json to json.decodeAs(CrowdfundAppData.serializer())
            }
                .onSuccess { (json, data) ->
                    raw = json
                    _state.update { it.withLoaded(data).copy(loading = false, dirty = false) }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, loadError = failure.asApiException()) }
                }
        }
    }

    fun save() {
        val snapshot = _state.value
        // Re-entrancy guard. `enabled` is one recomposition behind the click,
        // so two taps in the same frame would both get through and create two
        // crowdfunds.
        if (snapshot.saving || !snapshot.editable) return
        // An existing crowdfund is saved only on top of what was loaded; the
        // form is not shown before that.
        val loaded = raw
        if (appId != null && loaded == null) return

        val perks = snapshot.perksTemplate.trim()
        // Parsed with the same Json the API layer uses, so what passes here is
        // exactly what the request would carry.
        val perksValid = perks.isEmpty() ||
            runCatching { graph.client.json.parseToJsonElement(perks) }.getOrNull() is JsonArray
        val nameError = "Give this app a name so you can find it again.".takeIf { snapshot.appName.isBlank() }
        val targetError = targetProblem(snapshot.targetAmount)
        val perksError = "Perks must be a JSON array, for example [] or [{…}].".takeIf { !perksValid }
        if (nameError != null || targetError != null || perksError != null) {
            _state.update { it.copy(nameError = nameError, targetError = targetError, perksError = perksError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                val api = graph.session.requireApi()
                val request = snapshot.toRequest()
                if (appId != null && loaded != null) {
                    api.updateCrowdfundApp(appId, crowdfundBody(loaded, request))
                } else {
                    api.createCrowdfundApp(storeId ?: throw noStoreSelected(), request)
                }
            }.onSuccess {
                _state.update { it.copy(saving = false, finished = true, dirty = false) }
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update { it.copy(saving = false, error = error) }
            }
        }
    }
}

/** The form filled from the crowdfund as loaded. */
internal fun CrowdfundEditState.withLoaded(data: CrowdfundAppData) = copy(
    appName = data.appName,
    title = data.title.orEmpty(),
    description = data.description.orEmpty(),
    tagline = data.tagline.orEmpty(),
    enabled = data.enabled,
    enforceTargetAmount = data.enforceTargetAmount,
    startDate = data.startDate,
    endDate = data.endDate,
    targetCurrency = data.targetCurrency.orEmpty(),
    // `toInput`, so a loaded "1.500" is not refused as ambiguous on save.
    targetAmount = data.targetAmount?.let { amount -> Amounts.toInput(amount, 8) }.orEmpty(),
    mainImageUrl = data.mainImageUrl.orEmpty(),
    notificationUrl = data.notificationUrl.orEmpty(),
    soundsEnabled = data.soundsEnabled,
    animationsEnabled = data.animationsEnabled,
    resetEveryAmount = data.resetEveryAmount?.toString().orEmpty(),
    resetEvery = data.resetEvery?.takeIf { period -> period in RESET_PERIODS } ?: "Never",
    displayPerksValue = data.displayPerksValue,
    displayPerksRanking = data.displayPerksRanking,
    sortPerksByPopularity = data.sortPerksByPopularity,
    // The read side hands back a parsed array; the write side wants it
    // re-encoded into a string, so keep the text here.
    perksTemplate = data.perks?.toString().orEmpty(),
)

/**
 * The form as a typed request. Only what the form edits; [crowdfundBody]
 * keeps the rest. Call after the checks in save(): an unparsed target here
 * would go out as no target.
 */
internal fun CrowdfundEditState.toRequest() = CrowdfundAppRequest(
    appName = appName.trim(),
    title = title.trim().takeIf { it.isNotBlank() },
    description = description.takeIf { it.isNotBlank() },
    enabled = enabled,
    enforceTargetAmount = enforceTargetAmount,
    startDate = startDate,
    endDate = endDate,
    targetCurrency = targetCurrency.trim().takeIf { it.isNotBlank() },
    // Checked by [targetProblem], so null here means blank: no target.
    targetAmount = Amounts.parse(targetAmount),
    mainImageUrl = mainImageUrl.trim().takeIf { it.isNotBlank() },
    notificationUrl = notificationUrl.trim().takeIf { it.isNotBlank() },
    tagline = tagline.takeIf { it.isNotBlank() },
    soundsEnabled = soundsEnabled,
    animationsEnabled = animationsEnabled,
    resetEveryAmount = resetEveryAmount.toIntOrNull(),
    resetEvery = resetEvery,
    displayPerksValue = displayPerksValue,
    displayPerksRanking = displayPerksRanking,
    sortPerksByPopularity = sortPerksByPopularity,
    perksTemplate = perksTemplate.trim().takeIf { it.isNotEmpty() },
)

/**
 * The body of a crowdfund update: the app as loaded, with the typed edits on
 * top. Nulls in [request] mean "keep", except for the keys the form can clear.
 */
internal fun crowdfundBody(raw: JsonObject, request: CrowdfundAppRequest): JsonObject =
    raw.overlaid(ApiJson.editsOf(CrowdfundAppRequest.serializer(), request, CROWDFUND_CLEARABLE), drop = CROWDFUND_DROP)

/**
 * The field error for the target, or null. Blank means no target; anything
 * else must parse and be above zero. Unparsed text used to become "no
 * target" without a word.
 */
private fun targetProblem(text: String): String? {
    if (text.isBlank()) return null
    Amounts.parseProblem(text)?.let { return it }
    return "Enter a target above zero, or leave it blank.".takeIf { Amounts.parse(text)?.signum() != 1 }
}

@Composable
fun CrowdfundEditScreen(
    appId: String?,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "crowdfund-$appId") { CrowdfundEditViewModel(it, appId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The toolbar and the system back both ask first once something changed.
    val back = confirmDiscardChanges(state.dirty, onBack)

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    AppScreen(
        title = if (appId == null) "New crowdfund" else "Edit crowdfund",
        onBack = back,
        bottomBar = {
            if (state.editable) {
                ActionBar {
                    // The spinner pushes the Save button aside rather than
                    // appearing over it, so the bar reads as one control that
                    // has become busy.
                    AnimatedVisibility(
                        visible = state.saving,
                        enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp))
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
    // Read-only when the server has no update route: shown, but not changeable.
    val editable = state.editable

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        ErrorBanner(ApiException.Unsupported(CROWDFUND_EDIT_TOO_OLD).takeIf { !editable })

        ErrorBanner(state.error, onDismiss = viewModel::dismissError)

        FormSection(title = "Basics", modifier = Modifier.arrive(0)) {
            FormField(
                label = "App name",
                value = state.appName,
                onValueChange = viewModel::setAppName,
                error = state.nameError,
                supportingText = "Internal — only you see this.",
                enabled = editable,
            )
            FormField(label = "Title", value = state.title, onValueChange = viewModel::setTitle, enabled = editable)
            FormField(
                label = "Tagline",
                value = state.tagline,
                onValueChange = viewModel::setTagline,
                supportingText = "One line under the title.",
                enabled = editable,
            )
            FormField(
                label = "Description",
                value = state.description,
                onValueChange = viewModel::setDescription,
                singleLine = false,
                enabled = editable,
            )
            FormField(
                label = "Main image URL",
                value = state.mainImageUrl,
                onValueChange = viewModel::setMainImageUrl,
                keyboardType = KeyboardType.Uri,
                enabled = editable,
            )
            FormSwitch(
                title = "Enabled",
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                description = "Off keeps the page private while you prepare it.",
                enabled = editable,
            )
        }

        FormSection(title = "Target", modifier = Modifier.arrive(1)) {
            FormField(
                label = "Target currency",
                value = state.targetCurrency,
                onValueChange = viewModel::setTargetCurrency,
                placeholder = "EUR",
                enabled = editable,
            )
            FormField(
                label = "Target amount",
                value = state.targetAmount,
                onValueChange = viewModel::setTargetAmount,
                supportingText = "Leave blank for no target.",
                error = state.targetError,
                keyboardType = KeyboardType.Decimal,
                enabled = editable,
            )
            FormSwitch(
                title = "Enforce the target",
                checked = state.enforceTargetAmount,
                onCheckedChange = viewModel::setEnforceTargetAmount,
                description = "Refuse contributions once the target is reached.",
                enabled = editable,
            )
        }

        FormSection(title = "Dates", modifier = Modifier.arrive(2)) {
            DateRow(
                label = "Starts",
                epochSeconds = state.startDate,
                onPick = viewModel::setStartDate,
                endOfDay = false,
                enabled = editable,
                supportingText = "Before this, the page shows a countdown.",
            )
            DateRow(
                label = "Ends",
                epochSeconds = state.endDate,
                onPick = viewModel::setEndDate,
                endOfDay = true,
                enabled = editable,
                supportingText = "After this, contributions close.",
            )
            FormField(
                label = "Reset every",
                value = state.resetEveryAmount,
                onValueChange = viewModel::setResetEveryAmount,
                supportingText = "How many periods between resets. Blank means never.",
                keyboardType = KeyboardType.Number,
                enabled = editable,
            )
            FormDropdown(
                label = "Period",
                options = RESET_PERIODS,
                selected = state.resetEvery,
                onSelect = viewModel::setResetEvery,
                enabled = editable,
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
                enabled = editable,
            )
            FormSwitch(
                title = "Show perk value",
                checked = state.displayPerksValue,
                onCheckedChange = viewModel::setDisplayPerksValue,
                enabled = editable,
            )
            FormSwitch(
                title = "Show perk ranking",
                checked = state.displayPerksRanking,
                onCheckedChange = viewModel::setDisplayPerksRanking,
                enabled = editable,
            )
            FormSwitch(
                title = "Sort perks by popularity",
                checked = state.sortPerksByPopularity,
                onCheckedChange = viewModel::setSortPerksByPopularity,
                enabled = editable,
            )
        }

        FormSection(title = "Presentation", modifier = Modifier.arrive(4)) {
            FormSwitch(
                title = "Sounds",
                checked = state.soundsEnabled,
                onCheckedChange = viewModel::setSoundsEnabled,
                description = "Plays a chime on the page when a contribution lands.",
                enabled = editable,
            )
            FormSwitch(
                title = "Animations",
                checked = state.animationsEnabled,
                onCheckedChange = viewModel::setAnimationsEnabled,
                enabled = editable,
            )
        }

        FormSection(title = "After payment", modifier = Modifier.arrive(5)) {
            FormField(
                label = "Notification URL",
                value = state.notificationUrl,
                onValueChange = viewModel::setNotificationUrl,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                enabled = editable,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
