package com.btcpayapp.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
    /** Hides amounts on screen — useful at a market stall. There is no tap-to-reveal. */
    val privacyMode: Boolean = false,

    // --- Security ----------------------------------------------------------
    val appLock: AppLockMode = AppLockMode.Off,
    /**
     * How long the app may be away before it locks. On the Terminal it is also
     * the idle time without a touch, never less than 60 s.
     */
    val lockAfterSeconds: Int = 60,
    /** FLAG_SECURE: blocks screenshots and blanks the app in the recents switcher. */
    val blockScreenCapture: Boolean = true,
    /**
     * Asks for biometrics or the device PIN, whatever the lock timer, before
     * every action that sends funds, changes where the store receives funds,
     * or gives someone control of the store.
     */
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
    /**
     * The Terminal's currency by `"$accountId|$storeId"`. No entry means the
     * store's default currency. One global value would carry one store's
     * choice into every other store and account.
     */
    val terminalCurrencies: Map<String, String> = emptyMap(),
    val terminalKeepScreenOn: Boolean = true,
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

/**
 * Stored by name. A name this build does not know (a rename, a mode of a newer
 * build) reads as [Biometric]. The plain enum serializer would read it as the
 * property's default, [Off], because the app's Json coerces unknown enum values,
 * and the lock would turn itself off without a word.
 */
@Serializable(with = AppLockModeSerializer::class)
enum class AppLockMode {
    Off,

    /** Biometric, with the device PIN/pattern/password as fallback. */
    Biometric,
}

internal object AppLockModeSerializer : KSerializer<AppLockMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AppLockMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AppLockMode) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): AppLockMode {
        val name = decoder.decodeString()
        return AppLockMode.entries.firstOrNull { it.name == name } ?: AppLockMode.Biometric
    }
}
