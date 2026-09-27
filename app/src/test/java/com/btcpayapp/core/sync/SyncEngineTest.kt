package com.btcpayapp.core.sync

import android.content.ContextWrapper
import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.net.HttpFailure
import com.btcpayapp.core.net.HttpRequest
import com.btcpayapp.core.net.HttpResponse
import com.btcpayapp.core.net.TransportOptions
import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.data.api.ApiException
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.AppSettings
import com.btcpayapp.data.model.Credential
import com.btcpayapp.data.model.Vault
import com.btcpayapp.data.session.AccountRepository
import com.btcpayapp.data.session.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The engine against a fake server, with real (unencrypted) documents and a
 * notifier that records instead of posting. What matters: one failing check
 * does not stop the others, only a server that cannot be reached counts
 * against the account, and the alert says what failed.
 *
 * runBlocking, not runTest: the client and the documents switch to real
 * dispatchers, and virtual time would then fire the engine's timeouts early.
 */
class SyncEngineTest {

    private val dir: File = Files.createTempDirectory("btcpay-sync-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun <T> document(name: String, serializer: KSerializer<T>, default: T) = EncryptedJsonFile(
        File(dir, name), serializer, default, ApiJson.instance, scope,
        encrypt = { it.copyOf() }, decrypt = { it.copyOf() },
    )

    private val account = Account(
        id = "a",
        label = "Shop",
        baseUrl = "https://btcpay.example.org",
        credential = Credential.ApiKey("key"),
    )
    private val vault = document("vault", Vault.serializer(), Vault())
    private val state = document("sync", SyncState.serializer(), SyncState())
    private val server = FakeServer()
    private val notifier = RecordingNotifier()
    private val engine = SyncEngine(
        accounts = AccountRepository(vault),
        settings = SettingsRepository(document("settings", AppSettings.serializer(), AppSettings())),
        client = BtcPayClient(server),
        stateFile = state,
        notifier = notifier,
    )

    @Before fun seed() {
        runBlocking { vault.update { Vault(accounts = listOf(account)) } }
    }

    @After fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Every store answers with nothing new. */
    private fun answerAll(vararg storeIds: String) {
        server.answers["/api/v1/stores"] = ok(storeIds.joinToString(",", "[", "]") { """{"id":"$it"}""" })
        storeIds.forEach {
            server.answers["/api/v1/stores/$it/invoices"] = ok("[]")
            server.answers["/api/v1/stores/$it/payouts"] = ok("[]")
        }
        server.answers["/api/v1/users/me/notifications"] = ok("[]")
    }

    @Test fun `a store that refuses its invoices does not stop the next one`() = runBlocking<Unit> {
        answerAll("s1", "s2")
        server.answers["/api/v1/stores/s1/invoices"] = status(403)
        assertEquals(SyncEngine.Result.NoChange, engine.run())
        assertNull(state.state.value.invoicePoll("a", "s1"))
        assertNotNull(state.state.value.invoicePoll("a", "s2"))
        // A refusal came from a server that is up.
        assertTrue(state.state.value.accountFailureSince.isEmpty())
    }

    @Test fun `a payout list that does not arrive is not an outage, an invoice list is`() = runBlocking<Unit> {
        answerAll("s1")
        // The payout list has no paging and can pass the response cap.
        server.answers["/api/v1/stores/s1/payouts"] = unreachable
        assertEquals(SyncEngine.Result.NoChange, engine.run())
        assertTrue(state.state.value.accountFailureSince.isEmpty())

        server.answers["/api/v1/stores/s1/invoices"] = unreachable
        assertEquals(SyncEngine.Result.Failed, engine.run())
        assertTrue("a" in state.state.value.accountFailureSince)
    }

    @Test fun `an unreachable server starts the outage clock, and its answer stops it`() = runBlocking<Unit> {
        answerAll("s1")
        server.answers["/api/v1/stores"] = unreachable
        assertEquals(SyncEngine.Result.Failed, engine.run())
        assertTrue("a" in state.state.value.accountFailureSince)
        assertTrue("no alert before an hour", notifier.serverIssues.isEmpty())

        answerAll("s1")
        assertEquals(SyncEngine.Result.NoChange, engine.run())
        assertTrue(state.state.value.accountFailureSince.isEmpty())
        assertEquals(listOf("a"), notifier.cleared)
    }

    @Test fun `after an hour a refused key asks for a re-pair, once`() = runBlocking<Unit> {
        state.update { it.withAccountFailure("a", System.currentTimeMillis() - SyncState.SERVER_ISSUE_AFTER_MS) }
        server.answers["/api/v1/stores"] = status(401)
        engine.run()
        engine.run()
        assertEquals(listOf(ApiException.Unauthorized().userMessage), notifier.serverIssues)
    }

