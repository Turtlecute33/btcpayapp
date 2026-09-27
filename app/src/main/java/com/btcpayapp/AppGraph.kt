package com.btcpayapp

import android.content.Context
import com.btcpayapp.core.crypto.Keystore
import com.btcpayapp.core.lightning.NodeDirectory
import com.btcpayapp.core.lightning.openBundledNodeIndex
import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.security.AppLock
import com.btcpayapp.core.security.Biometrics
import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.core.store.saved
import com.btcpayapp.core.sync.Notifier
import com.btcpayapp.core.sync.SyncEngine
import com.btcpayapp.core.sync.SyncScheduler
import com.btcpayapp.core.sync.SyncState
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.model.AppLockMode
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.model.Vault
import com.btcpayapp.data.session.AccountRepository
import com.btcpayapp.data.session.SessionManager
import com.btcpayapp.data.session.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The object graph, assembled by hand.
 *
 * There is no Dagger/Hilt here on purpose. This app has roughly a dozen
 * singletons and one lifetime; an annotation processor to wire them would add a
 * build-time code generator, a second compilation pass, and a large dependency
 * — for something a single readable constructor expresses better. Everything
 * below is constructed once, in a known order, and is trivially replaceable in
 * a test by passing a different instance.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives every screen; cancelled only when the process dies. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = ApiJson.instance

    private val httpEngine = HttpEngine()

    val client = BtcPayClient(httpEngine, json)

    private val vaultFile = EncryptedJsonFile(
        file = File(appContext.filesDir, "vault.bin"),
        serializer = Vault.serializer(),
        defaultValue = Vault(),
        json = json,
        scope = scope,
    )

    private val settingsFile = EncryptedJsonFile(
        file = File(appContext.filesDir, "settings.bin"),
        serializer = AppSettings.serializer(),
        defaultValue = AppSettings(),
        json = json,
        scope = scope,
        // Settings that cannot be opened must not turn the app lock off. On
        // only with a screen lock: without one, nothing could unlock it.
        recovered = {
            AppSettings(appLock = if (Biometrics.hasScreenLock(appContext)) AppLockMode.Biometric else AppLockMode.Off)
        },
    )

    private val syncStateFile = EncryptedJsonFile(
        file = File(appContext.filesDir, "sync.bin"),
        serializer = SyncState.serializer(),
        defaultValue = SyncState(),
        json = json,
        scope = scope,
    )

    val accounts = AccountRepository(vaultFile)

    val settings = SettingsRepository(settingsFile)

    /**
     * True while the vault or the settings exist on disk but cannot be read
     * yet (for example the Keystore daemon is not up just after boot). The
     * shell then shows "Try again", which calls [retryStorage], instead of a
     * blank screen.
     */
    val storageUnreadable: StateFlow<Boolean> = combine(
        accounts.unreadable,
        accounts.loaded,
        settings.unreadable,
        settings.loaded,
    ) { vaultUnreadable, vaultLoaded, settingsUnreadable, settingsLoaded ->
        (vaultUnreadable && !vaultLoaded) || (settingsUnreadable && !settingsLoaded)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val session = SessionManager(accounts, client, scope)

    val notifier = Notifier(appContext)

    val appLock = AppLock(settings, scope)

    init {
        // Maps `assets/lnnodes.bin`; the pages are only touched when a screen
        // actually asks for a peer's name, so this costs a file descriptor and
        // nothing else. A failure leaves the curated list in charge.
        NodeDirectory.install(openBundledNodeIndex(appContext))
    }

    val syncEngine = SyncEngine(
        accounts = accounts,
        settings = settings,
        client = client,
        stateFile = syncStateFile,
        notifier = notifier,
    )

    init {
        // Notifications posted in full must leave the lock screen when app
        // lock or privacy mode turns on.
        scope.launch { syncEngine.followConcealment() }
    }

    /** Suspends until both persisted documents have been read from disk. */
    suspend fun awaitReady() {
        combine(accounts.loaded, settings.loaded) { a, b -> a && b }.first { it }
    }

    /** Reads every document that has not loaded yet again. */
    suspend fun retryStorage() {
        vaultFile.retry()
        settingsFile.retry()
        syncStateFile.retry()
    }

    /**
     * Removes an account and what the app still holds about it: its sync
     * state (pending invoice ids, announced payouts, watermarks), the
     * notifications it posted and its stores' Terminal currencies.
     * Throws when the vault write fails, like [AccountRepository.remove]. The
     * cleanups after it are best effort: once the account is gone, a failed
     * cleanup must not report the removal as failed. NonCancellable, so
     * leaving the screen cannot stop it halfway.
     */
    suspend fun removeAccount(accountId: String) {
        withContext(NonCancellable) {
            accounts.remove(accountId)
            notifier.cancelAccount(accountId)
            saved("AppGraph") { syncStateFile.update { it.forgetAccount(accountId) } }
            // Keyed "$accountId|$storeId" (AppSettings.terminalCurrencies).
            settings.update { current ->
                current.copy(terminalCurrencies = current.terminalCurrencies.filterKeys { !it.startsWith("$accountId|") })
            }
        }
    }

    /** Removes every account, setting and watermark. The lock key goes too. */
    suspend fun wipe() = withContext(NonCancellable + Dispatchers.IO) {
        SyncScheduler.cancel(appContext)
        // Posted notifications carry amounts and store names, and stay on the
        // lock screen after the data is gone unless removed too.
        notifier.cancelAll()
        accounts.clear()
        settings.reset()
        syncStateFile.clear()
        Keystore.deleteKey(Keystore.ALIAS_LOCK)
        // The clears above delete the quarantined copies too. A deleted file
        // can linger in flash storage; without its key, it cannot be read.
        Keystore.deleteKey(Keystore.ALIAS_VAULT)
    }
}
