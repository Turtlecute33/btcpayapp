package com.btcpayapp.ui.screens.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.AppItem
import com.btcpayapp.data.api.dto.AppItemPriceType
import com.btcpayapp.data.api.dto.PointOfSaleAppData
import com.btcpayapp.data.api.dto.PointOfSaleAppRequest
import com.btcpayapp.data.api.dto.PosView
import com.btcpayapp.data.api.endpoints.createPointOfSaleApp
import com.btcpayapp.data.api.endpoints.pointOfSaleAppJson
import com.btcpayapp.data.api.endpoints.updatePointOfSaleApp
import com.btcpayapp.data.api.overlaid
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.ActionBar
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.confirmDiscardChanges
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val POS_VIEWS = listOf(PosView.Static, PosView.Cart, PosView.Light, PosView.Print)

/**
 * What the body of the screen is showing. A discriminator rather than the
 * state, so typing into a field does not re-animate the form around it.
 */
private enum class PosEditPhase { Loading, Error, Form }

private val PRICE_TYPES = listOf(AppItemPriceType.Fixed, AppItemPriceType.Minimum, AppItemPriceType.Topup)

/**
 * The keys a PoS update may clear: the text fields this form can empty. An
 * absent key keeps the server's value, so a field the form does not show
 * (HTML language, meta tags, anything a newer server adds) is never wiped.
 */
private val POS_CLEARABLE = setOf(
    "title", "description", "currency", "tipText", "notificationUrl", "redirectUrl",
    "customAmountPayButtonText", "fixedAmountPayButtonText", "formId",
)

/** Read-only keys of the GET, and `items`, which the PUT takes as the `template` string. */
private val POS_DROP = setOf("id", "storeId", "created", "appType", "archived", "items")

/** The item keys the item editor can empty. */
private val ITEM_CLEARABLE = setOf("description", "price", "inventory", "buyButtonText")

/** A create with no store selected; the app list offers "New" even then. */
internal fun noStoreSelected() = ApiException.NotFound("Select a store before creating an app.")

/**
 * One row of the item editor. Strings throughout, because the fields are text boxes.
 *
 * [original] is the item as loaded, so what the editor does not show (image,
 * buy-button text, tax rate) goes back unchanged; null for a new item.
 */
data class PosItemDraft(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val price: String = "",
    val priceType: AppItemPriceType = AppItemPriceType.Fixed,
    val inventory: String = "",
    val categories: String = "",
    val disabled: Boolean = false,
    val original: AppItem? = null,
    val titleError: String? = null,
    val priceError: String? = null,
)

data class PointOfSaleEditState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loadError: ApiException? = null,
    val error: ApiException? = null,

    val appName: String = "",
    val title: String = "",
    val description: String = "",
    val defaultView: PosView = PosView.Static,
    val currency: String = "",
    val showItems: Boolean = true,
    val showCustomAmount: Boolean = false,
    val showDiscount: Boolean = false,
    val showSearch: Boolean = true,
    val showCategories: Boolean = true,
    val enableTips: Boolean = false,
    val tipPercentages: List<Int> = emptyList(),
    val tipDraft: String = "",
    val fixedAmountPayButtonText: String = "",
    val customAmountPayButtonText: String = "",
    val tipText: String = "",
    val notificationUrl: String = "",
    val redirectUrl: String = "",
    val redirectAutomatically: Boolean = false,
    val formId: String = "",

    val items: List<PosItemDraft> = emptyList(),
    val itemEditor: PosItemDraft? = null,
    val editingIndex: Int = -1,

    val nameError: String? = null,
    /** The store's currency, for item prices when [currency] is blank. */
    val storeCurrency: String = "",
    val dirty: Boolean = false,
    val finished: Boolean = false,
)

