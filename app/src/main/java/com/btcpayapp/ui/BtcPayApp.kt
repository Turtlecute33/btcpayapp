package com.btcpayapp.ui

import android.Manifest
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.btcpayapp.core.util.safeStartActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.btcpayapp.AppGraph
import com.btcpayapp.core.sync.Notifier
import com.btcpayapp.data.api.endpoints.markNotification
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.EmptyState
import com.btcpayapp.ui.nav.AccountsRoute
import com.btcpayapp.ui.nav.AppNavHost
import com.btcpayapp.ui.nav.CreateInvoiceRoute
import com.btcpayapp.ui.nav.HomeRoute
import com.btcpayapp.ui.nav.InvoiceDetailRoute
import com.btcpayapp.ui.nav.NotificationsRoute
import com.btcpayapp.ui.nav.PayoutsRoute
import com.btcpayapp.ui.nav.ScanPurpose
import com.btcpayapp.ui.nav.ScanRoute
import com.btcpayapp.ui.nav.TopLevelTab
import com.btcpayapp.ui.nav.toTopLevelTab
import com.btcpayapp.ui.nav.WelcomeRoute
import com.btcpayapp.ui.screens.lock.LockScreen
import com.btcpayapp.ui.screens.notifications.NotificationTarget
import com.btcpayapp.ui.screens.notifications.notificationTarget
import com.btcpayapp.ui.theme.BtcPayTheme
import com.btcpayapp.ui.theme.Motion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder

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
    val locked = settings.appLock != AppLockMode.Off && lockState
    val settingsLoaded by graph.settings.loaded.collectAsStateWithLifecycle()
    val accountsLoaded by graph.accounts.loaded.collectAsStateWithLifecycle()
    val storageUnreadable by graph.storageUnreadable.collectAsStateWithLifecycle()
    val vault by graph.accounts.vault.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The activity's own model store, so it outlives this composition.
    val accountModels = viewModel { AccountViewModelStores() }
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

                DeepLinkAccountSwitch(
                    graph = graph,
                    deepLink = deepLink,
                    activeAccountId = vault.activeAccount?.id,
                    locked = locked,
                    onDeepLinkHandled = onDeepLinkHandled,
                )

                LostSettingsNotice(graph, locked = locked, lockOn = settings.appLock != AppLockMode.Off)

                // Navigation and screen models belong to one server. Clear old
                // models (and their requests) when switching or removing it.
                val accountKey = vault.activeAccount?.id ?: NO_ACCOUNT
                key(accountKey) {
                    val owner = remember {
                        object : ViewModelStoreOwner {
                            override val viewModelStore = accountModels.storeFor(accountKey)
                        }
                    }
                    LaunchedEffect(accountKey) { accountModels.keepOnly(accountKey) }
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        val navController = rememberNavController()
                        // Above the lock, so scroll positions, open sections and
                        // typed text come back after unlocking. Dialogs own
                        // separate windows; remove them while locked.
                        val shellState = rememberSaveableStateHolder()
                        if (!locked) shellState.SaveableStateProvider(accountKey) {
                            AppShell(
                                navController = navController,
                                hasAccounts = vault.accounts.isNotEmpty(),
                                deepLink = deepLink,
                                onDeepLinkHandled = onDeepLinkHandled,
                            )
                        }
                    }
                }
            } else if (storageUnreadable) {
                // The read is retried with backoff anyway; this is for someone
                // who is watching. It shows no account data.
                EmptyState(
                    title = "Cannot open the app's data",
                    description = "The phone's secure storage did not answer. This can happen just " +
                        "after the phone starts. Your accounts are safe.",
                    icon = Icons.Rounded.Lock,
                    actionLabel = "Try again",
                    onAction = { scope.launch { graph.retryStorage() } },
                )
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
 * Tells the user once that the stored settings could not be opened and were
 * reset (see [com.btcpayapp.data.session.SettingsRepository.lost]), and why
 * the app lock may now be on. Dismissing saves the recovered settings as they
 * are, lock included, which ends the flag.
 */
@Composable
private fun LostSettingsNotice(graph: AppGraph, locked: Boolean, lockOn: Boolean) {
    val lost by graph.settings.lost.collectAsStateWithLifecycle()
    // Once, even if the save that ends the flag fails.
    var seen by rememberSaveable { mutableStateOf(false) }
    // A dialog owns its own window, so it waits until the app is unlocked.
    if (!lost || seen || locked) return
    val scope = rememberCoroutineScope()
    val dismiss = {
        seen = true
        scope.launch { graph.settings.update { it } }
        Unit
    }
    AlertDialog(
        onDismissRequest = dismiss,
        text = {
            Text(
                "Your settings could not be opened, so they were reset." +
                    if (lockOn) " The app lock is on." else "",
            )
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
    )
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

/**
 * The account half of a deep link: switches to the account the link names.
 *
 * Here, above the per-account `key`, because the switch replaces everything
 * below it; the store and the screen are then [AppShell]'s job, on the new
 * account.
 *
 * While a payment runs or its result is still open, the link is refused with a
 * message, as a manual switch is. Waiting for the payment and then switching
 * would remove the screen that shows its result as soon as the result is there.
 *
 * [reachedFor] is the link whose account is active or was reached; see
 * [accountStep].
 */
@Composable
private fun DeepLinkAccountSwitch(
    graph: AppGraph,
    deepLink: StateFlow<String?>,
    activeAccountId: String?,
    locked: Boolean,
    onDeepLinkHandled: () -> Unit,
) {
    val link by deepLink.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var reachedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(link, activeAccountId, locked) {
        val target = link
        if (target == null) {
            reachedFor = null
            return@LaunchedEffect
        }
        if (locked) return@LaunchedEffect
        val accountId = parseDeepLink(target)?.param("account") ?: return@LaunchedEffect
        val known = graph.accounts.vault.value.accounts.any { it.id == accountId }
        when (accountStep(accountId, activeAccountId, reachedBefore = reachedFor == target, known = known)) {
            AccountStep.Reached -> {
                reachedFor = target
            }
            AccountStep.Drop -> onDeepLinkHandled()
            AccountStep.Switch -> {
                if (refusedDuringPayment(graph, context)) {
                    onDeepLinkHandled()
                    return@LaunchedEffect
                }
                // Null means switched: this effect then restarts on the new id.
                val refused = graph.session.selectAccount(accountId) ?: return@LaunchedEffect
                Toast.makeText(context, refused, Toast.LENGTH_LONG).show()
                onDeepLinkHandled()
            }
        }
    }
}

/**
 * One [ViewModelStore] per account, held in the activity's own model store.
 *
 * A store made with `remember` died with the composition, and the activity is
 * recreated by every configuration change the manifest does not claim: a
 * keyboard or barcode scanner connecting, a language change. Every screen
 * model went with it, the NavHost's too, so forms emptied and a request in
 * flight lost its answer. Held here they survive that, as a normal activity's
 * models do, and still go when the account changes or the activity finishes.
 */
private class AccountViewModelStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun storeFor(account: String): ViewModelStore = stores.getOrPut(account) { ViewModelStore() }

    /** Clears every other account's models, and the requests they run. */
    fun keepOnly(account: String) {
        stores.keys.filter { it != account }.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
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
    val activeAccount by graph.session.activeAccount.collectAsStateWithLifecycle()
    val activeStore by graph.session.activeStore.collectAsStateWithLifecycle()
    val stores by graph.session.stores.collectAsStateWithLifecycle()
    val storesLoaded by graph.session.storesLoaded.collectAsStateWithLifecycle()
    val storesFailed = graph.session.lastError.collectAsStateWithLifecycle().value != null
    // Keyed on the ids, not the objects: `Account` carries the selected store,
    // so using it whole would restart this effect on a store switch as well as
    // an account one, and `StoreData` changes on every refresh.
    val activeAccountId = activeAccount?.id
    val activeStoreId = activeStore?.id

    /**
     * The store the screens on the back stack were opened for.
     *
     * A screen opened for store A must never act on store B. Every switch (the
     * picker, the store list, a notification) lands here, so when the active
     * store changes, every screen but the start one goes, saved tab stacks
     * included, before anything opens in the new store. Screens need no store
     * observers of their own, and the Terminal starts fresh.
     *
     * Saveable, so it outlives the lock and a recreation: a store that
     * changed meanwhile is caught when the shell comes back.
     */
    var boundStore by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(activeStoreId) {
        // Null while an account's stores load; not a switch.
        val current = activeStoreId ?: return@LaunchedEffect
        val bound = boundStore
        if (bound != null && bound != current) navController.dropStoreScreens()
        boundStore = current
    }

    // Launched here, not in the effect that asks: the store switch restarts
    // that effect, and would cancel the navigation it is waiting to do.
    val shellScope = rememberCoroutineScope()
    val context = LocalContext.current
    val openInStore: (String, Any) -> Unit = remember(navController, context) {
        { storeId: String, route: Any ->
            shellScope.launch {
                val session = graph.session
                val before = boundStore
                if (session.activeStore.value?.id != storeId) {
                    if (session.stores.value.none { it.id == storeId }) return@launch
                    // Refused while a payment runs or its result is still open,
                    // as a manual switch is: the switch would remove that screen.
                    session.selectStore(storeId)?.let { refused ->
                        Toast.makeText(context, refused, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
                // Opens only once the reset above has run for this store. If
                // the store changes to another one first, the user moved on.
                val bound = snapshotFlow { boundStore }.first { it == storeId || it != before }
                if (bound == storeId) navController.navigateOnce(route)
            }
        }
    }

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

    /** A web address from a link, waiting for the user to say yes. */
    var webLink by rememberSaveable { mutableStateOf<String?>(null) }

    // A loaded list is this account's once one of its stores is active (or it
    // has none); just after a switch it may still be the previous account's.
    val storesSettled = storesFailed || (storesLoaded && (activeStoreId != null || stores.isEmpty()))

    LaunchedEffect(link, hasAccounts, activeAccountId, activeStoreId, stores, storesSettled) {
        val target = link
        if (target == null) {
            // Armed again for next time. Tapping the same shortcut twice sends
            // the identical string, so the guard has to reset once the flow has
            // been drained rather than persist for the life of the screen.
            handledLink = null
            return@LaunchedEffect
        }
        if (target == handledLink) return@LaunchedEffect
        val action = resolveLink(
            link = target,
            hasAccounts = hasAccounts,
            activeAccountId = activeAccountId,
            baseUrl = activeAccount?.baseUrl,
            storeIds = stores.map { it.id },
            storesSettled = storesSettled,
        )
        if (action == LinkAction.Wait) return@LaunchedEffect
        handledLink = target
        if (closesScreens(action, activeStoreId) && refusedDuringPayment(graph, context)) {
            onDeepLinkHandled()
            return@LaunchedEffect
        }
        when (action) {
            LinkAction.Wait, LinkAction.Drop -> Unit
            is LinkAction.Open -> {
                action.markRead?.let { markRead(graph, it) }
                val storeId = action.storeId
                if (storeId != null) openInStore(storeId, action.route) else navController.navigateOnce(action.route)
            }
            is LinkAction.Web -> {
                action.markRead?.let { markRead(graph, it) }
                webLink = action.url
            }
            LinkAction.Terminal -> navController.switchTab(TopLevelTab.Terminal)
            LinkAction.Scan -> {
                // Onto Home itself, not onto a screen Home's saved stack
                // restores: the scanner hands its result to the screen under
                // it, and Home is the one that turns a code into a payment.
                // A screen open on Home is not closed: it can show a payment's
                // result that the user has not seen, and a new scan of the
                // same code would pay it again.
                navController.switchTab(TopLevelTab.Home)
                if (navController.nothingOpenOnHome()) {
                    navController.navigateOnce(ScanRoute(ScanPurpose.SEND_DESTINATION))
                } else {
                    Toast.makeText(context, CLOSE_TO_SCAN, Toast.LENGTH_LONG).show()
                }
            }
        }
        onDeepLinkHandled()
    }

    // Any app can send a link.
    webLink?.let { url -> OpenLinkDialog(url, onOpen = { uriHandler.openUri(url) }, onDismiss = { webLink = null }) }

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
    // The side the rail pads for the system bars, which the screen beside it must not pad again.
    val railInsets = NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)

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
            CompositionLocalProvider(LocalOpenInStore provides openInStore) {
                AppNavHost(
                    navController = navController,
                    startDestination = if (hasAccounts) HomeRoute else WelcomeRoute,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(bottom = padding.calculateBottomPadding())
                        // The bar's height already includes the navigation-bar
                        // inset. Consumed here, so each screen's own Scaffold
                        // does not add it a second time above the bar.
                        .consumeWindowInsets(padding)
                        // Likewise the rail's start inset: a navigation bar on
                        // that side, in landscape.
                        .then(if (showNavigation && wide) Modifier.consumeWindowInsets(railInsets) else Modifier),
                )
            }
        }
    }
}

private const val WIDE_BREAKPOINT_DP = 600

private const val DEEP_LINK_SCHEME = "btcpayapp"

/** The key for the models and saved state of the no-account screens (onboarding). */
private const val NO_ACCOUNT = "no-account"

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

/**
 * Leaves only the start destination, and forgets every tab's saved stack, so no
 * screen opened for one store is still open, or can be restored, in another.
 *
 * `clearBackStack` restores a saved stack and pops it at once, up to and
 * including the tab's own entry. That is right for every tab but Home: Home's
 * saved part begins *above* Home, so that pop would take the root with it and
 * leave nothing on screen. Home's part is restored by navigating to Home
 * instead, then popped like the rest.
 */
private fun NavHostController.dropStoreScreens() {
    val start = graph.findStartDestination().id
    popBackStack(start, inclusive = false, saveState = false)
    TopLevelTab.entries.filter { it != TopLevelTab.Home }.forEach { clearBackStack(it.route) }
    navigate(TopLevelTab.Home.route) {
        launchSingleTop = true
        restoreState = true
    }
    popBackStack(start, inclusive = false, saveState = false)
}

/**
 * True when Home has no screen open above it, or only the scanner, which the
 * Scan shortcut opens anyway. Read just after [switchTab] to Home.
 */
private fun NavHostController.nothingOpenOnHome(): Boolean {
    val start = graph.findStartDestination().id
    val top = currentBackStackEntry?.destination ?: return false
    return top.id == start || (top.hasRoute(ScanRoute::class) && previousBackStackEntry?.destination?.id == start)
}

private fun androidx.navigation.NavGraph.findStartDestination(): NavDestination {
    var node: NavDestination = findNode(startDestinationId) ?: this
    while (node is androidx.navigation.NavGraph) {
        node = node.findNode(node.startDestinationId) ?: break
    }
    return node
}

/** Marks a feed entry read on the server, as a tap in the in-app list does. */
private fun markRead(graph: AppGraph, notificationId: String) {
    graph.scope.launch { runCatching { graph.session.requireApi().markNotification(notificationId, true) } }
}

/**
 * Refuses a link while a payment runs or its result is still open
 * ([com.btcpayapp.data.session.SessionManager.busy]), and says so. True when
 * refused.
 *
 * For a link that must wait for the payment: one in [closesScreens], or one
 * that switches account, which closes every screen. The link is used up (a
 * tapped notification is gone), so the text does not say "try again", and a
 * feed entry is not marked read.
 */
private fun refusedDuringPayment(graph: AppGraph, context: Context): Boolean {
    if (!graph.session.busy.value) return false
    Toast.makeText(context, LINK_DURING_PAYMENT, Toast.LENGTH_LONG).show()
    return true
}

private const val LINK_DURING_PAYMENT =
    "This did not open, because a payment or a store change is in progress, or a payment result is still open. " +
        "Close the result, then open this from the app."

private const val CLOSE_TO_SCAN = "Close the open screen, then scan again."

/**
 * Every push from a link is `launchSingleTop`. The activity is `singleTask`, so
 * tapping a shortcut while that shortcut's screen is already open delivers the
 * intent to the running task rather than starting a new one — and without this
 * it would put a second copy of the same screen on the stack, which the user
 * then has to press back through twice to leave.
 */
private fun NavHostController.navigateOnce(route: Any) {
    navigate(route) { launchSingleTop = true }
}

// --- Deep links -------------------------------------------------------------
//
// Any installed app can send a `btcpayapp://` link, so these rules are a
// security boundary. They are plain functions over java.net.URI rather than
// android.net.Uri, so a JVM test runs them (DeepLinkTest).

/** A `btcpayapp://` link, taken apart. */
internal class DeepLink(val host: String?, val path: List<String>, private val query: Map<String, String>) {
    /** The first value of the query parameter [name], decoded. */
    fun param(name: String): String? = query[name]
}

/** [link] as this app's own link, or null when it is not one or does not parse. */
internal fun parseDeepLink(link: String): DeepLink? {
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    if (uri.scheme != DEEP_LINK_SCHEME) return null
    return runCatching {
        val query = LinkedHashMap<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val name = decodeQueryPart(pair.substringBefore('='))
            if (name !in query) query[name] = decodeQueryPart(pair.substringAfter('=', ""))
        }
        val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decodePathPart)
        DeepLink(uri.host, path, query)
    }.getOrNull()
}

// As android.net.Uri decodes: '+' is a space in a query, and a plus sign in a path.
private fun decodeQueryPart(part: String): String = URLDecoder.decode(part, "UTF-8")
private fun decodePathPart(part: String): String = URLDecoder.decode(part.replace("+", "%2B"), "UTF-8")

/** What [DeepLinkAccountSwitch] does with a link that names an account. */
internal enum class AccountStep { Reached, Switch, Drop }

/**
 * The account rule for a link that names [linkAccount].
 *
 * [reachedBefore] is true when this link's account was active earlier. The
 * user has then picked another account before the link was used (its store
 * list still loading, say), so the link is dropped. Switching back instead
 * would revert every manual switch for as long as the link stayed pending.
 */
internal fun accountStep(linkAccount: String, activeAccountId: String?, reachedBefore: Boolean, known: Boolean): AccountStep =
    when {
        linkAccount == activeAccountId -> AccountStep.Reached
        reachedBefore || !known -> AccountStep.Drop
        else -> AccountStep.Switch
    }

/** What [AppShell] does with a link; see [resolveLink]. */
internal sealed interface LinkAction {
    /** Not yet: the link names another account, or this account's stores are still loading. */
    data object Wait : LinkAction

    /** Nothing to open. */
    data object Drop : LinkAction

    /** Opens [route], in [storeId] first when it is set. [markRead] is a feed entry to mark read. */
    data class Open(val route: Any, val storeId: String? = null, val markRead: String? = null) : LinkAction

    /** Asks, then opens [url] in the browser. */
    data class Web(val url: String, val markRead: String? = null) : LinkAction

    /** The launcher's Terminal shortcut. */
    data object Terminal : LinkAction

    /** The launcher's Scan shortcut. */
    data object Scan : LinkAction
}

/**
 * True when [action] must wait while a payment runs or its result is still open.
 *
 * A switch to another store closes every store screen, the payment's too. The
 * Scan shortcut closes no screen, but it can leave the open tab for a new
 * payment on Home, so the user could scan and pay the same code again before
 * seeing the first result. The Terminal shortcut is not refused: it only
 * switches tab, and the Terminal receives payments. The open tab's stack is
 * saved, and the models of its screens, with their requests, live on.
 */
internal fun closesScreens(action: LinkAction, activeStoreId: String?): Boolean = when (action) {
    is LinkAction.Open -> action.storeId != null && action.storeId != activeStoreId
    LinkAction.Scan -> true
    else -> false
}

/**
 * The rules for [link], given the active account and its stores.
 *
 * Only a link that names its account can move the app to a store, and only to
 * one in that account's list. It waits while the list loads. Once the list has
 * loaded, or failed to, a store missing from it is not coming (a key limited
 * to other stores, or a store deleted since): the link is dropped rather than
 * held, and a feed entry is shown in the list instead. A web address is only
 * offered for a yes, and only when [webHost] can say where it goes.
 */
internal fun resolveLink(
    link: String,
    hasAccounts: Boolean,
    activeAccountId: String?,
    baseUrl: String?,
    storeIds: Collection<String>,
    storesSettled: Boolean,
): LinkAction {
    val parsed = parseDeepLink(link)
    if (parsed == null || !hasAccounts) return LinkAction.Drop
    val accountId = parsed.param("account")
    // Another account's link: `DeepLinkAccountSwitch` switches or drops it.
    if (accountId != null && accountId != activeAccountId) return LinkAction.Wait
    // An entry from the server's notification feed, resolved as a tap in the
    // in-app list would resolve it. Only read once the account above is
    // active, since the link is relative to that server.
    val feed = if (parsed.host == "notification") notificationTarget(parsed.param("link"), baseUrl) else null
    // Only a link that names its account came from this app's own
    // notifications; any other id is not worth a request.
    val readId = if (feed != null && accountId != null) parsed.path.lastOrNull() else null
    // Only a link that names its account can move the app to a store.
    val storeId = if (accountId == null) null else when (feed) {
        null -> parsed.param("store")
        is NotificationTarget.InApp -> parsed.param("store") ?: feed.storeId
        else -> null
    }
    val storeRoute = storeId?.let { (feed as? NotificationTarget.InApp)?.route ?: scopedRoute(parsed) }
    if (storeId != null && storeRoute != null) {
        return when {
            !storesSettled -> LinkAction.Wait
            storeId in storeIds -> LinkAction.Open(storeRoute, storeId, readId)
            feed != null -> LinkAction.Open(NotificationsRoute)
            else -> LinkAction.Drop
        }
    }
    return when (feed) {
        null -> appLinkAction(parsed)
        is NotificationTarget.InApp -> LinkAction.Open(feed.route, markRead = readId)
        is NotificationTarget.Web ->
            if (webHost(feed.url) != null) LinkAction.Web(feed.url, readId) else LinkAction.Open(NotificationsRoute, markRead = readId)
        NotificationTarget.None -> LinkAction.Open(NotificationsRoute, markRead = readId)
    }
}

/**
 * The question before a web link opens: the page's host first, then the whole
 * address, and it opens only on a yes, so the app does not lend its name to a
 * phishing page. [onDismiss] runs on both answers.
 */
@Composable
internal fun OpenLinkDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Open this link?",
        message = webHost(url)?.let { "This opens $it in your browser.\n\n$url" } ?: url,
        confirmLabel = "Open",
        onConfirm = {
            onDismiss()
            onOpen()
        },
        onDismiss = onDismiss,
    )
}

