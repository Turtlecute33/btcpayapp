package com.btcpayapp

import android.content.Context
import com.btcpayapp.core.crypto.Keystore
import com.btcpayapp.core.lightning.NodeDirectory
import com.btcpayapp.core.lightning.openBundledNodeIndex
import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.security.AppLock
import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.core.sync.Notifier
import com.btcpayapp.core.sync.SyncEngine
import com.btcpayapp.core.sync.SyncState
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.model.Vault
import com.btcpayapp.data.session.AccountRepository
import com.btcpayapp.data.session.SessionManager
import com.btcpayapp.data.session.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /** Suspends until both persisted documents have been read from disk. */
    suspend fun awaitReady() {
        combine(accounts.loaded, settings.loaded) { a, b -> a && b }.first { it }
    }

    /** Removes every account, setting and watermark. The lock key goes too. */
    suspend fun wipe() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
        com.btcpayapp.core.sync.SyncScheduler.cancel(appContext)
        accounts.clear()
        settings.reset()
        syncStateFile.clear()
        Keystore.deleteKey(Keystore.ALIAS_LOCK)
        // Quarantined encrypted files may remain for diagnostics. Destroying
        // their wrapping key makes a full wipe include those older documents.
        Keystore.deleteKey(Keystore.ALIAS_VAULT)
    }
}
