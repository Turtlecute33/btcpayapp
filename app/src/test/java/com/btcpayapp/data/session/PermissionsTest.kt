package com.btcpayapp.data.session

import com.btcpayapp.core.pairing.Pairing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app hides controls a key cannot drive. Matching names exactly hid Send
 * for every store-admin key on BTCPay 2.3, so these follow the
 * server's policy tree in both directions: what a policy implies, and what it
 * does not.
 */
class PermissionsTest {

    @Test
    fun `the full default set covers signing`() {
        assertTrue(Permissions.covers(Pairing.DEFAULT_PERMISSIONS, "${STORE}cansigntransactions"))
    }

    @Test
    fun `store settings alone covers spending, lightning and webhooks`() {
        // What a 2.3 server returns for the default set: it drops the 2.4
        // wallet policies it does not know, but keeps canmodifystoresettings.
        val granted = listOf("${STORE}canmodifystoresettings")
        listOf(
            "${STORE}cansigntransactions",
            "${STORE}canbroadcasttransactions",
            "${STORE}canuselightningnode",
            "${STORE}webhooks.canmodifywebhooks",
            "${STORE}cancreatelightninginvoice",
        ).forEach { assertTrue(it, Permissions.covers(granted, it)) }
    }

    @Test
    fun `viewing store settings covers payouts but not invoice creation or the wallet`() {
        val granted = listOf("${STORE}canviewstoresettings")
        assertTrue(Permissions.covers(granted, "${STORE}canviewpayouts"))
        assertFalse(Permissions.covers(granted, "${STORE}cancreateinvoice"))
        assertFalse(Permissions.covers(granted, "${STORE}canviewwallet"))
    }

    @Test
    fun `managing pull payments covers viewing them but not managing payouts`() {
        val granted = listOf("${STORE}canmanagepullpayments")
        assertTrue(Permissions.covers(granted, "${STORE}canviewpullpayments"))
        assertFalse(Permissions.covers(granted, "${STORE}canmanagepayouts"))
    }

    @Test
    fun `managing users does not cover viewing them`() {
        assertFalse(Permissions.covers(listOf("btcpay.server.canmanageusers"), "btcpay.server.canviewusers"))
    }

    @Test
    fun `an empty scope is unscoped`() {
        val granted = listOf("${STORE}canmodifystoresettings:")
        assertTrue(Permissions.covers(granted, "${STORE}cansigntransactions", storeId = "store-a"))
        assertTrue(Permissions.covers(granted, "${STORE}cansigntransactions", storeId = "store-b"))
    }

    @Test
    fun `a grant scoped to another store is refused`() {
        val granted = listOf("${STORE}canmodifystoresettings:store-a")
        assertTrue(Permissions.covers(granted, "${STORE}cansigntransactions", storeId = "store-a"))
        assertFalse(Permissions.covers(granted, "${STORE}cansigntransactions", storeId = "store-b"))
    }

    @Test
    fun `only unrecognised names let the server decide`() {
        assertTrue(Permissions.covers(listOf("some.future.policy"), "${STORE}cansigntransactions"))
    }

    @Test
    fun `an unknown grant lets the server decide`() {
        assertTrue(Permissions.covers(emptyList(), "${STORE}cansigntransactions"))
        assertTrue(Permissions.covers(null, "${STORE}cansigntransactions"))
    }

    @Test
    fun `unrestricted covers everything`() {
        assertTrue(Permissions.covers(listOf("unrestricted"), "btcpay.server.canmodifyserversettings"))
    }

    private companion object {
        const val STORE = "btcpay.store."
    }
}
