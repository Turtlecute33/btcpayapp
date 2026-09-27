package com.btcpayapp.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.btcpayapp.ui.LocalAppGraph
import com.btcpayapp.ui.components.LocalNavAnimatedScope
import com.btcpayapp.ui.components.LocalSharedTransitionScope
import com.btcpayapp.ui.theme.LocalReducedMotion
import com.btcpayapp.ui.theme.Motion
import com.btcpayapp.ui.screens.accounts.AccountDetailScreen
import com.btcpayapp.ui.screens.accounts.AccountsScreen
import com.btcpayapp.ui.screens.apps.AppsScreen
import com.btcpayapp.ui.screens.apps.CrowdfundEditScreen
import com.btcpayapp.ui.screens.apps.PointOfSaleEditScreen
import com.btcpayapp.ui.screens.home.HomeScreen
import com.btcpayapp.ui.screens.home.MoreScreen
import com.btcpayapp.ui.screens.invoice.CheckoutScreen
import com.btcpayapp.ui.screens.invoice.CreateInvoiceScreen
import com.btcpayapp.ui.screens.invoice.InvoiceDetailScreen
import com.btcpayapp.ui.screens.invoice.InvoiceListScreen
import com.btcpayapp.ui.screens.invoice.RefundScreen
import com.btcpayapp.ui.screens.lightning.LightningAddressesScreen
import com.btcpayapp.ui.screens.lightning.LightningChannelsScreen
import com.btcpayapp.ui.screens.lightning.LightningPaymentsScreen
import com.btcpayapp.ui.screens.lightning.LightningReceiveScreen
import com.btcpayapp.ui.screens.lightning.LightningScreen
import com.btcpayapp.ui.screens.notifications.NotificationsScreen
import com.btcpayapp.ui.screens.onboarding.ApiKeyScreen
import com.btcpayapp.ui.screens.onboarding.ConnectScreen
import com.btcpayapp.ui.screens.onboarding.WelcomeScreen
import com.btcpayapp.ui.screens.payout.PayoutCreateScreen
import com.btcpayapp.ui.screens.payout.PayoutListScreen
import com.btcpayapp.ui.screens.payout.PullPaymentCreateScreen
import com.btcpayapp.ui.screens.payout.PullPaymentDetailScreen
import com.btcpayapp.ui.screens.payout.PullPaymentListScreen
import com.btcpayapp.ui.screens.paymentrequest.PaymentRequestEditScreen
import com.btcpayapp.ui.screens.paymentrequest.PaymentRequestListScreen
import com.btcpayapp.ui.screens.scan.ScanScreen
import com.btcpayapp.ui.screens.send.SendScreen
import com.btcpayapp.ui.screens.server.PayoutProcessorsScreen
import com.btcpayapp.ui.screens.server.ServerEmailScreen
import com.btcpayapp.ui.screens.server.ServerScreen
import com.btcpayapp.ui.screens.server.ServerUsersScreen
import com.btcpayapp.ui.screens.settings.AboutScreen
import com.btcpayapp.ui.screens.settings.AppSettingsScreen
import com.btcpayapp.ui.screens.store.PaymentMethodEditScreen
import com.btcpayapp.ui.screens.store.PaymentMethodsScreen
import com.btcpayapp.ui.screens.store.StoreEmailScreen
import com.btcpayapp.ui.screens.store.StoreListScreen
import com.btcpayapp.ui.screens.store.StoreRatesScreen
import com.btcpayapp.ui.screens.store.StoreSettingsScreen
import com.btcpayapp.ui.screens.store.StoreUsersScreen
import com.btcpayapp.ui.screens.store.WebhookEditScreen
import com.btcpayapp.ui.screens.store.WebhooksScreen
import com.btcpayapp.ui.screens.terminal.TerminalScreen
import com.btcpayapp.ui.screens.wallet.TransactionDetailScreen
import com.btcpayapp.ui.screens.wallet.UtxoScreen
import com.btcpayapp.ui.screens.wallet.WalletReceiveScreen
import com.btcpayapp.ui.screens.wallet.WalletScreen

