package com.btcpayapp

import android.content.Intent
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
import com.btcpayapp.ui.BtcPayApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()

        // Draw behind the system bars with transparent scrims, which is the
        // required behaviour from targetSdk 35 onwards anyway.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )

        super.onCreate(savedInstanceState)

        val graph = (application as BtcPayApplication).graph

        // Hold the system splash until the encrypted vault has been opened, so
        // the first frame is never the "no accounts" screen for someone who has
        // accounts.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        lifecycleScope.launch {
            // Bounded. `awaitReady()` waits on two Keystore-backed decrypts; if
            // the keystore daemon is wedged, an unbounded wait would leave the
            // user staring at the splash screen with no way out. Falling
            // through renders the app's own empty state instead, which is
            // recoverable.
            withTimeoutOrNull(SPLASH_TIMEOUT_MS) { graph.awaitReady() }
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

    /**
     * `FLAG_SECURE` blocks screenshots and screen recording, and blanks the
     * window in the recents switcher. Default on: a merchant's invoice list is
     * a revenue ledger, and the recents thumbnail is the easiest place to read
     * it over someone's shoulder.
     */
    private fun applyScreenCapturePolicy(graph: AppGraph) {
        lifecycleScope.launch {
            // `repeatOnLifecycle` so the collector is not live while the
            // Activity is stopped, and `distinctUntilChanged` on the one field
            // that matters — otherwise every unrelated settings write (theme,
            // sync interval, terminal currency) would re-apply the window flag
            // and force a relayout.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.settings.settings
                    .map { it.blockScreenCapture }
                    .distinctUntilChanged()
                    .collect { block ->
                        if (block) {
                            window.setFlags(
                                WindowManager.LayoutParams.FLAG_SECURE,
                                WindowManager.LayoutParams.FLAG_SECURE,
                            )
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
            }
        }
    }

    private companion object {
        const val SPLASH_TIMEOUT_MS = 5_000L
    }
}
