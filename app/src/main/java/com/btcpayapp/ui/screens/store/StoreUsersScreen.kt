package com.btcpayapp.ui.screens.store
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.ServerVersion
import com.btcpayapp.data.api.dto.RoleData
import com.btcpayapp.data.api.dto.StoreInvitationData
import com.btcpayapp.data.api.dto.StoreUserData
import com.btcpayapp.data.api.dto.StoreUserRequest
import com.btcpayapp.data.api.endpoints.addStoreUser
import com.btcpayapp.data.api.endpoints.removeStoreUser
import com.btcpayapp.data.api.endpoints.storeInvitations
import com.btcpayapp.data.api.endpoints.storeRoles
import com.btcpayapp.data.api.endpoints.storeUsers
import com.btcpayapp.data.api.endpoints.updateStoreUser
import com.btcpayapp.data.session.Permissions
import com.btcpayapp.data.session.StoreBinding
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormField
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.SkeletonList
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.rememberSpendGate
import com.btcpayapp.ui.components.afterSpendGate
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Who can reach this store, and with which role.
 *
 * `GET /stores/{id}/roles` returns a bare array even though the reference docs
 * describe an object, so it is decoded as a list.
 */
/** Used when the roles route is missing or unreadable, so adding a user still works. */
private val BUILT_IN_ROLES = listOf(
    RoleData(id = "Owner", role = "Owner"),
    RoleData(id = "Manager", role = "Manager"),
    RoleData(id = "Employee", role = "Employee"),
    RoleData(id = "Guest", role = "Guest"),
)

/**
 * The store policies that let a user move funds or change where they go:
 * store settings (wallets, users), the Lightning node, payouts, pull payments
 * that pay without approval, and on 2.4 wallet settings and signing.
 * [Permissions.covers] reads the policy tree, so a parent such as
 * canmanagewallets counts too.
 */
private val CONTROL_POLICIES = listOf(
    "btcpay.store.canmodifystoresettings",
    "btcpay.store.canuselightningnode",
    "btcpay.store.canmanagepayouts",
    "btcpay.store.cancreatepullpayments",
    "btcpay.store.canmanagewalletsettings",
    "btcpay.store.cansigntransactions",
)

/**
 * True when [roleId] gives control of the store: Owner, or a role with any of
 * [CONTROL_POLICIES]. A role whose permissions this app cannot read (the roles
 * list did not load) counts too, as it may be any of them.
 */
internal fun roleGivesControl(roleId: String, roles: List<RoleData>): Boolean {
    if (roleId.equals("Owner", ignoreCase = true)) return true
    val permissions = roles.firstOrNull { it.id.equals(roleId, ignoreCase = true) }?.permissions
    // covers() answers true for a null or empty grant.
    return CONTROL_POLICIES.any { Permissions.covers(permissions, it) }
}

/**
 * A role change that gives someone control of the store: they could then
 * spend from it or point its payments at their own wallet. It waits for a
 * confirmation and the spend gate.
 */
sealed interface OwnerGrant {
    val role: String
    val who: String

    data class Add(val idOrEmail: String, override val role: String) : OwnerGrant {
        override val who: String get() = idOrEmail.trim()
    }

    data class Change(val user: StoreUserData, override val role: String) : OwnerGrant {
        override val who: String get() = user.email.ifBlank { user.id }
    }
}

data class StoreUsersState(
    val users: List<StoreUserData> = emptyList(),
    val invitations: List<StoreInvitationData> = emptyList(),
    val roles: List<RoleData> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val error: ApiException? = null,
    val message: String? = null,
    val showAdd: Boolean = false,
    val invitationLink: String? = null,
    val editing: StoreUserData? = null,
    val removing: StoreUserData? = null,
    val grant: OwnerGrant? = null,
)

class StoreUsersViewModel(private val graph: AppGraph) : ViewModel() {

    private val bound = StoreBinding(graph.session)
    private val _state = MutableStateFlow(StoreUsersState())
    val state = _state.asStateFlow()

