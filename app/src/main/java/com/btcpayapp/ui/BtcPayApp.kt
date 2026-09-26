package com.btcpayapp.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.btcpayapp.core.util.safeStartActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.btcpayapp.AppGraph
import com.btcpayapp.core.sync.Notifier
import com.btcpayapp.data.api.endpoints.markNotification
import com.btcpayapp.ui.nav.AppNavHost
import com.btcpayapp.ui.nav.HomeRoute
import com.btcpayapp.ui.nav.InvoicesRoute
import com.btcpayapp.ui.nav.MoreRoute
import com.btcpayapp.ui.nav.NotificationsRoute
import com.btcpayapp.ui.nav.TerminalRoute
import com.btcpayapp.ui.nav.TopLevelTab
import com.btcpayapp.ui.nav.toTopLevelTab
import com.btcpayapp.ui.nav.WalletRoute
import com.btcpayapp.ui.nav.WelcomeRoute
import com.btcpayapp.ui.screens.lock.LockScreen
import com.btcpayapp.ui.screens.notifications.NotificationTarget
import com.btcpayapp.ui.screens.notifications.notificationTarget
import com.btcpayapp.ui.theme.BtcPayTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The composition root.
 *
 * Three things happen here and nowhere else: the dependency graph is published
 * to the tree, the theme is applied, and the lock overlay is drawn above
 * everything. Keeping the lock at this level means no screen can forget it.
 */
@Composable
fun BtcPayApp(
    graph: AppGraph,
    activity: FragmentActivity,
    deepLink: StateFlow<String?>,
    onDeepLinkHandled: () -> Unit,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val lockState by graph.appLock.locked.collectAsStateWithLifecycle()
    val locked = settings.appLock != com.btcpayapp.data.model.AppLockMode.Off && lockState
    val settingsLoaded by graph.settings.loaded.collectAsStateWithLifecycle()
    val accountsLoaded by graph.accounts.loaded.collectAsStateWithLifecycle()
    val vault by graph.accounts.vault.collectAsStateWithLifecycle()
    val uriHandler = remember(activity) { object : UriHandler {
        override fun openUri(uri: String) {
            activity.safeStartActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
        }
    } }

    CompositionLocalProvider(
        LocalAppGraph provides graph,
        LocalSettings provides settings,
        LocalIsLocked provides locked,
        LocalUriHandler provides uriHandler,
    ) {
        BtcPayTheme(
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            pureBlack = settings.pureBlackDark,
        ) {
            // Nothing is drawn until the vault has been read, so a returning
            // user never sees a flash of the welcome screen.
            if (settingsLoaded && accountsLoaded) {
                // On API 33+ the permission is denied until something asks for
                // it, so `canPost()` alone is not enough. Asked once, and only
                // when the user actually has an account and has left payment
                // alerts switched on.
                NotificationPermissionRequest(
                    enabled = !locked && vault.accounts.isNotEmpty() &&
                        (settings.notifyPayments || settings.notifyPayouts),
                    notifier = graph.notifier,
                )

                // Navigation and screen models belong to one server. Clear old
                // models (and their requests) when switching or removing it.
                key(vault.activeAccount?.id) {
                    val owner = remember {
                        object : ViewModelStoreOwner {
                            override val viewModelStore = ViewModelStore()
                        }
                    }
                    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        val navController = rememberNavController()
                        // Dialogs own separate windows; remove them while locked.
                        if (!locked) AppShell(
                            navController = navController,
                            hasAccounts = vault.accounts.isNotEmpty(),
                            deepLink = deepLink,
                            onDeepLinkHandled = onDeepLinkHandled,
                        )
                    }
                }
            }

            // The lock arrives with no animation and leaves with one.
            //
            // That asymmetry is the whole point. Locking happens when the app
            // comes back to the foreground, and every frame the cover spends
            // being partly transparent is a frame in which a balance is
            // readable by whoever is holding the phone — so it is simply
            // there, on the first frame, before anything behind it is drawn.
            // Unlocking is the opposite situation: the user has just proved
            // who they are and is watching, so the cover expands slightly and
            // dissolves, which reads as a lid coming off rather than as a
            // screen being replaced.
            AnimatedVisibility(
                visible = settingsLoaded && locked,
                enter = EnterTransition.None,
                exit = fadeOut(Motion.effects) +
                    scaleOut(Motion.spatial, targetScale = LOCK_LIFT_SCALE),
            ) {
                // Swallows back so the lock cannot be dismissed by navigating.
                BackHandler(enabled = true) {}
                LockScreen(
                    activity = activity,
                    onUnlocked = { graph.appLock.markUnlocked() },
                )
            }
        }
    }
}

