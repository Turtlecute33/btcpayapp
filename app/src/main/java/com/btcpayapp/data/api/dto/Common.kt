package com.btcpayapp.data.api.dto

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * `GreenfieldAPIError`. Returned for 400/401/403/404/409/410.
 * The 403 variant adds [missingPermission].
 */
@Serializable
data class ApiErrorBody(
    val code: String = "generic-error",
    val message: String = "",
    val missingPermission: String? = null,
)

/**
 * `GreenfieldValidationError`. Note the body is a **bare JSON array**, not an
 * object — 422 always uses this shape, and 400 may use either.
 */
@Serializable
data class ApiValidationError(
    val path: String = "",
    val message: String = "",
)

// ---------------------------------------------------------------------------
// Health and server
// ---------------------------------------------------------------------------

/** `GET /api/v1/health` — the only unauthenticated endpoint, used to probe a URL. */
@Serializable
data class HealthData(
    val synchronized: Boolean = false,
)

@Serializable
data class ServerInfoData(
    val version: String = "",
    /** Present when the instance also serves a v3 onion; offered as an alternate endpoint. */
    val onion: String? = null,
    val supportedPaymentMethods: List<String> = emptyList(),
    val fullySynched: Boolean = false,
    val syncStatus: List<ServerSyncStatus> = emptyList(),
)

@Serializable
data class ServerSyncStatus(
    val paymentMethodId: String = "",
    val available: Boolean = false,
    val chainHeight: Int = 0,
    val syncHeight: Int? = null,
    val nodeInformation: NodeInformation? = null,
)

@Serializable
data class NodeInformation(
    val headers: Int = 0,
    val blocks: Int = 0,
    val verificationProgress: Double = 0.0,
)

@Serializable
data class RoleData(
    val id: String = "",
    val role: String = "",
    val permissions: List<String> = emptyList(),
    val isServerRole: Boolean = false,
)

// ---------------------------------------------------------------------------
// Email
// ---------------------------------------------------------------------------

@Serializable
data class EmailSettingsData(
    val from: String? = null,
    val server: String? = null,
    val port: Int? = null,
    val login: String? = null,
    val disableCertificateCheck: Boolean = false,
    val passwordSet: Boolean = false,
    val enableStoresToUseServerEmailSettings: Boolean? = null,
)

@Serializable
data class UpdateEmailSettingsRequest(
    val from: String? = null,
    val server: String? = null,
    val port: Int? = null,
    val login: String? = null,
    /** Null or empty leaves the stored password untouched. */
    val password: String? = null,
    val disableCertificateCheck: Boolean? = null,
    val enableStoresToUseServerEmailSettings: Boolean? = null,
)

@Serializable
data class SendEmailRequest(
    val email: String,
    val subject: String,
    val body: String,
)

// ---------------------------------------------------------------------------
// Users and API keys
// ---------------------------------------------------------------------------

@Serializable
data class ApplicationUserData(
    val id: String = "",
    val email: String = "",
    val name: String? = null,
    val imageUrl: String? = null,
    val invitationUrl: String? = null,
    val emailConfirmed: Boolean = false,
    val requiresEmailConfirmation: Boolean = false,
    val approved: Boolean = true,
    val requiresApproval: Boolean = false,
    val storeQuota: Int? = null,
    val created: Long? = null,
    val disabled: Boolean = false,
    val allowGreenfieldBasicAuth: Boolean = false,
    val roles: List<String> = emptyList(),
) {
    val isAdmin: Boolean get() = roles.any { it.equals("ServerAdmin", ignoreCase = true) }
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: email
}

@Serializable
data class CreateUserRequest(
    val email: String,
    val password: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val isAdministrator: Boolean? = null,
    val sendInvitationEmail: Boolean? = null,
)

@Serializable
data class ApiKeyData(
    val id: String = "",
    /**
     * Returned exactly once, at creation. Since the 2026 hardening the server
     * nulls the cleartext column five minutes later, so the app must persist it
     * immediately or lose it for good.
     */
    val apiKey: String? = null,
    val label: String? = null,
    val created: Long? = null,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class SetApprovalRequest(val approved: Boolean)

@Serializable
data class SetLockRequest(val locked: Boolean)

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Serializable
data class NotificationData(
    val id: String = "",
    val identifier: String = "",
    val type: String = "",
    /** HTML fragment. Rendered as plain text after tag stripping — see `Html.kt`. */
    val body: String = "",
    val storeId: String? = null,
    val link: String? = null,
    val createdTime: Long = 0,
    val seen: Boolean = false,
)

@Serializable
data class UpdateNotificationRequest(
    /** Null toggles. */
    val seen: Boolean? = null,
)

@Serializable
data class NotificationSettingsData(
    val notifications: List<NotificationSettingItem> = emptyList(),
)

@Serializable
data class NotificationSettingItem(
    val identifier: String = "",
    val name: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class UpdateNotificationSettingsRequest(
    /** Identifiers to disable, or the single element `"all"`. */
    val disabled: List<String>,
)

// ---------------------------------------------------------------------------
// Misc (these live at the site root, not under /api/v1)
// ---------------------------------------------------------------------------

@Serializable
data class RateSourceData(
    val id: String = "",
    val name: String = "",
)

// ---------------------------------------------------------------------------
// Shared value types
// ---------------------------------------------------------------------------

@Serializable
data class LabelData(
    val type: String = "",
    val text: String = "",
)
