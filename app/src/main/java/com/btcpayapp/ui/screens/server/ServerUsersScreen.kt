package com.btcpayapp.ui.screens.server

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.ApplicationUserData
import com.btcpayapp.data.api.dto.CreateUserRequest
import com.btcpayapp.data.api.endpoints.createUser
import com.btcpayapp.data.api.endpoints.deleteUser
import com.btcpayapp.data.api.endpoints.setUserApproved
import com.btcpayapp.data.api.endpoints.setUserLocked
import com.btcpayapp.data.api.endpoints.users
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.SecretField
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the new-user sheet asks for; held by the screen while an administrator is confirmed. */
data class NewUser(
    val email: String,
    val name: String,
    val password: String,
    val isAdministrator: Boolean,
    val sendInvitationEmail: Boolean,
)

data class ServerUsersState(
    val users: List<ApplicationUserData> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val busyUserId: String? = null,
    val pendingDelete: ApplicationUserData? = null,
    val createOpen: Boolean = false,
    val creating: Boolean = false,
    val createError: String? = null,
    /** Present when the new account was created without sending an email. */
    val invitationUrl: String? = null,
    val message: String? = null,
)

class ServerUsersViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(ServerUsersState())
    val state = _state.asStateFlow()

    init {
        load(refreshing = false)
    }

    fun refresh() = load(refreshing = true)

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun openCreate() = _state.update { it.copy(createOpen = true, createError = null, invitationUrl = null) }

    fun closeCreate() = _state.update { it.copy(createOpen = false, createError = null, invitationUrl = null) }

    fun askDelete(user: ApplicationUserData) = _state.update { it.copy(pendingDelete = user) }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    fun approve(user: ApplicationUserData) = act(user) { api -> api.setUserApproved(user.id, true) }

    fun setLocked(user: ApplicationUserData, locked: Boolean) {
        if (locked && isCurrentUser(user)) {
            _state.update { it.copy(error = ApiException.Transport("Use another administrator account to lock this account.")) }
            return
        }
        act(user) { api -> api.setUserLocked(user.id, locked) }
    }

    private fun isCurrentUser(user: ApplicationUserData): Boolean = user.id == graph.session.user.value?.id ||
        user.id == graph.session.activeAccount.value?.userId

    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        if (isCurrentUser(target)) {
            _state.update { it.copy(error = ApiException.Transport("Use another administrator account to delete this account.")) }
            return
        }
        act(target) { api -> api.deleteUser(target.id) }
    }

    /** An administrator is confirmed by the screen first; see [ServerUsersScreen]. */
    fun create(user: NewUser) {
        if (user.email.isBlank()) {
            _state.update { it.copy(createError = "An email address is required.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(creating = true, createError = null) }
            runCatching {
                graph.session.requireApi().createUser(
                    CreateUserRequest(
                        email = user.email.trim(),
                        password = user.password.takeIf { it.isNotBlank() },
                        name = user.name.takeIf { it.isNotBlank() },
                        isAdministrator = user.isAdministrator,
                        sendInvitationEmail = user.sendInvitationEmail,
                    ),
                )
            }.onSuccess { created ->
                val invitation = created.invitationUrl?.takeIf { it.isNotBlank() }
                _state.update {
                    it.copy(
                        creating = false,
                        createOpen = invitation != null,
                        invitationUrl = invitation,
                        message = if (invitation == null) "Account created." else null,
                    )
                }
                load(refreshing = true)
            }.onFailure { failure ->
                _state.update { it.copy(creating = false, createError = failure.asApiException().userMessage) }
            }
        }
    }

    private fun act(user: ApplicationUserData, block: suspend (BtcPayApi) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busyUserId = user.id) }
            runCatching { block(graph.session.requireApi()) }
                .onSuccess {
                    _state.update { it.copy(busyUserId = null) }
                    load(refreshing = true)
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busyUserId = null, message = failure.asApiException().userMessage)
                    }
                }
        }
    }

    fun load(refreshing: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.users.isEmpty(), refreshing = refreshing, error = null)
            }
            runCatching { graph.session.requireApi().users() }
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            users = list.sortedBy { user -> user.email.lowercase() },
                            loading = false,
                            refreshing = false,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = failure.asApiException())
                    }
                }
        }
    }

}

