package com.btcpayapp.core.sync

import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.core.store.saved
import com.btcpayapp.core.util.Amounts
import com.btcpayapp.core.util.Log
import com.btcpayapp.core.util.Text
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.api.dto.InvoiceData
import com.btcpayapp.data.api.dto.InvoiceStatus
import com.btcpayapp.data.api.dto.PayoutState
import com.btcpayapp.data.api.endpoints.invoice
import com.btcpayapp.data.api.endpoints.invoices
import com.btcpayapp.data.api.endpoints.notifications
import com.btcpayapp.data.api.endpoints.payouts
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.session.AccountRepository
import com.btcpayapp.data.session.Permissions
import com.btcpayapp.data.session.SettingsRepository
import com.btcpayapp.data.session.toEndpoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The work the background job actually does: ask every configured account
 * whether anything was paid or needs approving since last time, and what its
 * own notification feed has added, and raise a notification for what is new.
 *
 * Polling rather than push is a deliberate trade. Push would mean routing
 * payment events through Firebase — a third party learning when and how often a
 * merchant gets paid — and would tie the app to Google Play services. A poll
 * talks only to the merchant's own server: per store, the invoices created
 * since the last run, one read per invoice still open and the payout list; per
 * account, the feed. Its cost follows the invoices opened in the last expiry
 * window, not the store's history.
 */
