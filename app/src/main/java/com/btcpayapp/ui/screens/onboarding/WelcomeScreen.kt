package com.btcpayapp.ui.screens.onboarding

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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LockPerson
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.btcpayapp.R
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.arrive

/**
 * First run.
 *
 * No view model: nothing here is asynchronous and nothing survives the screen.
 * The whole job is to explain what the app is about to connect to and send the
 * user onward.
 *
 * Deliberately one button, with no QR entry point beside it. BTCPay does not
 * publish a "connect this app" code, so the only thing it could scan is a
 * server URL someone has already turned into a QR themselves — a path that
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
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "This app talks to a BTCPay Server instance you control — your own box, " +
                        "or one a host runs for you. There is no service in between: no account to " +
                        "create here, no analytics, and no third party that sees your takings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Feature(
                icon = Icons.Rounded.LockPerson,
                title = "Your password never reaches this app",
                description = "Pairing happens in your browser, on your server's own page. " +
                    "The server hands back a scoped API key, which is all the app ever stores — " +
                    "and which you can revoke server-side at any time.",
                modifier = Modifier.arrive(1),
            )
            Feature(
                icon = Icons.Rounded.Bolt,
                title = "Invoices, wallet and Lightning",
                description = "Take payments at a stall, watch settlements arrive, and manage " +
                    "payouts from the same key.",
                modifier = Modifier.arrive(2),
            )
            Feature(
                icon = Icons.Rounded.Shield,
                title = "Self-signed and Tor instances welcome",
                description = "A private certificate can be pinned for this account alone, and " +
                    "an .onion address is routed through Orbot.",
                modifier = Modifier.arrive(3),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(4),
            ) {
                Text("Connect a server")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Feature(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
