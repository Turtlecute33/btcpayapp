package com.btcpayapp.data.api

import android.security.NetworkSecurityPolicy
import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpFailure
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.NEEDS_HTTPS
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.net.buildUrl
import com.btcpayapp.core.util.Log
import com.btcpayapp.data.api.dto.ApiErrorBody
import com.btcpayapp.data.api.dto.ApiValidationError
import com.btcpayapp.data.model.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Locale

/**
 * Turns an [HttpRequest] into a typed result, and every failure into an
 * [ApiException].
 *
 * Authentication is `Authorization: token <key>`. The server also accepts
 * `Bearer`, but `token` is the documented form and the one every BTCPay example
 * uses, so it is what this client sends.
 */
class BtcPayClient(
    private val engine: HttpEngine,
    val json: Json = ApiJson.instance,
) {

    suspend fun execute(
        endpoint: Endpoint,
        method: String,
        path: String,
        query: List<Pair<String, Any?>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = if (body != null) "application/json" else null,
        authenticate: Boolean = true,
    ): HttpResponse {
        // Inside the translation boundary: `buildUrl` throws `HttpFailure`,
        // which is an `IOException`, not an `ApiException`. Uncaught, it would
        // escape the API layer entirely, and callers doing `as? ApiException`
        // would get null — rendering neither data nor an error, i.e. a blank
        // screen.
        val url = try {
            buildUrl(endpoint.baseUrl, path, query)
        } catch (e: HttpFailure) {
            throw ApiException.Transport(e.message ?: "The server address is not valid.", e)
        }

        val cleartext = url.protocol.equals("http", ignoreCase = true)

        // The platform blocks cleartext per the network security config, and
        // says so at connect time with the same text. Checking first answers
        // before anything is opened. `getInstance()` is null only under the JVM
        // unit-test stubs, where the check is skipped; the SDK declares it
        // non-null, hence the explicit type.
        val policy: NetworkSecurityPolicy? = NetworkSecurityPolicy.getInstance()
        if (cleartext && policy?.isCleartextTrafficPermitted(url.host) == false) {
            throw ApiException.Transport(NEEDS_HTTPS)
        }

        // The cleartext rule is checked against the target, not the proxy hop.
        // Plain HTTP through a proxy on another machine therefore crosses the
        // Wi-Fi with the key in clear text. Refused, never rerouted: sending it
        // somewhere the user did not choose would be worse.
        //
        // What stays: an .onion request reaches the proxy on this phone in
        // clear text too (Orbot's 127.0.0.1:9050 by default), and Tor encrypts
        // it only from there. While Orbot is stopped, any app can listen on
        // that port and read the key; for an https server it cannot read the
        // key, but it learns the server's name and can connect from this
        // phone's address. No public Orbot API lets this app prove who holds
        // the port, so nothing here can check it.
        val proxy = endpoint.transport.proxy
        if (cleartext && proxy != null && !isOnThisPhone(proxy.host)) {
            throw ApiException.Transport(
                "This account's proxy is not on this phone, so the key would travel unencrypted. " +
                    "Change the proxy in the account settings.",
            )
        }

        // Sent anyway, an authenticated call with no credential would go out
        // anonymously, come back 401, and tell the user to re-pair a perfectly
        // good account.
        if (authenticate && endpoint.credential == null) throw ApiException.NoAccount()

        val headers = buildMap {
            if (authenticate) endpoint.credential?.let { put("Authorization", it.toHeaderValue()) }
        }

        val transport = endpoint.transport
        // Only a POST can do something twice. The other verbs set a state, so
        // they keep the plain Timeout and Transport answers, whose retry is safe.
        val unsafeToRepeat = method.equals("POST", ignoreCase = true)
        val response = try {
            // `readTimeout` is per-read, so a server dribbling one byte every
            // 29s keeps a coroutine alive forever. This is the whole-call
            // deadline that bounds it. `OrNull`, so that null is unmistakably
            // this deadline: an outer one (a job's time limit) arrives as a
            // cancellation and must stay one.
            withTimeoutOrNull(transport.connectTimeoutMs + transport.readTimeoutMs * 2L) {
                engine.execute(
                    HttpRequest(method = method, url = url, headers = headers, body = body, contentType = contentType),
                    transport,
                )
            }
        } catch (e: IOException) {
            // A cancelled caller's disconnect surfaces as an IOException, and it
            // wins over the cancellation unless it is rethrown as one here.
            currentCoroutineContext().ensureActive()
            throw e.toApiException(unsafeToRepeat)
        } ?: throw if (unsafeToRepeat) ApiException.OutcomeUnknown() else ApiException.Timeout()

        if (!response.isSuccess) {
            val failure = withContext(Dispatchers.Default) { response.toApiException(json) }
            // A proxy's answer, not BTCPay's: BTCPay may still have done the
            // request, so a POST must not be offered as a plain retry. The
            // answer stays the cause, for a caller that knows its POST changes
            // nothing and shows the real failure.
            if (unsafeToRepeat && response.code in GATEWAY_LOST_ANSWER) throw ApiException.OutcomeUnknown(failure)
            throw failure
        }
        return response
    }

    /**
     * Parsing is CPU-bound and must not run on the caller's dispatcher.
     *
     * `HttpEngine.execute` wraps only the socket work in `Dispatchers.IO`, and
     * `withContext` restores the caller's context on return — and every caller
     * is a `viewModelScope.launch`, i.e. `Dispatchers.Main.immediate`. Without
     * a switch here the full body→String copy and the JSON parse would run on
     * the UI thread for every response, including the home dashboard's
     * 100-invoice fetch.
     */
    suspend fun <T> decode(response: HttpResponse, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.Default) {
            val text = response.bodyAsText()
            try {
                json.decodeFromString(deserializer, text)
            } catch (e: SerializationException) {
                Log.e("BtcPayClient", e) { "could not decode a ${text.length}-byte response" }
                throw ApiException.Decoding(e)
            } catch (e: IllegalArgumentException) {
                throw ApiException.Decoding(e)
            }
        }
}

