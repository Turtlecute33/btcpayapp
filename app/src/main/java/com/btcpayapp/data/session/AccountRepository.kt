package com.btcpayapp.data.session

import com.btcpayapp.core.net.ProxySpec
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.data.api.Endpoint
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.Credential
import com.btcpayapp.data.model.Vault
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class AccountRepository internal constructor(private val file: EncryptedJsonFile<Vault>) {

    val vault: StateFlow<Vault> = file.state
    val loaded: StateFlow<Boolean> = file.loaded

    suspend fun add(account: Account, makeActive: Boolean = true): Account {
        val stored = account.copy(
            id = account.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = if (account.createdAt == 0L) System.currentTimeMillis() else account.createdAt,
        )
        file.update { vault ->
            vault.copy(
                accounts = vault.accounts.filterNot { it.id == stored.id } + stored,
                activeAccountId = if (makeActive) stored.id else vault.activeAccountId,
            )
        }
        return stored
    }

    suspend fun update(id: String, transform: (Account) -> Account) {
        file.update { vault ->
            vault.copy(accounts = vault.accounts.map { if (it.id == id) transform(it) else it })
        }
    }

    suspend fun remove(id: String) {
        file.update { vault ->
            val remaining = vault.accounts.filterNot { it.id == id }
            vault.copy(
                accounts = remaining,
                activeAccountId = vault.activeAccountId
                    ?.takeIf { it != id }
                    ?: remaining.firstOrNull()?.id,
            )
        }
    }

    suspend fun setActive(id: String) {
        file.update { it.copy(activeAccountId = id) }
    }

    suspend fun setActiveStore(accountId: String, storeId: String?) {
        update(accountId) { it.copy(activeStoreId = storeId) }
    }

    /** Wipes every stored credential. Used by "remove all data". */
    suspend fun clear() = file.clear()
}

/**
 * Builds the transport configuration for an account.
 *
 * Tor gets a much longer budget: a three-hop circuit to a hidden service is
 * routinely several seconds before the first byte, and timing it out at the
 * clearnet default would make onion instances look broken.
 */
fun Account.toEndpoint(credentialOverride: Credential? = null): Endpoint = Endpoint(
    baseUrl = baseUrl,
    credential = credentialOverride ?: credential,
    transport = TransportOptions(
        proxy = proxy?.let { ProxySpec(it.host, it.port, it.socks) }
            ?: ProxySpec.ORBOT.takeIf { isOnion },
        pinnedSpki = certificatePins.toSet(),
        connectTimeoutMs = if (isOnion) 45_000 else 15_000,
        readTimeoutMs = if (isOnion) 60_000 else 30_000,
    ),
)
