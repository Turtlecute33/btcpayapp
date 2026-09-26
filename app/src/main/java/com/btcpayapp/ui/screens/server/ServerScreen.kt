package com.btcpayapp.ui.screens.server
import kotlinx.coroutines.async

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.asApiException
import com.btcpayapp.data.api.dto.HealthData
import com.btcpayapp.data.api.dto.RoleData
import com.btcpayapp.data.api.dto.ServerInfoData
import com.btcpayapp.data.api.dto.ServerSyncStatus
import com.btcpayapp.data.api.endpoints.health
import com.btcpayapp.data.api.endpoints.serverInfo
import com.btcpayapp.data.api.endpoints.serverRoles
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AnimatedValue
import com.btcpayapp.ui.components.AppCard
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CopyableField
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.ErrorState
import com.btcpayapp.ui.components.LoadingState
import com.btcpayapp.ui.components.PulsingDot
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.nav.LightningRoute
import com.btcpayapp.ui.nav.PayoutProcessorsRoute
import com.btcpayapp.ui.nav.ServerEmailRoute
import com.btcpayapp.ui.nav.ServerUsersRoute
import com.btcpayapp.ui.theme.AppTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerState(
    val info: ServerInfoData? = null,
    val health: HealthData? = null,
    val roles: List<RoleData> = emptyList(),
    /** Kept apart from [error]: a non-admin key is refused roles but still sees the rest. */
    val rolesError: ApiException? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: ApiException? = null,
    val isAdmin: Boolean = false,
)

class ServerViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(
        // Seed from what the session already fetched, so the screen opens populated.
        ServerState(info = graph.session.serverInfo.value, isAdmin = graph.session.isServerAdmin),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.session.user.collect {
                _state.update { current -> current.copy(isAdmin = graph.session.isServerAdmin) }
            }
        }
        load(refreshing = false)
    }

    fun refresh() = load(refreshing = true)

    fun load(refreshing: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(loading = !refreshing && it.info == null, refreshing = refreshing, error = null)
            }

            val api = runCatching { graph.session.requireApi() }.getOrElse { failure ->
                _state.update { it.copy(loading = false, refreshing = false, error = failure.asApiException()) }
                return@launch
            }

            val infoCall = async { runCatching { api.serverInfo() } }
            val healthCall = async { runCatching { api.health() } }
            val rolesCall = async { runCatching { api.serverRoles() } }
            val info = infoCall.await()
            val healthResult = healthCall.await()
            val roles = rolesCall.await()

            _state.update { current ->
                current.copy(
                    loading = false,
                    refreshing = false,
                    info = info.getOrNull() ?: current.info,
                    health = healthResult.getOrNull() ?: current.health,
                    roles = roles.getOrNull() ?: emptyList(),
                    rolesError = roles.exceptionOrNull()?.asApiException(),
                    error = info.exceptionOrNull()?.asApiException(),
                )
            }
        }
    }

}

/** What the body is showing; a cheap discriminator, not the server info. */
private enum class ServerPhase { Loading, Error, Content }

/** The same, for the roles list nested inside it. */
private enum class RolesPhase { Refused, Failed, Empty, Content }