    /** For the confirmation that names the store an owner is added to. */
    val storeName: String get() = bound.name

    init {
        load()
        bound.retryWhenKnown(viewModelScope) { load() }
    }

    fun load(refreshing: Boolean = false) {
        val store = bound.id
        if (store == null) {
            _state.update { it.copy(loading = false, error = ApiException.NoAccount()) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.users.isEmpty(), refreshing = refreshing, error = null)
            }
            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(loading = false, refreshing = false, error = failure.asApi()) }
                return@launch
            }

            val usersCall = async { runCatching { api.storeUsers(store) } }
            val rolesCall = async { runCatching { api.storeRoles(store) } }
            // The invitations route came with 2.4.4, so it is not asked for
            // before; a failure there must not blank the user list either.
            val invitesCall = if (graph.session.serverAtLeast(ServerVersion.STORE_INVITATIONS)) {
                async { runCatching { api.storeInvitations(store) } }
            } else {
                null
            }

            val users = usersCall.await()
            val roles = rolesCall.await()
            val invitations = invitesCall?.await()?.getOrNull().orEmpty()
            _state.update {
                it.copy(
                    users = users.getOrDefault(it.users),
                    roles = roles.getOrNull()?.takeIf { list -> list.isNotEmpty() } ?: BUILT_IN_ROLES,
                    invitations = invitations,
                    loading = false,
                    refreshing = false,
                    error = users.exceptionOrNull()?.asApi(),
                )
            }
        }
    }

    fun refresh() = load(refreshing = true)

    fun showAdd(show: Boolean) = _state.update { it.copy(showAdd = show) }

    fun edit(user: StoreUserData?) = _state.update { it.copy(editing = user) }

    fun askRemove(user: StoreUserData?) = _state.update { it.copy(removing = user) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun dismissInvitation() = _state.update { it.copy(invitationLink = null) }

    fun dismissGrant() = _state.update { it.copy(grant = null) }

    /** Adds the user, or first asks for a confirmation when [role] gives control of the store. */
    fun requestAdd(idOrEmail: String, role: String) {
        if (givesControl(role)) _state.update { it.copy(grant = OwnerGrant.Add(idOrEmail, role)) } else add(idOrEmail, role)
    }

    /** As [requestAdd], for a role change. Keeping the same role sends nothing. */
    fun requestRole(user: StoreUserData, role: String) {
        when {
            role.equals(user.roleId, ignoreCase = true) -> edit(null)
            givesControl(role) -> _state.update { it.copy(editing = null, grant = OwnerGrant.Change(user, role)) }
            else -> changeRole(user, role)
        }
    }

    /** Call only after the spend gate. */
    fun grant(grant: OwnerGrant) = when (grant) {
        is OwnerGrant.Add -> add(grant.idOrEmail, grant.role)
        is OwnerGrant.Change -> changeRole(grant.user, grant.role)
    }

    private fun givesControl(roleId: String): Boolean = roleGivesControl(roleId, _state.value.roles)

    private fun add(idOrEmail: String, role: String) {
        if (_state.value.busy) return
        val store = bound.id ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                graph.session.requireApi().addStoreUser(
                    storeId = store,
                    request = StoreUserRequest(
                        id = idOrEmail.trim(),
                        storeRole = role,
                        requireInvitation = true,
                    ),
                )
            }.onSuccess { result ->
                val link = result.storeInvitation?.link
                _state.update {
                    it.copy(
                        busy = false,
                        showAdd = false,
                        // Without SMTP configured the server sends nothing, so
                        // this link is the only copy that will ever exist.
                        invitationLink = link,
                        message = if (link == null) "User added." else null,
                    )
                }
                load(refreshing = true)
            }.onFailure { failure ->
                _state.update { it.copy(busy = false, error = failure.asApi()) }
            }
        }
    }

    private fun changeRole(user: StoreUserData, role: String) {
        val store = bound.id ?: return
        _state.update { it.copy(editing = null) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                graph.session.requireApi().updateStoreUser(
                    storeId = store,
                    request = StoreUserRequest(id = user.identifier(), storeRole = role),
                )
            }.onSuccess {
                _state.update { it.copy(busy = false, message = "Role updated.") }
                load(refreshing = true)
            }.onFailure { failure ->
                _state.update { it.copy(busy = false, error = failure.asApi()) }
            }
        }
    }

    fun remove(user: StoreUserData) {
        val store = bound.id ?: return
        _state.update { it.copy(removing = null) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { graph.session.requireApi().removeStoreUser(store, user.identifier()) }
                .onSuccess {
                    _state.update { it.copy(busy = false, message = "User removed.") }
                    load(refreshing = true)
                }
                .onFailure { failure -> _state.update { it.copy(busy = false, error = failure.asApi()) } }
        }
    }

    /**
     * The two 409s this endpoint family returns are perfectly clear to the
     * server and meaningless to a merchant, so they are rewritten here.
     */
    private fun Throwable.asApi(): ApiException = when {
        this is ApiException.Server && code == "already-store-user" -> ApiException.Server(
            status = status,
            code = code,
            message = "That person already has access to this store. Change their role instead.",
        )

        this is ApiException.Server && code == "store-user-role-orphaned" -> ApiException.Server(
            status = status,
            code = code,
            message = "A store must keep at least one owner. Give the owner role to somebody " +
                "else before changing or removing this one.",
        )

        this is ApiException -> this
        else -> ApiException.Transport(message ?: "Unexpected failure")
    }
}

