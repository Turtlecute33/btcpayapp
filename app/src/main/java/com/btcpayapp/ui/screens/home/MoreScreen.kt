package com.btcpayapp.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.btcpayapp.ui.LocalAppGraph
import com.btcpayapp.ui.components.chainSubtitle
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.SectionHeader
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import com.btcpayapp.ui.components.pressScale
import com.btcpayapp.ui.nav.AboutRoute
import com.btcpayapp.ui.nav.AccountsRoute
import com.btcpayapp.ui.nav.AppSettingsRoute
import com.btcpayapp.ui.nav.AppsRoute
import com.btcpayapp.ui.nav.LightningRoute
import com.btcpayapp.ui.nav.NotificationsRoute
import com.btcpayapp.ui.nav.PaymentMethodsRoute
import com.btcpayapp.ui.nav.PaymentRequestsRoute
import com.btcpayapp.ui.nav.PayoutProcessorsRoute
import com.btcpayapp.ui.nav.PayoutsRoute
import com.btcpayapp.ui.nav.PullPaymentsRoute
import com.btcpayapp.ui.nav.ServerRoute
import com.btcpayapp.ui.nav.StoreEmailRoute
import com.btcpayapp.ui.nav.StoreRatesRoute
import com.btcpayapp.ui.nav.StoreSettingsRoute
import com.btcpayapp.ui.nav.StoreUsersRoute
import com.btcpayapp.ui.nav.WebhooksRoute

/**
 * Everything that does not deserve a tab.
 *
 * Store rows the connected key cannot use are hidden rather than shown
 * disabled — an operator with a read-only key should not be looking at a list
 * of things that will 403. Each row names the policy its screen needs, and the
 * check follows the server's policy tree, so a store owner's
 * `canmodifystoresettings` shows them all. A key whose grant the app cannot
 * read shows every row and lets the server decide. The Server section is for
 * server admins only.
 */
@Composable
fun MoreScreen(onNavigate: (Any) -> Unit) {
    val graph = LocalAppGraph.current
    val store by graph.session.activeStore.collectAsStateWithLifecycle()
    val user by graph.session.user.collectAsStateWithLifecycle()
    val serverInfo by graph.session.serverInfo.collectAsStateWithLifecycle()
    val lightningCodes = graph.session.lightningCryptoCodes
    val storeId = store?.id
    val can = { policy: String -> graph.session.hasPermission("btcpay.store.$policy", storeId) }

    AppScreen(title = "More", large = true) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Each group arrives a beat after the one above it. The `Column`
            // wrappers carry nothing but that entrance — they leave the layout
            // exactly as it was — and the stagger is what stops a screen that
            // is nothing but rows from appearing as one undifferentiated wall.
            val paymentRequests = can("canviewpaymentrequests")
            val apps = can("canviewstoresettings")
            if (paymentRequests || apps) {
                Column(Modifier.arrive(0)) {
                    SectionHeader("Money in")
                    if (paymentRequests) {
                        MenuRow("Payment requests", Icons.Rounded.RequestQuote) { onNavigate(PaymentRequestsRoute) }
                    }
                    if (apps) {
                        MenuRow("Point of sale and crowdfunds", Icons.Rounded.Apps) { onNavigate(AppsRoute) }
                    }
                }
            }

            val pullPayments = can("canviewpullpayments")
            val payouts = can("canviewpayouts")
            val processors = can("canmodifystoresettings")
            if (pullPayments || payouts || processors) {
                Column(Modifier.arrive(1)) {
                    SectionHeader("Money out")
                    if (pullPayments) {
                        MenuRow("Pull payments", Icons.Rounded.Receipt) { onNavigate(PullPaymentsRoute) }
                    }
                    if (payouts) {
                        MenuRow("Payouts", Icons.Rounded.Payments) { onNavigate(PayoutsRoute) }
                    }
                    if (processors) {
                        MenuRow(
                            title = "Automated payouts",
                            icon = Icons.Rounded.Tune,
                            subtitle = "Let the server send approved payouts on its own",
                        ) { onNavigate(PayoutProcessorsRoute) }
                    }
                }
            }

            // The node screen reads the node's info and balance.
            if (lightningCodes.isNotEmpty() && can("canuselightningnode")) {
                Column(Modifier.arrive(2)) {
                    SectionHeader("Lightning")
                    lightningCodes.forEach { code ->
                        MenuRow(chainSubtitle(code, prefix = "Lightning node") ?: "Lightning node", Icons.Rounded.Bolt) {
                            onNavigate(LightningRoute(cryptoCode = code, serverNode = false))
                        }
                    }
                }
            }

            // Settings and rates can be read with the view policy (the server
            // refuses a save). Payment methods, users and email are read
            // through routes that need `canmodifystoresettings`.
            val storeSettings = can("canviewstoresettings")
            val manageStore = can("canmodifystoresettings")
            val webhooks = can("webhooks.canmodifywebhooks")
            if (storeSettings || manageStore || webhooks) {
                Column(Modifier.arrive(3)) {
                    SectionHeader(store?.name ?: "Store")
                    if (storeSettings) {
                        MenuRow("Store settings", Icons.Rounded.Storefront) { onNavigate(StoreSettingsRoute) }
                    }
                    if (manageStore) {
                        MenuRow("Payment methods", Icons.Rounded.CurrencyExchange) { onNavigate(PaymentMethodsRoute) }
                    }
                    if (storeSettings) {
                        MenuRow("Rates", Icons.Rounded.CurrencyExchange) { onNavigate(StoreRatesRoute) }
                    }
                    if (manageStore) {
                        MenuRow("Users and roles", Icons.Rounded.Group) { onNavigate(StoreUsersRoute) }
                        MenuRow("Email", Icons.Rounded.AlternateEmail) { onNavigate(StoreEmailRoute) }
                    }
                    if (webhooks) {
                        MenuRow("Webhooks", Icons.Rounded.Webhook) { onNavigate(WebhooksRoute) }
                    }
                }
            }

            if (user?.isAdmin == true) {
                Column(Modifier.arrive(4)) {
                    SectionHeader("Server")
                    MenuRow(
                        title = "Server settings",
                        icon = Icons.Rounded.Dns,
                        subtitle = serverInfo?.version?.let { "BTCPay $it" },
                    ) { onNavigate(ServerRoute) }
                }
            }

            Column(Modifier.arrive(5)) {
                SectionHeader("App")
                MenuRow("Notifications", Icons.Rounded.Notifications) { onNavigate(NotificationsRoute) }
                MenuRow(
                    title = "Connected servers",
                    icon = Icons.Rounded.AccountCircle,
                    subtitle = user?.email,
                ) { onNavigate(AccountsRoute) }
                MenuRow("Settings", Icons.Rounded.Settings) { onNavigate(AppSettingsRoute) }
                MenuRow("About", Icons.Rounded.Info) { onNavigate(AboutRoute) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    // A full-width row is too large a target for the ripple alone to answer
    // for: a tap near either end leaves the ink so far from the finger that it
    // reads as nothing having happened. The row shrinks slightly instead, and
    // the divider below it stays put, so the row reads as the one thing pressed.
    val interactions = remember { MutableInteractionSource() }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(interactions)
                .clickable(interactionSource = interactions, indication = ripple(), onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ThinDivider(Modifier.padding(start = 54.dp))
    }
}
