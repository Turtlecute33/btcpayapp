package com.btcpayapp.core.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.btcpayapp.MainActivity
import com.btcpayapp.R

/**
 * System notifications.
 *
 * Separate channels so a merchant can silence server chatter without losing
 * payment alerts. Nothing sensitive goes in the ticker text: the amount and the
 * store name are shown, never an address, a BOLT11 invoice or a key.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val channels = listOf(
            NotificationChannel(
                CHANNEL_PAYMENTS,
                context.getString(R.string.notif_channel_payments),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_payments_desc)
                enableVibration(true)
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_PAYOUTS,
                context.getString(R.string.notif_channel_payouts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_payouts_desc)
            },
            NotificationChannel(
                CHANNEL_SERVER,
                context.getString(R.string.notif_channel_server),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_server_desc)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_FEED,
                context.getString(R.string.notif_channel_feed),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_feed_desc)
            },
        )
        val system = context.getSystemService(NotificationManager::class.java)
        channels.forEach(system::createNotificationChannel)
    }

    /**
     * `POST_NOTIFICATIONS` only exists from API 33. Below that,
     * `checkSelfPermission` returns `PERMISSION_DENIED` for it, so the check is
     * gated on the API level. Unconditional, it would return false on 8
     * through 12, and the whole polling subsystem would run, advance its
     * watermarks and spend battery without ever showing the merchant anything.
     */
    fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled()
    }

    /** True when the runtime prompt is the thing standing in the way. */
    fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun paymentReceived(invoiceId: String, accountId: String, storeId: String, title: String, body: String) {
        post(
            id = "$accountId|$storeId|invoice|$invoiceId".hashCode(),
            channel = CHANNEL_PAYMENTS,
            title = title,
            body = body,
            deepLink = scopedLink("invoice", invoiceId, accountId, storeId),
            category = Notification.CATEGORY_EVENT,
        )
    }

    fun payoutWaiting(payoutId: String, accountId: String, storeId: String, title: String, body: String) {
        post(
            id = "$accountId|$storeId|payout|$payoutId".hashCode(),
            channel = CHANNEL_PAYOUTS,
            title = title,
            body = body,
            deepLink = scopedLink("payout", payoutId, accountId, storeId),
        )
    }

    fun serverIssue(accountId: String, title: String, body: String) {
        post(
            id = accountId.hashCode(),
            channel = CHANNEL_SERVER,
            title = title,
            body = body,
            deepLink = "btcpayapp://settings/connections",
        )
    }

    /**
     * An entry from the server's own notification feed.
     *
     * The tap goes through the same resolution as a tap in the in-app list, so
     * the entry's link travels with it. It is resolved on arrival, against the
     * account the link names, and not here.
     */
    fun serverNotification(notificationId: String, accountId: String, storeId: String?, link: String?, title: String, body: String) {
        val deepLink = Uri.Builder()
            .scheme("btcpayapp").authority("notification").appendPath(notificationId)
            .appendQueryParameter("account", accountId)
            .apply {
                storeId?.let { appendQueryParameter("store", it) }
                link?.let { appendQueryParameter("link", it) }
            }
            .build().toString()
        post(
            id = "$accountId|feed|$notificationId".hashCode(),
            channel = CHANNEL_FEED,
            title = title,
            body = body,
            deepLink = deepLink,
        )
    }

    private fun scopedLink(kind: String, id: String, accountId: String, storeId: String) = Uri.Builder()
        .scheme("btcpayapp").authority(kind).appendPath(id)
        .appendQueryParameter("account", accountId).appendQueryParameter("store", storeId).build().toString()

    // `canPost()` performs exactly the check lint is asking for, but it lives in
    // its own function so the detector cannot follow it. The `notify` call is
    // additionally wrapped in `runCatching`, so a SecurityException from an OEM
    // that disagrees cannot crash a background sync either.
    @SuppressLint("MissingPermission")
    private fun post(
        id: Int,
        channel: String,
        title: String,
        body: String,
        deepLink: String,
        category: String = Notification.CATEGORY_STATUS,
    ) {
        if (!canPost()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(deepLink)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(category)
            // Keeps the content off a locked screen; the title alone is enough
            // to know something arrived.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_PAYOUTS = "payouts"
        const val CHANNEL_SERVER = "server"
        const val CHANNEL_FEED = "feed"
    }
}