class PointOfSaleEditViewModel(
    private val graph: AppGraph,
    private val appId: String?,
) : ViewModel() {

    /**
     * The store this screen was opened for, and the only one it creates in
     * (see [StoreBinding]). A store switch closes the screen (the shell's
     * reset), so it never changes.
     */
    private val bound = StoreBinding(graph.session)
    private val storeId: String? get() = bound.id

    private val _state = MutableStateFlow(
        PointOfSaleEditState(storeCurrency = bound.store?.defaultCurrency.orEmpty()),
    )
    val state = _state.asStateFlow()

    /**
     * The app as the server sent it, and its items by id. The update replaces
     * the whole app, so it starts from these and changes only what this form
     * edits; a typed round trip dropped item images, tax rates and the HTML
     * settings.
     */
    private var raw: JsonObject? = null
    private var rawItems: Map<String, JsonObject> = emptyMap()

    init {
        if (appId != null) load()
        // After a cold start the store comes later, and with it the default currency.
        bound.retryWhenKnown(viewModelScope) {
            _state.update { it.copy(storeCurrency = bound.store?.defaultCurrency.orEmpty()) }
        }
    }

    fun setAppName(value: String) = edit { it.copy(appName = value, nameError = null) }
    fun setTitle(value: String) = edit { it.copy(title = value) }
    fun setDescription(value: String) = edit { it.copy(description = value) }
    fun setDefaultView(value: PosView) = edit { it.copy(defaultView = value) }
    fun setCurrency(value: String) = edit { it.copy(currency = value.uppercase()) }
    fun setShowItems(value: Boolean) = edit { it.copy(showItems = value) }
    fun setShowCustomAmount(value: Boolean) = edit { it.copy(showCustomAmount = value) }
    fun setShowDiscount(value: Boolean) = edit { it.copy(showDiscount = value) }
    fun setShowSearch(value: Boolean) = edit { it.copy(showSearch = value) }
    fun setShowCategories(value: Boolean) = edit { it.copy(showCategories = value) }
    fun setEnableTips(value: Boolean) = edit { it.copy(enableTips = value) }
    fun setTipDraft(value: String) = edit { it.copy(tipDraft = value.filter(Char::isDigit)) }
    fun setFixedButtonText(value: String) = edit { it.copy(fixedAmountPayButtonText = value) }
    fun setCustomButtonText(value: String) = edit { it.copy(customAmountPayButtonText = value) }
    fun setTipText(value: String) = edit { it.copy(tipText = value) }
    fun setNotificationUrl(value: String) = edit { it.copy(notificationUrl = value) }
    fun setRedirectUrl(value: String) = edit { it.copy(redirectUrl = value) }
    fun setRedirectAutomatically(value: Boolean) = edit { it.copy(redirectAutomatically = value) }
    fun setFormId(value: String) = edit { it.copy(formId = value) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** A change the user made, so leaving asks first. */
    private fun edit(transform: (PointOfSaleEditState) -> PointOfSaleEditState) =
        _state.update { transform(it).copy(dirty = true) }

    fun addTipPercentage() = edit { current ->
        val value = current.tipDraft.toIntOrNull()
        if (value == null || value <= 0 || value in current.tipPercentages) {
            current.copy(tipDraft = "")
        } else {
            current.copy(tipPercentages = (current.tipPercentages + value).sorted(), tipDraft = "")
        }
    }

    fun removeTipPercentage(value: Int) =
        edit { it.copy(tipPercentages = it.tipPercentages - value) }

    // --- Item editor -------------------------------------------------------

    fun newItem() = _state.update { it.copy(itemEditor = PosItemDraft(), editingIndex = -1) }

    fun editItem(index: Int) = _state.update { current ->
        current.items.getOrNull(index)?.let { current.copy(itemEditor = it, editingIndex = index) } ?: current
    }

    fun closeItemEditor() = _state.update { it.copy(itemEditor = null, editingIndex = -1) }

    fun updateItemDraft(block: (PosItemDraft) -> PosItemDraft) =
        _state.update { current -> current.itemEditor?.let { current.copy(itemEditor = block(it)) } ?: current }

    fun commitItem() = _state.update { current ->
        val draft = current.itemEditor ?: return@update current
        val titleError = "An item needs a name.".takeIf { draft.title.isBlank() }
        val priceError = itemPriceProblem(draft.price, draft.priceType)
        if (titleError != null || priceError != null) {
            return@update current.copy(itemEditor = draft.copy(titleError = titleError, priceError = priceError))
        }
        val resolved = draft.copy(
            id = draft.id.ifBlank { slugify(draft.title) },
            titleError = null,
            priceError = null,
        )
        val items = if (current.editingIndex >= 0) {
            current.items.toMutableList().apply { this[current.editingIndex] = resolved }
        } else {
            current.items + resolved
        }
        current.copy(items = items, itemEditor = null, editingIndex = -1, dirty = true)
    }

    fun removeItem(index: Int) = edit { current ->
        current.copy(items = current.items.filterIndexed { i, _ -> i != index })
    }

    // --- Load and save -----------------------------------------------------

    fun load() {
        val id = appId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            runCatching {
                val json = graph.session.requireApi().pointOfSaleAppJson(id)
                json to json.decodeAs(PointOfSaleAppData.serializer())
            }
                .onSuccess { (json, data) ->
                    raw = json
                    rawItems = json.itemsById()
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
        // apps.
        if (snapshot.saving) return
        if (snapshot.appName.isBlank()) {
            _state.update { it.copy(nameError = "Give this app a name so you can find it again.") }
            return
        }
        // An existing app is saved only on top of what was loaded; the form
        // is not shown before that.
        val loaded = raw
        if (appId != null && loaded == null) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                val api = graph.session.requireApi()
                val request = snapshot.toRequest(itemsTemplate(snapshot.items))
                if (appId != null && loaded != null) {
                    api.updatePointOfSaleApp(appId, pointOfSaleBody(loaded, request))
                } else {
                    api.createPointOfSaleApp(storeId ?: throw noStoreSelected(), request)
                }
            }.onSuccess {
                _state.update { it.copy(saving = false, finished = true, dirty = false) }
            }.onFailure { failure ->
                val error = failure.asApiException()
                _state.update { it.copy(saving = false, error = error) }
            }
        }
    }

    /**
     * The `template` string: each item's raw JSON with the edits on top. The
     * read model returns `items` as an array, the write model wants the same
     * list JSON-encoded into a string.
     */
    private fun itemsTemplate(items: List<PosItemDraft>): String = JsonArray(
        items.map { draft -> mergeItem(draft.original?.let { rawItems[it.id] }, draft.toAppItem()) },
    ).toString()
}

