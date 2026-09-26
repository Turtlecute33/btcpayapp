package com.btcpayapp.data.session

import com.btcpayapp.core.util.Log
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.BtcPayClient
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val _paymentMethods = MutableStateFlow<List<PaymentMethodData>>(emptyList())
    val paymentMethods: StateFlow<List<PaymentMethodData>> = _paymentMethods.asStateFlow()
    private val _paymentMethodsLoaded = MutableStateFlow(false)
    val paymentMethodsLoaded = _paymentMethodsLoaded.asStateFlow()
    private val storesOwner = MutableStateFlow<Account?>(null)

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

    init {
        scope.launch {
            activeAccount.map { it?.sessionIdentity() }.distinctUntilChanged().collectLatest { account ->
                storesOwner.value = null
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

    private fun Account.sessionIdentity() = copy(activeStoreId = null)

    suspend fun selectStore(storeId: String) {
        val accountId = activeAccount.value?.id ?: return
        accounts.setActiveStore(accountId, storeId)
    }

    suspend fun selectAccount(accountId: String) = accounts.setActive(accountId)

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
                    _lastError.value = null
                }
                .onFailure { it.record() }

            user.onSuccess { _user.value = it }.onFailure { it.record() }
            // Server info needs no special permission on a modern instance, but
            // an old or tightly scoped key can still be refused. That must not
            // mark the whole session as failed when the store list loaded.
            info.onSuccess { _serverInfo.value = it }
        } finally {
            _refreshing.value = false
        }
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

    fun hasPermission(permission: String, storeId: String? = null): Boolean {
        val granted = activeAccount.value?.permissions ?: return true // unknown: let the server decide
        if (granted.isEmpty()) return true
        if (granted.any { it == "unrestricted" || it == "unrestricted:" }) return true
        return granted.any { entry ->
            val (name, scope) = entry.split(':', limit = 2).let {
                it[0] to it.getOrNull(1)?.takeIf(String::isNotBlank)
            }
            name == permission && (scope == null || storeId == null || scope == storeId)
        }
    }

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
}