/**
 * How far the screen *underneath* lags behind, as a fraction of the width.
 *
 * The moving screen always travels the full width; this is the parallax on the
 * one it covers or uncovers. Equal travel for both would look like a strip of
 * film being pulled sideways — the offset is what says one is in front.
 */
private const val PARALLAX_FRACTION = 4

/**
 * Which way a change between two tabs travels, or null when this is not one.
 *
 * `+1` when the tab being opened sits to the right of the one being left, `-1`
 * when it sits to the left, `0` when it is the same tab arriving again.
 *
 * Read from the order of the bar itself — [TopLevelTab] is declared in the
 * order it is drawn — rather than from whether navigation called this a push or
 * a pop. That distinction is the one thing a bottom bar cannot be trusted on:
 * the bar restores each tab's saved stack, so the same two taps can arrive as a
 * push one time and a pop the next, and a direction taken from it would flip
 * between tries.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int? {
    val from = initialState.destination.toTopLevelTab() ?: return null
    val to = targetState.destination.toTopLevelTab() ?: return null
    return (to.ordinal - from.ordinal).coerceIn(-1, 1)
}

// ---------------------------------------------------------------------------
// Screen transitions
//
// A screen going deeper, or coming back, slides. It does not zoom, and it does
// not dissolve.
//
// Both are wrong in the same way: they animate a property a screen does not
// have. A screen is an opaque surface that
// fills the display. It does not become see-through when it moves, and it
// certainly does not shrink into a small window in the middle of a black
// background — that shrinking window is the Android 4 activity zoom, and it is
// instantly recognisable as old no matter how modern the spring driving it is.
//
// What is left is the honest construction: the moving screen travels the full
// width and stays solid; the screen it covers or uncovers lags a quarter of the
// width behind it. That offset is the entire depth cue and it is enough — it is
// what says one surface is in front of the other, without either one pretending
// to be transparent or to be a shrinking rectangle.
//
// The spring is critically damped, so a screen arrives and stops rather than
// arriving and wobbling. It still carries velocity, so a back gesture abandoned
// halfway continues from where the thumb actually left it.
// ---------------------------------------------------------------------------

// Going deeper: the new screen comes the whole way in, opaque, over the top.
private val Push = slideInHorizontally(Motion.screenSlide) { it }

// The screen it covers slides a quarter of the way out and waits there.
private val PushExit = slideOutHorizontally(Motion.screenSlide) { -it / PARALLAX_FRACTION }

// Coming back: the screen underneath slides up from where it was waiting. It
// was never gone, so nothing about it needs to be created, faded or grown.
private val Pop = slideInHorizontally(Motion.screenSlide) { -it / PARALLAX_FRACTION }

// And the screen being dismissed leaves the way the thumb pushed it: all the way.
private val PopExit = slideOutHorizontally(Motion.screenSlide) { it }

/**
 * Tabs are peers, so they step sideways rather than being pushed on top of one
 * another: a short travel plus a fade, along the axis the bar is laid out on.
 *
 * Not a plain cross-fade, which is wrong in practice for a reason that does
 * not show up in a storyboard. Two full screens dissolving through
 * each other have no leading edge, so at 150ms the change is over before the
 * eye has anything to follow, and the tab appears to cut. Giving it a direction
 * — left is back towards Home, right is further along the bar — makes the same
 * 150ms legible, and says which way you moved through the bar.
 *
 * An eighth of the width, not the whole of it. A full-width slide is the push
 * of a hierarchy and would claim these screens sit on top of each other; a
 * short step says "sideways, same level" and stops.
 *
 * The outgoing screen leaves on the quicker spring, so it is mostly gone before
 * the incoming one is solid and the two spend as little time superimposed as
 * the physics allow.
 */
private const val TAB_TRAVEL = 8

private fun tabIn(direction: Int) =
    slideInHorizontally(Motion.screenSlide) { direction * it / TAB_TRAVEL } +
        fadeIn(Motion.effectsSlow)

private fun tabOut(direction: Int) =
    slideOutHorizontally(Motion.screenSlide) { -direction * it / TAB_TRAVEL } +
        fadeOut(Motion.effects)