/** The form filled from the app as loaded. */
internal fun PointOfSaleEditState.withLoaded(data: PointOfSaleAppData) = copy(
    appName = data.appName,
    title = data.title.orEmpty(),
    description = data.description.orEmpty(),
    // Kept even when Unknown: a view from a newer server goes back as the
    // server sent it (ApiJson.editsOf).
    defaultView = data.defaultView,
    currency = data.currency.orEmpty(),
    showItems = data.showItems,
    showCustomAmount = data.showCustomAmount,
    showDiscount = data.showDiscount,
    showSearch = data.showSearch,
    showCategories = data.showCategories,
    enableTips = data.enableTips,
    tipPercentages = data.customTipPercentages,
    fixedAmountPayButtonText = data.fixedAmountPayButtonText.orEmpty(),
    customAmountPayButtonText = data.customAmountPayButtonText.orEmpty(),
    tipText = data.tipText.orEmpty(),
    notificationUrl = data.notificationUrl.orEmpty(),
    redirectUrl = data.redirectUrl.orEmpty(),
    redirectAutomatically = data.redirectAutomatically,
    formId = data.formId.orEmpty(),
    items = data.items.map { item -> item.toDraft() },
)

/** The form as a typed request. Only what the form edits; [pointOfSaleBody] keeps the rest. */
internal fun PointOfSaleEditState.toRequest(template: String) = PointOfSaleAppRequest(
    appName = appName.trim(),
    title = title.trim().takeIf { it.isNotBlank() },
    description = description.takeIf { it.isNotBlank() },
    defaultView = defaultView,
    showItems = showItems,
    showCustomAmount = showCustomAmount,
    showDiscount = showDiscount,
    showSearch = showSearch,
    showCategories = showCategories,
    enableTips = enableTips,
    currency = currency.trim().takeIf { it.isNotBlank() },
    fixedAmountPayButtonText = fixedAmountPayButtonText.takeIf { it.isNotBlank() },
    customAmountPayButtonText = customAmountPayButtonText.takeIf { it.isNotBlank() },
    tipText = tipText.takeIf { it.isNotBlank() },
    customTipPercentages = tipPercentages,
    notificationUrl = notificationUrl.trim().takeIf { it.isNotBlank() },
    redirectUrl = redirectUrl.trim().takeIf { it.isNotBlank() },
    redirectAutomatically = redirectAutomatically,
    formId = formId.trim().takeIf { it.isNotBlank() },
    template = template,
)