private fun StoreUserData.identifier(): String = id.ifBlank { email }

/** What the body is showing; see the note on [AnimatedSwap] about discriminators. */
private enum class StoreUsersPhase { Loading, Error, Empty, Content }

@Composable
fun StoreUsersScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { StoreUsersViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val gate = rememberSpendGate()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AppScreen(
        title = "Users",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showAdd(true) },
                icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        val phase = when {
            state.loading -> StoreUsersPhase.Loading
            state.error != null && state.users.isEmpty() -> StoreUsersPhase.Error
            state.users.isEmpty() && state.invitations.isEmpty() -> StoreUsersPhase.Empty
            else -> StoreUsersPhase.Content
        }

        AnimatedSwap(phase, label = "storeUsers") { shown ->
            when (shown) {
                StoreUsersPhase.Loading -> SkeletonList(Modifier.padding(padding), rows = 4)

                StoreUsersPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::refresh,
                )

                StoreUsersPhase.Empty -> EmptyState(
                    title = "Nobody else has access",
                    description = "Add a user by email to share this store.",
                    icon = Icons.Rounded.Group,
                    modifier = Modifier.padding(padding),
                    actionLabel = "Add a user",
                    onAction = { viewModel.showAdd(true) },
                )

                StoreUsersPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ErrorBanner(state.error, onDismiss = viewModel::dismissError)

                    SectionHeader("Users", Modifier.arrive(0))
                    state.users.forEachIndexed { index, user ->
                        Column(Modifier.arrive(index + 1)) {
                            StoreUserRow(
                                user = user,
                                roleLabel = state.roles.labelFor(user.roleId),
                                enabled = !state.busy,
                                onClick = { viewModel.edit(user) },
                                onRemove = { viewModel.askRemove(user) },
                            )
                            ThinDivider()
                        }
                    }

                    // The whole section appears the moment an invitation is
                    // created and disappears the moment it is accepted, both
                    // while the screen is being looked at.
                    AnimatedVisibility(
                        visible = state.invitations.isNotEmpty(),
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        Column {
                            SectionHeader("Pending invitations")
                            state.invitations.forEach { invitation ->
                                StoreInvitationRow(
                                    invitation = invitation,
                                    roleLabel = state.roles.labelFor(invitation.roleId),
                                )
                                ThinDivider()
                            }
                        }
                    }

                    Spacer(Modifier.height(96.dp))
                }
            }
        }
    }

    if (state.showAdd) {
        AddStoreUserDialog(
            roles = state.roles,
            busy = state.busy,
            onAdd = viewModel::requestAdd,
            onDismiss = { viewModel.showAdd(false) },
        )
    }

    state.editing?.let { user ->
        ChangeRoleDialog(
            user = user,
            roles = state.roles,
            onSelect = { viewModel.requestRole(user, it) },
            onDismiss = { viewModel.edit(null) },
        )
    }

    state.removing?.let { user ->
        ConfirmDialog(
            title = "Remove ${user.email.ifBlank { user.id }}?",
            message = "They lose access to this store immediately. Their invoices and payouts stay.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { viewModel.remove(user) },
            onDismiss = { viewModel.askRemove(null) },
        )
    }

    state.invitationLink?.let { link ->
        InvitationDialog(link = link, onDismiss = viewModel::dismissInvitation)
    }

    state.grant?.let { grant ->
        ConfirmDialog(
            title = "Give ${grant.who} the ${state.roles.labelFor(grant.role)} role?",
            message = "With this role they may be able to move funds out of ${viewModel.storeName} " +
                "or change where it receives payments.",
            confirmLabel = "Give role",
            destructive = true,
            onConfirm = {
                viewModel.dismissGrant()
                scope.afterSpendGate(gate, "Confirm store access", grant.who, { snackbarHostState.showSnackbar(it) }) { viewModel.grant(grant) }
            },
            onDismiss = viewModel::dismissGrant,
        )
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

@Composable
private fun StoreUserRow(
    user: StoreUserData,
    roleLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = user.email.ifBlank { user.id },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            StatusPill(
                label = roleLabel,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Rounded.PersonRemove, contentDescription = "Remove user")
        }
    }
}

