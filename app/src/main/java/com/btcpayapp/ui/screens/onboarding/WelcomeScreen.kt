package com.btcpayapp.ui.screens.onboarding

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
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btcpayapp.R
import com.btcpayapp.ui.LocalAppGraph
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.arrive

/**
 * First run.
 *
 * No view model: nothing here is asynchronous and nothing survives the screen.
 * The whole job is to say what the app is and send the user onward.
 *
 * Deliberately one button, with no QR entry point beside it. BTCPay does not
 * publish a "connect this app" code, so the only thing it could scan is a
 * server URL someone has already turned into a QR themselves, a path that
 * raises "what am I meant to scan?" far more often than it saves typing. The
 * scanner is for invoices, addresses and payouts, where a code is genuinely
 * what you have in front of you.
 */
@Composable
fun WelcomeScreen(onConnect: () -> Unit) {
    AppScreen(title = stringResource(R.string.app_name)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // The first screen anyone sees, so the blocks arrive in reading
            // order rather than all at once. It costs about a tenth of a
            // second in total and it is the difference between a page that
            // was drawn and a page that was laid out for you.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .arrive(0),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Storefront,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Connect your BTCPay Server",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                // Right under the title, the first thing read: the
                // name and the mark are the project's, so without this line a
                // user can take the app for the official one.
                Text(
                    text = "Unofficial app. Not made or endorsed by the BTCPay Server Foundation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The app talks only to your server. No sign-up, no analytics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // No dismiss: the flag ends at the next vault save, such as
            // connecting an account.
            if (LocalAppGraph.current.accounts.lost.collectAsStateWithLifecycle().value) LostAccountsNotice()

            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(1),
            ) {
                Text("Connect a server")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The vault could not be opened and was moved aside. The API keys it held are
 * still valid on their servers, so the user must revoke them there.
 */
@Composable
private fun LostAccountsNotice() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Saved accounts could not be opened", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "The app started with no accounts. Their API keys still work on your servers. " +
                        "Revoke them on each server (Account > API keys), then connect again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
