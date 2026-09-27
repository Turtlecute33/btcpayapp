package com.btcpayapp.data.api

import com.btcpayapp.core.net.TlsProblem
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
     * A POST whose result is not known, so the server may have done it: no
     * answer in time, a connection dropped after it opened, the whole-call
     * deadline, a proxy that lost BTCPay's answer (502, 504, 520, 524), or a
     * success answer this app could not read. For those last two an answer did
     * arrive, and its [ApiException] is the [cause].
     *
     * Only POST produces this. GET, PUT, PATCH and DELETE set a state rather
     * than add one, so repeating them is safe, and for them the same failures
     * stay [Timeout], [Transport], [Server] or [Decoding].
     * A money screen that gets this must never offer a plain retry: it locks the
     * submit and has the user check the result first, because a second send can
     * pay twice.
     */
    class OutcomeUnknown(cause: Throwable? = null) : ApiException(
        "No clear answer came from the server, so this may have gone through. Check before you try again.",
        cause,
    )

    /**
     * The certificate could not be validated, and [problem] says why. The
     * message is fixed per problem, never the platform's text. Onboarding may
     * offer trust-on-first-use only for [TlsProblem.UntrustedIssuer]; everywhere
     * else it is a hard failure, because a pin that changes mid-session is what
     * an attack looks like.
     */
    class Tls(val problem: TlsProblem = TlsProblem.Other, cause: Throwable? = null) :
        ApiException(problem.userMessage, cause)

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

    /**
     * 404. [code] is the Greenfield error code when the body carries one (for
     * example `transaction-not-found`), and null for an empty 404, which means
     * the route itself is missing.
     */
    class NotFound(message: String = "Not found on this server.", val code: String? = null) : ApiException(message)

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
 * Whether a request that makes or moves something (a payment, a channel, a
 * refund, a payout, a pull payment) may still have been done despite this
 * failure, so it must not be offered again until the user has checked. No
 * clear answer is the plain case. Any 5xx is too: the server may have failed
 * after it did the work (a refund saves its pull payment before the link to
 * the invoice, a proxy gives up waiting). A 4xx is a refusal before anything
 * was done.
 */
fun ApiException.mayHaveGoneThrough(): Boolean =
    this is ApiException.OutcomeUnknown || (this is ApiException.Server && status >= 500)

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

/**
 * `runCatching` that lets a cancellation through, for a failure that is not
 * turned into an [ApiException]. See [asApiException] for why it must.
 */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
