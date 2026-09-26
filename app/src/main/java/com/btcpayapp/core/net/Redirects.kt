package com.btcpayapp.core.net

import java.net.URL

/** A See Other response retrieves a result; it must not repeat a payment POST. */
internal fun redirectedRequest(request: HttpRequest, status: Int, target: URL): HttpRequest {
    if (target.userInfo != null) throw HttpFailure.Transport("redirect contains user information")
    if (status == 303 && request.method != "HEAD") {
        return request.copy(url = target, method = "GET", body = null, contentType = null,
            headers = request.headers.filterKeys { !it.equals("Content-Type", true) && !it.equals("Content-Length", true) })
    }
    if (status in setOf(301, 302) && request.method !in setOf("GET", "HEAD")) {
        throw HttpFailure.Transport("Refused to repeat a write through an ambiguous redirect. Check the server URL.")
    }
    return request.copy(url = target)
}
