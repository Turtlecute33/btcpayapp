package com.btcpayapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.btcpayapp.AppGraph
import com.btcpayapp.data.model.AppSettings

/**
 * Dependency access for composables.
 *
 * This is the whole of the app's "DI framework": one composition local holding
 * the hand-built [AppGraph], plus a helper that turns a lambda into a
 * `ViewModelProvider.Factory`. No annotations, no generated code, no reflection
 * — and a view model's dependencies are visible at its call site rather than
 * hidden behind a qualifier.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("LocalAppGraph was read outside of BtcPayApp()")
}

/**
 * Live app settings, so any screen can honour privacy mode or the unit choice.
 *
 * `compositionLocalOf`, **not** `staticCompositionLocalOf`. A static local does
 * not track its readers: when the provided value changes Compose invalidates
 * the entire content lambda of the provider, which here is the whole app —
 * theme, nav host and every composed screen. Since this value changes whenever
 * any setting is written (privacy mode, theme, sync interval, even the terminal's
 * remembered currency), a static local would mean a full-tree recomposition on
 * each one.
 * A non-static local recomposes only the composables that actually read it.
 */
val LocalSettings = compositionLocalOf { AppSettings() }

/** True while the app-lock overlay is up; screens use it to pause polling. */
val LocalIsLocked = compositionLocalOf { false }

/**
 * Opens a route in a given store: switches to `storeId`, waits until the shell
 * has dropped the old store's screens, then navigates. A store this account
 * cannot see is ignored. While a payment runs or its result is on screen, the
 * switch is refused with a message, as a manual switch is, so the result stays
 * on screen.
 *
 * The one way into another store's screen. Navigating first and switching
 * after would leave a screen open on a store it was not opened for, or have
 * the switch pop it straight away.
 */
val LocalOpenInStore = staticCompositionLocalOf<(storeId: String, route: Any) -> Unit> {
    error("LocalOpenInStore is provided by AppShell")
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppGraph) -> VM,
): VM {
    val graph = LocalAppGraph.current
    // Remembered: the factory is only consulted when the view model is first
    // created, but building it unremembered would allocate a fresh factory and
    // initializer list on every recomposition of every screen in the app.
    val factory = remember(graph) { viewModelFactory { initializer { create(graph) } } }
    return viewModel(key = key, factory = factory)
}
