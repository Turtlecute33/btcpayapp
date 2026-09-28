package com.btcpayapp.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Dedicated POS devices may have no browser or share target installed.
 *
 * A view opens web pages only. Some of the links come from the server (an
 * invoice's checkout link), and a `bitcoin:` or `lightning:` one would open
 * the merchant's own wallet with a payment filled in.
 */
fun Context.safeStartActivity(intent: Intent) {
    if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme?.lowercase() !in setOf("https", "http")) {
        Toast.makeText(this, "This link type is not supported.", Toast.LENGTH_LONG).show()
        return
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app is installed to open this link or shared content.", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, "Android blocked opening this content.", Toast.LENGTH_LONG).show()
    }
}
