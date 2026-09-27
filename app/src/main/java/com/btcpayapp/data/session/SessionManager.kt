package com.btcpayapp.data.session

import com.btcpayapp.core.store.saved
import com.btcpayapp.core.util.Log
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.api.ServerVersion
import com.btcpayapp.data.api.dto.ApplicationUserData
import com.btcpayapp.data.api.dto.PaymentMethodData
import com.btcpayapp.data.api.dto.ServerInfoData
import com.btcpayapp.data.api.dto.StoreData
import com.btcpayapp.data.api.endpoints.currentUser
import com.btcpayapp.data.api.endpoints.paymentMethods
import com.btcpayapp.data.api.endpoints.serverInfo
import com.btcpayapp.data.api.endpoints.stores
import com.btcpayapp.data.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds "which server and store am I looking at", and the small amount of state
 * every screen needs (the store list, the signed-in user, server capabilities).
 *
 * A [BtcPayApi] is derived from the active account rather than mutated in place,
 * so switching accounts cannot leave an in-flight request pointed at the old
 * server with the new server's key.
 */
class SessionManager(
    private val accounts: AccountRepository,
    private val client: BtcPayClient,
    private val scope: CoroutineScope,
) {

    val activeAccount: StateFlow<Account?> = accounts.vault
        .map { it.activeAccount }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val allAccounts: StateFlow<List<Account>> = accounts.vault
        .map { it.accounts }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Derived for the UI's convenience only.
     *
     * Nothing inside this class may read it. It is a *second, independent*
     * collector of [activeAccount], so when an account first appears there is no
     * ordering guarantee between this flow updating and the `init` collector
     * below running — reading `api.value` from there could produce a null on
     * first pair and leave the app permanently empty. Internal callers go
     * through [currentApi], which derives from the account directly and
     * cannot race.
     */
    val api: StateFlow<BtcPayApi?> = activeAccount
        .map { account -> account?.let(::apiFor) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun apiFor(account: Account): BtcPayApi = BtcPayApi(client, account.toEndpoint())

    private fun currentApi(): BtcPayApi? = accounts.vault.value.activeAccount?.let(::apiFor)

    private val _stores = MutableStateFlow<List<StoreData>>(emptyList())
    val stores: StateFlow<List<StoreData>> = _stores.asStateFlow()

    private val _user = MutableStateFlow<ApplicationUserData?>(null)
    val user: StateFlow<ApplicationUserData?> = _user.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfoData?>(null)
    val serverInfo: StateFlow<ServerInfoData?> = _serverInfo.asStateFlow()

    /**
     * The live version from `server/info`, else the one stored on the account
     * at the last successful refresh. Null when neither is known: gates then
     * let the server decide (see [serverAtLeast]).
     */
    val serverVersion: StateFlow<ServerVersion?> = combine(serverInfo, activeAccount) { info, account ->
        ServerVersion.parse(info?.version?.takeIf(String::isNotBlank) ?: account?.serverVersion)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** True when the server runs [version] or later, or its version is unknown. */
    fun serverAtLeast(version: ServerVersion): Boolean =
        serverVersion.value.let { it == null || it >= version }

    private val _paymentMethods = MutableStateFlow<List<PaymentMethodData>>(emptyList())
    val paymentMethods: StateFlow<List<PaymentMethodData>> = _paymentMethods.asStateFlow()
    private val _paymentMethodsLoaded = MutableStateFlow(false)
    val paymentMethodsLoaded = _paymentMethodsLoaded.asStateFlow()
    private val storesOwner = MutableStateFlow<Account?>(null)

    /**
     * True once the store list loaded for the current account; false again on
     * an account change. Tells "this key sees no store" apart from "not loaded".
     */
    private val _storesLoaded = MutableStateFlow(false)
    val storesLoaded: StateFlow<Boolean> = _storesLoaded.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastError = MutableStateFlow<ApiException?>(null)
    val lastError: StateFlow<ApiException?> = _lastError.asStateFlow()

    val activeStore: StateFlow<StoreData?> = combine(activeAccount, stores, storesOwner) { account, list, owner ->
        if (account?.sessionIdentity() != owner || account == null) null
        else list.firstOrNull { it.id == account.activeStoreId } ?: list.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** True once a store is selected and its payment methods are known. */
    val ready: StateFlow<Boolean> = combine(activeAccount, activeStore) { account, store ->
        account != null && store != null
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val refreshMutex = Mutex()

    /**
     * Gates the refresh collectors below. The graph, and so this class, is
     * built in every process, including those started only for a sync job or
     * at boot, where the UI's requests would be pure cost.
     */
    private val started = MutableStateFlow(false)

    /** Lets the session load. Called by the activity; later calls do nothing. */
    fun start() {
        started.value = true
    }

    init {
        scope.launch {
            started.first { it }
            activeAccount.map { it?.sessionIdentity() }.distinctUntilChanged().collectLatest { account ->
                storesOwner.value = null
                _storesLoaded.value = false
                _stores.value = emptyList()
                _user.value = null
                _serverInfo.value = null
                _paymentMethods.value = emptyList()
                _paymentMethodsLoaded.value = false
                _unspendableMethods.value = emptySet()
                _lastError.value = null
                if (account != null) refresh()
            }
        }
        scope.launch {
            started.first { it }
            activeStore.map { it?.id }.distinctUntilChanged().collectLatest { storeId ->
                _paymentMethods.value = emptyList()
                _paymentMethodsLoaded.value = false
                // Payment method ids repeat across stores (`BTC-CHAIN` in both),
                // so one store's refusal must not disable the next store's send.
                _unspendableMethods.value = emptySet()
                if (storeId != null) refreshPaymentMethods(storeId)
            }
        }
    }

    suspend fun requireApi(): BtcPayApi = currentApi() ?: throw ApiException.NoAccount()

    /**
     * Throws an [ApiException] rather than an `IllegalStateException`, so a
     * caller's `runCatching` turns "no store yet" into a normal error state
     * instead of a crash.
     */
    fun requireStoreId(): String {
        val account = accounts.vault.value.activeAccount ?: throw ApiException.NoAccount()
        if (account.sessionIdentity() != storesOwner.value) throw ApiException.NoAccount()
        return _stores.value.firstOrNull { it.id == account.activeStoreId }?.id
            ?: _stores.value.firstOrNull()?.id ?: throw ApiException.NoAccount()
    }

    /**
     * The account minus what may change without making it another session:
     * the chosen store, and the server version this class writes back itself.
     */
    private fun Account.sessionIdentity() = copy(activeStoreId = null, serverVersion = null)

    // --- Payments in flight --------------------------------------------------

    private val spendLock = Any()
    private var spendCount = 0
    private var holdCount = 0
    private val _spendInFlight = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)

    /**
     * True while a request that moves money, or changes where it goes, runs
     * (see [spending]). A store or account switch clears every store screen
     * and its ViewModel, which would cancel the request and lose its outcome.
     */
    val spendInFlight: StateFlow<Boolean> = _spendInFlight.asStateFlow()

    /** True while a money request runs (spending{}) OR a money outcome is still open (also on a tab that is not shown). Store and account switches are refused while it is true. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Runs [block] marked as a payment in flight. Wrap every request that
     * sends funds or changes where they go. Counted, so overlapping payments keep the mark until the
     * last one ends; the lock keeps the counts and the flags in step.
     */
    suspend fun <T> spending(block: suspend () -> T): T {
        countMarks(spend = +1)
        return try {
            block()
        } finally {
            countMarks(spend = -1)
        }
    }

    /**
     * Marks one money outcome as open until the returned function is called; calling it twice is harmless.
     * Screens take it through [OutcomeHold].
     */
    fun holdOutcome(): () -> Unit {
        countMarks(hold = +1)
        // Once only: a repeated call must not take away the mark of another
        // outcome that is still open.
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) countMarks(hold = -1) }
    }

    private fun countMarks(spend: Int = 0, hold: Int = 0) = synchronized(spendLock) {
        spendCount += spend
        holdCount += hold
        _spendInFlight.value = spendCount > 0
        _busy.value = spendCount > 0 || holdCount > 0
    }

    /**
     * Switches store. Returns null when switched, else the text to show.
     * Refused while [busy], for a deep link as for a tap: the switch clears
     * the store's screens, and with them a payment's result, which the user
     * must see before paying again. Waiting for the payment to end and then
     * switching is no better: the result appears only then, and the switch
     * would remove it at once. Never throws for a failed write.
     */
    suspend fun selectStore(storeId: String): String? {
        if (_busy.value) return SPEND_IN_FLIGHT
        val accountId = activeAccount.value?.id ?: return null
        return if (saved(TAG) { accounts.setActiveStore(accountId, storeId) }) null else SAVE_FAILED
    }

    /** As [selectStore], for the active account. */
    suspend fun selectAccount(accountId: String): String? {
        if (_busy.value) return SPEND_IN_FLIGHT
        return if (saved(TAG) { accounts.setActive(accountId) }) null else SAVE_FAILED
    }

    /**
     * Pulls the three things the shell needs. The calls are independent, so they
     * run concurrently; a failure in one does not blank the others.
     */
    suspend fun refresh() = refreshMutex.withLock {
        val owner = accounts.vault.value.activeAccount?.sessionIdentity()
        val api = currentApi() ?: run {
            _lastError.value = ApiException.NoAccount()
            return@withLock
        }
        _refreshing.value = true
        try {
            // `coroutineScope`, not the application scope: these are children of
            // this call, so cancelling the caller cancels them and a failure
            // cannot escape into the app-wide supervisor unnoticed.
            val (stores, user, info) = coroutineScope {
                val storesDeferred = async { runCatching { api.stores() } }
                val userDeferred = async { runCatching { api.currentUser() } }
                val infoDeferred = async { runCatching { api.serverInfo() } }
                Triple(storesDeferred.await(), userDeferred.await(), infoDeferred.await())
            }
            coroutineContext.ensureActive()
            if (owner != accounts.vault.value.activeAccount?.sessionIdentity()) return@withLock

            stores
                .onSuccess { list ->
                    _stores.value = list.sortedBy { it.name.lowercase() }
                    storesOwner.value = owner
                    _storesLoaded.value = true
                    _lastError.value = null
                }
                .onFailure { it.record() }

            // Pairing accepts a key without "View your profile", so that
            // refusal is not a session failure, as for server info below.
            user.onSuccess { _user.value = it }.onFailure { if (it !is ApiException.Forbidden) it.record() }
            // Server info needs no special permission on a modern instance, but
            // an old or tightly scoped key can still be refused. That must not
            // mark the whole session as failed when the store list loaded.
            info.onSuccess {
                _serverInfo.value = it
                rememberServerVersion(it.version)
            }
        } finally {
            _refreshing.value = false
        }
    }

    /**
     * Stores the reported version on the account, so version gates hold before
     * the next `server/info` answers, or when a scoped key is refused it.
     * [sessionIdentity] ignores the field, so the write does not reset the
     * session. A failed write only costs the cached value.
     */
    private suspend fun rememberServerVersion(version: String) {
        val account = accounts.vault.value.activeAccount ?: return
        if (version.isBlank() || version == account.serverVersion) return
        saved(TAG) { accounts.update(account.id) { it.copy(serverVersion = version) } }
    }

    suspend fun refreshPaymentMethods(storeId: String) {
        val owner = accounts.vault.value.activeAccount?.sessionIdentity()
        val api = currentApi() ?: return
        val result = runCatching { api.paymentMethods(storeId, includeConfig = false) }
        coroutineContext.ensureActive()
        if (owner != accounts.vault.value.activeAccount?.sessionIdentity() ||
            runCatching { requireStoreId() }.getOrNull() != storeId) return
        result.onSuccess {
            _paymentMethods.value = it
            _paymentMethodsLoaded.value = true
            _lastError.value = null
        }.onFailure { it.record() }
    }

    fun clearError() {
        _lastError.value = null
    }

    private fun Throwable.record() {
        if (this is ApiException) {
            _lastError.value = this
            // Also logged in debug builds. An API failure that only ever lands
            // in a StateFlow is invisible when the screen reading it has not
            // been written yet, which is exactly how a silent empty dashboard
            // happens.
            Log.w("SessionManager") { "refresh failed: ${this::class.simpleName}" }
        } else {
            Log.e("SessionManager", this) { "unexpected failure during refresh" }
        }
    }

    // --- Capability checks used to hide UI a key cannot drive ---------------

    /** Follows the server's policy tree; unknown grants let the server decide. See [Permissions]. */
    fun hasPermission(permission: String, storeId: String? = null): Boolean =
        Permissions.covers(activeAccount.value?.permissions, permission, storeId)

    val isServerAdmin: Boolean
        get() = user.value?.isAdmin == true

    /** Payment method ids the active store has switched on, e.g. `BTC-CHAIN`. */
    val enabledPaymentMethodIds: List<String>
        get() = paymentMethods.value.filter { it.enabled }.map { it.paymentMethodId }

    val hasOnChain: Boolean
        get() = enabledPaymentMethodIds.any { it.endsWith("-CHAIN", ignoreCase = true) }

    val hasLightning: Boolean
        get() = enabledPaymentMethodIds.any { it.endsWith("-LN", ignoreCase = true) }

    /** Crypto codes with an on-chain wallet, derived from `BTC-CHAIN` → `BTC`. */
    val onChainCryptoCodes: List<String>
        get() = enabledPaymentMethodIds
            .filter { it.endsWith("-CHAIN", ignoreCase = true) }
            .map { it.substringBefore('-') }

    val lightningCryptoCodes: List<String>
        get() = enabledPaymentMethodIds
            .filter { it.endsWith("-LN", ignoreCase = true) }
            .map { it.substringBefore('-') }

    // --- Wallets this app cannot spend from ---------------------------------

    private val _unspendableMethods = MutableStateFlow<Set<String>>(emptySet())

    /**
     * On-chain payment methods the server has already refused to sign for.
     *
     * Greenfield has no "is this a hot wallet" flag: `GET /payment-methods`
     * returns the derivation scheme either way, so a watch-only store looks
     * exactly like a spendable one until a transaction is attempted. Rather
     * than let the operator fill in a send form and discover that at the end,
     * the first refusal is remembered and the Send button is disabled with the
     * reason from then on.
     *
     * Deliberately not persisted. A store can be turned into a hot wallet on
     * the server at any time, and a stale "no" cached across restarts would be
     * an app that refuses to spend from a wallet that works.
     */
    val unspendableMethods: StateFlow<Set<String>> = _unspendableMethods.asStateFlow()

    fun markUnspendable(paymentMethodId: String) {
        _unspendableMethods.update { it + paymentMethodId }
    }

    /**
     * False when this app certainly cannot spend from [paymentMethodId] — the
     * key was granted without signing rights, or the server has already said it
     * holds no private key.
     *
     * Unknown permissions mean true: [hasPermission] lets the server decide
     * rather than hiding a control that might work.
     */
    fun canSpendOnChain(paymentMethodId: String): Boolean =
        hasPermission("btcpay.store.cansigntransactions", activeStore.value?.id) &&
            paymentMethodId !in _unspendableMethods.value

    fun canSpendLightning(): Boolean =
        hasPermission("btcpay.store.canuselightningnode", activeStore.value?.id)

    fun canCreateInvoice(storeId: String): Boolean = hasPermission("btcpay.store.cancreateinvoice", storeId)

    private companion object {
        const val TAG = "SessionManager"
        // An open result can wait on a tab that is not shown, so waiting alone may not end it.
        const val SPEND_IN_FLIGHT = "A payment or a store change is in progress, or a payment result is still open. " +
            "Wait for it to end or close the result, then try again."
        const val SAVE_FAILED = "Could not save the change. Try again."
    }
}

/** One screen's claim on [SessionManager.holdOutcome], owned by its ViewModel so that it outlives composition (a tab switch or a screen
 *  pushed on top must not release an unseen result). set(true) takes the hold once, set(false) releases it, close() releases it for good. */
class OutcomeHold(private val session: SessionManager) : AutoCloseable {
    private var release: (() -> Unit)? = null
    private var closed = false

    // Synchronized: a ViewModel may call set() from a background coroutine
    // while it is cleared, and so closed, on the main thread.
    @Synchronized
    fun set(active: Boolean) {
        if (active && !closed) {
            if (release == null) release = session.holdOutcome()
        } else {
            release?.invoke()
            release = null
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        set(false)
    }
}
