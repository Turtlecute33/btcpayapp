package com.btcpayapp.ui.components

import com.btcpayapp.core.util.toHex
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.btcpayapp.core.net.CertificateProbe
import com.btcpayapp.core.net.isLocalNetworkHost

/**
 * How many hex characters of a fingerprint the user must type: 128 bits.
 *
 * Not fewer. Nothing checks the certificate's signature, so an attacker can
 * vary its unchecked bytes and grind SHA-256 until the hash starts with the
 * real one's first characters. 64 bits is weeks on rented GPUs; 128 bits is
 * out of reach.
 */
private const val MIN_FINGERPRINT_HEX = 32

/** A run of hex digits, with the colons `openssl x509 -fingerprint` puts between them. */
private val HEX_RUN = Regex("[0-9A-Fa-f:]+")

/**
 * Trust-on-first-use, where the user proves the key instead of accepting it.
 *
 * The dialog shows nothing the certificate says about itself. A probe reads
 * the certificate without validating it, so its subject, issuer, self-signed
 * flag and even its fingerprint are whatever the other end chose to send — on
 * a hostile network, the attacker's. Instead the user runs one command on the
 * server and types the start of what it prints; "Trust this key" stays off
 * until that matches ([fingerprintMatches]). Cancel is the filled button.
 *
 * An expired or not-yet-valid certificate is never offered: the pinned
 * connection checks the dates too, so trusting it would only fail on the next
 * request. A public host name gets a warning in the error colour, because a
 * public server with an untrusted certificate is usually an interception.
 *
 * [onTrust] always receives [CertificateProbe.pin], the SPKI pin, whichever
 * fingerprint the user typed.
 */
@Composable
fun CertificateCheckDialog(
    host: String,
    port: Int,
    probe: CertificateProbe,
    onTrust: (pin: String) -> Unit,
    onCancel: () -> Unit,
) {
    if (!probe.isCurrentlyValid()) {
        AlertDialog(
            onDismissRequest = onCancel,
            icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
            title = { Text("Check this server's key") },
            text = {
                Text("This certificate has expired or is not valid yet. Renew it on the server, then try again.")
            },
            confirmButton = { Button(onClick = onCancel) { Text("Close") } },
        )
        return
    }

    var typed by rememberSaveable(probe.pin) { mutableStateOf("") }
    val matches = fingerprintMatches(typed, probe)
    // A mismatch is worth saying out loud: it is what an interception looks
    // like from here.
    val mismatch = !matches && typed.count(Char::isLetterOrDigit) >= MIN_FINGERPRINT_HEX
    val command = "openssl s_client -connect 127.0.0.1:$port -servername $host </dev/null 2>/dev/null" +
        " | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -r"

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
        title = { Text("Check this server's key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!isLocalNetworkHost(host)) {
                    Text(
                        text = "$host is a public name. A public server normally has a certificate your " +
                            "phone trusts, so this usually means someone is intercepting the connection. " +
                            "Do not continue unless you set up this certificate yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (!probe.namesHost(host)) {
                    Text(
                        text = "This certificate does not name $host.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = "Run this on the server itself (use the port your web server listens on), " +
                        "then type the first $MIN_FINGERPRINT_HEX characters it prints:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                CopyableField(label = "Command", value = command)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The SHA-256 fingerprint of the certificate file also works (type what " +
                        "comes after the = sign): openssl x509 -in cert.pem -noout -fingerprint -sha256",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FormField(
                    label = "Fingerprint from the server",
                    value = typed,
                    onValueChange = { typed = it },
                    supportingText = "At least $MIN_FINGERPRINT_HEX letters and digits. Colons and spaces are ignored.",
                    error = if (mismatch) "Does not match this server's key." else null,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                )
            }
        },
        confirmButton = { Button(onClick = onCancel) { Text("Cancel") } },
        dismissButton = {
            TextButton(
                onClick = { onTrust(probe.pin) },
                enabled = matches,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Trust this key")
            }
        },
    )
}

/**
 * True when [typed] identifies the probed certificate.
 *
 * Accepts at least [MIN_FINGERPRINT_HEX] hex characters that start the SHA-256
 * of the public key (what the pin is made of) or of the whole certificate, in
 * either case, with or without colons and spaces. The value is the first run
 * of that many hex characters after any `=`, so a whole output line pasted as
 * it is also works: `SHA256 Fingerprint=AB:CD:…`, `SHA2-256(stdin)= abcd…`,
 * or `abcd… *stdin` from `openssl dgst -r`. The full base64 pin pasted as it
 * is also matches. Anything shorter matches nothing, so an empty or
 * half-typed field never enables trust.
 */
internal fun fingerprintMatches(typed: String, probe: CertificateProbe): Boolean {
    val compact = typed.filterNot { it.isWhitespace() }
    if (compact.isNotEmpty() && compact == probe.pin) return true

    val hex = HEX_RUN.findAll(compact.substringAfterLast('='))
        .map { it.value.replace(":", "").lowercase() }
        .firstOrNull { it.length >= MIN_FINGERPRINT_HEX }
        ?: return false

    // java.util.Base64, not android.util: the same on API 26+, and it runs in a JVM test.
    val spkiHex = runCatching { java.util.Base64.getDecoder().decode(probe.pin) }
        .getOrNull()
        ?.toHex()
        .orEmpty()
    return (spkiHex.isNotEmpty() && spkiHex.startsWith(hex)) ||
        (probe.certificateSha256Hex.isNotEmpty() && probe.certificateSha256Hex.lowercase().startsWith(hex))
}
