package com.btcpayapp.core.net

import com.btcpayapp.core.util.Log
import java.net.URL

/** A See Other response retrieves a result; it must not repeat a payment POST. */
internal fun redirectedRequest(request: HttpRequest, status: Int, target: URL): HttpRequest {
    if (target.userInfo != null) throw redirectRefused("the target has user information")
    if (status == 303 && request.method != "HEAD") {
        return request.copy(url = target, method = "GET", body = null, contentType = null,
            headers = request.headers.filterKeys { !it.equals("Content-Type", true) && !it.equals("Content-Length", true) })
    }
    if (status in setOf(301, 302) && request.method !in setOf("GET", "HEAD")) {
        throw redirectRefused("a $status could repeat a ${request.method}")
    }
    return request.copy(url = target)
}

/**
 * Same scheme, same host (case-insensitive) and same port, with an absent port
 * read as the scheme's default. Only such a redirect may carry the
 * `Authorization` header; a scheme change also counts as another origin, so a
 * redirect can never downgrade https to http.
 */
internal fun isSameOrigin(from: URL, to: URL): Boolean {
    if (!from.host.equals(to.host, ignoreCase = true)) return false
    if (!from.protocol.equals(to.protocol, ignoreCase = true)) return false
    return from.effectivePort() == to.effectivePort()
}

private fun URL.effectivePort(): Int =
    if (port != -1) port else if (protocol == "https") 443 else 80

/**
 * Every redirect this app does not follow fails with the same plain text, like
 * the other [HttpFailure]s. The [reason] goes to the debug log only.
 */
internal fun redirectRefused(reason: String): HttpFailure.Transport {
    Log.w("Http") { "redirect not followed: $reason" }
    return HttpFailure.Transport(REDIRECT_NOT_FOLLOWED)
}

internal const val REDIRECT_NOT_FOLLOWED = "The server sent a redirect that the app does not follow. Check the server address."
