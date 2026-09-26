package com.btcpayapp.data.api

import android.security.NetworkSecurityPolicy
import android.util.Base64
import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpFailure
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.net.buildUrl
import com.btcpayapp.core.util.Log
import com.btcpayapp.data.api.dto.ApiErrorBody
import com.btcpayapp.data.api.dto.ApiValidationError
import com.btcpayapp.data.model.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

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

        // The platform blocks cleartext per the network security config. Check
        // first so the user gets an actionable message instead of a bare
        // "cleartext not permitted" IOException three layers down.
        if (url.protocol.equals("http", ignoreCase = true) &&
            !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(url.host)
        ) {
            throw ApiException.Transport(
                "This build only allows unencrypted HTTP to .onion and loopback addresses. " +
                    "Use https:// for ${url.host}, or reach it over Tor.",
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
        val response = try {
            // `readTimeout` is per-read, so a server dribbling one byte every
            // 29s keeps a coroutine alive forever. This is the whole-call
            // deadline that bounds it.
            withTimeout(transport.connectTimeoutMs + transport.readTimeoutMs * 2L) {
                engine.execute(
                    HttpRequest(method = method, url = url, headers = headers, body = body, contentType = contentType),
                    transport,
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw ApiException.Timeout(e)
        } catch (e: HttpFailure.Tls) {
            throw ApiException.Tls(e.message ?: "The server's certificate was rejected.", e)
        } catch (e: HttpFailure.Timeout) {
            throw ApiException.Timeout(e)
        } catch (e: HttpFailure) {
            throw ApiException.Transport(e.message ?: "Could not reach the server.", e)
        } catch (e: IOException) {
            // Backstop: nothing below this layer may surface a raw IOException.
            throw ApiException.Transport(e.message ?: "Could not reach the server.", e)
        }

        if (!response.isSuccess) {
            throw withContext(Dispatchers.Default) { response.toApiException(json) }
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

/** Where to send a request and how to authenticate it. */
data class Endpoint(
    val baseUrl: String,
    val credential: Credential?,
    val transport: TransportOptions = TransportOptions(),
)

private fun Credential.toHeaderValue(): String = when (this) {
    is Credential.ApiKey -> "token $key"
    is Credential.Basic -> {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP).also { raw.fill(0) }
    }
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
