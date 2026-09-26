package com.btcpayapp.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes.
 *
 * Every destination is a `@Serializable` class, so arguments are checked by the
 * compiler instead of being stringly-typed into a URL template. A renamed field
 * becomes a build error rather than a silently null argument at runtime.
 */

// --- Onboarding ------------------------------------------------------------

@Serializable
data object WelcomeRoute

@Serializable
data object ConnectRoute

@Serializable
data class PairRoute(
    val baseUrl: String,
    /** Comma-separated SPKI pins accepted during the probe, if any. */
    val pins: String = "",
)

@Serializable
data class ManualKeyRoute(val baseUrl: String, val pins: String = "")

// --- Top-level tabs --------------------------------------------------------

@Serializable
data object HomeRoute

@Serializable
data object InvoicesRoute

@Serializable
data object TerminalRoute

@Serializable
data object WalletRoute

@Serializable
data object MoreRoute

// --- Invoices --------------------------------------------------------------

@Serializable
data class InvoiceDetailRoute(val invoiceId: String)

@Serializable
data class CreateInvoiceRoute(val prefillAmount: String? = null, val prefillCurrency: String? = null)

@Serializable
data class RefundRoute(val invoiceId: String)

/** Full-screen payment view: QR, countdown, live status. */
@Serializable
data class CheckoutRoute(val invoiceId: String)

// --- On-chain wallet -------------------------------------------------------

@Serializable
data class WalletReceiveRoute(val paymentMethodId: String)

@Serializable
data class TransactionDetailRoute(val paymentMethodId: String, val transactionId: String)

@Serializable
data class UtxoRoute(val paymentMethodId: String)

// --- Lightning -------------------------------------------------------------

@Serializable
data class LightningRoute(val cryptoCode: String = "BTC", val serverNode: Boolean = false)

@Serializable
data class LightningChannelsRoute(val cryptoCode: String = "BTC", val serverNode: Boolean = false)

@Serializable
data class LightningPaymentsRoute(val cryptoCode: String = "BTC", val serverNode: Boolean = false)

@Serializable
data class LightningReceiveRoute(val cryptoCode: String = "BTC", val serverNode: Boolean = false)

@Serializable
data object LightningAddressesRoute

// --- Sending ---------------------------------------------------------------

/**
 * One destination for both rails.
 *
 * Which one is on screen follows from what the sender pastes, so both halves
 * are arguments of the same route rather than two routes the caller has to
 * choose between before knowing the answer. A null half is a rail this store
 * does not have.
 */
@Serializable
data class SendRoute(
    val paymentMethodId: String? = null,
    val lightningCryptoCode: String? = null,
    val serverNode: Boolean = false,
    /** Which rail to open on, when the store has both and neither is implied. */
    val lightning: Boolean = false,
    val prefill: String? = null,
)

// --- Payment requests, pull payments, payouts ------------------------------

@Serializable
data object PaymentRequestsRoute

@Serializable
data class PaymentRequestEditRoute(val paymentRequestId: String? = null)

@Serializable
data object PullPaymentsRoute

@Serializable
data object PullPaymentCreateRoute

@Serializable
data class PullPaymentDetailRoute(val pullPaymentId: String)

@Serializable
data object PayoutsRoute

@Serializable
data object PayoutCreateRoute

// --- Apps ------------------------------------------------------------------

@Serializable
data object AppsRoute

@Serializable
data class PointOfSaleEditRoute(val appId: String? = null)

@Serializable
data class CrowdfundEditRoute(val appId: String? = null)

// --- Store and server ------------------------------------------------------

@Serializable
data object NotificationsRoute

@Serializable
data object StoreSettingsRoute

@Serializable
data object StoreListRoute

@Serializable
data object PaymentMethodsRoute

@Serializable
data class PaymentMethodEditRoute(val paymentMethodId: String)

@Serializable
data object StoreUsersRoute

@Serializable
data object StoreRatesRoute

@Serializable
data object StoreEmailRoute

@Serializable
data object WebhooksRoute

@Serializable
data class WebhookEditRoute(val webhookId: String? = null)

@Serializable
data object ServerRoute

@Serializable
data object ServerUsersRoute

@Serializable
data object ServerEmailRoute

@Serializable
data object PayoutProcessorsRoute

// --- App-level -------------------------------------------------------------

@Serializable
data object AppSettingsRoute

@Serializable
data object AccountsRoute

@Serializable
data class AccountDetailRoute(val accountId: String)

@Serializable
data object AboutRoute

/** Shared scanner. [purpose] tells the caller's screen what to do with the result. */
@Serializable
data class ScanRoute(val purpose: String = ScanPurpose.GENERIC)

object ScanPurpose {
    const val GENERIC = "generic"
    const val SERVER = "server"
    const val PAYOUT_DESTINATION = "payout"
    const val LIGHTNING_INVOICE = "bolt11"
    const val ONCHAIN_DESTINATION = "onchain"
    /** Either rail: the send screen works out which from the code itself. */
    const val SEND_DESTINATION = "send"
    const val NODE_URI = "node"
}

/** Key used to hand a scan result back through the previous entry's saved state. */
const val SCAN_RESULT_KEY = "scan_result"

// --- Tab model -------------------------------------------------------------

enum class TopLevelTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
) {
    Home("Home", Icons.Rounded.Dashboard, HomeRoute),
    Invoices("Invoices", Icons.AutoMirrored.Rounded.ReceiptLong, InvoicesRoute),
    Terminal("Terminal", Icons.Rounded.Dialpad, TerminalRoute),
    Wallet("Wallet", Icons.Rounded.AccountBalanceWallet, WalletRoute),
    More("More", Icons.Rounded.MoreHoriz, MoreRoute),
}

/**
 * Which tab a destination belongs to, or null for everything below the tabs.
 *
 * Lives here rather than next to the navigation bar because the transition
 * spec needs it too: moving between two tabs is a short sideways step, while
 * going from a tab down into a detail screen is a full-width push.
 */
internal fun NavDestination.toTopLevelTab(): TopLevelTab? = when {
    hasRoute(HomeRoute::class) -> TopLevelTab.Home
    hasRoute(InvoicesRoute::class) -> TopLevelTab.Invoices
    hasRoute(TerminalRoute::class) -> TopLevelTab.Terminal
    hasRoute(WalletRoute::class) -> TopLevelTab.Wallet
    hasRoute(MoreRoute::class) -> TopLevelTab.More
    else -> null
}
