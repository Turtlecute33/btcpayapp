package com.btcpayapp.ui.screens.accounts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.data.model.Account
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.components.StatusPill
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.pressScale
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountsState(
    val accounts: List<Account> = emptyList(),
    val activeId: String? = null,
)

class AccountsViewModel(private val graph: AppGraph) : ViewModel() {

    val state: StateFlow<AccountsState> = graph.accounts.vault
        .map { vault ->
            // `activeAccount` falls back to the first entry, so the tick always
            // marks the account the session is actually using.
            AccountsState(accounts = vault.accounts, activeId = vault.activeAccount?.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AccountsState(graph.accounts.vault.value.accounts, graph.accounts.vault.value.activeAccount?.id))

    fun setActive(id: String) {
        viewModelScope.launch { graph.session.selectAccount(id) }
    }
}

@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenAccount: (String) -> Unit,
) {
    val viewModel = appViewModel { AccountsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val activate: (Account) -> Unit = { account ->
        viewModel.setActive(account.id)
        scope.launch { snackbarHostState.showSnackbar("Now using ${account.label}") }
    }

    AppScreen(
        title = "Accounts",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAccount,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedSwap(state.accounts.isEmpty(), label = "accounts") { empty ->
                if (empty) {
                    EmptyState(
                        title = "No servers connected",
                        description = "Connect a BTCPay Server to start taking payments.",
                        icon = Icons.Rounded.Dns,
                        actionLabel = "Connect a server",
                        onAction = onAddAccount,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.accounts, key = { it.id }) { account ->
                            // Row and divider travel as one, so a removed
                            // account does not leave its rule behind for a
                            // frame.
                            Column(Modifier.animateItem()) {
                                AccountRow(
                                    account = account,
                                    active = account.id == state.activeId,
                                    onClick = { onOpenAccount(account.id) },
                                    onActivate = { activate(account) },
                                )
                                ThinDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    active: Boolean,
    onClick: () -> Unit,
    onActivate: () -> Unit,
) {
    // One interaction source for both, so the row shrinks under a finger and
    // the long press that switches account keeps working unchanged.
    val interactions = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .combinedClickable(
                interactionSource = interactions,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onActivate,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = account.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            account.userEmail?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }

            AnimatedVisibility(
                visible = active || account.usesPinnedCertificate || account.isOnion,
                enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
                exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
            ) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The badge is the only confirmation that a long press
                    // landed, so it grows into the row rather than appearing
                    // between two frames.
                    AnimatedVisibility(
                        visible = active,
                        enter = expandHorizontally(Motion.spatialSize) + fadeIn(Motion.effects),
                        exit = shrinkHorizontally(Motion.spatialSize) + fadeOut(Motion.effectsFast),
                    ) {
                        StatusPill(
                            label = "Active",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (account.usesPinnedCertificate) {
                        StatusPill(
                            label = "Pinned certificate",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (account.isOnion) {
                        StatusPill(
                            label = "Tor",
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onActivate, enabled = !active) {
            Icon(
                imageVector = if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (active) "Active account" else "Use this account",
                modifier = Modifier.size(22.dp),
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
