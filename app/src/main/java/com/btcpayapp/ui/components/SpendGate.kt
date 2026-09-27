package com.btcpayapp.ui.components

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.btcpayapp.core.security.AuthOutcome
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.ui.LocalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The label of the Settings switch that turns the spend prompt on and off.
 *
 * One constant, because the refusal below tells the user to turn that switch
 * off. A message that names a setting by a label the Settings screen does not
 * show is a message nobody can act on.
 */
const val CONFIRM_PAYMENTS_LABEL = "Confirm payments"

/** Why a spend may, or may not, go ahead. */
sealed interface SpendAuth {
    /** Confirmed, or [CONFIRM_PAYMENTS_LABEL] is off. The only answer that allows the action. */
    data object Granted : SpendAuth

    /** The user dismissed the prompt. A deliberate "no": show nothing. */
    data object Cancelled : SpendAuth

    /** Nothing was done. [message] says why and what to change; show it. */
    data class Refused(val message: String) : SpendAuth
}

private const val NO_PROMPT = "This screen cannot show the confirmation prompt, so nothing was done."
private const val NO_LOCK =
    "Set a screen lock on this device to confirm payments, or turn off “$CONFIRM_PAYMENTS_LABEL” in Settings."
private const val NOT_CONFIRMED = "Not confirmed, so nothing was done. Try again."
private const val CANNOT_CONFIRM = "This phone cannot confirm payments now, so nothing was done."

/**
 * The one gate in front of every action that sends funds, changes where the
 * store receives funds, changes when an invoice counts as paid, or where the
 * store's events or email are sent, or gives someone control of the store.
 *
 * It fails closed. Every path that is not a confirmed prompt, or the setting
 * switched off, ends in [SpendAuth.Refused] — a missing activity, a phone with
 * no screen lock, a key the Keystore will not prepare. Reading it the other way
 * round (`confirm && activity != null`) would let a failed cast send the money
 * with no prompt at all, and a security control that degrades to "allow" is not
 * one. The refusals share one wording on every screen, and the one a user can
 * fix names the setting by its real label.
 *
 * Call [authorize] from a `rememberCoroutineScope()` in the composable, after
 * the screen's own review dialog, and call the view model only on
 * [SpendAuth.Granted]. Never keep a gate in a view model or pass it to one: it
 * holds the activity, which a view model outlives.
 */
@Stable
class SpendGate internal constructor(
    private val activity: FragmentActivity?,
    private val context: Context,
    private val required: State<Boolean>,
) {
    suspend fun authorize(title: String, subtitle: String? = null): SpendAuth {
        if (!required.value) return SpendAuth.Granted
        val activity = activity ?: return SpendAuth.Refused(NO_PROMPT)
        // Checked before the prompt, because without a screen lock the prompt
        // fails in a different way on each API level (a Keystore key that
        // cannot be made, or a terminal error code), and none of those say
        // what the user can do about it.
        if (!Biometrics.hasScreenLock(context)) return SpendAuth.Refused(NO_LOCK)
        return when (val outcome = Biometrics.prompt(activity, title, subtitle)) {
            is AuthOutcome.Success -> SpendAuth.Granted
            is AuthOutcome.Cancelled -> SpendAuth.Cancelled
            // The phone has a lock, so "set one" would be wrong. The system's
            // own reason (a lockout, a security update) says what to do.
            is AuthOutcome.Unavailable ->
                SpendAuth.Refused(listOf(CANNOT_CONFIRM, outcome.message.trim()).filter(String::isNotEmpty).joinToString(" "))
            is AuthOutcome.Failed -> SpendAuth.Refused(NOT_CONFIRMED)
        }
    }
}

/**
 * Runs [action] once [gate] allows it, after the screen's own confirmation.
 * A refusal goes to [onRefused] to be shown; a cancelled prompt does nothing.
 * Launched in the composable's scope, because the gate holds the activity and
 * must never reach a view model.
 */
fun CoroutineScope.afterSpendGate(
    gate: SpendGate,
    title: String,
    subtitle: String?,
    onRefused: suspend (String) -> Unit,
    action: () -> Unit,
) {
    launch {
        when (val auth = gate.authorize(title, subtitle)) {
            SpendAuth.Granted -> action()
            SpendAuth.Cancelled -> Unit
            is SpendAuth.Refused -> onRefused(auth.message)
        }
    }
}

/**
 * The [SpendGate] for this screen.
 *
 * The setting is read through `rememberUpdatedState`, so a gate remembered
 * before the user changed it still asks the current question.
 */
@Composable
fun rememberSpendGate(): SpendGate {
    val activity = LocalActivity.current as? FragmentActivity
    val context = LocalContext.current
    val required = rememberUpdatedState(LocalSettings.current.confirmSpendsWithBiometrics)
    return remember(activity, context, required) { SpendGate(activity, context, required) }
}
