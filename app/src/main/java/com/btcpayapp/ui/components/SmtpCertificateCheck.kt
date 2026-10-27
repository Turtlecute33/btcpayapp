package com.btcpayapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.btcpayapp.ui.theme.Motion

/**
 * The SMTP switch that turns off the certificate check, and the warning that
 * shows while it is on. The server and the store email forms both use it, so
 * that the two screens show the same title and the same warning.
 */
@Composable
fun SmtpCertificateCheckSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    FormSwitch(
        title = "Skip certificate checks",
        checked = checked,
        onCheckedChange = onCheckedChange,
        description = "Accepts any TLS certificate the mail server offers.",
    )
    // The warning is the result of the switch above it, so the user must see it
    // arrive. If it is only there on the next frame, it looks like part of the
    // page that the user already read.
    AnimatedVisibility(
        visible = checked,
        enter = expandVertically(Motion.spatialSize) + fadeIn(Motion.effects),
        exit = shrinkVertically(Motion.spatialSize) + fadeOut(Motion.effectsFast),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "With this on, the connection to the mail server is encrypted but not " +
                        "authenticated: anyone able to intercept it can present their own certificate " +
                        "and read the SMTP password and every message. Use it only against a host on " +
                        "your own network with a self-signed certificate.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
