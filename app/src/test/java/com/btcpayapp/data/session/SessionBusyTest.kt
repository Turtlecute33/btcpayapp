package com.btcpayapp.data.session

import com.btcpayapp.core.net.HttpEngine
import com.btcpayapp.core.store.EncryptedJsonFile
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.BtcPayClient
import com.btcpayapp.data.model.Account
import com.btcpayapp.data.model.Credential
import com.btcpayapp.data.model.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A store or account switch clears every store screen, and with it a payment's
 * result. So [SessionManager.busy] must stay set while a payment runs and
 * while its outcome is on screen, and the switches must refuse while it is set.
 *
 * The session is never started, so nothing here reaches a server.
 */
class SessionBusyTest {

    private val dir: File = Files.createTempDirectory("btcpay-session-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = EncryptedJsonFile(
        File(dir, "vault"), Vault.serializer(), Vault(), ApiJson.instance, scope,
        encrypt = { it.copyOf() }, decrypt = { it.copyOf() },
    )
    private val session = SessionManager(AccountRepository(vault), BtcPayClient(HttpEngine()), scope)

    @After fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `busy follows spending, also when the payment fails`() = runBlocking {
        assertFalse(session.busy.value)
        session.spending {
            assertTrue(session.busy.value)
            assertTrue(session.spendInFlight.value)
        }
        assertFalse(session.busy.value)
        runCatching { session.spending { error("refused") } }
        assertFalse(session.busy.value)
        assertFalse(session.spendInFlight.value)
    }

    @Test
    fun `an outcome on screen keeps busy set, but is not a payment in flight`() = runBlocking {
        val release = session.holdOutcome()
        assertTrue(session.busy.value)
        assertFalse(session.spendInFlight.value)
        session.spending { }
        assertTrue(session.busy.value)
        release()
        assertFalse(session.busy.value)
    }

    @Test
    fun `a second release does not clear another outcome`() {
        val first = session.holdOutcome()
        val second = session.holdOutcome()
        first()
        first()
        assertTrue(session.busy.value)
        second()
        assertFalse(session.busy.value)
    }

    @Test
    fun `an OutcomeHold holds once, releases on set false and on close, and stays closed`() {
        val other = session.holdOutcome()
        val hold = OutcomeHold(session)
        hold.set(true)
        hold.set(true)
        other()
        assertTrue(session.busy.value)
        // One release undoes both set(true) calls: the hold was taken once.
        hold.set(false)
        assertFalse(session.busy.value)
        hold.set(false)
        assertFalse(session.busy.value)

        hold.set(true)
        assertTrue(session.busy.value)
        hold.close()
        assertFalse(session.busy.value)
        hold.set(true)
        assertFalse(session.busy.value)
    }

    @Test
    fun `an OutcomeHold release does not clear another outcome`() {
        val other = session.holdOutcome()
        val hold = OutcomeHold(session)
        hold.set(false)
        hold.close()
        assertTrue(session.busy.value)
        other()
        assertFalse(session.busy.value)
    }

    @Test
    fun `switches are refused while busy and allowed after`() = runBlocking {
        vault.update { Vault(accounts = listOf(account("a"), account("b")), activeAccountId = "a") }
        session.activeAccount.first { it?.id == "a" }

        val release = session.holdOutcome()
        assertNotNull(session.selectStore("s2"))
        assertNotNull(session.selectAccount("b"))
        assertNull(vault.state.value.activeAccount?.activeStoreId)
        assertEquals("a", vault.state.value.activeAccountId)

        release()
        assertNull(session.selectStore("s2"))
        assertEquals("s2", vault.state.value.activeAccount?.activeStoreId)
        assertNull(session.selectAccount("b"))
        assertEquals("b", vault.state.value.activeAccountId)
    }

    private fun account(id: String) = Account(
        id = id,
        label = "Shop $id",
        baseUrl = "https://btcpay.example.org",
        credential = Credential.ApiKey("key-$id"),
    )
}
