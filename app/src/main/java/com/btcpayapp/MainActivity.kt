package com.btcpayapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.ui.BtcPayApp
import com.btcpayapp.ui.components.clearExpiredSensitiveClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's single activity.
 *
 * It extends [FragmentActivity] rather than `ComponentActivity` because
 * `BiometricPrompt` requires it — that is the only reason, and the UI is
 * entirely Compose.
 */
class MainActivity : FragmentActivity() {

    private val deepLink = MutableStateFlow<String?>(null)

    /**
     * Hoisted to a field. Calling `deepLink.asStateFlow()` inside `setContent`
     * builds a new wrapper object on every recomposition of the root, so
     * `BtcPayApp` would receive a parameter that never compares equal and
     * could never skip — defeating recomposition skipping for the entire app
     * tree.
     */
    private val deepLinkFlow = deepLink.asStateFlow()

    private val graph: AppGraph
        get() = (application as BtcPayApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()

        // Draw behind the system bars with transparent scrims, which is the
        // required behaviour from targetSdk 35 onwards anyway.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )

        super.onCreate(savedInstanceState)

        // The session's store, user and server requests serve only the UI.
        // Started here rather than when the graph is built, so a process
        // started for a sync job or at boot does not send them.
        graph.session.start()

        // Hold the system splash until the encrypted vault has been opened, so
        // the first frame is never the "no accounts" screen for someone who has
        // accounts.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        lifecycleScope.launch {
            // Released early when a document cannot be read: `BtcPayApp` shows
            // that as an error with "Try again", which the splash cannot. The
            // timeout only bounds a read that never answers at all (a wedged
            // keystore daemon); the app then stays blank until it does.
            withTimeoutOrNull(SPLASH_TIMEOUT_MS) {
                merge(
                    flow { emit(graph.awaitReady()) },
                    graph.storageUnreadable.filter { it }.map { },
                ).first()
            }
            ready = true
        }

        applyScreenCapturePolicy(graph)
        // Only on a fresh start. The Activity retains the launching intent, so
        // doing this unconditionally would mean that returning via recents
        // after a process death re-fires an old notification's deep link and
        // throws away the restored back stack.
        if (savedInstanceState == null) deepLink.value = intent?.dataString

        setContent {
            BtcPayApp(
                graph = graph,
                activity = this,
                deepLink = deepLinkFlow,
                onDeepLinkHandled = {
                    deepLink.value = null
                    // Also clear it on the Activity, or `onCreate` after a
                    // process death would find it again.
                    intent?.data = null
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.dataString
    }

    /** Feeds the idle lock; see [com.btcpayapp.core.security.AppLock.armIdleLock]. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        graph.appLock.onUserInteraction()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A sensitive clip whose clear came due in the background, where the
        // clipboard cannot be read, or while the app was not running, is
        // checked now that it can be.
        if (hasFocus) clearExpiredSensitiveClip(this)
    }

    /**
     * `FLAG_SECURE` blocks screenshots and screen recording, and blanks the
     * window in the recents switcher. Default on: a merchant's invoice list is
     * a revenue ledger, and the recents thumbnail is the easiest place to read
     * it over someone's shoulder.
     *
     * With app lock on, the recents thumbnail is withheld as well, even when
     * screenshots are allowed. The lock engages only on the way back in, so
     * the thumbnail taken on the way out would show the last screen to anyone
     * holding the phone. From API 33 the system is told to take none. Below
     * that there is no such call, and the recents animation can take the
     * thumbnail while the app is still in front, before a flag set on the way
     * out applies. So there the window stays secure while the lock is on.
     */
    private fun applyScreenCapturePolicy(graph: AppGraph) {
        lifecycleScope.launch {
            // `repeatOnLifecycle` so the collector is not live while the
            // Activity is stopped, and `distinctUntilChanged` on the two fields
            // that matter — otherwise every unrelated settings write (theme,
            // sync interval, terminal currency) would re-apply the window flag
            // and force a relayout.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.settings.settings
                    .map { it.blockScreenCapture to (it.appLock != AppLockMode.Off) }
                    .distinctUntilChanged()
                    .collect { (block, lock) ->
                        val recentsCall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        if (recentsCall) setRecentsScreenshotEnabled(!lock)
                        setSecure(block || (lock && !recentsCall))
                    }
            }
        }
    }

    private fun setSecure(secure: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        // Changed only when it differs: each change relayouts the window.
        if (((window.attributes.flags and flag) != 0) == secure) return
        if (secure) window.addFlags(flag) else window.clearFlags(flag)
    }

    private companion object {
        const val SPLASH_TIMEOUT_MS = 5_000L
    }
}