/**
 * The host a web link opens, or null when it is not a plain http(s) link.
 *
 * Parsed strictly: java.net.URI refuses a backslash, a space or a raw
 * non-ASCII host, which browsers read in their own ways. A link with user info
 * is refused too: in `https://pay.mystore.com@evil.example/`, the part the
 * merchant reads is not where the browser goes.
 */
internal fun webHost(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    return uri.host?.takeIf { it.isNotBlank() }
}

/** A link that is not a feed entry: a store's own link opened in the active store, a setting, a shortcut. */
private fun appLinkAction(link: DeepLink): LinkAction {
    scopedRoute(link)?.let { return LinkAction.Open(it) }
    return when (link.host) {
        "settings" -> LinkAction.Open(AccountsRoute)
        "shortcut" -> when (link.path.firstOrNull()) {
            "terminal" -> LinkAction.Terminal
            "new-invoice" -> LinkAction.Open(CreateInvoiceRoute())
            "scan" -> LinkAction.Scan
            else -> LinkAction.Drop
        }
        else -> LinkAction.Drop
    }
}

/** The screen a store's own link opens: `btcpayapp://invoice/<id>` or `btcpayapp://payout/<id>`. */
private fun scopedRoute(link: DeepLink): Any? = when (link.host) {
    "invoice" -> link.path.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::InvoiceDetailRoute)
    "payout" -> PayoutsRoute
    else -> null
}
