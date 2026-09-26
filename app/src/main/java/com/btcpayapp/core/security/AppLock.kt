package com.btcpayapp.core.security

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
 * Authentication is bound to a Keystore key that requires user presence, and
 * the prompt is handed a [javax.crypto.Cipher] initialised from it. Unlocking
 * therefore means "the TEE released a key after verifying the user", not "a
 * callback said true" — which is the difference between a lock and a decoration
 * on a rooted or instrumented device.
 */
class AppLock(
    private val settings: SettingsRepository,
    scope: CoroutineScope,
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

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var graceSeconds: Int = 60

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
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onEnterForeground() {
        if (!enabled || _locked.value) return
        val away = (SystemClock.elapsedRealtime() - backgroundedAt) / 1000
        if (backgroundedAt != 0L && away >= graceSeconds) _locked.value = true
    }

    fun lockNow() {
        if (enabled) _locked.value = true
    }

    fun markUnlocked() {
        _locked.value = false
    }
}

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

    fun availability(context: android.content.Context): BiometricAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return if (context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true)
                BiometricAvailability.Available else BiometricAvailability.NotEnrolled
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
