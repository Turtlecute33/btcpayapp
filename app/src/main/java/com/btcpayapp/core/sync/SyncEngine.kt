package com.btcpayapp.core.sync

import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Log
import com.btcpayapp.core.util.Text
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.endpoints.invoices
import com.btcpayapp.data.api.endpoints.invoice
import com.btcpayapp.data.api.endpoints.notifications
import com.btcpayapp.data.api.endpoints.payouts
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.session.AccountRepository
import com.btcpayapp.data.session.SettingsRepository
import com.btcpayapp.data.session.toEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The work the background job actually does: ask every configured account
 * whether anything was paid or needs approving since last time, and what its
 * own notification feed has added, and raise a notification for what is new.
 *
 * Polling rather than push is a deliberate trade. Push would mean routing
 * payment events through Firebase — a third party learning when and how often a
 * merchant gets paid — and would tie the app to Google Play services. A poll
 * every 15 minutes costs a few hundred bytes and leaks nothing.
 */
class SyncEngine internal constructor(
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val client: BtcPayClient,
    private val stateFile: EncryptedJsonFile<SyncState>,
    private val notifier: Notifier,
) {

    suspend fun run(): Result {
        settings.loaded.first { it }
        accounts.loaded.first { it }
        // The watermark file too. Without this, a cold start (which is the
        // normal case for a periodic job) could read `stateFile.state.value`
        // while its decrypt is still in flight, see the default `SyncState()`,
        // take the "first run" branch and `update` it — which marks the file
        // loaded and discards the real document when it finally arrives. Every
        // invoice watermark and announced-payout id would be lost, so pending
        // payouts would be re-announced and settled invoices silently skipped.
        stateFile.loaded.first { it }

        val config = settings.settings.value
        if (!config.backgroundSync) return Result.Skipped

        val all = accounts.vault.value.accounts
        if (all.isEmpty()) return Result.Skipped

        var found = 0
        var failures = 0
        val accountFailures = stateFile.state.value.accountFailures.toMutableMap()

        all.forEach { account ->
            try {
                found += syncAccount(account, config.notifyPayments, config.notifyPayouts, config.notifyServerNotifications)
                accountFailures.remove(account.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures++
                Log.w("SyncEngine") { "account sync failed: ${e.javaClass.simpleName}" }
                val count = ((accountFailures[account.id] ?: 0) + 1).coerceAtMost(FAILURE_ALERT_THRESHOLD)
                accountFailures[account.id] = count
                if (config.notifyServerIssues && count >= FAILURE_ALERT_THRESHOLD) {
                    notifier.serverIssue(
                        accountId = account.id,
                        title = account.label,
                        body = "This server has been unreachable for a while.",
                    )
                }
            }
        }

        stateFile.update { state ->
            if (failures == all.size) {
                state.copy(
                    lastFailureAt = System.currentTimeMillis(),
                    consecutiveFailures = state.consecutiveFailures + 1,
                    accountFailures = accountFailures.filterKeys { id -> all.any { it.id == id } },
                )
            } else {
                state.copy(lastRunAt = System.currentTimeMillis(), consecutiveFailures = 0,
                    accountFailures = accountFailures.filterKeys { id -> all.any { it.id == id } })
            }
        }

        return when {
            failures == all.size -> Result.Failed
            found > 0 -> Result.FoundUpdates(found)
            else -> Result.NoChange
        }
    }

    private suspend fun syncAccount(
        account: Account,
        notifyPayments: Boolean,
        notifyPayouts: Boolean,
        notifyFeed: Boolean,
    ): Int {
        val api = BtcPayApi(client, account.toEndpoint())
        val storeIds = api.stores().map { it.id }

        var announcements = 0
        storeIds.forEach { storeId ->
            if (notifyPayments) announcements += notifyNewSettlements(api, account, storeId)
            if (notifyPayouts) announcements += notifyPendingPayouts(api, account, storeId)
        }
        if (notifyFeed && account.mayReadNotifications()) {
            announcements += try {
                notifyServerFeed(api, account, notifyPayments, notifyPayouts)
            } catch (e: ApiException) {
                // The feed is an addition. A server that refuses it has still
                // answered for its payments, so this is not an account failure.
                Log.w("SyncEngine") { "feed read failed: ${e.javaClass.simpleName}" }
                0
            }
        }
        return announcements
    }

    /**
     * One request per account, whatever the number of stores: the server's own
     * notification feed, unseen entries only. An entry read on the web first
     * does not reach the phone as well.
     */
    private suspend fun notifyServerFeed(api: BtcPayApi, account: Account, payments: Boolean, payouts: Boolean): Int {
        val previous = stateFile.state.value.feedPoll(account.id)
        val result = evaluateFeed(previous, api.notifications(seen = false, take = FEED_TAKE))
        val shown = result.fresh.filterNot { coveredByOwnAlerts(it.identifier, payments, payouts) }

        shown.forEach { item ->
            notifier.serverNotification(
                notificationId = item.id,
                accountId = account.id,
                storeId = item.storeId,
                link = item.link,
                title = account.label,
                body = Text.stripHtml(item.body).ifBlank { "New notification" },
            )
        }

        if (result.state != previous) stateFile.update { it.withFeedPoll(account.id, result.state) }
        return shown.size
    }

    /** An empty list is a key whose permissions were never recorded; let the server decide. */
    private fun Account.mayReadNotifications(): Boolean =
        permissions.isEmpty() || permissions.any { it.substringBefore(':') in NOTIFICATION_READERS }

    private suspend fun notifyNewSettlements(api: BtcPayApi, account: Account, storeId: String): Int {
        val previous = stateFile.state.value.invoicePoll(account.id, storeId)
        val pollStarted = nowSeconds()
        val settled = mutableListOf<com.btcpayapp.data.api.dto.InvoiceData>()
        var offset = 0
        do {
            val page = api.invoices(
                storeId = storeId,
                // The initial run adopts outstanding invoices without announcing
                // history. Later runs discover all newly created invoices.
                statuses = if (previous == null) listOf(InvoiceStatus.New, InvoiceStatus.Processing) else null,
                startDate = previous?.since,
                endDate = pollStarted,
                skip = offset,
                take = MAX_BATCH,
            )
            settled += page
            offset += page.size
        } while (page.size == MAX_BATCH)

        val discovered = settled.map { it.id }.toSet()
        previous?.pending.orEmpty().filterNot { it in discovered }.chunked(4).forEach { batch ->
            settled += coroutineScope {
                batch.map { invoiceId -> async {
                    try {
                        api.invoice(storeId, invoiceId)
                    } catch (_: ApiException.NotFound) {
                        // Only missing invoices are dropped. Other failures
                        // abort the checkpoint so the next poll retries them.
                        null
                    }
                } }.awaitAll().filterNotNull()
            }
        }
        val result = evaluateInvoices(previous, settled, pollStarted)
        val fresh = result.payments
        fresh.forEach { invoice ->
            notifier.paymentReceived(
                invoiceId = invoice.id,
                accountId = account.id,
                storeId = storeId,
                title = if (invoice.status == InvoiceStatus.Settled) "Payment received" else "Payment detected",
                body = buildString {
                    append(if (settings.settings.value.privacyMode) "Payment received" else Amounts.format(invoice.paidAmount, invoice.currency))
                    append(" · ")
                    append(account.label)
                },
            )
        }

        stateFile.update { it.withInvoicePoll(account.id, storeId, result.state) }
        return fresh.size
    }

    private suspend fun notifyPendingPayouts(api: BtcPayApi, account: Account, storeId: String): Int {
        val waiting = api.payouts(storeId).filter { it.state == PayoutState.AwaitingApproval }
        val alreadyTold = stateFile.state.value.announced(account.id, storeId)
        val fresh = waiting.filterNot { it.id in alreadyTold }

        fresh.forEach { payout ->
            notifier.payoutWaiting(
                payoutId = payout.id,
                accountId = account.id,
                storeId = storeId,
                title = "Payout awaiting approval",
                body = if (settings.settings.value.privacyMode) "Payout awaiting approval · ${account.label}"
                    else "${Amounts.format(payout.originalAmount, payout.originalCurrency)} · ${account.label}",
            )
        }

        val waitingIds = waiting.map { it.id }.distinct()
        if (waitingIds != alreadyTold) {
            stateFile.update {
                it.withAnnounced(account.id, storeId, waitingIds)
            }
        }
        return fresh.size
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    sealed interface Result {
        data object Skipped : Result
        data object NoChange : Result
        data class FoundUpdates(val count: Int) : Result
        data object Failed : Result
    }

    private companion object {
        const val MAX_BATCH = 25
        const val FAILURE_ALERT_THRESHOLD = 4
        const val FEED_TAKE = 20
        val NOTIFICATION_READERS = setOf(
            "unrestricted",
            "btcpay.user.canviewnotificationsforuser",
            "btcpay.user.canmanagenotificationsforuser",
        )
    }
}