/**
 * Asks for `POST_NOTIFICATIONS` once per process.
 *
 * Deliberately not on first launch: asking before the user has connected a
 * server gives them no context for the decision, and a denial is sticky.
 */
@Composable
private fun NotificationPermissionRequest(enabled: Boolean, notifier: Notifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is respected; Settings explains how to turn alerts back on. */ }

    LaunchedEffect(enabled, asked) {
        if (enabled && !asked && notifier.needsPermission()) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppShell(
    navController: NavHostController,
    hasAccounts: Boolean,
    deepLink: StateFlow<String?>,
    onDeepLinkHandled: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = remember(destination) { destination?.toTopLevelTab() }
    val showNavigation = hasAccounts && currentTab != null

    // Back from any tab other than Home goes to Home, inside the app.
    //
    // Two things depend on this being handled here rather than left to the nav
    // stack. It is the behaviour a bottom bar is supposed to have — Home is the
    // one destination back always leads to, from any tab, whatever route got
    // you there. And a back press that the app has not claimed is a back press
    // the system takes to mean "leave", so it plays the window animation for
    // returning to the launcher — the app shrinking away — over the top of
    // whatever the app then does. Claiming it keeps the change on the app's own
    // motion.
    //
    // Home itself is left alone: there, back does mean leave.
    BackHandler(enabled = currentTab != null && currentTab != TopLevelTab.Home) {
        navController.switchTab(TopLevelTab.Home)
    }

    val link by deepLink.collectAsStateWithLifecycle()
    val graph = LocalAppGraph.current
    val uriHandler = LocalUriHandler.current
    val locked = LocalIsLocked.current
    val activeAccount by graph.session.activeAccount.collectAsStateWithLifecycle()
    val activeStore by graph.session.activeStore.collectAsStateWithLifecycle()
    // Keyed on the ids, not the objects: `Account` carries the selected store,
    // so using it whole would restart this effect on a store switch as well as
    // an account one, and `StoreData` changes on every refresh.
    val activeAccountId = activeAccount?.id
    val activeStoreId = activeStore?.id

    /**
     * The link this effect has already acted on.
     *
     * Without it a launcher shortcut would push its destination two or three
     * times. The effect deliberately re-runs while it waits for an account or
     * store switch to land, and on a cold start the store list arrives a moment
     * after the account does — so the keys can change again *after* `navigate`
     * has been called but before `onDeepLinkHandled` has propagated the null
     * back through the StateFlow. Each re-run would see the same non-null link
     * and navigate again.
     *
     * The visible result would be a stack of identical screens: back appears to
     * do nothing, and the animation appears broken, because popping one
     * duplicate reveals another one exactly like it.
     */
    var handledLink by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(link, hasAccounts, locked, activeAccountId, activeStoreId) {
        val target = link
        if (target == null) {
            // Armed again for next time. Tapping the same shortcut twice sends
            // the identical string, so the guard has to reset once the flow has
            // been drained rather than persist for the life of the screen.
            handledLink = null
            return@LaunchedEffect
        }
        if (locked) return@LaunchedEffect
        if (target == handledLink) return@LaunchedEffect
        val uri = android.net.Uri.parse(target)
        if (uri.scheme != "btcpayapp") {
            onDeepLinkHandled()
            return@LaunchedEffect
        }
        val accountId = uri.getQueryParameter("account")
        // An entry from the server's notification feed, resolved as a tap in
        // the in-app list would resolve it. Only read once the account below
        // is active, since the link is relative to that server.
        val feedTarget = if (uri.host == "notification") {
            notificationTarget(uri.getQueryParameter("link"), activeAccount?.baseUrl)
        } else {
            null
        }
        val storeId = when (feedTarget) {
            null -> uri.getQueryParameter("store")
            is NotificationTarget.InApp -> uri.getQueryParameter("store") ?: feedTarget.storeId
            else -> null
        }
        if (accountId != null) {
            if (graph.accounts.vault.value.accounts.none { it.id == accountId }) {
                onDeepLinkHandled()
                return@LaunchedEffect
            }
            // Both branches return without marking the link handled: the switch
            // is asynchronous, and this effect is restarted by the id it is
            // waiting on.
            if (activeAccountId != accountId) {
                graph.session.selectAccount(accountId)
                return@LaunchedEffect
            }
            if (storeId != null && activeStoreId != storeId) {
                if (graph.session.stores.value.any { it.id == storeId }) graph.session.selectStore(storeId)
                return@LaunchedEffect
            }
        }
        if (hasAccounts) {
            handledLink = target
            if (feedTarget == null) {
                navController.handleDeepLink(target)
            } else {
                uri.lastPathSegment?.let { id ->
                    graph.scope.launch { runCatching { graph.session.requireApi().markNotification(id, true) } }
                }
                when (feedTarget) {
                    is NotificationTarget.InApp -> navController.navigateOnce(feedTarget.route)
                    is NotificationTarget.Web -> uriHandler.openUri(feedTarget.url)
                    NotificationTarget.None -> navController.navigateOnce(NotificationsRoute)
                }
            }
        }
        onDeepLinkHandled()
    }

    /**
     * Adaptive chrome without a windowing dependency: a phone in portrait gets
     * a bottom bar, anything 600dp or wider — a tablet, an unfolded foldable, a
     * phone in landscape — gets a side rail, which keeps the thumb reach
     * sensible and stops the bar eating vertical space.
     *
     * Read from `LocalConfiguration` rather than measured with
     * `BoxWithConstraints`. `BoxWithConstraints` is a `SubcomposeLayout`:
     * wrapped around the entire application, it would defer all composition
     * into the measure pass, and — because the manifest sets `adjustResize` —
     * re-subcompose the whole tree on every frame of the keyboard opening and
     * closing, which is typing lag on every form in the app. `screenWidthDp`
     * does not change when the IME appears.
     */
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BREAKPOINT_DP

    // One `AppNavHost` call site, always. Called from two separate branches,
    // crossing the 600dp threshold — or, on a tablet, merely navigating from a
    // tab to a detail screen, which flips `showNavigation` — would discard and
    // rebuild the whole nav subtree along with every screen's remembered
    // state. Only the chrome varies.
    Scaffold(
        bottomBar = {
            // The bar slides out of the way when the user goes deeper than a
            // tab, rather than vanishing. Removing it outright would make the
            // whole screen jump upward by the bar's height on the same frame as
            // the push transition starts, so two unrelated movements would
            // happen at once and neither would be legible. Sliding it means
            // `Scaffold` remeasures the content padding as it goes and the
            // screen underneath settles smoothly.
            AnimatedVisibility(
                visible = showNavigation && !wide,
                enter = slideInVertically(Motion.spatialOffset) { it } + fadeIn(Motion.effects),
                exit = slideOutVertically(Motion.spatialOffset) { it } + fadeOut(Motion.effectsFast),
            ) {
                NavigationBar {
                    TopLevelTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { navController.switchTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Row(Modifier.fillMaxSize()) {
            if (showNavigation && wide) {
                NavigationRail {
                    TopLevelTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = currentTab == tab,
                            onClick = { navController.switchTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
            AppNavHost(
                navController = navController,
                startDestination = if (hasAccounts) HomeRoute else WelcomeRoute,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            )
        }
    }
}

private const val WIDE_BREAKPOINT_DP = 600

/** How far the lock cover grows as it dissolves. Barely, on purpose. */
private const val LOCK_LIFT_SCALE = 1.06f

/**
 * Tab switching keeps one back stack per tab and restores it, which is what
 * users expect from a bottom bar: leaving Invoices and coming back should land
 * where you were, not at the top.
 */
private fun NavHostController.switchTab(tab: TopLevelTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun androidx.navigation.NavGraph.findStartDestination(): NavDestination {
    var node: NavDestination = findNode(startDestinationId) ?: this
    while (node is androidx.navigation.NavGraph) {
        node = node.findNode(node.startDestinationId) ?: break
    }
    return node
}

/**
 * Notification and launcher-shortcut targets.
 *
 * Every push is `launchSingleTop`. The activity is `singleTask`, so tapping a
 * shortcut while that shortcut's screen is already open delivers the intent to
 * the running task rather than starting a new one — and without this it would
 * put a second copy of the same screen on the stack, which the user then has to
 * press back through twice to leave.
 */
private fun NavHostController.handleDeepLink(uri: String) {
    val parsed = android.net.Uri.parse(uri)
    val head = parsed.host
    val tail = parsed.pathSegments.firstOrNull().orEmpty()
    when (head) {
        "invoice" -> if (tail.isNotBlank()) {
            navigateOnce(com.btcpayapp.ui.nav.InvoiceDetailRoute(tail))
        }
        "payout" -> navigateOnce(com.btcpayapp.ui.nav.PayoutsRoute)
        "settings" -> navigateOnce(com.btcpayapp.ui.nav.AccountsRoute)
        "shortcut" -> when (tail) {
            "terminal" -> switchTab(TopLevelTab.Terminal)
            "new-invoice" -> navigateOnce(com.btcpayapp.ui.nav.CreateInvoiceRoute())
            "scan" -> navigateOnce(com.btcpayapp.ui.nav.ScanRoute())
        }
    }
}

private fun NavHostController.navigateOnce(route: Any) {
    navigate(route) { launchSingleTop = true }
}
