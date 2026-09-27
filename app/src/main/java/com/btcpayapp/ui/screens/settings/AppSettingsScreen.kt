package com.btcpayapp.ui.screens.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.LocalActivity
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.security.AuthOutcome
import com.btcpayapp.core.security.BiometricAvailability
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.attempt
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.data.model.ThemeMode
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.CONFIRM_PAYMENTS_LABEL
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppSettingsViewModel(private val graph: AppGraph) : ViewModel() {

    val settings = graph.settings.settings
    val vault = graph.accounts.vault

    private val _wiped = MutableStateFlow(false)
    val wiped = _wiped.asStateFlow()
    private val _error = MutableStateFlow<ApiException?>(null)
    val error = _error.asStateFlow()
    fun dismissError() { _error.value = null }
    fun authenticationFailed() { _error.value = ApiException.Transport("Not confirmed, so nothing was changed.") }

    /** The repository never throws here; false means the change was not stored. */
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            if (!graph.settings.update(transform)) _error.value = ApiException.Transport("Could not save the setting.")
        }
    }

    fun wipe() {
        viewModelScope.launch {
            attempt { graph.wipe() }.onSuccess { _wiped.value = true }.onFailure {
                _error.value = ApiException.Transport("Could not remove all data. Try again or clear app storage in Android Settings.")
            }
        }
    }
}

