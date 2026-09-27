package com.btcpayapp.core.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.btcpayapp.core.crypto.Keystore
import com.btcpayapp.core.util.Log
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.session.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Screen lock for the app itself.
 *
 * A gate in the UI, not encryption. While it is up nothing behind it is drawn,
 * but the vault key is not bound to it: background sync must read the vault
 * while the phone sits locked in a pocket. On a rooted or instrumented device
 * the data can therefore be read without this prompt.
 *
 * On API 30+ the prompt is handed a [javax.crypto.Cipher] from a Keystore key
 * that needs user authentication, and unlocking needs that cipher to work. That
 * proves a Keystore operation ran after the user authenticated, rather than
 * trusting a callback that said true. Below API 30 it is a plain prompt.
 */
class AppLock(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds (see the note on [onEnterBackground]); a test moves it by hand. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    // `@Volatile` because these are written by the settings collector on
    // `Dispatchers.Default` and read by `onEnterForeground`/`onEnterBackground`,
    // which ProcessLifecycleOwner invokes on the main thread. Without a
    // happens-before edge the main thread could keep observing the initial
    // `enabled = false` after the collector had set it true — and an app lock
    // that silently never locks is the one failure it must not have.
    @Volatile
    private var backgroundedAt: Long = 0L

    /** Whether an in-app prompt was up when the app went to the background. */
    @Volatile
    private var leftDuringPrompt: Boolean = false

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var graceSeconds: Int = 60

    @Volatile
    private var lastInteraction: Long = clock()

    // Written on the main thread only (lifecycle callbacks and composition);
    // `@Volatile` for the idle watch, which reads them on `Dispatchers.Default`.
    // One idle floor per arm; the list is replaced, never changed in place, so
    // the watch always reads a whole one.
    @Volatile
    private var armedFloors: List<Int> = emptyList()

    @Volatile
    private var foreground: Boolean = false

    private var idleWatch: Job? = null

    init {
        scope.launch {
            settings.loaded.first { it }
            settings.settings.collect { current ->
                enabled = current.appLock != AppLockMode.Off
                graceSeconds = current.lockAfterSeconds
                if (!enabled) _locked.value = false
            }
        }
        scope.launch {
            // Start locked if the feature is on, so a cold start always asks.
            settings.loaded.first { it }
            if (settings.settings.value.appLock != AppLockMode.Off) _locked.value = true
        }
    }

    // `elapsedRealtime`, not `currentTimeMillis`. The wall clock is adjustable
    // and jumps on NTP sync and timezone changes: a backward jump would make
    // `away` negative so the app would not lock on return, and a forward jump
    // would lock it mid-sale. `elapsedRealtime` is monotonic and counts through
    // sleep.
    fun onEnterBackground() {
        foreground = false
        backgroundedAt = clock()
        leftDuringPrompt = Biometrics.promptShowing
        updateIdleWatch()
    }

    fun onEnterForeground() {
        foreground = true
        lastInteraction = clock()
        updateIdleWatch()
        if (!enabled || _locked.value || backgroundedAt == 0L) return
        val away = (clock() - backgroundedAt) / 1000
        // Below API 30 the PIN fallback of an in-app prompt is a system
        // activity, so confirming a payment "leaves" the app. Locking on that
        // return would remove the screen and cancel the action being confirmed.
        // Only a short trip is excused: a prompt left open while the user walks
        // away must not switch the lock off.
        val promptTrip = leftDuringPrompt && away < PROMPT_TRIP_SECONDS
        if (away >= graceSeconds && !promptTrip) _locked.value = true
    }

    fun lockNow() {
        if (enabled) _locked.value = true
    }

    fun markUnlocked() {
        // The unlock itself counts as activity. A fingerprint on the system
        // prompt never reaches the activity, so without this an idle Terminal
        // would lock again on the next check.
        lastInteraction = clock()
        _locked.value = false
    }

    /** Every touch or key press in the activity; see [armIdleLock]. */
    fun onUserInteraction() {
        lastInteraction = clock()
    }

    /**
     * Arms the idle lock until the returned release is called.
     *
     * A screen that keeps the display on stops an unattended phone from going
     * to the background, so the lock on return never runs. While armed, in the
     * foreground and with the lock on, the app locks after
     * `max(lockAfterSeconds, floor)` seconds without a touch, except while an
     * in-app prompt is up. The floor is 60 seconds, which keeps "Immediately"
     * from locking mid-sale. [customerFacing] makes it 15 minutes, for a screen
     * a customer reads to pay: it must not lock under them, and a checkout left
     * on the counter must still lock. With several arms, the shortest floor wins.
     *
     * Only screens that keep the display on arm it. Touches in a dialog and
     * text typed on the keyboard do not reach `Activity.onUserInteraction`, so
     * on a form the app would lock while the user types. Call the release once;
     * later calls do nothing. Main thread only.
     */
    fun armIdleLock(customerFacing: Boolean = false): () -> Unit {
        val floor = if (customerFacing) CUSTOMER_IDLE_FLOOR_SECONDS else IDLE_FLOOR_SECONDS
        val first = armedFloors.isEmpty()
        armedFloors = armedFloors + floor
        if (first) {
            // Arriving on the screen is activity too, however it happened.
            lastInteraction = clock()
            updateIdleWatch()
        }
        var released = false
        return {
            if (!released) {
                released = true
                armedFloors = armedFloors - floor
                if (armedFloors.isEmpty()) updateIdleWatch()
            }
        }
    }

    /** Runs the idle check only while it can matter: armed and in the foreground. */
    private fun updateIdleWatch() {
        if (!foreground || armedFloors.isEmpty()) {
            idleWatch?.cancel()
            idleWatch = null
            return
        }
        if (idleWatch?.isActive == true) return
        idleWatch = scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                val floors = armedFloors
                val floor = floors.minOrNull() ?: IDLE_FLOOR_SECONDS
                if (!Biometrics.promptShowing && idleExpired(clock(), lastInteraction, graceSeconds, floors.size, floor)) {
                    lockNow()
                }
            }
        }
    }

    private companion object {
        /** How long the PIN screen of an in-app prompt may keep the app away. */
        const val PROMPT_TRIP_SECONDS = 120

        const val IDLE_CHECK_MS = 5_000L
    }
}

