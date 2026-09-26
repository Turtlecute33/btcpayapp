package com.btcpayapp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One configured BTCPay Server connection. The app is multi-account by design:
 * running a personal node and a work instance side by side is normal, and
 * forcing a re-pair to switch is the kind of friction that makes people write
 * the API key down somewhere worse.
 *
 * Every field here lives inside the encrypted vault document.
 */
@Serializable
data class Account(
    val id: String,
    val label: String,
    /** Normalised: scheme + host + optional port + optional base path, no trailing slash. */
    val baseUrl: String,
    val credential: Credential,
    val userId: String? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    /** Last store the user looked at, restored on next launch. */
    val activeStoreId: String? = null,
    /** Permissions the key reported at pairing time; used to hide unusable UI. */
    val permissions: List<String> = emptyList(),
    /** Base64 SHA-256 SPKI pins. Empty means "use the system CA store". */
    val certificatePins: List<String> = emptyList(),
    val proxy: AccountProxy? = null,
    val createdAt: Long = 0L,
    val serverVersion: String? = null,
) {
    val host: String get() = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl
    val isOnion: Boolean get() = host.endsWith(".onion", ignoreCase = true)
    val usesPinnedCertificate: Boolean get() = certificatePins.isNotEmpty()
}

@Serializable
sealed interface Credential {

    /**
     * The normal case. A scoped API key sent as `Authorization: token <key>`.
     * Revocable server-side without touching the account password.
     */
    @Serializable
    @SerialName("apiKey")
    data class ApiKey(val key: String) : Credential

    /**
     * HTTP Basic with the account's email and password. BTCPay accepts it only
     * when the user has explicitly opted in (`allowGreenfieldBasicAuth`), and it
     * grants *unrestricted* access, so the app uses it for exactly one thing:
     * minting a scoped API key during onboarding, after which it is discarded.
     * It is never persisted by the pairing flow.
     */
    @Serializable
    @SerialName("basic")
    data class Basic(val username: String, val password: String) : Credential
}

@Serializable
data class AccountProxy(
    val host: String = "127.0.0.1",
    val port: Int = 9050,
    val socks: Boolean = true,
)

@Serializable
data class Vault(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
) {
    val activeAccount: Account?
        get() = accounts.firstOrNull { it.id == activeAccountId } ?: accounts.firstOrNull()
}