@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    onAccounts: () -> Unit,
    onAbout: () -> Unit,
) {
    val viewModel = appViewModel { AppSettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val vault by viewModel.vault.collectAsStateWithLifecycle()
    // An http:// onion account sends its API key in clear text to Orbot's
    // port on this phone, in the background too.
    val cleartextOnion = vault.accounts.any { it.isOnion && it.baseUrl.startsWith("http://", ignoreCase = true) }
    val wiped by viewModel.wiped.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The lock switches, raising "Lock after", letting screenshots through,
    // showing amounts and erasing everything.
    val owner = rememberOwnerCheck(onFailed = viewModel::authenticationFailed)
    val authenticating = owner.busy
    // Only changes that weaken protection ask; tightening one never does.
    val secureUpdate: (weakens: Boolean, transform: (AppSettings) -> AppSettings) -> Unit = { weakens, transform ->
        if (weakens) owner.confirm("Change security settings") { viewModel.update(transform) } else viewModel.update(transform)
    }

    var confirmWipe by remember { mutableStateOf(false) }

    // Read again on every return: the no-screen-lock text sends the user to
    // the phone's settings, and the switches must follow what they did there.
    var biometrics by remember { mutableStateOf(Biometrics.availability(context)) }
    var screenLock by remember { mutableStateOf(Biometrics.hasScreenLock(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        biometrics = Biometrics.availability(context)
        screenLock = Biometrics.hasScreenLock(context)
    }
    val biometricsUsable = biometrics == BiometricAvailability.Available
    // Confirm payments is on, but nothing can confirm one.
    val paymentsBlocked = settings.confirmSpendsWithBiometrics && !screenLock

    LaunchedEffect(wiped) {
        if (wiped) onBack()
    }

    // No job scheduling here. BtcPayApplication follows the three sync
    // settings already, and a second schedule() each time this screen opens
    // would stop a sync that is running.

    AppScreen(title = "Settings", onBack = onBack, large = true) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Appearance -------------------------------------------------
            ErrorBanner(error, onDismiss = viewModel::dismissError)

            // Each group arrives as a group. This page is five headings and
            // twenty controls, and fading it in as one sheet gives the eye no
            // clue where the headings are.
            FormSection(title = "Appearance", modifier = Modifier.arrive(0)) {
                FormDropdown(
                    label = "Theme",
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) } },
                    optionLabel = { mode ->
                        when (mode) {
                            ThemeMode.System -> "Follow the system"
                            ThemeMode.Light -> "Light"
                            ThemeMode.Dark -> "Dark"
                        }
                    },
                )

                FormSwitch(
                    title = "Use the wallpaper palette",
                    checked = settings.dynamicColor && DYNAMIC_COLOUR_SUPPORTED,
                    onCheckedChange = { value -> viewModel.update { it.copy(dynamicColor = value) } },
                    enabled = DYNAMIC_COLOUR_SUPPORTED,
                    description = if (DYNAMIC_COLOUR_SUPPORTED) {
                        "Material You derives the colours from your wallpaper."
                    } else {
                        "Android 12 introduced wallpaper-derived colours; this device runs " +
                            "Android ${Build.VERSION.RELEASE}, so the built-in palette is used."
                    },
                )

                FormSwitch(
                    title = "Pure black in dark mode",
                    checked = settings.pureBlackDark,
                    onCheckedChange = { value -> viewModel.update { it.copy(pureBlackDark = value) } },
                    description = "Saves power on an OLED panel and is easier on the eyes at a " +
                        "night market.",
                )

                FormDropdown(
                    label = "Bitcoin unit",
                    options = BitcoinUnit.entries,
                    selected = settings.bitcoinUnit,
                    onSelect = { unit -> viewModel.update { it.copy(bitcoinUnit = unit) } },
                    optionLabel = { unit ->
                        when (unit) {
                            BitcoinUnit.Btc -> "BTC"
                            BitcoinUnit.Sat -> "Satoshi"
                        }
                    },
                )

                FormSwitch(
                    title = "Privacy mode",
                    checked = settings.privacyMode,
                    // Off also puts amounts and store names back in alerts on
                    // the lock screen when the app lock is off, so it asks.
                    onCheckedChange = { value -> secureUpdate(!value) { it.copy(privacyMode = value) } },
                    enabled = !authenticating,
                    // There is no tap-to-reveal, so the text must not promise one.
                    description = "Hides amounts on screen and in alerts. Turn it off to see them.",
                )
            }

            ThinDivider()

            // --- Security ---------------------------------------------------

            FormSection(title = "Security", modifier = Modifier.arrive(1)) {
                FormSwitch(
                    title = "Lock the app",
                    checked = settings.appLock != AppLockMode.Off,
                    onCheckedChange = { value ->
                        // Turning it on asks too: it proves the prompt works
                        // before the app depends on it to open.
                        secureUpdate(true) {
                            it.copy(appLock = if (value) AppLockMode.Biometric else AppLockMode.Off)
                        }
                    },
                    enabled = biometricsUsable && !authenticating,
                    description = biometrics.explain(),
                )

                FormDropdown(
                    label = "Lock after",
                    options = LOCK_DELAYS,
                    selected = settings.lockAfterSeconds,
                    onSelect = { seconds ->
                        secureUpdate(seconds > settings.lockAfterSeconds) { it.copy(lockAfterSeconds = seconds) }
                    },
                    enabled = biometricsUsable && settings.appLock != AppLockMode.Off && !authenticating,
                    // The Terminal and the payment-code screens keep the screen
                    // on, so the app never leaves them; time without a touch
                    // then counts too (see AppLock.armIdleLock).
                    supportingText = "Locks after this long away from the app. On the Terminal, also " +
                        "after this long without a touch (at least a minute). While a payment code " +
                        "is showing, after 15 minutes without a touch.",
                    optionLabel = ::lockDelayLabel,
                )

                FormSwitch(
                    title = "Block screenshots",
                    checked = settings.blockScreenCapture,
                    onCheckedChange = { value -> secureUpdate(!value) { it.copy(blockScreenCapture = value) } },
                    enabled = !authenticating,
                    description = "Stops screen recording and blanks the app in the recents switcher. " +
                        "The recents thumbnail is the easiest place to read a takings list over your " +
                        "shoulder. On Android 12 and older, the app lock also blocks screenshots and " +
                        "recording.",
                )

                FormSwitch(
                    title = CONFIRM_PAYMENTS_LABEL,
                    checked = settings.confirmSpendsWithBiometrics,
                    onCheckedChange = { value ->
                        secureUpdate(true) { it.copy(confirmSpendsWithBiometrics = value) }
                    },
                    // Turning it on needs a working prompt. Turning it off stays
                    // open on a phone with no screen lock, where the gate refuses
                    // every payment and no prompt could ever confirm the change.
                    enabled = !authenticating && (biometricsUsable || paymentsBlocked),
                    description = if (paymentsBlocked) {
                        "This phone has no screen lock, so payments cannot be confirmed. Set a screen " +
                            "lock in the phone's settings, or turn this off."
                    } else {
                        "Asks for your fingerprint, face or PIN before any action that sends funds, " +
                            "changes where the store receives funds or sends its events or email, accepts less " +
                            "as paid, or gives someone control of the store."
                    },
                )
            }

            ThinDivider()

            // --- Terminal ---------------------------------------------------

            // These were read by the Terminal but had no switch.
            FormSection(title = "Terminal", modifier = Modifier.arrive(2)) {
                FormSwitch(
                    title = "Keep the screen on",
                    checked = settings.terminalKeepScreenOn,
                    onCheckedChange = { value -> viewModel.update { it.copy(terminalKeepScreenOn = value) } },
                    description = "At the terminal and while a customer pays.",
                )
                FormSwitch(
                    title = "Vibrate when paid",
                    checked = settings.terminalVibrateOnPaid,
                    onCheckedChange = { value -> viewModel.update { it.copy(terminalVibrateOnPaid = value) } },
                )
                FormSwitch(
                    title = "Ask for a tip",
                    checked = settings.terminalAskForTip,
                    onCheckedChange = { value -> viewModel.update { it.copy(terminalAskForTip = value) } },
                    description = "Offers 10, 15 or 20 % before charging.",
                )
            }

            ThinDivider()

            // --- Background -------------------------------------------------

            // Payment alerts come from polling this server, not from a push
            // service. Firebase would be less battery-hungry, but it would put
            // a third party — and Google's servers — in the path of every
            // payment event, which is the opposite of the point of self-hosting.
            FormSection(title = "Background", modifier = Modifier.arrive(3)) {
                FormSwitch(
                    title = "Check for activity in the background",
                    checked = settings.backgroundSync,
                    onCheckedChange = { value -> viewModel.update { it.copy(backgroundSync = value) } },
                    description = "The app polls your server directly. No push service is involved, " +
                        "so no third party learns when you are paid." +
                        if (cleartextOnion && settings.backgroundSync) {
                            " While Orbot is off, another app can take its port and read your .onion " +
                                "account's API key. Keep Orbot always on, or turn this off."
                        } else {
                            ""
                        },
                )

                FormDropdown(
                    label = "How often",
                    options = SYNC_INTERVALS,
                    selected = settings.syncIntervalMinutes,
                    onSelect = { minutes -> viewModel.update { it.copy(syncIntervalMinutes = minutes) } },
                    enabled = settings.backgroundSync,
                    // 15 minutes is the platform floor for a periodic JobScheduler
                    // job; anything shorter is silently rounded up by the system.
                    supportingText = "Android will not run a periodic job more often than every " +
                        "15 minutes.",
                    optionLabel = { minutes -> if (minutes >= 60) "Every hour" else "Every $minutes minutes" },
                )

                FormSwitch(
                    title = "Only on Wi-Fi",
                    checked = settings.syncOnUnmeteredOnly,
                    onCheckedChange = { value -> viewModel.update { it.copy(syncOnUnmeteredOnly = value) } },
                    enabled = settings.backgroundSync,
                    description = "Skips the check on a metered connection.",
                )

                FormSwitch(
                    title = "Payment notifications",
                    checked = settings.notifyPayments,
                    onCheckedChange = { value -> viewModel.update { it.copy(notifyPayments = value) } },
                    enabled = settings.backgroundSync,
                )

                FormSwitch(
                    title = "Payout notifications",
                    checked = settings.notifyPayouts,
                    onCheckedChange = { value -> viewModel.update { it.copy(notifyPayouts = value) } },
                    enabled = settings.backgroundSync,
                )

                FormSwitch(
                    title = "BTCPay notifications",
                    checked = settings.notifyServerNotifications,
                    onCheckedChange = { value -> viewModel.update { it.copy(notifyServerNotifications = value) } },
                    enabled = settings.backgroundSync,
                    description = "Shows the server's own notifications, such as new versions and users awaiting " +
                        "approval. One extra request per server on each check.",
                )

                FormSwitch(
                    title = "Server problems",
                    checked = settings.notifyServerIssues,
                    onCheckedChange = { value -> viewModel.update { it.copy(notifyServerIssues = value) } },
                    enabled = settings.backgroundSync,
                    description = "Warns when your server stops answering or falls behind the chain.",
                )
            }

            ThinDivider()

            // --- Data -------------------------------------------------------

            FormSection(title = "Data", modifier = Modifier.arrive(4)) {
                LinkRow(
                    title = "Accounts",
                    description = "Servers, keys and certificates.",
                    onClick = onAccounts,
                )
                LinkRow(
                    title = "About",
                    description = "Version, privacy and links.",
                    onClick = onAbout,
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmWipe = true },
                enabled = !authenticating,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(5),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text("Erase everything", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Erase everything?",
            message = "Every account, key and setting is deleted from this device, and the " +
                "encryption key is destroyed. API keys are not revoked on your servers — do that " +
                "from each account first if you need to.",
            confirmLabel = "Erase",
            destructive = true,
            onConfirm = {
                confirmWipe = false
                owner.confirm("Erase everything", viewModel::wipe)
            },
            onDismiss = { confirmWipe = false },
        )
    }
}

