package com.btcpayapp.ui.screens.settings
import com.btcpayapp.core.util.safeStartActivity

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btcpayapp.AppGraph
import com.btcpayapp.BuildConfig
import com.btcpayapp.R
import com.btcpayapp.core.lightning.NodeDirectory
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.DetailRow
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.launch

class AboutViewModel(graph: AppGraph) : ViewModel() {
    val serverInfo = graph.session.serverInfo
    val activeAccount = graph.session.activeAccount
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { AboutViewModel(it) }
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val account by viewModel.activeAccount.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val open: (String) -> Unit = { url ->
        val opened = runCatching {
            context.safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!opened) {
            scope.launch { snackbarHostState.showSnackbar("No browser is available to open that link.") }
        }
    }

    AppScreen(title = "About", onBack = onBack, snackbarHostState = snackbarHostState) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .arrive(0),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "An unofficial client for BTCPay Server's Greenfield API.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Nothing here is waiting on the network, so the stagger is the
            // only thing that stops a wall of claims from landing as one block.
            SectionHeader("What this app does not do", Modifier.arrive(1))
            Claim("No analytics, no telemetry, no crash reporting. Nothing is measured and " +
                "nothing is sent anywhere.", Modifier.arrive(2))
            Claim("No Google Play services and no push service. Payment alerts come from polling " +
                "your own server, so no third party learns when you are paid.", Modifier.arrive(3))
            // "Where the phone has it": the Keystore falls back from StrongBox to
            // the TEE, and to software on some devices, without telling the app.
            // An unconditional "secure element" would promise more
            // than many phones give.
            Claim("Credentials are sealed with an Android Keystore key, held in secure hardware " +
                "where the phone has it. A copy of the app's storage is useless without this " +
                "phone.", Modifier.arrive(4))
            Claim("User-installed certificate authorities are not trusted. An MDM profile or a " +
                "sideloaded root cannot intercept the connection; a private server certificate " +
                "is pinned per account instead.", Modifier.arrive(5))
            Claim("The only network traffic is to the server you configured. There is no " +
                "update check, no rate feed and no analytics endpoint.", Modifier.arrive(6))

            ThinDivider()
            SectionHeader("Connected server", Modifier.arrive(7))
            if (account == null) {
                Text(
                    text = "No server is connected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).arrive(8),
                )
            } else {
                DetailRow(
                    label = "Address",
                    value = account?.host.orEmpty(),
                    modifier = Modifier.arrive(8),
                    monospace = true,
                )
                DetailRow(
                    label = "BTCPay Server",
                    value = serverInfo?.version
                        ?: account?.serverVersion
                        ?: "Not reported yet",
                    modifier = Modifier.arrive(8),
                )
                serverInfo?.let { info ->
                    DetailRow(
                        label = "Chain",
                        value = if (info.fullySynched) "Fully synchronised" else "Catching up",
                        modifier = Modifier.arrive(8),
                    )
                }
            }

            ThinDivider()
            SectionHeader("Lightning peer names", Modifier.arrive(8))
            DetailRow(
                label = "Bundled directory",
                value = "%,d nodes".format(NodeDirectory.size),
                modifier = Modifier.arrive(8),
            )
            NodeDirectory.generatedAt?.let { captured ->
                DetailRow(
                    label = "Captured",
                    value = captured.toString(),
                    modifier = Modifier.arrive(8),
                )
            }
            Text(
                text = "Channel peers are named from this offline snapshot and from the nicknames " +
                    "you set. No pubkey is ever sent to an explorer, which is also why the " +
                    "snapshot ages between releases.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).arrive(8),
            )

            ThinDivider()
            SectionHeader("Links", Modifier.arrive(8))
            LinkOut("BTCPay Server", "btcpayserver.org", "https://btcpayserver.org/", open, Modifier.arrive(8))
            LinkOut(
                title = "Documentation",
                subtitle = "docs.btcpayserver.org",
                url = "https://docs.btcpayserver.org/",
                open = open,
                modifier = Modifier.arrive(8),
            )
            LinkOut(
                title = "Greenfield API reference",
                subtitle = "The API this app speaks",
                url = "https://docs.btcpayserver.org/API/Greenfield/v1/",
                open = open,
                modifier = Modifier.arrive(8),
            )
            LinkOut(
                title = "Create an API key",
                subtitle = "How permissions and scoping work",
                url = "https://docs.btcpayserver.org/API/Greenfield/v1/#section/Authentication",
                open = open,
                modifier = Modifier.arrive(8),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Claim(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkOut(
    title: String,
    subtitle: String,
    url: String,
    open: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { open(url) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