/**
 * The body of a PoS update: the app as loaded, with the typed edits on top.
 * Nulls in [request] mean "keep", except for the keys the form can clear.
 */
internal fun pointOfSaleBody(raw: JsonObject, request: PointOfSaleAppRequest): JsonObject =
    raw.overlaid(ApiJson.editsOf(PointOfSaleAppRequest.serializer(), request, POS_CLEARABLE), drop = POS_DROP)

/**
 * One saved item: its raw JSON (null for a new item) with [edited] on top.
 * The raw object keeps what [AppItem] does not model (payment methods, keys a
 * newer server adds); an `Unknown` price type is left as the server wrote it.
 */
internal fun mergeItem(raw: JsonObject?, edited: AppItem): JsonObject =
    (raw ?: JsonObject(emptyMap())).overlaid(ApiJson.editsOf(AppItem.serializer(), edited, ITEM_CLEARABLE))

/**
 * The field error for an item price, or null. The price must parse, and a
 * Fixed or Minimum item must have one: an unparsed price used to be saved as
 * no price while the card showed the typed text. Zero stays
 * allowed for a Fixed item, which the server shows as free.
 */
internal fun itemPriceProblem(price: String, type: AppItemPriceType): String? {
    Amounts.parseProblem(price)?.let { return it }
    val value = Amounts.parse(price)
    return when {
        value != null && value.signum() < 0 -> "A price cannot be below zero."
        type == AppItemPriceType.Fixed && value == null -> "Enter a price, or 0 for a free item."
        type == AppItemPriceType.Minimum && (value == null || value.signum() == 0) -> "Enter a minimum price above zero."
        else -> null
    }
}

/** The GET's items by id, so each saved item can start from its own raw JSON. */
private fun JsonObject.itemsById(): Map<String, JsonObject> =
    (this["items"] as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>()
        .associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull.orEmpty() }

/** Decodes the raw GET into its typed model; a shape this app cannot read is a decoding failure. */
internal fun <T> JsonObject.decodeAs(deserializer: DeserializationStrategy<T>): T =
    try {
        ApiJson.instance.decodeFromJsonElement(deserializer, this)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        throw ApiException.Decoding(e)
    }

private fun slugify(title: String): String {
    val base = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    // The id is the item code the server stores against each sale, so it has to
    // be unique within the app and stable once used.
    return (base.ifBlank { "item" } + "-" + System.currentTimeMillis().toString().takeLast(5))
}

private fun AppItem.toDraft(): PosItemDraft = PosItemDraft(
    id = id,
    title = title,
    description = description.orEmpty(),
    // `toInput`, so a loaded "1.125" is not refused as ambiguous on save.
    price = price?.let { Amounts.toInput(it, 8) }.orEmpty(),
    // Kept even when Unknown, for the reason given on [mergeItem].
    priceType = priceType,
    inventory = inventory?.toString().orEmpty(),
    categories = categories.joinToString(", "),
    disabled = disabled,
    original = this,
)

/** The loaded item with the edited fields replaced, so the ones the editor does not show survive. */
private fun PosItemDraft.toAppItem(): AppItem = (original ?: AppItem()).copy(
    id = id,
    title = title.trim(),
    description = description.takeIf { it.isNotBlank() },
    price = Amounts.parse(price),
    priceType = priceType,
    inventory = inventory.toIntOrNull(),
    disabled = disabled,
    categories = categories.split(',').map(String::trim).filter(String::isNotEmpty),
)

/**
 * The price as it will be saved, not as it was typed: a card that echoed
 * the text hid a price that would not parse.
 */
private fun priceLabel(price: String, currency: String): String {
    val value = Amounts.parse(price) ?: return "no price"
    return if (currency.isBlank()) Amounts.trim(value, 8) else Amounts.format(value, currency)
}