/** The idle-lock rule; see [AppLock.armIdleLock]. */
internal fun idleExpired(
    nowMs: Long,
    lastInteractionMs: Long,
    lockAfterSeconds: Int,
    armed: Int,
    floorSeconds: Int = IDLE_FLOOR_SECONDS,
): Boolean = armed > 0 && nowMs - lastInteractionMs >= maxOf(lockAfterSeconds, floorSeconds) * 1000L

/** The shortest idle lock. "Immediately" must still leave time to ring up a sale. */
private const val IDLE_FLOOR_SECONDS = 60

/** The shortest idle lock on a screen a customer reads to pay: time to scan and pay, with no touch on this phone. */
internal const val CUSTOMER_IDLE_FLOOR_SECONDS = 15 * 60

/** What the device can actually do, so Settings can explain why a toggle is off. */
enum class BiometricAvailability {
    Available,
    NoHardware,
    HardwareUnavailable,
    NotEnrolled,
    SecurityUpdateRequired,
    Unsupported,
    Unknown,
}

object Biometrics {

    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * True while [prompt] is running, from any caller.
     *
     * Read by [AppLock]: below API 30 the PIN fallback is a separate system
     * activity, so the app goes to the background in the middle of a prompt,
     * and the idle lock must not fire under one either.
     */
    @Volatile
    internal var promptShowing: Boolean = false