/**
 * What is left when the user has asked the system to stop moving things.
 *
 * Still a transition, not a cut. Removing it entirely leaves no signal that
 * the screen changed at all, which is disorienting in a different way — the
 * point is to stop things travelling across the display, not to stop telling
 * the user what happened.
 */
private val StillIn = fadeIn(Motion.effects)
private val StillOut = fadeOut(Motion.effectsFast)

// ---------------------------------------------------------------------------
// The four transitions, resolved in one place
//
// Written as functions rather than inline in `NavHost` because each one is
// needed twice: once for navigation the user committed — a tap, the back button
// — and once for the back *gesture*, which `NavHost` animates through a
// separate pair of parameters.
//
// Those two parameters are easy to miss and change everything. Leaving them
// unset does not fall back to `popEnterTransition` and `popExitTransition`; it
// falls back to the library's own default, which is `scaleOut(targetScale =
// 0.7f)` on the screen being dismissed. Unset, every back *swipe* would shrink
// the whole screen to seventy percent and drop it, whatever this file says, and
// the transitions above would be seen only by people using the back button.
// ---------------------------------------------------------------------------

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(still: Boolean): EnterTransition {
    val tab = tabDirection()
    return when {
        still -> StillIn
        tab != null -> tabIn(tab)
        else -> Push
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(still: Boolean): ExitTransition {
    val tab = tabDirection()
    return when {
        still -> StillOut
        tab != null -> tabOut(tab)
        else -> PushExit
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(still: Boolean): EnterTransition {
    val tab = tabDirection()
    return when {
        still -> StillIn
        tab != null -> tabIn(tab)
        else -> Pop
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(still: Boolean): ExitTransition {
    val tab = tabDirection()
    return when {
        still -> StillOut
        tab != null -> tabOut(tab)
        else -> PopExit
    }
}

/**
 * Every destination in the app.
 *
 * Screens take plain lambdas rather than a `NavController`, so they can be
 * previewed and tested without navigation, and so the entire navigation graph
 * is readable in one file instead of being scattered across thirty screens.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
) {
    val still = LocalReducedMotion.current
    val graph = LocalAppGraph.current

    /**
     * Back for every screen: pops, but never the start destination.
     *
     * A screen can ask to go back after it has already gone. Picking or
     * deleting a store switches the active store, and the shell then drops
     * every store screen, the asking one included. That screen is still
     * composed for its exit animation when its own "done, go back" effect
     * runs, and popping then would take the root and leave nothing on screen.
     */
    val back: () -> Unit = remember(navController) {
        { if (navController.previousBackStackEntry != null) navController.popBackStack() }
    }

    /**
     * [back] from [entry] only while it is on top. A result a screen reports
     * twice (a double tap) or after a store reset dropped it must not pop, or
     * hand a result to, whatever is now below.
     */
    fun backFrom(entry: NavBackStackEntry) {
        if (navController.currentBackStackEntry == entry) back()
    }

    // Everything the app draws lives inside one shared-transition scope, so a
    // row on a list and the screen it opens can be declared as the same object
    // and animate as one. Screens reach the scope through a composition local
    // rather than a parameter — see `LocalSharedTransitionScope` — so a screen
    // still renders on its own in a preview, just without the continuity.
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = modifier,
                enterTransition = { enter(still) },
                exitTransition = { exit(still) },
                popEnterTransition = { popEnter(still) },
                popExitTransition = { popExit(still) },
                // The back gesture animates the same way the back button does.
                // The swipe edge is handed to these and deliberately ignored:
                // it says which side the thumb started from, not which way the
                // stack is moving, and a screen that leaves rightwards or
                // leftwards depending on where the thumb landed is a screen
                // whose position in the app is decided by grip.
                predictivePopEnterTransition = { popEnter(still) },
                predictivePopExitTransition = { popExit(still) },
            ) {
                // --- Onboarding ----------------------------------------------------

                screen<WelcomeRoute> {
                    WelcomeScreen(onConnect = { navController.navigate(ConnectRoute) })
                }

                screen<ConnectRoute> {
                    ConnectScreen(
                        onBack = back,
                        onConnected = { baseUrl, pins ->
                            navController.navigate(ApiKeyRoute(baseUrl, pins.joinToString(",")))
                        },
                    )
                }

                screen<ApiKeyRoute> { entry ->
                    val route = entry.toRoute<ApiKeyRoute>()
                    ApiKeyScreen(
                        baseUrl = route.baseUrl,
                        pins = route.pins.splitPins(),
                        onBack = back,
                        onPaired = { navController.toHomeClearingOnboarding() },
                    )
                }

                // --- Tabs ----------------------------------------------------------

                screen<HomeRoute> {
                    // A code scanned from Home (or the Scan shortcut) is
                    // something to pay: the send screen tells the rails apart.
                    val scanned = navController.consumeScanResult()
                    LaunchedEffect(scanned) {
                        if (scanned == null) return@LaunchedEffect
                        navController.navigate(
                            SendRoute(
                                paymentMethodId = graph.session.enabledPaymentMethodIds
                                    .firstOrNull { it.endsWith("-CHAIN", ignoreCase = true) },
                                lightningCryptoCode = graph.session.lightningCryptoCodes.firstOrNull(),
                                prefill = scanned,
                            ),
                        )
                    }
                    HomeScreen(
                        onOpenInvoice = { navController.navigate(InvoiceDetailRoute(it)) },
                        onOpenInvoices = { navController.navigate(InvoicesRoute) },
                        onOpenWallet = { navController.navigate(WalletRoute) },
                        onOpenLightning = { navController.navigate(LightningRoute(it)) },
                        onOpenNotifications = { navController.navigate(NotificationsRoute) },
                        onOpenAccounts = { navController.navigate(AccountsRoute) },
                        onOpenStores = { navController.navigate(StoreListRoute) },
                        onCreateInvoice = { navController.navigate(CreateInvoiceRoute()) },
                        onScan = { navController.navigate(ScanRoute(ScanPurpose.SEND_DESTINATION)) },
                    )
                }

                screen<InvoicesRoute> {
                    InvoiceListScreen(
                        onOpenInvoice = { navController.navigate(InvoiceDetailRoute(it)) },
                        onCreateInvoice = { navController.navigate(CreateInvoiceRoute()) },
                    )
                }

                screen<TerminalRoute> {
                    TerminalScreen(
                        onCheckout = { navController.navigate(CheckoutRoute(it)) },
                    )
                }

                screen<WalletRoute> {
                    WalletScreen(
                        onReceive = { navController.navigate(WalletReceiveRoute(it)) },
                        onSend = { paymentMethodId, cryptoCode, lightning ->
                            navController.navigate(
                                SendRoute(
                                    paymentMethodId = paymentMethodId,
                                    lightningCryptoCode = cryptoCode,
                                    lightning = lightning,
                                ),
                            )
                        },
                        onOpenTransaction = { pm, tx -> navController.navigate(TransactionDetailRoute(pm, tx)) },
                        onOpenUtxos = { navController.navigate(UtxoRoute(it)) },
                        onOpenLightning = { navController.navigate(LightningRoute(it)) },
                        onLightningReceive = { navController.navigate(LightningReceiveRoute(it)) },
                    )
                }

                screen<MoreRoute> {
                    MoreScreen(onNavigate = { navController.navigate(it) })
                }

                // --- Invoices ------------------------------------------------------

                screen<InvoiceDetailRoute> { entry ->
                    val route = entry.toRoute<InvoiceDetailRoute>()
                    InvoiceDetailScreen(
                        invoiceId = route.invoiceId,
                        onBack = back,
                        onRefund = { navController.navigate(RefundRoute(route.invoiceId)) },
                        onCheckout = { navController.navigateOrPop(CheckoutRoute(route.invoiceId)) },
                    )
                }

                screen<CreateInvoiceRoute> { entry ->
                    val route = entry.toRoute<CreateInvoiceRoute>()
                    CreateInvoiceScreen(
                        prefillAmount = route.prefillAmount,
                        prefillCurrency = route.prefillCurrency,
                        onBack = back,
                        onCreated = { invoiceId ->
                            navController.navigate(CheckoutRoute(invoiceId)) {
                                popUpTo<CreateInvoiceRoute> { inclusive = true }
                            }
                        },
                    )
                }

                screen<RefundRoute> { entry ->
                    RefundScreen(
                        invoiceId = entry.toRoute<RefundRoute>().invoiceId,
                        onBack = back,
                        onCreated = { pullPaymentId ->
                            navController.navigate(PullPaymentDetailRoute(pullPaymentId)) {
                                popUpTo<RefundRoute> { inclusive = true }
                            }
                        },
                    )
                }

                screen<CheckoutRoute> { entry ->
                    CheckoutScreen(
                        invoiceId = entry.toRoute<CheckoutRoute>().invoiceId,
                        onBack = back,
                        onOpenInvoice = { navController.navigateOrPop(InvoiceDetailRoute(it)) },
                    )
                }

                // --- On-chain wallet ------------------------------------------------

                screen<WalletReceiveRoute> { entry ->
                    WalletReceiveScreen(
                        paymentMethodId = entry.toRoute<WalletReceiveRoute>().paymentMethodId,
                        onBack = back,
                    )
                }

                screen<TransactionDetailRoute> { entry ->
                    val route = entry.toRoute<TransactionDetailRoute>()
                    TransactionDetailScreen(
                        paymentMethodId = route.paymentMethodId,
                        transactionId = route.transactionId,
                        onBack = back,
                    )
                }

                screen<UtxoRoute> { entry ->
                    UtxoScreen(
                        paymentMethodId = entry.toRoute<UtxoRoute>().paymentMethodId,
                        onBack = back,
                    )
                }

                // --- Lightning -----------------------------------------------------

                screen<LightningRoute> { entry ->
                    val route = entry.toRoute<LightningRoute>()
                    LightningScreen(
                        cryptoCode = route.cryptoCode,
                        serverNode = route.serverNode,
                        onBack = back,
                        onChannels = { navController.navigate(LightningChannelsRoute(route.cryptoCode, route.serverNode)) },
                        onPayments = { navController.navigate(LightningPaymentsRoute(route.cryptoCode, route.serverNode)) },
                        onSend = {
                            navController.navigate(
                                SendRoute(
                                    lightningCryptoCode = route.cryptoCode,
                                    serverNode = route.serverNode,
                                    lightning = true,
                                ),
                            )
                        },
                        onReceive = { navController.navigate(LightningReceiveRoute(route.cryptoCode, route.serverNode)) },
                        onAddresses = { navController.navigate(LightningAddressesRoute) },
                    )
                }

                screen<LightningChannelsRoute> { entry ->
                    val route = entry.toRoute<LightningChannelsRoute>()
                    LightningChannelsScreen(
                        cryptoCode = route.cryptoCode,
                        serverNode = route.serverNode,
                        scanResult = navController.consumeScanResult(),
                        onScan = { navController.navigate(ScanRoute(ScanPurpose.NODE_URI)) },
                        onBack = back,
                    )
                }

                screen<LightningPaymentsRoute> { entry ->
                    val route = entry.toRoute<LightningPaymentsRoute>()
                    LightningPaymentsScreen(
                        cryptoCode = route.cryptoCode,
                        serverNode = route.serverNode,
                        onBack = back,
                    )
                }

                screen<LightningReceiveRoute> { entry ->
                    val route = entry.toRoute<LightningReceiveRoute>()
                    LightningReceiveScreen(
                        cryptoCode = route.cryptoCode,
                        serverNode = route.serverNode,
                        onBack = back,
                    )
                }

                screen<LightningAddressesRoute> {
                    LightningAddressesScreen(onBack = back)
                }

                // --- Sending -------------------------------------------------------

                screen<SendRoute> { entry ->
                    val route = entry.toRoute<SendRoute>()
                    SendScreen(
                        paymentMethodId = route.paymentMethodId,
                        lightningCryptoCode = route.lightningCryptoCode,
                        serverNode = route.serverNode,
                        startOnLightning = route.lightning,
                        prefill = route.prefill,
                        scanResult = navController.consumeScanResult(),
                        // The purpose comes from the screen: it knows which
                        // rails this store actually has, and the scanner should
                        // keep refusing codes that cannot be paid from here.
                        onScan = { purpose -> navController.navigate(ScanRoute(purpose)) },
                        onBack = back,
                        onSent = { backFrom(entry) },
                    )
                }

                // --- Payment requests ----------------------------------------------

                screen<PaymentRequestsRoute> {
                    PaymentRequestListScreen(
                        onOpen = { navController.navigate(PaymentRequestEditRoute(it)) },
                        onCreate = { navController.navigate(PaymentRequestEditRoute()) },
                        onBack = back,
                    )
                }

                screen<PaymentRequestEditRoute> { entry ->
                    PaymentRequestEditScreen(
                        paymentRequestId = entry.toRoute<PaymentRequestEditRoute>().paymentRequestId,
                        onBack = back,
                    )
                }

                // --- Pull payments and payouts --------------------------------------

                screen<PullPaymentsRoute> {
                    PullPaymentListScreen(
                        onOpen = { navController.navigate(PullPaymentDetailRoute(it)) },
                        onCreate = { navController.navigate(PullPaymentCreateRoute) },
                        onBack = back,
                    )
                }

                screen<PullPaymentCreateRoute> {
                    PullPaymentCreateScreen(
                        onBack = back,
                        onCreated = { id ->
                            navController.navigate(PullPaymentDetailRoute(id)) {
                                popUpTo<PullPaymentCreateRoute> { inclusive = true }
                            }
                        },
                    )
                }

                screen<PullPaymentDetailRoute> { entry ->
                    PullPaymentDetailScreen(
                        pullPaymentId = entry.toRoute<PullPaymentDetailRoute>().pullPaymentId,
                        onBack = back,
                    )
                }

                screen<PayoutsRoute> {
                    PayoutListScreen(
                        onCreate = { navController.navigate(PayoutCreateRoute) },
                        onBack = back,
                    )
                }

                screen<PayoutCreateRoute> {
                    PayoutCreateScreen(
                        scanResult = navController.consumeScanResult(),
                        onScan = { navController.navigate(ScanRoute(ScanPurpose.PAYOUT_DESTINATION)) },
                        onBack = back,
                    )
                }

                // --- Apps ----------------------------------------------------------

                screen<AppsRoute> {
                    AppsScreen(
                        onBack = back,
                        onEditPointOfSale = { navController.navigate(PointOfSaleEditRoute(it)) },
                        onEditCrowdfund = { navController.navigate(CrowdfundEditRoute(it)) },
                    )
                }

                screen<PointOfSaleEditRoute> { entry ->
                    PointOfSaleEditScreen(
                        appId = entry.toRoute<PointOfSaleEditRoute>().appId,
                        onBack = back,
                    )
                }

                screen<CrowdfundEditRoute> { entry ->
                    CrowdfundEditScreen(
                        appId = entry.toRoute<CrowdfundEditRoute>().appId,
                        onBack = back,
                    )
                }

                // --- Store ---------------------------------------------------------

                screen<NotificationsRoute> {
                    NotificationsScreen(
                        onBack = back,
                        onNavigate = { navController.navigate(it) },
                    )
                }

                screen<StoreSettingsRoute> {
                    StoreSettingsScreen(
                        onBack = back,
                        onNavigate = { navController.navigate(it) },
                    )
                }

                screen<StoreListRoute> {
                    StoreListScreen(onBack = back)
                }

                screen<PaymentMethodsRoute> {
                    PaymentMethodsScreen(
                        onBack = back,
                        onEdit = { navController.navigate(PaymentMethodEditRoute(it)) },
                    )
                }

                screen<PaymentMethodEditRoute> { entry ->
                    PaymentMethodEditScreen(
                        paymentMethodId = entry.toRoute<PaymentMethodEditRoute>().paymentMethodId,
                        onBack = back,
                    )
                }

                screen<StoreUsersRoute> { StoreUsersScreen(onBack = back) }
                screen<StoreRatesRoute> { StoreRatesScreen(onBack = back) }
                screen<StoreEmailRoute> { StoreEmailScreen(onBack = back) }

                screen<WebhooksRoute> {
                    WebhooksScreen(
                        onBack = back,
                        onEdit = { navController.navigate(WebhookEditRoute(it)) },
                    )
                }

                screen<WebhookEditRoute> { entry ->
                    WebhookEditScreen(
                        webhookId = entry.toRoute<WebhookEditRoute>().webhookId,
                        onBack = back,
                    )
                }

                // --- Server --------------------------------------------------------

                screen<ServerRoute> {
                    ServerScreen(
                        onBack = back,
                        onNavigate = { navController.navigate(it) },
                    )
                }

                screen<ServerUsersRoute> { ServerUsersScreen(onBack = back) }
                screen<ServerEmailRoute> { ServerEmailScreen(onBack = back) }
                screen<PayoutProcessorsRoute> { PayoutProcessorsScreen(onBack = back) }

                // --- App-level -----------------------------------------------------

                screen<AppSettingsRoute> {
                    AppSettingsScreen(
                        onBack = back,
                        onAccounts = { navController.navigate(AccountsRoute) },
                        onAbout = { navController.navigate(AboutRoute) },
                    )
                }

                screen<AccountsRoute> {
                    AccountsScreen(
                        onBack = back,
                        onAddAccount = { navController.navigate(ConnectRoute) },
                        onOpenAccount = { navController.navigate(AccountDetailRoute(it)) },
                    )
                }

                screen<AccountDetailRoute> { entry ->
                    AccountDetailScreen(
                        accountId = entry.toRoute<AccountDetailRoute>().accountId,
                        onBack = back,
                    )
                }

                screen<AboutRoute> { AboutScreen(onBack = back) }

                screen<ScanRoute> { entry ->
                    ScanScreen(
                        purpose = entry.toRoute<ScanRoute>().purpose,
                        onBack = back,
                        onResult = { raw ->
                            // Only while the scanner is on top; see backFrom.
                            if (navController.currentBackStackEntry == entry) {
                                navController.previousBackStackEntry?.savedStateHandle?.set(SCAN_RESULT_KEY, raw)
                                back()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A destination, plus the one thing a destination has that its screen cannot
 * obtain for itself.
 *
 * `composable<T>` hands its content an [androidx.compose.animation.AnimatedContentScope]
 * as a receiver, and a shared element needs that scope to know which
 * transition it is part of. Screens here take plain lambdas and no receiver,
 * so this publishes the scope as a composition local instead — which also
 * means a screen that has no shared elements pays nothing and needs to know
 * nothing about any of this.
 *
 * Every destination goes through here rather than only the handful that
 * currently animate. Marking two elements as shared should be a one-line
 * change in a screen, not a change in a screen and a change to the graph.
 */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}

/**
 * Reads a scan result left by [ScanScreen] and clears it, so rotating the
 * device does not re-apply the same scan.
 */
@Composable
private fun NavHostController.consumeScanResult(): String? {
    val handle = currentBackStackEntry?.savedStateHandle ?: return null
    val value = handle.get<String>(SCAN_RESULT_KEY)
    if (value != null) handle.remove<String>(SCAN_RESULT_KEY)
    return value
}

/**
 * Goes back when the screen underneath is already [route], and opens it
 * otherwise.
 *
 * Checkout and invoice detail link to each other. Pushing on every tap would
 * stack them without limit, and back would have to walk through each copy.
 */
private inline fun <reified T : Any> NavHostController.navigateOrPop(route: T) {
    val previous = previousBackStackEntry
    if (previous != null && previous.destination.hasRoute(T::class) && previous.toRoute<T>() == route) {
        popBackStack()
    } else {
        navigate(route)
    }
}

private fun String.splitPins(): List<String> =
    split(',').map(String::trim).filter(String::isNotEmpty)

private fun NavHostController.toHomeClearingOnboarding() {
    navigate(HomeRoute) {
        popUpTo(graph.id) { inclusive = true }
    }
}