@Composable
fun PointOfSaleEditScreen(
    appId: String?,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "pos-$appId") { PointOfSaleEditViewModel(it, appId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The toolbar and the system back both ask first once something changed.
    val back = confirmDiscardChanges(state.dirty, onBack)

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    state.itemEditor?.let { draft ->
        ItemEditorSheet(
            draft = draft,
            existing = state.editingIndex >= 0,
            onChange = viewModel::updateItemDraft,
            onCommit = viewModel::commitItem,
            onDismiss = viewModel::closeItemEditor,
        )
    }

    AppScreen(
        title = if (appId == null) "New point of sale" else "Edit point of sale",
        onBack = back,
        bottomBar = {
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
        },
    ) { padding ->
        val phase = when {
            state.loading -> PosEditPhase.Loading
            state.loadError != null -> PosEditPhase.Error
            else -> PosEditPhase.Form
        }

        AnimatedSwap(phase, label = "posEdit") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is coming is one app's
                // settings behind a form, not a list of rows.
                PosEditPhase.Loading -> LoadingState(Modifier.padding(padding))

                PosEditPhase.Error -> state.loadError?.let {
                    ErrorState(
                        error = it,
                        modifier = Modifier.padding(padding),
                        onRetry = viewModel::load,
                    )
                }

                PosEditPhase.Form ->
                    PosForm(state = state, viewModel = viewModel, modifier = Modifier.padding(padding))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosForm(
    state: PointOfSaleEditState,
    viewModel: PointOfSaleEditViewModel,
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
            FormField(
                label = "Title",
                value = state.title,
                onValueChange = viewModel::setTitle,
                supportingText = "Heading on the public page.",
            )
            FormField(
                label = "Description",
                value = state.description,
                onValueChange = viewModel::setDescription,
                singleLine = false,
            )
            FormDropdown(
                label = "Default view",
                options = POS_VIEWS,
                selected = state.defaultView,
                onSelect = viewModel::setDefaultView,
                optionLabel = { view ->
                    when (view) {
                        PosView.Static -> "Static — a grid of items"
                        PosView.Cart -> "Cart — items with a basket"
                        PosView.Light -> "Light — keypad only"
                        PosView.Print -> "Print — a printable list"
                        PosView.Unknown -> "Set on the server"
                    }
                },
            )
            FormField(
                label = "Currency",
                value = state.currency,
                onValueChange = viewModel::setCurrency,
                placeholder = "EUR",
                supportingText = "Leave blank to use the store's default.",
            )
        }

        FormSection(title = "On the page", modifier = Modifier.arrive(1)) {
            FormSwitch("Show items", state.showItems, viewModel::setShowItems)
            FormSwitch(
                title = "Allow a custom amount",
                checked = state.showCustomAmount,
                onCheckedChange = viewModel::setShowCustomAmount,
                description = "Adds a keypad for an amount that is not on the list.",
            )
            FormSwitch("Allow a discount", state.showDiscount, viewModel::setShowDiscount)
            FormSwitch("Show search", state.showSearch, viewModel::setShowSearch)
            FormSwitch("Show categories", state.showCategories, viewModel::setShowCategories)
        }

        FormSection(title = "Tips", modifier = Modifier.arrive(2)) {
            FormSwitch("Ask for a tip", state.enableTips, viewModel::setEnableTips)
            // Everything here only exists because the switch above is on, so it
            // opens out of that switch rather than being conjured underneath it.
            AnimatedVisibility(
                visible = state.enableTips,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column {
                    FormField(
                        label = "Tip prompt",
                        value = state.tipText,
                        onValueChange = viewModel::setTipText,
                        placeholder = "Would you like to leave a tip?",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FormField(
                            label = "Add a percentage",
                            value = state.tipDraft,
                            onValueChange = viewModel::setTipDraft,
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        )
                        IconButton(onClick = viewModel::addTipPercentage) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add percentage")
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.tipPercentages.forEach { percentage ->
                            AssistChip(
                                onClick = { viewModel.removeTipPercentage(percentage) },
                                label = { Text("$percentage%") },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove $percentage%",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        FormSection(title = "Buttons", modifier = Modifier.arrive(3)) {
            FormField(
                label = "Fixed amount button",
                value = state.fixedAmountPayButtonText,
                onValueChange = viewModel::setFixedButtonText,
                placeholder = "Buy",
            )
            FormField(
                label = "Custom amount button",
                value = state.customAmountPayButtonText,
                onValueChange = viewModel::setCustomButtonText,
                placeholder = "Pay",
            )
        }

        ItemsSection(
            items = state.items,
            currency = state.currency.ifBlank { state.storeCurrency },
            onAdd = viewModel::newItem,
            onEdit = viewModel::editItem,
            onRemove = viewModel::removeItem,
            modifier = Modifier.arrive(4),
        )

        FormSection(title = "After payment", modifier = Modifier.arrive(5)) {
            FormField(
                label = "Notification URL",
                value = state.notificationUrl,
                onValueChange = viewModel::setNotificationUrl,
                supportingText = "Posted to when an invoice from this app settles.",
                keyboardType = KeyboardType.Uri,
            )
            FormField(
                label = "Redirect URL",
                value = state.redirectUrl,
                onValueChange = viewModel::setRedirectUrl,
                supportingText = "Where the buyer lands once they have paid.",
                keyboardType = KeyboardType.Uri,
            )
            FormSwitch(
                title = "Redirect automatically",
                checked = state.redirectAutomatically,
                onCheckedChange = viewModel::setRedirectAutomatically,
                description = "Send them on without waiting for a tap.",
            )
            FormField(
                label = "Form id",
                value = state.formId,
                onValueChange = viewModel::setFormId,
                supportingText = "A form defined on the server, shown before checkout.",
                imeAction = ImeAction.Done,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ItemsSection(
    items: List<PosItemDraft>,
    currency: String,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Items",
            action = { TextButton(onClick = onAdd) { Text("Add") } },
        )

        AnimatedSwap(items.isEmpty(), label = "posItems") { empty ->
            if (empty) {
                Text(
                    text = "No items yet. A point of sale with no items only takes a custom amount.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    // A plain `Column`, not a lazy list, so `arrive` is safe
                    // here: a card is composed once and only a newly added item
                    // takes a slot that has not animated yet.
                    items.forEachIndexed { index, item ->
                        AppCard(modifier = Modifier.arrive(index), onClick = { onEdit(index) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = buildString {
                                            append(priceLabel(item.price, currency))
                                            if (item.priceType != AppItemPriceType.Fixed) {
                                                append(" · ${item.priceType.name}")
                                            }
                                            if (item.inventory.isNotBlank()) {
                                                append(" · ${item.inventory} in stock")
                                            }
                                            if (item.disabled) append(" · disabled")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onRemove(index) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Remove ${item.title}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditorSheet(
    draft: PosItemDraft,
    existing: Boolean,
    onChange: ((PosItemDraft) -> PosItemDraft) -> Unit,
    onCommit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (existing) "Edit item" else "New item",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )

            FormField(
                label = "Name",
                value = draft.title,
                onValueChange = { value -> onChange { it.copy(title = value, titleError = null) } },
                error = draft.titleError,
            )
            FormField(
                label = "Description",
                value = draft.description,
                onValueChange = { value -> onChange { it.copy(description = value) } },
                singleLine = false,
            )
            FormField(
                label = "Price",
                value = draft.price,
                onValueChange = { value -> onChange { it.copy(price = value, priceError = null) } },
                supportingText = "In the app's currency. Type 0 for a free item.",
                error = draft.priceError,
                keyboardType = KeyboardType.Decimal,
            )
            FormDropdown(
                label = "Price type",
                options = PRICE_TYPES,
                selected = draft.priceType,
                onSelect = { value -> onChange { it.copy(priceType = value, priceError = null) } },
                optionLabel = { type ->
                    when (type) {
                        AppItemPriceType.Fixed -> "Fixed — exactly the price"
                        AppItemPriceType.Minimum -> "Minimum — the price or more"
                        AppItemPriceType.Topup -> "Top-up — the buyer decides"
                        AppItemPriceType.Unknown -> "Set on the server"
                    }
                },
            )
            FormField(
                label = "Inventory",
                value = draft.inventory,
                // Nine digits always fit the server's Int. A longer number was
                // saved as no limit while the card showed it as the stock.
                onValueChange = { value -> onChange { it.copy(inventory = value.filter(Char::isDigit).take(9)) } },
                supportingText = "Leave blank for unlimited stock.",
                keyboardType = KeyboardType.Number,
            )
            FormField(
                label = "Categories",
                value = draft.categories,
                onValueChange = { value -> onChange { it.copy(categories = value) } },
                supportingText = "Comma separated.",
                imeAction = ImeAction.Done,
            )
            FormSwitch(
                title = "Disabled",
                checked = draft.disabled,
                onCheckedChange = { value -> onChange { it.copy(disabled = value) } },
                description = "Keeps the item but hides it from the till.",
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onCommit) { Text(if (existing) "Update" else "Add") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
