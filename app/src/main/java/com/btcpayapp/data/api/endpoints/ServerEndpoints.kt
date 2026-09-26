package com.btcpayapp.data.api.endpoints

import com.btcpayapp.data.api.BtcPayApi
import com.btcpayapp.data.api.dto.ApiKeyData
import com.btcpayapp.data.api.dto.ApplicationUserData
import com.btcpayapp.data.api.dto.CreateApiKeyRequest
import com.btcpayapp.data.api.dto.CreateUserRequest
import com.btcpayapp.data.api.dto.EmailSettingsData
import com.btcpayapp.data.api.dto.FileData
import com.btcpayapp.data.api.dto.HealthData
import com.btcpayapp.data.api.dto.LanguageData
import com.btcpayapp.data.api.dto.NotificationData
import com.btcpayapp.data.api.dto.NotificationSettingsData
import com.btcpayapp.data.api.dto.PermissionMetadata
import com.btcpayapp.data.api.dto.RateSourceData
import com.btcpayapp.data.api.dto.RoleData
import com.btcpayapp.data.api.dto.ServerInfoData
import com.btcpayapp.data.api.dto.SetApprovalRequest
import com.btcpayapp.data.api.dto.SetLockRequest
import com.btcpayapp.data.api.dto.UpdateCurrentUserRequest
import com.btcpayapp.data.api.dto.UpdateEmailSettingsRequest
import com.btcpayapp.data.api.dto.UpdateNotificationRequest
import com.btcpayapp.data.api.dto.UpdateNotificationSettingsRequest

// ---------------------------------------------------------------------------
// Health and server
// ---------------------------------------------------------------------------

/**
 * The only unauthenticated endpoint. Used to validate a URL during onboarding,
 * where no credential exists yet.
 *
 * `authenticate = false` is load-bearing, not a micro-optimisation. Without it
 * the Connect screen's probe would be rejected by [com.btcpayapp.data.api.BtcPayClient]'s
 * missing-credential guard before any socket is opened, so onboarding could
 * never complete against any server.
 */
internal suspend fun BtcPayApi.health(): HealthData = get("api/v1/health", authenticate = false)

internal suspend fun BtcPayApi.serverInfo(): ServerInfoData = get("api/v1/server/info")

internal suspend fun BtcPayApi.serverRoles(): List<RoleData> = get("api/v1/server/roles")

internal suspend fun BtcPayApi.serverEmailSettings(): EmailSettingsData = get("api/v1/server/email")

internal suspend fun BtcPayApi.updateServerEmailSettings(request: UpdateEmailSettingsRequest): EmailSettingsData =
    put("api/v1/server/email", body(request))

// ---------------------------------------------------------------------------
// Current user
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.currentUser(): ApplicationUserData = get("api/v1/users/me")

internal suspend fun BtcPayApi.updateCurrentUser(request: UpdateCurrentUserRequest): ApplicationUserData =
    put("api/v1/users/me", body(request))

// ---------------------------------------------------------------------------
// API keys
// ---------------------------------------------------------------------------

/**
 * Mints a scoped key for the *authenticated* user. The response carries the
 * cleartext key exactly once — the server nulls its stored copy five minutes
 * later — so the caller must persist it immediately.
 */
internal suspend fun BtcPayApi.createApiKey(request: CreateApiKeyRequest): ApiKeyData =
    post("api/v1/api-keys", body(request))

internal suspend fun BtcPayApi.currentApiKey(): ApiKeyData = get("api/v1/api-keys/current")

/** Revokes the key this client is authenticating with. Used on "disconnect". */
internal suspend fun BtcPayApi.revokeCurrentApiKey() {
    call("DELETE", "api/v1/api-keys/current")
}

internal suspend fun BtcPayApi.revokeApiKey(apiKeyId: String) {
    call("DELETE", "api/v1/api-keys/$apiKeyId")
}

// ---------------------------------------------------------------------------
// Users (server admin)
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.users(): List<ApplicationUserData> = get("api/v1/users")

internal suspend fun BtcPayApi.user(idOrEmail: String): ApplicationUserData =
    get("api/v1/users/${idOrEmail.pathSegment()}")

internal suspend fun BtcPayApi.createUser(request: CreateUserRequest): ApplicationUserData =
    post("api/v1/users", body(request))

internal suspend fun BtcPayApi.deleteUser(idOrEmail: String) {
    call("DELETE", "api/v1/users/${idOrEmail.pathSegment()}")
}

internal suspend fun BtcPayApi.setUserApproved(idOrEmail: String, approved: Boolean) {
    call("POST", "api/v1/users/${idOrEmail.pathSegment()}/approve", body(SetApprovalRequest(approved)))
}

internal suspend fun BtcPayApi.setUserLocked(idOrEmail: String, locked: Boolean) {
    call("POST", "api/v1/users/${idOrEmail.pathSegment()}/lock", body(SetLockRequest(locked)))
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.notifications(
    storeIds: List<String>? = null,
    seen: Boolean? = null,
    skip: Int? = null,
    take: Int? = null,
): List<NotificationData> = get(
    "api/v1/users/me/notifications",
    listOf("storeId" to storeIds, "seen" to seen, "skip" to skip, "take" to take),
)

internal suspend fun BtcPayApi.notification(id: String): NotificationData =
    get("api/v1/users/me/notifications/${id.pathSegment()}")

/** A null [seen] toggles. */
internal suspend fun BtcPayApi.markNotification(id: String, seen: Boolean?): NotificationData =
    put("api/v1/users/me/notifications/${id.pathSegment()}", body(UpdateNotificationRequest(seen)))

internal suspend fun BtcPayApi.deleteNotification(id: String) {
    call("DELETE", "api/v1/users/me/notifications/${id.pathSegment()}")
}

internal suspend fun BtcPayApi.notificationSettings(): NotificationSettingsData =
    get("api/v1/users/me/notification-settings")

internal suspend fun BtcPayApi.updateNotificationSettings(disabled: List<String>): NotificationSettingsData =
    put("api/v1/users/me/notification-settings", body(UpdateNotificationSettingsRequest(disabled)))

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.files(): List<FileData> = get("api/v1/files")

internal suspend fun BtcPayApi.deleteFile(fileId: String) {
    call("DELETE", "api/v1/files/${fileId.pathSegment()}")
}

// ---------------------------------------------------------------------------
// Misc — note these are served from the site root, not under /api/v1
// ---------------------------------------------------------------------------

internal suspend fun BtcPayApi.permissionMetadata(): List<PermissionMetadata> = get("misc/permissions")

internal suspend fun BtcPayApi.rateSources(): List<RateSourceData> = get("misc/rate-sources")

internal suspend fun BtcPayApi.languages(): List<LanguageData> = get("misc/lang")

/**
 * Percent-encodes a value being interpolated into a path. Store ids and invoice
 * ids are server-generated and safe, but emails and labels are not, and a `/`
 * or `..` in one would otherwise change which endpoint is called.
 */
internal fun String.pathSegment(): String {
    require(this != "." && this != "..") { "Invalid path identifier" }
    return java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
