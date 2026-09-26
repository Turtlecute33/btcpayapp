package com.btcpayapp.ui.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.btcpayapp.R
import com.btcpayapp.core.security.AuthOutcome
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.ui.components.AnimatedSwap
import com.btcpayapp.ui.components.arrive
import kotlinx.coroutines.launch

/**
 * The lock overlay.
 *
 * Drawn above the whole app by `BtcPayApp`, so the surface must be opaque and
 * full-bleed: anything translucent here would leave the invoice list readable
 * underneath, which is the one thing this screen exists to prevent.
 *
 * No view model. The authentication result belongs to the prompt, not to
 * retained state — a cached "unlocked" boolean surviving a configuration change
 * is precisely the bug a lock screen must not have.
 */
@Composable
fun LockScreen(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var authenticating by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf<String?>(null) }

    val prompt: () -> Unit = {
        if (!authenticating) {
            authenticating = true
            failed = false
            unavailable = null
            scope.launch {
                val outcome = Biometrics.prompt(
                    activity = activity,
                    title = "Unlock BTCPay",
                    subtitle = "Authenticate to access your accounts.",
                )
                authenticating = false
                when (outcome) {
                    is AuthOutcome.Success -> onUnlocked()

                    // Authentication errors must never grant access or disable
                    // the user's lock. Recovery requires device authentication.
                    is AuthOutcome.Unavailable -> {
                        unavailable = outcome.message.ifBlank {
                            "This device can no longer authenticate you."
                        }
                    }

                    // Dismissing the prompt is not an error worth shouting about.
                    is AuthOutcome.Cancelled -> Unit
                    is AuthOutcome.Failed -> failed = true
                }
            }
        }
    }

    // Asks once, as soon as the overlay appears, so the common case is a single
    // tap on the sensor rather than a tap on a button and then the sensor.
    LaunchedEffect(Unit) { prompt() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The overlay's own fade belongs to the caller, so what is left
            // here is a short stagger: the padlock, then the name, then the
            // line explaining why the app is asking. It arrives in the order
            // it would be read, which is the only thing this screen has to do
            // while the system prompt is on its way up.
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp).arrive(0),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.arrive(1),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Unlocked with your device credentials",
                modifier = Modifier.arrive(2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            val blocked = unavailable
            if (blocked != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "$blocked\n\nUnlock your device or restore device authentication in Android Settings, then try again.",
                    // No index: this appears on its own, long after the
                    // stagger above has finished, so it has nothing to queue
                    // behind.
                    modifier = Modifier.arrive(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            } else if (failed) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "That did not unlock. Use your fingerprint, face or device PIN to " +
                        "carry on.",
                    modifier = Modifier.arrive(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = prompt,
                enabled = !authenticating,
                modifier = Modifier.fillMaxWidth(0.7f).arrive(3),
            ) {
                AnimatedSwap(authenticating, label = "unlock") { waiting ->
                    if (waiting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Waiting…")
                        }
                    } else {
                        Text(
                            when {
                                blocked != null -> "Try again"
                                failed -> "Try again"
                                else -> "Unlock"
                            },
                        )
                    }
                }
            }
        }
    }
}
