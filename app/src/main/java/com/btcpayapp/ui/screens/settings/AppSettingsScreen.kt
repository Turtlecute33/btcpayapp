package com.btcpayapp.ui.screens.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.LocalActivity
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.btcpayapp.AppGraph
import com.btcpayapp.core.security.BiometricAvailability
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.sync.SyncScheduler
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.model.BitcoinUnit
import com.btcpayapp.data.model.ThemeMode
import com.btcpayapp.ui.appViewModel
import com.btcpayapp.ui.components.AppScreen
import com.btcpayapp.ui.components.ConfirmDialog
import com.btcpayapp.ui.components.ErrorBanner
import com.btcpayapp.ui.components.FormDropdown
import com.btcpayapp.ui.components.FormSection
import com.btcpayapp.ui.components.FormSwitch
import com.btcpayapp.ui.components.ThinDivider
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppSettingsViewModel(private val graph: AppGraph) : ViewModel() {

    val settings = graph.settings.settings

    private val _wiped = MutableStateFlow(false)
    val wiped = _wiped.asStateFlow()
    private val _error = MutableStateFlow<com.btcpayapp.data.api.ApiException?>(null)
    val error = _error.asStateFlow()
    fun dismissError() { _error.value = null }
    fun authenticationFailed() { _error.value = com.btcpayapp.data.api.ApiException.Transport("Authenticate to change this security setting.") }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { graph.settings.update(transform) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _error.value = com.btcpayapp.data.api.ApiException.Transport("Could not save settings. Check device storage and try again.")
            }
        }
    }

    fun wipe() {
        viewModelScope.launch {
            runCatching { graph.wipe() }.onSuccess { _wiped.value = true }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _error.value = com.btcpayapp.data.api.ApiException.Transport("Could not remove all data. Try again or clear app storage in Android Settings.")
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
    val wiped by viewModel.wiped.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var authenticating by remember { mutableStateOf(false) }
    val secureUpdate: ((AppSettings) -> AppSettings) -> Unit = { transform ->
        if (!authenticating) {
            authenticating = true
            scope.launch {
                try {
                    if (activity != null && Biometrics.authenticate(activity, "Change security settings")) viewModel.update(transform)
                    else viewModel.authenticationFailed()
                } finally { authenticating = false }
            }
        }
    }

    var confirmWipe by remember { mutableStateOf(false) }

    val biometrics = remember { Biometrics.availability(context) }
    val biometricsUsable = biometrics == BiometricAvailability.Available

    LaunchedEffect(wiped) {
        if (wiped) onBack()
    }

    // The job is re-armed here rather than inside the repository so the settings
    // document stays a plain value object with no Android dependency.
    LaunchedEffect(settings.backgroundSync, settings.syncIntervalMinutes, settings.syncOnUnmeteredOnly) {
        if (settings.backgroundSync) {
            SyncScheduler.schedule(context, settings.syncIntervalMinutes, settings.syncOnUnmeteredOnly)
        } else {
            SyncScheduler.cancel(context)
        }
    }

    AppScreen(title = "Settings", onBack = onBack, large = true) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Appearance -------------------------------------------------
            ErrorBanner(error, onDismiss = viewModel::dismissError)

            // Each group arrives as a group. This page is four headings and
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
                    onCheckedChange = { value -> viewModel.update { it.copy(privacyMode = value) } },
                    description = "Masks every amount until you tap it.",
                )
            }

            ThinDivider()

            // --- Security ---------------------------------------------------

            FormSection(title = "Security", modifier = Modifier.arrive(1)) {
                FormSwitch(
                    title = "Lock the app",
                    checked = settings.appLock != AppLockMode.Off,
                    onCheckedChange = { value ->
                        secureUpdate {
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
                    onSelect = { seconds -> viewModel.update { it.copy(lockAfterSeconds = seconds) } },
                    enabled = biometricsUsable && settings.appLock != AppLockMode.Off,
                    optionLabel = ::lockDelayLabel,
                )

                FormSwitch(
                    title = "Block screenshots",
                    checked = settings.blockScreenCapture,
                    onCheckedChange = { value -> viewModel.update { it.copy(blockScreenCapture = value) } },
                    description = "Stops screen recording and blanks the app in the recents switcher. " +
                        "The recents thumbnail is the easiest place to read a takings list over your " +
                        "shoulder.",
                )

                FormSwitch(
                    title = "Confirm spending",
                    checked = settings.confirmSpendsWithBiometrics,
                    onCheckedChange = { value ->
                        secureUpdate { it.copy(confirmSpendsWithBiometrics = value) }
                    },
                    enabled = biometricsUsable && !authenticating,
                    description = "Asks for your fingerprint or PIN before a send or a payout, even " +
                        "when the app is already unlocked.",
                )
            }

            ThinDivider()

            // --- Background -------------------------------------------------

            // Payment alerts come from polling this server, not from a push
            // service. Firebase would be less battery-hungry, but it would put
            // a third party — and Google's servers — in the path of every
            // payment event, which is the opposite of the point of self-hosting.
            FormSection(title = "Background", modifier = Modifier.arrive(2)) {
                FormSwitch(
                    title = "Check for activity in the background",
                    checked = settings.backgroundSync,
                    onCheckedChange = { value -> viewModel.update { it.copy(backgroundSync = value) } },
                    description = "The app polls your server directly. No push service is involved, " +
                        "so no third party learns when you are paid.",
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

            FormSection(title = "Data", modifier = Modifier.arrive(3)) {
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).arrive(4),
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
                viewModel.wipe()
            },
            onDismiss = { confirmWipe = false },
        )
    }
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