    @Test fun `after an hour without an answer the alert says the server could not be reached`() = runBlocking<Unit> {
        state.update { it.withAccountFailure("a", System.currentTimeMillis() - SyncState.SERVER_ISSUE_AFTER_MS) }
        server.answers["/api/v1/stores"] = unreachable
        engine.run()
        assertEquals(listOf("Could not reach this server for over an hour."), notifier.serverIssues)
    }

    @Test fun `after an hour of error answers the alert does not say the server could not be reached`() = runBlocking<Unit> {
        state.update { it.withAccountFailure("a", System.currentTimeMillis() - SyncState.SERVER_ISSUE_AFTER_MS) }
        server.answers["/api/v1/stores"] = status(500)
        engine.run()
        assertEquals(listOf("This server has answered with errors for over an hour."), notifier.serverIssues)
    }

    @Test fun `a payment since the last run is announced with its status`() = runBlocking<Unit> {
        answerAll("s1")
        state.update { it.withInvoicePoll("a", "s1", InvoicePollState(since = 1)) }
        server.answers["/api/v1/stores/s1/invoices"] = ok("""[{"id":"i1","createdTime":5,"status":"Processing"}]""")
        assertEquals(SyncEngine.Result.FoundUpdates(1), engine.run())
        assertEquals(listOf("i1: Payment detected"), notifier.payments)
    }

    @Test fun `payments made while notifications were off are not replayed when they come back`() = runBlocking<Unit> {
        answerAll("s1")
        state.update {
            it.withInvoicePoll("a", "s1", InvoicePollState(since = 1)).withFeedPoll("a", FeedPollState(since = 1))
        }
        server.answers["/api/v1/stores/s1/invoices"] = ok("""[{"id":"i1","createdTime":5,"status":"Settled"}]""")

        notifier.permitted = false
        assertEquals(SyncEngine.Result.Skipped, engine.run())
        assertTrue("nothing is asked", server.asked.isEmpty())

        notifier.permitted = true
        assertEquals(SyncEngine.Result.NoChange, engine.run())
        assertTrue(notifier.payments.isEmpty())
        assertNotNull("adopted again, as on a first run", state.state.value.invoicePoll("a", "s1"))
    }

    @Test fun `what the key may not read is not asked`() = runBlocking<Unit> {
        vault.update { Vault(accounts = listOf(account.copy(permissions = listOf("btcpay.store.canviewinvoices:s1")))) }
        answerAll("s1", "s2")
        engine.run()
        assertEquals(listOf("/api/v1/stores", "/api/v1/stores/s1/invoices"), server.asked)
    }

    /** Answers by path, and a path with no answer is a 404. Records every path asked. */
    private class FakeServer : HttpEngine() {
        val answers = mutableMapOf<String, () -> HttpResponse>()
        val asked = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest, options: TransportOptions): HttpResponse {
            val path = request.url.path
            synchronized(asked) { asked += path }
            return answers[path]?.invoke() ?: HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    /** Built on an empty Android stub context: the overrides never reach the platform. */
    private class RecordingNotifier : Notifier(ContextWrapper(null)) {
        var permitted = true
        val payments = mutableListOf<String>()
        val serverIssues = mutableListOf<String>()
        val cleared = mutableListOf<String>()

        override fun canPost() = permitted

        override fun paymentReceived(
            invoiceId: String,
            accountId: String,
            storeId: String,
            title: String,
            body: String,
            concealed: Boolean,
        ) {
            payments += "$invoiceId: $title"
        }

        override fun payoutWaiting(
            payoutId: String,
            accountId: String,
            storeId: String,
            title: String,
            body: String,
            concealed: Boolean,
        ) = Unit

        override fun serverIssue(accountId: String, title: String, body: String, concealed: Boolean) {
            serverIssues += body
        }

        override fun clearServerIssue(accountId: String) {
            cleared += accountId
        }

        override fun serverNotification(
            notificationId: String,
            accountId: String,
            storeId: String?,
            link: String?,
            title: String,
            body: String,
            concealed: Boolean,
        ) = Unit

        override fun cancelAccount(accountId: String) = Unit
    }

    private companion object {
        fun ok(json: String): () -> HttpResponse = { HttpResponse(200, emptyMap(), json.toByteArray()) }

        fun status(code: Int): () -> HttpResponse = { HttpResponse(code, emptyMap(), ByteArray(0)) }

        val unreachable: () -> HttpResponse = { throw HttpFailure.Transport("connection refused") }
    }
}