/**
 * Runs an action only once the owner has proved who they are: a change that
 * weakens the app's protection, or one that deletes what it holds. Someone
 * holding the unlocked phone for a moment must not be able to weaken the lock
 * for later or throw accounts away. Shared with the account screen.
 *
 * It asks whenever the phone has a screen lock, whatever [CONFIRM_PAYMENTS_LABEL]
 * says. With no screen lock nothing can prove it, so the tap (and the action's
 * own dialog) is all it takes: a switch that needs an impossible prompt to turn
 * off is a trap. [busy] is true while a prompt is on its way or showing, so a
 * second tap cannot start another.
 */
@Stable
internal class OwnerCheck(
    private val context: Context,
    private val activity: FragmentActivity?,
    private val scope: CoroutineScope,
    private val onFailed: State<() -> Unit>,
) {
    var busy by mutableStateOf(false)
        private set

    fun confirm(title: String, action: () -> Unit) {
        when {
            busy -> Unit
            !Biometrics.hasScreenLock(context) -> action()
            else -> {
                busy = true
                scope.launch {
                    try {
                        when (activity?.let { Biometrics.prompt(it, title) }) {
                            is AuthOutcome.Success -> action()
                            is AuthOutcome.Cancelled -> Unit
                            else -> onFailed.value()
                        }
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}

/** The [OwnerCheck] for this screen. [onFailed] runs when a prompt did not confirm. */
@Composable
internal fun rememberOwnerCheck(onFailed: () -> Unit): OwnerCheck {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val failed = rememberUpdatedState(onFailed)
    return remember(context, activity, scope) { OwnerCheck(context, activity, scope, failed) }
}

@Composable
private fun LinkRow(title: String, description: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DYNAMIC_COLOUR_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private val LOCK_DELAYS = listOf(0, 30, 60, 300, 900)

private val SYNC_INTERVALS = listOf(15, 30, 60)

private fun lockDelayLabel(seconds: Int): String = when (seconds) {
    0 -> "Immediately"
    30 -> "After 30 seconds"
    60 -> "After 1 minute"
    300 -> "After 5 minutes"
    900 -> "After 15 minutes"
    else -> "After $seconds seconds"
}

/** Says exactly why the toggle is unavailable, rather than greying it out mutely. */
private fun BiometricAvailability.explain(): String = when (this) {
    BiometricAvailability.Available ->
        "Asks for your fingerprint, face or device PIN when the app comes back to the foreground."
    BiometricAvailability.NoHardware ->
        "This device has no biometric sensor and no screen lock the app can use."
    BiometricAvailability.HardwareUnavailable ->
        "The biometric sensor is busy or switched off right now. Try again shortly."
    BiometricAvailability.NotEnrolled ->
        "Set up a screen lock or enrol a fingerprint in the system settings first."
    BiometricAvailability.SecurityUpdateRequired ->
        "A pending security update has to be installed before biometrics can be trusted."
    BiometricAvailability.Unsupported ->
        "This Android version cannot offer a strong biometric prompt to the app."
    BiometricAvailability.Unknown ->
        "The device did not report whether it can authenticate you."
}
