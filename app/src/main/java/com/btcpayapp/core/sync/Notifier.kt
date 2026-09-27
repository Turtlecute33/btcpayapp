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
import com.btcpayapp.core.util.toHex
import java.security.MessageDigest

/**
 * System notifications.
 *
 * Separate channels so a merchant can silence server chatter without losing
 * payment alerts. A notification shows at most an amount, the account label and
 * the server's own feed text, never an address, a BOLT11 invoice or a key.
 *
 * Each one is tagged with a hash of its account id, so removing an account
 * can take its notifications out of the shade ([cancelAccount]). Each one also
 * carries a public version with only a title that names no amount, store or
 * server text, and with `concealed` set (app lock or privacy mode on) the
 * notification itself is that title: the lock screen then shows none of them,
 * whatever the user's system setting is. Those posted in full before either
 * turned on are removed ([cancelRevealing]).
 *
 * `open` for one reason: SyncEngine's JVM test records what would be posted.
 */
open class Notifier(private val context: Context) {

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
    open fun canPost(): Boolean {
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

    /**
     * [title] is the payment's status from [paymentTitle]. It names no amount
     * or store, so it is also the concealed and lock-screen title. A generic
     * "Payment received" there would show an unconfirmed or partial payment
     * as a full one, and the merchant could hand over the goods for it.
     */
    open fun paymentReceived(
        invoiceId: String,
        accountId: String,
        storeId: String,
        title: String,
        body: String,
        concealed: Boolean = false,
    ) {
        post(
            accountId = accountId,
            id = "$accountId|$storeId|invoice|$invoiceId".hashCode(),
            channel = CHANNEL_PAYMENTS,
            title = title,
            body = body,
            publicTitle = title,
            concealed = concealed,
            deepLink = scopedLink("invoice", invoiceId, accountId, storeId),
            category = Notification.CATEGORY_EVENT,
        )
    }

    open fun payoutWaiting(
        payoutId: String,
        accountId: String,
        storeId: String,
        title: String,
        body: String,
        concealed: Boolean = false,
    ) {
        post(
            accountId = accountId,
            id = "$accountId|$storeId|payout|$payoutId".hashCode(),
            channel = CHANNEL_PAYOUTS,
            title = title,
            body = body,
            publicTitle = "Payout awaiting approval",
            concealed = concealed,
            deepLink = scopedLink("payout", payoutId, accountId, storeId),
        )
    }

    /**
     * One per account. SyncEngine posts it once per outage, and an update of a
     * notification still showing makes no sound. [clearServerIssue] removes it
     * when the server answers again.
     */
    open fun serverIssue(accountId: String, title: String, body: String, concealed: Boolean = false) {
        post(
            accountId = accountId,
            id = serverIssueId(accountId),
            channel = CHANNEL_SERVER,
            title = title,
            body = body,
            publicTitle = "Server issue",
            concealed = concealed,
            deepLink = "btcpayapp://settings/connections",
            onlyAlertOnce = true,
        )
    }

    open fun clearServerIssue(accountId: String) {
        runCatching { manager.cancel(tagOf(accountId), serverIssueId(accountId)) }
    }

    /**
     * An entry from the server's own notification feed.
     *
     * The tap goes through the same resolution as a tap in the in-app list, so
     * the entry's link travels with it. It is resolved on arrival, against the
     * account the link names, and not here.
     */
    open fun serverNotification(
        notificationId: String,
        accountId: String,
        storeId: String?,
        link: String?,
        title: String,
        body: String,
        concealed: Boolean = false,
    ) {
        val deepLink = Uri.Builder()
            .scheme("btcpayapp").authority("notification").appendPath(notificationId)
            .appendQueryParameter("account", accountId)
            .apply {
                storeId?.let { appendQueryParameter("store", it) }
                link?.let { appendQueryParameter("link", it) }
            }
            .build().toString()
        post(
            accountId = accountId,
            id = "$accountId|feed|$notificationId".hashCode(),
            channel = CHANNEL_FEED,
            title = title,
            body = body,
            publicTitle = "BTCPay notification",
            concealed = concealed,
            deepLink = deepLink,
        )
    }

    /** Removes every notification posted for [accountId], found by its tag. */
    open fun cancelAccount(accountId: String) {
        val tag = tagOf(accountId)
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.filter { it.tag == tag }
                ?.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /**
     * Removes every notification posted in full, not concealed. Only those
     * have a body text ([post]).
     */
    fun cancelRevealing() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.filter { it.notification.extras?.getCharSequence(Notification.EXTRA_TEXT) != null }
                ?.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /** Removes every notification of this app, for "Erase everything". */
    fun cancelAll() {
        runCatching { manager.cancelAll() }
    }

    private fun scopedLink(kind: String, id: String, accountId: String, storeId: String) = Uri.Builder()
        .scheme("btcpayapp").authority(kind).appendPath(id)
        .appendQueryParameter("account", accountId).appendQueryParameter("store", storeId).build().toString()

    private fun serverIssueId(accountId: String) = "$accountId|server".hashCode()

    /**
     * Not the account id itself: any app with notification access reads tags,
     * and the id is what a `btcpayapp://…?account=` link needs to switch this
     * app to another account.
     */
    private fun tagOf(accountId: String): String =
        MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8)).toHex()

    // `canPost()` performs exactly the check lint is asking for, but it lives in
    // its own function so the detector cannot follow it. The `notify` call is
    // additionally wrapped in `runCatching`, so a SecurityException from an OEM
    // that disagrees cannot crash a background sync either.
    @SuppressLint("MissingPermission")
    private fun post(
        accountId: String,
        id: Int,
        channel: String,
        title: String,
        body: String,
        publicTitle: String,
        concealed: Boolean,
        deepLink: String,
        category: String = Notification.CATEGORY_STATUS,
        onlyAlertOnce: Boolean = false,
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

        // What a secure lock screen shows when the user chose "hide sensitive
        // content": that something happened, not how much, where or from whom.
        val publicVersion = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(publicTitle)
            .setCategory(category)
            .build()

        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(category)
            .setOnlyAlertOnce(onlyAlertOnce)
            // PRIVATE swaps in the public version only under "hide sensitive
            // content". Under Android's default, "show all content", the lock
            // screen shows the full notification, which is why a concealed one
            // holds nothing but the public title.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (concealed) {
            builder.setContentTitle(publicTitle)
        } else {
            builder.setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
        }

        runCatching { manager.notify(tagOf(accountId), id, builder.build()) }
    }

    companion object {
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_PAYOUTS = "payouts"
        const val CHANNEL_SERVER = "server"
        const val CHANNEL_FEED = "feed"
    }
}