@Composable
fun ServerScreen(
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
) {
    val viewModel = appViewModel { ServerViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    AppScreen(
        title = "Server",
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
    ) { padding ->
        val phase = when {
            state.loading -> ServerPhase.Loading
            state.error != null && state.info == null && state.error !is ApiException.Forbidden ->
                ServerPhase.Error
            else -> ServerPhase.Content
        }

        AnimatedSwap(phase, label = "server") { shown ->
            when (shown) {
                // A spinner rather than a skeleton: what is coming is a page of
                // cards of several shapes, not a list of like rows.
                ServerPhase.Loading -> LoadingState(Modifier.padding(padding))

                ServerPhase.Error -> ErrorState(
                    error = state.error,
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::refresh,
                )

                ServerPhase.Content -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AnimatedVisibility(
                        visible = state.error is ApiException.Forbidden,
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        CalmNotice(
                            "This API key cannot read server information. Ask the instance administrator " +
                                "for a key with server permissions, or use this app for store work only.",
                        )
                    }

                    state.info?.let { info ->
                        Column(Modifier.arrive(0)) { ServerInfoSection(info = info, health = state.health) }
                    }

                    state.info?.onion?.takeIf { it.isNotBlank() }?.let { onion ->
                        Column(Modifier.arrive(1)) { OnionSection(onion) }
                    }

                    Column(Modifier.arrive(2)) {
                        RolesSection(roles = state.roles, error = state.rolesError)
                    }

                    SectionHeader("Administration", Modifier.arrive(3))
                    // Admin status is resolved after the page is already on screen,
                    // so this notice usually arrives rather than being there.
                    AnimatedVisibility(
                        visible = !state.isAdmin,
                        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        CalmNotice("These screens belong to the instance administrator. Your account is not one.")
                    }
                    MenuRow(
                        icon = Icons.Rounded.Group,
                        title = "Users",
                        subtitle = "Approve, lock and remove accounts",
                        enabled = state.isAdmin,
                        onClick = { onNavigate(ServerUsersRoute) },
                    )
                    ThinDivider()
                    MenuRow(
                        icon = Icons.Rounded.Email,
                        title = "Email",
                        subtitle = "SMTP settings used for server notices",
                        enabled = state.isAdmin,
                        onClick = { onNavigate(ServerEmailRoute) },
                    )
                    ThinDivider()
                    MenuRow(
                        icon = Icons.Rounded.Payments,
                        title = "Payout processors",
                        subtitle = "Send approved payouts automatically",
                        enabled = state.isAdmin,
                        onClick = { onNavigate(PayoutProcessorsRoute) },
                    )
                    ThinDivider()
                    MenuRow(
                        icon = Icons.Rounded.Bolt,
                        title = "Internal Lightning node",
                        subtitle = "The node this instance runs itself",
                        enabled = state.isAdmin,
                        onClick = { onNavigate(LightningRoute(cryptoCode = "BTC", serverNode = true)) },
                    )

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerInfoSection(info: ServerInfoData, health: HealthData?) {
    SectionHeader("This instance")
    AppCard {
        Column(Modifier.padding(vertical = 8.dp)) {
            DetailRow(label = "Version", value = info.version.ifBlank { "unknown" })
            DetailRow(
                label = "Fully synchronised",
                value = if (info.fullySynched) "Yes" else "Not yet",
                valueColor = if (info.fullySynched) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (health != null) {
                DetailRow(
                    label = "Health check",
                    value = if (health.synchronized) "Passing" else "Reporting a problem",
                    valueColor = if (health.synchronized) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }

    if (info.supportedPaymentMethods.isNotEmpty()) {
        SectionHeader("Supported payment methods")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            info.supportedPaymentMethods.forEach { method ->
                StatusPill(
                    label = method,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (info.syncStatus.isNotEmpty()) {
        SectionHeader("Chain synchronisation")
        info.syncStatus.forEach { status -> SyncStatusCard(status) }
    }
}

@Composable
private fun SyncStatusCard(status: ServerSyncStatus) {
    val colors = AppTheme.statusColors
    val node = status.nodeInformation
    // Prefer the NBXplorer heights; fall back to the node's own view when the
    // indexer has not reported one yet.
    val synced = status.syncHeight ?: node?.blocks
    val target = status.chainHeight.takeIf { it > 0 } ?: node?.headers ?: 0
    val fraction = if (target > 0 && synced != null) (synced.toFloat() / target).coerceIn(0f, 1f) else 0f
    val syncing = target > 0 && (synced ?: 0) < target

    // The bar is driven by a height that arrives in jumps of several hundred
    // blocks, so it is animated to the new figure rather than redrawn at it.
    val progress by animateFloatAsState(fraction, Motion.spatialSlow, label = "sync")

    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status.paymentMethodId.ifBlank { "Unknown chain" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                // A chain that is still catching up is the one thing on this
                // page that is changing while it is read. A beating dot says so
                // in a way "Block 812,000 of 812,400" cannot.
                if (syncing) {
                    PulsingDot(color = colors.pending)
                    Spacer(Modifier.width(8.dp))
                }
                StatusPill(
                    label = if (status.available) "Available" else "Unavailable",
                    container = if (status.available) colors.settled else colors.invalid,
                    content = if (status.available) colors.onSettled else colors.onInvalid,
                )
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // A block height only ever rises, so the roll is always upward.
            AnimatedValue(
                value = "Block ${synced ?: 0} of $target",
                upward = true,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (node != null) {
                Spacer(Modifier.height(8.dp))
                // Two lines allowed, unlike the block line above it. This is a
                // sentence with three figures in it, not a figure: on a narrow
                // phone, or at a raised font scale, clamping it to one line
                // ellipsises away the header count and the percentage — which
                // is most of what it was put there to say.
                AnimatedValue(
                    value = "Node: ${node.blocks} blocks, ${node.headers} headers, " +
                        "%.2f%% verified".format(node.verificationProgress * 100),
                    upward = true,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun OnionSection(onion: String) {
    SectionHeader("Tor address")
    AppCard {
        Column(Modifier.padding(16.dp)) {
            CopyableField(label = "Onion endpoint", value = onion, truncate = true)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The same instance, reachable as a hidden service. Connecting to it needs Tor " +
                    "running on this device — it will not resolve over the ordinary network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RolesSection(roles: List<RoleData>, error: ApiException?) {
    val phase = when {
        error is ApiException.Forbidden -> RolesPhase.Refused
        error != null -> RolesPhase.Failed
        roles.isEmpty() -> RolesPhase.Empty
        else -> RolesPhase.Content
    }

    SectionHeader("Roles")
    AnimatedSwap(phase, label = "roles") { shown ->
        when (shown) {
            RolesPhase.Refused -> CalmNotice("Reading the role list needs a server administrator key.")

            RolesPhase.Failed -> CalmNotice(error?.userMessage.orEmpty())

            RolesPhase.Empty -> CalmNotice("This server reports no roles.")

            RolesPhase.Content -> Column {
                roles.forEachIndexed { index, role ->
                    AppCard(modifier = Modifier.arrive(index)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = role.role.ifBlank { role.id },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (role.isServerRole) {
                                    StatusPill(
                                        label = "Server",
                                        container = MaterialTheme.colorScheme.tertiaryContainer,
                                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                            if (role.permissions.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = role.permissions.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A refusal or an absence stated plainly — not dressed up as a failure. */
@Composable
private fun CalmNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (enabled) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.Lock,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