/** What the body is showing; a cheap discriminator, not the user list. */
private enum class ServerUsersPhase { Loading, Error, Empty, Content }

@Composable
fun ServerUsersScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { ServerUsersViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gate = rememberSpendGate()
    var pendingAdmin by remember { mutableStateOf<NewUser?>(null) }
    // Shown in the sheet: a snackbar would sit behind it.
    var refusal by remember { mutableStateOf<String?>(null) }

    val visible = remember(state.users, state.query) {
        val needle = state.query.trim().lowercase()
        if (needle.isEmpty()) {
            state.users
        } else {
            state.users.filter { user ->
                user.email.lowercase().contains(needle) ||
                    user.name.orEmpty().lowercase().contains(needle) ||
                    user.roles.any { role -> role.lowercase().contains(needle) }
            }
        }
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    state.pendingDelete?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.displayName}?",
            message = "The account, its API keys and its store access are removed. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }

    if (state.createOpen) {
        CreateUserSheet(
            creating = state.creating,
            error = state.createError,
            refusal = refusal,
            invitationUrl = state.invitationUrl,
            onDismiss = {
                refusal = null
                viewModel.closeCreate()
            },
            onCreate = { request ->
                refusal = null
                if (request.isAdministrator && request.email.isNotBlank()) pendingAdmin = request else viewModel.create(request)
            },
        )
    }

    // An administrator controls the server, every store and every user, so
    // making one takes the same confirmation as sending funds.
    // Drawn after the sheet, so its window sits on top of it.
    pendingAdmin?.let { request ->
        ConfirmDialog(
            title = "Create an administrator?",
            message = "${request.email.trim()} will have full control of this server, every store and every user.",
            confirmLabel = "Create administrator",
            destructive = true,
            onConfirm = {
                pendingAdmin = null
                scope.afterSpendGate(gate, "Confirm new administrator", request.email.trim(), { refusal = it }) {
                    viewModel.create(request)
                }
            },
            onDismiss = { pendingAdmin = null },
        )
    }

    AppScreen(
        title = "Users",
        subtitle = "Accounts on this server",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openCreate,
                icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                text = { Text("New user") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            FormField(
                label = "Search",
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = "Email, name or role",
                // The list filters as you type, so the key only closes the keyboard.
                imeAction = ImeAction.Done,
            )

            if (state.users.isNotEmpty()) {
                ErrorBanner(state.error, onDismiss = viewModel::dismissError, onRetry = viewModel::refresh)
            }

            val phase = when {
                state.loading -> ServerUsersPhase.Loading
                state.error != null && state.users.isEmpty() -> ServerUsersPhase.Error
                visible.isEmpty() -> ServerUsersPhase.Empty
                else -> ServerUsersPhase.Content
            }

            AnimatedSwap(phase, Modifier.fillMaxSize(), label = "serverUsers") { shown ->
                when (shown) {
                    ServerUsersPhase.Loading -> SkeletonList()

                    ServerUsersPhase.Error -> ErrorState(
                        error = state.error,
                        onRetry = viewModel::refresh,
                    )

                    ServerUsersPhase.Empty -> EmptyState(
                        title = if (state.query.isBlank()) "No users" else "Nothing matches",
                        description = if (state.query.isBlank()) {
                            "Accounts registered on this server appear here."
                        } else {
                            "Try a different email, name or role."
                        },
                        icon = Icons.Rounded.Group,
                    )

                    ServerUsersPhase.Content -> LazyColumn(Modifier.fillMaxSize()) {
                        // Keyed by account id, so a row filtered out by the
                        // search box slides away rather than the list below it
                        // jumping up into its place.
                        items(visible, key = { it.id }) { user ->
                            UserCard(
                                user = user,
                                busy = state.busyUserId == user.id,
                                modifier = Modifier.animateItem(),
                                onApprove = { viewModel.approve(user) },
                                onSetLocked = { viewModel.setLocked(user, it) },
                                onDelete = { viewModel.askDelete(user) },
                            )
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(
    user: ApplicationUserData,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onApprove: () -> Unit,
    onSetLocked: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.statusColors
    var menuOpen by remember { mutableStateOf(false) }

    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    user.name?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The overflow button is replaced by a spinner for the length
                // of an approve or a lock. Cross-fading says the control is
                // thinking; swapping on the frame says it went away.
                AnimatedSwap(busy, label = "userBusy") { working ->
                    if (working) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        // Shut as soon as a request starts. The menu is a
                        // `Popup` in its own window, so it is drawn opaque over
                        // a control that is fading out; left open it would
                        // vanish with the branch and then reappear by itself
                        // when the request returned and the branch recomposed.
                        LaunchedEffect(busy) { if (busy) menuOpen = false }
                        Box {
                            IconButton(enabled = !busy, onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Actions for ${user.email}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!user.approved) {
                                    DropdownMenuItem(
                                        text = { Text("Approve") },
                                        leadingIcon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            onApprove()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (user.disabled) "Unlock" else "Lock") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (user.disabled) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onSetLocked(!user.disabled)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (user.roles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = user.roles.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val badges = buildList {
                if (user.disabled) add("Locked" to true)
                if (!user.approved) add("Not approved" to true)
                if (!user.emailConfirmed) add("Email unconfirmed" to false)
            }
            // Approving or unlocking an account removes its badges, and that
            // is the only confirmation the row gives that the action landed.
            AnimatedVisibility(
                visible = badges.isNotEmpty(),
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        badges.forEach { (label, severe) ->
                            StatusPill(
                                label = label,
                                container = if (severe) colors.invalid else colors.pending,
                                content = if (severe) colors.onInvalid else colors.onPending,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateUserSheet(
    creating: Boolean,
    error: String?,
    refusal: String?,
    invitationUrl: String?,
    onDismiss: () -> Unit,
    onCreate: (NewUser) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var administrator by remember { mutableStateOf(false) }
    var sendInvitation by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // Material animates the sheet itself; the contents only need to be
            // staggered so the form assembles rather than appearing whole.
            AnimatedSwap(invitationUrl != null, label = "createUser") { invited ->
                if (invited) {
                    Column {
                        Text(
                            text = "Invitation link",
                            modifier = Modifier.padding(horizontal = 16.dp).arrive(0),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.padding(horizontal = 16.dp).arrive(1)) {
                            CopyableField(
                                label = "Send this to the new user",
                                value = invitationUrl.orEmpty(),
                                sensitive = true,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "It is not shown again. Anyone holding the link can set the password for " +
                                    "this account, so pass it over a channel you trust.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(2),
                        ) { Text("Done") }
                    }
                } else {
                    Column {
                        Text(
                            text = "New user",
                            modifier = Modifier.padding(horizontal = 16.dp).arrive(0),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FormField(
                            label = "Email",
                            value = email,
                            onValueChange = { email = it },
                            modifier = Modifier.arrive(1),
                            error = error,
                            keyboardType = KeyboardType.Email,
                        )
                        FormField(
                            label = "Name",
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.arrive(2),
                        )
                        SecretField(
                            label = "Password",
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.arrive(3),
                            supportingText = if (sendInvitation) {
                                "Optional — the invitation email lets them choose their own."
                            } else {
                                "Required when no invitation is sent."
                            },
                        )
                        FormSwitch(
                            title = "Administrator",
                            checked = administrator,
                            onCheckedChange = { administrator = it },
                            modifier = Modifier.arrive(4),
                            description = "Full control of the server, every store and every user.",
                        )
                        FormSwitch(
                            title = "Send invitation email",
                            checked = sendInvitation,
                            onCheckedChange = { sendInvitation = it },
                            modifier = Modifier.arrive(5),
                            description = "Needs working SMTP settings on this server.",
                        )
                        refusal?.let {
                            Text(
                                text = it,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(6),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AnimatedVisibility(
                                visible = creating,
                                enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                                exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                    Spacer(Modifier.width(16.dp))
                                }
                            }
                            Button(
                                onClick = { onCreate(NewUser(email, name, password, administrator, sendInvitation)) },
                                enabled = !creating,
                            ) { Text("Create") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
