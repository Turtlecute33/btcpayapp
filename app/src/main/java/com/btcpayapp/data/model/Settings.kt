package com.btcpayapp.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,

    // --- Appearance --------------------------------------------------------
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You. On by default; falls back to the built-in palette below API 31. */
    val dynamicColor: Boolean = true,
    /** True black surfaces for OLED panels. */
    val pureBlackDark: Boolean = false,
    val bitcoinUnit: BitcoinUnit = BitcoinUnit.Sat,
    /** Masks every amount until tapped — useful at a market stall. */
    val privacyMode: Boolean = false,

    // --- Security ----------------------------------------------------------
    val appLock: AppLockMode = AppLockMode.Off,
    val lockAfterSeconds: Int = 60,
    /** FLAG_SECURE: blocks screenshots and blanks the app in the recents switcher. */
    val blockScreenCapture: Boolean = true,
    /** Requires re-authentication before spending, regardless of the lock timer. */
    val confirmSpendsWithBiometrics: Boolean = true,

    // --- Background --------------------------------------------------------
    val backgroundSync: Boolean = true,
    val syncIntervalMinutes: Int = 15,
    val syncOnUnmeteredOnly: Boolean = false,
    val notifyPayments: Boolean = true,
    val notifyPayouts: Boolean = true,
    val notifyServerIssues: Boolean = true,
    /** Mirrors the server's own notification feed into the system shade. */
    val notifyServerNotifications: Boolean = true,

    // --- Lightning ---------------------------------------------------------
    /**
     * Names the operator gave their channel peers, keyed by lowercase node
     * pubkey.
     *
     * Kept here rather than on the account because a node's identity is global:
     * the same peer seen from two BTCPay servers is the same peer, and having
     * to name it twice would be a bug, not a feature. Nothing is sent anywhere —
     * [com.btcpayapp.core.lightning.NodeDirectory] never leaves the device.
     */
    val lightningNodeNicknames: Map<String, String> = emptyMap(),

    // --- Terminal ----------------------------------------------------------
    val terminalCurrency: String? = null,
    val terminalKeepScreenOn: Boolean = true,
    val terminalSoundOnPaid: Boolean = true,
    val terminalVibrateOnPaid: Boolean = true,
    val terminalTipPercentages: List<Int> = listOf(10, 15, 20),
    val terminalAskForTip: Boolean = false,
)

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class BitcoinUnit {
    /** 100 000 000 sat. */
    Btc,

    /** The default: merchant amounts are small and sats avoid leading zeros. */
    Sat,
}

@Serializable
enum class AppLockMode {
    Off,

    /** Biometric, with the device PIN/pattern/password as fallback. */
    Biometric,
}