class SyncEngine internal constructor(
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val client: BtcPayClient,
    private val stateFile: EncryptedJsonFile<SyncState>,
    private val notifier: Notifier,
) {

    suspend fun run(): Result {
        // Nothing could be shown, so asking the servers would only spend
        // battery, data and their time.
        if (!notifier.canPost()) {
            forgetPollPositions()
            return Result.Skipped
        }

        // The watermark file as well as the other two. Without it, a cold
        // start (which is the normal case for a periodic job) could read
        // `stateFile.state.value` while its decrypt is still in flight, see the
        // default `SyncState()`, take the "first run" branch and `update` it —
        // which marks the file loaded and discards the real document when it
        // finally arrives. Every invoice watermark and announced-payout id
        // would be lost, so pending payouts would be re-announced and settled
        // invoices silently skipped. Bounded: a Keystore that stays unavailable
        // fails the run, and JobScheduler retries it with backoff, instead of
        // holding the job to its 8-minute limit.
        withTimeoutOrNull(DOCUMENTS_TIMEOUT_MS) {
            settings.loaded.first { it }
            accounts.loaded.first { it }
            stateFile.loaded.first { it }
        } ?: return Result.Failed

        val config = settings.settings.value
        if (!config.backgroundSync) return Result.Skipped

        val all = accounts.vault.value.accounts
        if (all.isEmpty()) return Result.Skipped

        // All accounts at once, each on its own clock: one onion server that
        // accepts connections and then hangs must not use up the job's time
        // and leave the other servers unpolled.
        val outcomes = coroutineScope {
            all.map { account -> async { syncAndRecord(account, config) } }.awaitAll()
        }
        val failed = outcomes.count { it.problem != null }
        val found = outcomes.sumOf { it.found }

        // An account removed while this run was busy may have had entries
        // written, or notifications posted, after its removal cleaned up.
        val current = accounts.vault.value.accounts.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        stateFile.update { state ->
            val kept = state.retainAccounts(current)
            if (failed == all.size) {
                kept.copy(lastFailureAt = now, consecutiveFailures = state.consecutiveFailures + 1)
            } else {
                kept.copy(lastRunAt = now, consecutiveFailures = 0)
            }
        }
        all.filterNot { it.id in current }.forEach { notifier.cancelAccount(it.id) }

        return when {
            failed == all.size -> Result.Failed
            found > 0 -> Result.FoundUpdates(found)
            else -> Result.NoChange
        }
    }

    /**
     * Runs for the life of the process, not in the job. A notification posted
     * in full keeps its amount and store on a lock screen that shows all
     * content, until it is dismissed. So when app lock or privacy mode turns
     * on, this removes the notifications posted in full. Those posted
     * concealed stay. Until the settings load, they conceal nothing, so this
     * removes nothing.
     */
    suspend fun followConcealment() {
        settings.settings.map { it.concealsNotifications }.distinctUntilChanged().collect { conceal ->
            if (conceal) notifier.cancelRevealing()
        }
    }

    /**
     * Notifications are off. What happens until they are back is history, not
     * news: without the poll positions, the next run adopts what it finds, as
     * a first run does. Kept, they would replay every payment of those days
     * as new, each one looking as if it had just come in.
     */
    private suspend fun forgetPollPositions() {
        withTimeoutOrNull(DOCUMENTS_TIMEOUT_MS) { stateFile.loaded.first { it } } ?: return
        val state = stateFile.state.value
        if (state.invoicePolls.isEmpty() && state.feedPolls.isEmpty()) return
        saved(TAG) { stateFile.update { it.withoutPollPositions() } }
    }

    /** One account, bounded by [ACCOUNT_TIMEOUT_MS], with its outage state saved as soon as it is known. */
    private suspend fun syncAndRecord(account: Account, config: AppSettings): AccountOutcome {
        val outcome = try {
            withTimeoutOrNull(ACCOUNT_TIMEOUT_MS) { syncAccount(account, config) }
                ?: AccountOutcome(found = 0, problem = UNREACHABLE_BODY).also { Log.w(TAG) { "account sync timed out" } }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG) { "account sync failed: ${e.javaClass.simpleName}" }
            AccountOutcome(found = 0, problem = e.serverIssueBody())
        }
        try {
            recordReachability(account, config, outcome.problem)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG) { "sync state not saved: ${e.javaClass.simpleName}" }
        }
        return outcome
    }

    private suspend fun syncAccount(account: Account, config: AppSettings): AccountOutcome {
        val api = BtcPayApi(client, account.toEndpoint())
        // The one call that must succeed. When it fails, the server, the
        // network or the key is gone, so the account counts as failed.
        val storeIds = api.stores().map { it.id }
        val announcedInvoices = mutableSetOf<String>()
        var found = 0
        var reached = true

        // One check of one store, or the feed. Its failure stops only that
        // check: a store that refuses its payouts still has its invoices read,
        // and the other stores are still polled. Only a failure to reach the
        // server counts against the account; a refusal or an error page came
        // from a server that is up.
        suspend fun attempt(what: String, countsAgainstAccount: Boolean = true, block: suspend () -> Int) {
            try {
                found += block()
            } catch (e: Exception) {
                // A cancelled run (the job's limit, onStopJob, this account's
                // limit) can fail its call as a plain IO error. Rethrow the
                // cancellation: that server was not unreachable, the app gave up.
                currentCoroutineContext().ensureActive()
                Log.w(TAG) { "$what failed: ${e.javaClass.simpleName}" }
                if (countsAgainstAccount && e.isReachabilityFailure()) reached = false
            }
        }

        storeIds.forEach { storeId ->
            if (config.notifyPayments && Permissions.covers(account.permissions, CAN_VIEW_INVOICES, storeId)) {
                attempt("invoice poll") { notifyNewSettlements(api, account, storeId, announcedInvoices) }
            }
            // Never counted against the account: stores() has just shown the
            // server answers, and the payout list has no server paging, so a
            // store with a long history can pass the 8 MB response cap, which
            // fails as a Transport error.
            if (config.notifyPayouts && Permissions.covers(account.permissions, CAN_VIEW_PAYOUTS, storeId)) {
                attempt("payout check", countsAgainstAccount = false) {
                    notifyPendingPayouts(api, account, storeId)
                }
            }
        }
        // After the invoice polls, so that a late payment or a failure to
        // confirm that they announced is not announced again by the feed.
        if (config.notifyServerNotifications && Permissions.covers(account.permissions, CAN_VIEW_NOTIFICATIONS)) {
            attempt("feed read") { notifyServerFeed(api, account, config, announcedInvoices) }
        }
        return AccountOutcome(found, problem = if (reached) null else UNREACHABLE_BODY)
    }

    /**
     * Starts the account's outage clock, or stops it and removes the alert.
     * The alert comes once, after [SyncState.SERVER_ISSUE_AFTER_MS] of
     * failures: a server restarting for an update is not an issue. It says
     * [problem], the latest failure; null means the server answered.
     */
    private suspend fun recordReachability(account: Account, config: AppSettings, problem: String?) {
        if (problem == null) {
            val state = stateFile.state.value
            if (account.id in state.accountFailureSince || account.id in state.alertedAccounts) {
                stateFile.update { it.withAccountReached(account.id) }
                notifier.clearServerIssue(account.id)
            }
            return
        }
        val now = System.currentTimeMillis()
        var alert = false
        stateFile.update { state ->
            val failing = state.withAccountFailure(account.id, now)
            alert = config.notifyServerIssues && failing.serverIssueDue(account.id, now)
            if (alert) failing.withServerIssueAlerted(account.id) else failing
        }
        if (alert) {
            notifier.serverIssue(
                accountId = account.id,
                title = account.label,
                body = problem,
                concealed = concealed,
            )
        }
    }

    /**
     * One request per account, whatever the number of stores: the server's own
     * notification feed, unseen entries only. An entry read on the web first
     * does not reach the phone as well.
     */
    private suspend fun notifyServerFeed(
        api: BtcPayApi,
        account: Account,
        config: AppSettings,
        announcedInvoices: Set<String>,
    ): Int {
        val previous = stateFile.state.value.feedPoll(account.id)
        val result = evaluateFeed(previous, api.notifications(seen = false, take = FEED_TAKE))
        val shown = result.fresh.filterNot {
            coveredByOwnAlerts(it, config.notifyPayments, config.notifyPayouts, announcedInvoices)
        }

        shown.forEach { item ->
            notifier.serverNotification(
                notificationId = item.id,
                accountId = account.id,
                storeId = item.storeId,
                link = item.link,
                title = account.label,
                body = Text.stripHtml(item.body).ifBlank { "New notification" },
                concealed = concealed,
            )
        }

        if (result.state != previous) stateFile.update { it.withFeedPoll(account.id, result.state) }
        return shown.size
    }

    private suspend fun notifyNewSettlements(
        api: BtcPayApi,
        account: Account,
        storeId: String,
        announcedInvoices: MutableSet<String>,
    ): Int {
        val previous = stateFile.state.value.invoicePoll(account.id, storeId)
        val pollStarted = nowSeconds()
        val invoices = mutableListOf<InvoiceData>()
        val listedIds = HashSet<String>()
        var offset = 0
        var pages = 0
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
            // A server or proxy that ignores `skip` sends the same page again
            // and again. Stop at a page with nothing new, and after MAX_PAGES.
            val added = page.filter { listedIds.add(it.id) }
            invoices += added
            offset += page.size
            pages++
        } while (page.size == MAX_BATCH && added.isNotEmpty() && pages < MAX_PAGES)

        previous?.pending.orEmpty().filterNot { it in listedIds }.chunked(4).forEach { batch ->
            invoices += coroutineScope {
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
        val result = evaluateInvoices(previous, invoices, pollStarted)
        result.payments.forEach { invoice ->
            notifier.paymentReceived(
                invoiceId = invoice.id,
                accountId = account.id,
                storeId = storeId,
                title = paymentTitle(invoice),
                body = "${Amounts.format(invoice.paidAmount, invoice.currency)} · ${account.label}",
                concealed = concealed,
            )
            announcedInvoices += invoice.id
        }

        stateFile.update { it.withInvoicePoll(account.id, storeId, result.state) }
        return result.payments.size
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
                body = "${Amounts.format(payout.originalAmount, payout.originalCurrency)} · ${account.label}",
                concealed = concealed,
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

    /**
     * The failures that say the server could not be reached: no connection
     * (Tor not running is one too), no answer in time or no answer at all, or
     * a certificate that does not check out.
     */
    private fun Exception.isReachabilityFailure(): Boolean =
        this is ApiException.Transport || this is ApiException.Timeout ||
            this is ApiException.Tls || this is ApiException.OutcomeUnknown

    /**
     * The server-issue text for a failed account. A refused key gets the API
     * layer's own words (re-pair, or the missing permission): checking the
     * network or the server would not help. Any other answer, such as an error
     * page or data this app cannot read, came from a server that was reached.
     */
    private fun Exception.serverIssueBody(): String = when {
        this is ApiException.Unauthorized || this is ApiException.Forbidden -> userMessage
        isReachabilityFailure() -> UNREACHABLE_BODY
        else -> ERROR_BODY
    }

    /**
     * App lock or privacy mode: the lock screen may show a notification in full
     * (Android's default), so it gets only a title that names no amount, store
     * or server text; see [Notifier].
     */
    private val AppSettings.concealsNotifications: Boolean
        get() = appLock != AppLockMode.Off || privacyMode

    /**
     * Read at each post, not once per run: a run can take minutes, and the
     * switch can turn on while it runs.
     */
    private val concealed: Boolean
        get() = settings.settings.value.concealsNotifications

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    /** [problem] is what a server-issue alert would say, or null when the server answered. */
    private data class AccountOutcome(val found: Int, val problem: String?)

    sealed interface Result {
        data object Skipped : Result
        data object NoChange : Result
        data class FoundUpdates(val count: Int) : Result
        data object Failed : Result
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val MAX_BATCH = 25
        /** 1,000 invoices created since the last run, whatever the server does with `skip`. */
        const val MAX_PAGES = 40
        const val FEED_TAKE = 20
        const val DOCUMENTS_TIMEOUT_MS = 20_000L
        /** Half the job's 8 minutes, so that one slow server leaves time for the final save. */
        const val ACCOUNT_TIMEOUT_MS = 4 * 60_000L
        const val CAN_VIEW_INVOICES = "btcpay.store.canviewinvoices"
        const val CAN_VIEW_PAYOUTS = "btcpay.store.canviewpayouts"
        const val CAN_VIEW_NOTIFICATIONS = "btcpay.user.canviewnotificationsforuser"
        const val UNREACHABLE_BODY = "Could not reach this server for over an hour."
        const val ERROR_BODY = "This server has answered with errors for over an hour."
    }
}