@Composable
private fun StoreInvitationRow(invitation: StoreInvitationData, roleLabel: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = invitation.userEmail.ifBlank { invitation.userId },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                label = roleLabel,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = if (invitation.isExpired) "Expired" else "Awaiting acceptance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddStoreUserDialog(
    roles: List<RoleData>,
    busy: Boolean,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var idOrEmail by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(roles.defaultRoleId()) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add a user") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FormField(
                    label = "Email or user id",
                    value = idOrEmail,
                    onValueChange = { idOrEmail = it },
                    enabled = !busy,
                    keyboardType = KeyboardType.Email,
                )
                FormDropdown(
                    label = "Role",
                    options = roles.map { it.id },
                    selected = role,
                    onSelect = { role = it },
                    enabled = !busy,
                    optionLabel = { id -> roles.labelFor(id) },
                    supportingText = "An invitation is created; share the link if the server " +
                        "cannot send email.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(idOrEmail, role) },
                enabled = !busy && idOrEmail.isNotBlank() && role.isNotBlank(),
            ) {
                AnimatedSwap(busy, label = "add") { working ->
                    if (working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Add")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun ChangeRoleDialog(
    user: StoreUserData,
    roles: List<RoleData>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var role by rememberSaveable(user.id) { mutableStateOf(user.roleId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(user.email.ifBlank { user.id }) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FormDropdown(
                    label = "Role",
                    options = roles.map { it.id },
                    selected = role,
                    onSelect = { role = it },
                    optionLabel = { id -> roles.labelFor(id) },
                )
                roles.firstOrNull { it.id == role }?.permissions?.takeIf { it.isNotEmpty() }?.let { permissions ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = permissions.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(role) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InvitationDialog(link: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send this invitation") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "The user has been added but still has to accept. If this server has " +
                        "no email configured, nothing was sent — this link is the only way they " +
                        "will receive the invitation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                CopyableField(label = "Invitation link", value = link, sensitive = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    context.safeStartActivity(Intent.createChooser(intent, "Send the invitation"))
                },
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun List<RoleData>.labelFor(roleId: String): String =
    firstOrNull { it.id.equals(roleId, ignoreCase = true) }?.role?.takeIf { it.isNotBlank() }
        ?: roleId.ifBlank { "No role" }

private fun List<RoleData>.defaultRoleId(): String =
    firstOrNull { it.id.equals("Guest", ignoreCase = true) }?.id ?: lastOrNull()?.id.orEmpty()