    /** A PIN, pattern or password is set. Without one, no prompt can confirm anything. */
    fun hasScreenLock(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun availability(context: Context): BiometricAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return if (hasScreenLock(context)) BiometricAvailability.Available else BiometricAvailability.NotEnrolled
        }
        return when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SecurityUpdateRequired
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.Unsupported
            else -> BiometricAvailability.Unknown
        }
    }

    /**
     * Shows the system prompt and returns true only if a Keystore operation
     * actually unlocked.
     *
     * On API 30+ the prompt is asked for `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`
     * so a device without a fingerprint sensor still gets a PIN fallback. Below
     * that, `setDeviceCredentialAllowed` is the only route and it cannot be
     * combined with a crypto object, so the call degrades to a plain prompt —
     * documented here rather than silently.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): Boolean = prompt(activity, title, subtitle) is AuthOutcome.Success

    /**
     * As [authenticate], but distinguishes *why* it did not succeed.
     *
     * The lock screen needs this. Collapsing every error code to `false` would
     * mean that once authentication becomes structurally impossible — the user
     * removed their screen lock, enrolled a new fingerprint and invalidated the
     * key, or hit a permanent lockout — the prompt returns false for ever,
     * while back is swallowed and the overlay is opaque and full-bleed. The app
     * would be bricked short of clearing its data.
     */
    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): AuthOutcome {
        promptShowing = true
        try {
            return showPrompt(activity, title, subtitle)
        } finally {
            promptShowing = false
        }
    }

    private suspend fun showPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
    ): AuthOutcome {
        // Off the main thread: on first use this generates a StrongBox-backed
        // AES-256 key, which routinely takes several hundred milliseconds to
        // over a second. On the caller's dispatcher — for the lock screen, the
        // main thread — that is a guaranteed stall on every cold start, and an
        // ANR on slower secure elements.
        val cipher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withContext(Dispatchers.IO) { lockCipher() }
        } else {
            // Below R a crypto object cannot be combined with a device
            // credential fallback, so there is nothing to acquire. The lock key
            // is left alone: deleting it here would regenerate and destroy the
            // expensive key on every unlock.
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cipher == null) {
            return AuthOutcome.Failed(-1, "Could not prepare secure authentication. Try again.")
        }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)

            var resumed = false
            fun finish(result: AuthOutcome) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(result)
                }
            }

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val verified = cipher == null || runCatching {
                        val authenticatedCipher = result.cryptoObject?.cipher
                            ?: error("Missing authenticated cipher")
                        authenticatedCipher.doFinal(byteArrayOf(1))
                    }.isSuccess
                    finish(if (verified) AuthOutcome.Success else AuthOutcome.Failed(-1, "Authentication could not be verified."))
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) = finish(
                    when (code) {
                        in CANCEL_CODES -> AuthOutcome.Cancelled
                        in TERMINAL_CODES -> AuthOutcome.Unavailable(code, message.toString())
                        else -> AuthOutcome.Failed(code, message.toString())
                    },
                )

                // Not final: a failed fingerprint lets the user try again.
                override fun onAuthenticationFailed() = Unit
            }

            val prompt = BiometricPrompt(activity, executor, callback)

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setAllowedAuthenticators(AUTHENTICATORS)
                    } else {
                        @Suppress("DEPRECATION")
                        setDeviceCredentialAllowed(true)
                    }
                }
                .build()

            // Without this, rotating or backgrounding while the dialog is up
            // cancels the coroutine but leaves the prompt attached through its
            // fragment; the continuation is dead, `onUnlocked` never runs, the
            // screen's `authenticating` flag stays true, and each orphaned
            // attempt retains the FragmentActivity.
            continuation.invokeOnCancellation {
                executor.execute { runCatching { prompt.cancelAuthentication() } }
            }

            if (cipher != null) {
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            } else {
                prompt.authenticate(info)
            }
        }
    }

    /**
     * Returns an initialised cipher, regenerating the key once if it was
     * permanently invalidated by a biometric enrolment change. Only that
     * specific failure justifies deleting the key.
     *
     * The regeneration means an enrolment change protects nothing here, and
     * that is accepted: the lock is a UI gate (see [AppLock]), and anyone who
     * can enrol a fingerprint already knows the PIN the prompt accepts.
     */
    private fun lockCipher(): Cipher? = try {
        Keystore.lockCipherForEncrypt()
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.w("Biometrics") { "lock key invalidated by an enrolment change; regenerating" }
        Keystore.deleteKey(Keystore.ALIAS_LOCK)
        runCatching { Keystore.lockCipherForEncrypt() }.getOrNull()
    } catch (e: Exception) {
        Log.w("Biometrics") { "could not prepare the lock cipher: ${e.message}" }
        null
    }

    /** The user dismissed it. Neutral — not an error to shout about. */
    private val CANCEL_CODES = setOf(
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
    )

    /** Retrying will never work; the caller must offer a way out. */
    private val TERMINAL_CODES = setOf(
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
    )
}

/** Why a prompt ended. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome

    /** Dismissed by the user. */
    data object Cancelled : AuthOutcome

    /** Temporary — worth offering "Try again". */
    data class Failed(val code: Int, val message: String) : AuthOutcome

    /** Structural. Retrying cannot succeed; the lock must be releasable. */
    data class Unavailable(val code: Int, val message: String) : AuthOutcome
}
