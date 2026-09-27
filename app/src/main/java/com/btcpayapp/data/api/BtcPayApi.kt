package com.btcpayapp.data.api

import com.btcpayapp.core.net.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * The typed Greenfield surface.
 *
 * One instance is bound to one [Endpoint], so an account switch produces a new
 * instance rather than mutating a shared client — there is no window in which a
 * request could be sent to one server with another's key.
 *
 * The endpoints themselves live in the `endpoints` package as extension functions.
 * Splitting them that way keeps this class to the transport concern and lets
 * each API area read as a flat list of one-line declarations.
 */
class BtcPayApi(
    val client: BtcPayClient,
    val endpoint: Endpoint,
) {

    val json: Json get() = client.json

    /**
     * The same server with a longer per-read timeout, for the one call that
     * legitimately waits on the server (a Lightning payment blocks until the
     * node gives up). Never shorter than the account's own, and the whole-call
     * deadline in [BtcPayClient.execute] grows with it.
     */
    fun withReadTimeout(readTimeoutMs: Int): BtcPayApi = BtcPayApi(
        client,
        endpoint.copy(
            transport = endpoint.transport.copy(readTimeoutMs = maxOf(endpoint.transport.readTimeoutMs, readTimeoutMs)),
        ),
    )

    /** Serialises a request body. Resolved at compile time, no reflection. */
    inline fun <reified B> body(value: B): String = json.encodeToString(value)

    /**
     * [authenticate] exists for `/api/v1/health`, the one endpoint that must be
     * callable before an account exists. Leaving it defaulted on every other
     * call is correct: an authenticated call with no credential is a bug, and
     * [BtcPayClient.execute] fails it fast rather than letting it go out
     * anonymously and come back 401.
     */
    suspend inline fun <reified T> get(
        path: String,
        query: List<Pair<String, Any?>> = emptyList(),
        authenticate: Boolean = true,
    ): T = decode(raw("GET", path, null, query, authenticate))

    /**
     * A success answer that cannot be read is [ApiException.OutcomeUnknown]
     * here: the server has done the request, and a screen must not offer to
     * send it again as if it had failed.
     */
    suspend inline fun <reified T> post(
        path: String,
        body: String? = null,
        query: List<Pair<String, Any?>> = emptyList(),
    ): T {
        val response = raw("POST", path, body, query)
        return try {
            decode<T>(response)
        } catch (e: ApiException.Decoding) {
            throw ApiException.OutcomeUnknown(e)
        }
    }

    suspend inline fun <reified T> put(
        path: String,
        body: String? = null,
        query: List<Pair<String, Any?>> = emptyList(),
    ): T = decode(raw("PUT", path, body, query))

    suspend inline fun <reified T> patch(
        path: String,
        body: String? = null,
        query: List<Pair<String, Any?>> = emptyList(),
    ): T = decode(raw("PATCH", path, body, query))

    /** For the many endpoints that answer `200` with an empty body. */
    suspend fun call(
        method: String,
        path: String,
        body: String? = null,
        query: List<Pair<String, Any?>> = emptyList(),
    ) {
        raw(method, path, body, query)
    }

    suspend fun raw(
        method: String,
        path: String,
        body: String? = null,
        query: List<Pair<String, Any?>> = emptyList(),
        authenticate: Boolean = true,
    ): HttpResponse = client.execute(
        endpoint = endpoint,
        method = method,
        path = path,
        query = query,
        body = body?.toByteArray(Charsets.UTF_8),
        authenticate = authenticate,
    )

    // `PATCH` is in the permitted method set of Android's HttpURLConnection
    // (it is OkHttp underneath), so the usual X-HTTP-Method-Override dance is
    // not needed here.

    /** Suspends: the parse runs on `Dispatchers.Default`, never on the caller's. */
    suspend inline fun <reified T> decode(response: HttpResponse): T =
        client.decode(response, serializer())
}
