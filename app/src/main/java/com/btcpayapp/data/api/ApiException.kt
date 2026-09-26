package com.btcpayapp.data.api

import com.btcpayapp.data.api.dto.ApiValidationError
import kotlinx.coroutines.CancellationException

/**
 * Everything the API layer can fail with, modelled so the UI can react
 * differently to each case instead of showing one generic "something went
 * wrong".
 *
 * [userMessage] is what a screen shows. It never contains a key, a token, a
 * stack trace, or a raw response body.
 */
sealed class ApiException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    /** DNS, connection refused, no route, proxy down. */
    class Transport(message: String, cause: Throwable? = null) :
        ApiException(message, cause)

    /** The server accepted the connection but did not answer in time. */
    class Timeout(cause: Throwable? = null) :
        ApiException("The server did not respond in time.", cause)

    /**
     * The certificate could not be validated. The onboarding flow catches this
     * specifically to offer trust-on-first-use pinning; everywhere else it is a
     * hard failure, because a pin that changes mid-session is an attack.
     */
    class Tls(message: String, cause: Throwable? = null) :
        ApiException(message, cause)

    /** 401. The key was revoked, or the instance was re-provisioned. */
    class Unauthorized :
        ApiException("This connection is no longer authorised. Re-pair the account.")

    /** 403. [missingPermission] names the scope the key lacks, when the server says. */
    class Forbidden(val missingPermission: String?) : ApiException(
        if (missingPermission != null) {
            "The API key is missing the “$missingPermission” permission."
        } else {
            "The API key is not allowed to do this."
        },
    )

    /** 404. */
    class NotFound(message: String = "Not found on this server.") : ApiException(message)

    /**
     * 410 `unsupported-in-v2`, or any other route the instance has retired.
     * Usually means the server is older or newer than this screen expects.
     */
    class Unsupported(message: String) : ApiException(message)

    /** 400/422 with the array-of-field-errors body. */
    class Validation(val errors: List<ApiValidationError>) : ApiException(
        errors.joinToString("\n") { error ->
            if (error.path.isBlank()) error.message else "${error.path}: ${error.message}"
        }.ifBlank { "The server rejected those values." },
    ) {
        fun messageFor(path: String): String? =
            errors.firstOrNull { it.path.equals(path, ignoreCase = true) }?.message
    }

    /** Any other non-2xx with a `{code, message}` body. */
    class Server(
        val status: Int,
        val code: String,
        message: String,
    ) : ApiException(message.ifBlank { "The server returned an error ($status)." })

    /** The response was not the shape this client expects. */
    class Decoding(cause: Throwable? = null) :
        ApiException("The server sent a response this app could not read.", cause)

    /** No account is selected, so there is nothing to call. */
    class NoAccount : ApiException("No server is connected.")
}

/**
 * Converts a caught failure into an [ApiException] for display.
 *
 * **Rethrows [CancellationException].** This is the important part, and the
 * reason this function exists once instead of being copied into twenty-odd
 * screen files. The house pattern is `runCatching { api.call() }`, and
 * `runCatching` catches `Throwable` — which includes the `CancellationException`
 * that coroutines use for ordinary control flow. So navigating away from a
 * screen, switching store, or letting `collectLatest` restart a load would
 * "fail", and the view model would write a user-visible error banner reading
 * "StandaloneCoroutine was cancelled" over an otherwise healthy screen.
 *
 * Swallowing a cancellation is also a correctness bug in its own right: it
 * breaks structured concurrency, because the coroutine carries on running after
 * its scope has been torn down.
 */
fun Throwable.asApiException(): ApiException {
    if (this is CancellationException) throw this
    return this as? ApiException ?: ApiException.Transport(message ?: "Unexpected failure")
}