/**
 * [unsafeToRepeat]: a POST that failed after the connection opened may have
 * been done, so it becomes [ApiException.OutcomeUnknown] instead of a failure
 * the user would retry.
 */
private fun IOException.toApiException(unsafeToRepeat: Boolean): ApiException {
    // Backstop: nothing below this layer may surface a raw IOException, or its text.
    val failure = this as? HttpFailure ?: return ApiException.Transport("The connection to the server failed.", this)
    if (unsafeToRepeat && failure.requestSent) return ApiException.OutcomeUnknown(failure)
    return when (failure) {
        is HttpFailure.Tls -> ApiException.Tls(failure.problem, failure)
        is HttpFailure.Timeout -> ApiException.Timeout(failure)
        is HttpFailure.Transport -> ApiException.Transport(failure.message ?: "The connection to the server failed.", failure)
    }
}

/**
 * Whether a proxy [host] is this phone. Literal names only: a name is never
 * resolved to decide this, because a lookup is what an attacker on the network
 * would answer. The account screen checks with the same list before it saves.
 */
internal fun isOnThisPhone(host: String): Boolean = host.lowercase(Locale.ROOT) in LOOPBACK_HOSTS

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]", "localhost")

/**
 * Bad gateway, gateway timeout, and Cloudflare's "unknown error" and "a
 * timeout occurred": a proxy in front of BTCPay has no answer to pass on, and
 * BTCPay may have received the request.
 */
private val GATEWAY_LOST_ANSWER = setOf(502, 504, 520, 524)

/** Where to send a request and how to authenticate it. */
data class Endpoint(
    val baseUrl: String,
    val credential: Credential?,
    val transport: TransportOptions = TransportOptions(),
)

private fun Credential.toHeaderValue(): String = when (this) {
    is Credential.ApiKey -> "token $key"
}

/**
 * BTCPay uses two different error bodies and picks between them per endpoint,
 * not per status code: 422 is always the array form, 400 may be either. So the
 * first non-whitespace byte decides.
 */
private fun HttpResponse.toApiException(json: Json): ApiException {
    val text = bodyAsText().trim()

    if (text.startsWith("[")) {
        val errors = runCatching {
            json.decodeFromString(ListSerializer(ApiValidationError.serializer()), text)
        }.getOrNull()
        if (!errors.isNullOrEmpty()) return ApiException.Validation(errors)
    }

    val error = if (text.startsWith("{")) {
        runCatching { json.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
    } else {
        null
    }

    return when (code) {
        401 -> ApiException.Unauthorized()
        403 -> ApiException.Forbidden(error?.missingPermission)
        404 -> ApiException.NotFound(
            error?.message?.takeIf { it.isNotBlank() } ?: "Not found on this server.",
            code = error?.code?.takeIf { it.isNotBlank() },
        )
        410 -> ApiException.Unsupported(
            error?.message?.takeIf { it.isNotBlank() }
                ?: "This server version no longer supports that operation.",
        )
        in 500..599 -> ApiException.Server(
            status = code,
            code = error?.code ?: "server-error",
            message = error?.message?.takeIf { it.isNotBlank() }
                ?: "The server is having trouble ($code).",
        )
        else -> ApiException.Server(
            status = code,
            code = error?.code ?: "generic-error",
            message = error?.message.orEmpty(),
        )
    }
}
