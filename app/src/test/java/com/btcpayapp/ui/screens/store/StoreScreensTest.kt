package com.btcpayapp.ui.screens.store

import com.btcpayapp.data.api.dto.RoleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreScreensTest {

    @Test
    fun `a node is named by type and host, never by its credentials`() {
        assertEquals(
            "lnd-rest · node.example",
            connectionSummary("type=lnd-rest;server=https://node.example:8080/;macaroon=abcd"),
        )
        // Java reads no host from a name with '_'; the review must still show one.
        val lnbits = connectionSummary("type=lnbits;server=https://user:secret@lnbits_host.example/;api-key=k")
        assertEquals("lnbits · lnbits_host.example", lnbits)
        assertFalse(lnbits.contains("secret"))
        assertEquals("Server's internal node", connectionSummary(" internal node "))
        assertEquals("External node", connectionSummary("garbage"))
    }

    @Test
    fun `a role that can spend or change the wallet gives control of the store`() {
        val roles = listOf(
            RoleData(id = "Cashier", permissions = listOf("btcpay.store.canviewinvoices", "btcpay.store.cancreateinvoice")),
            RoleData(id = "Refunds", permissions = listOf("btcpay.store.cancreatenonapprovedpullpayments")),
            RoleData(id = "Payer", permissions = listOf("btcpay.store.canmanagepayouts")),
            RoleData(id = "Lightning", permissions = listOf("btcpay.store.canuselightningnode")),
            RoleData(id = "Wallet", permissions = listOf("btcpay.store.canmanagewallets")),
            RoleData(id = "Admin", permissions = listOf("btcpay.store.canmodifystoresettings")),
            RoleData(id = "Blank"),
        )
        assertTrue(roleGivesControl("owner", emptyList()))
        assertFalse(roleGivesControl("Cashier", roles))
        assertFalse("payouts still need approval", roleGivesControl("Refunds", roles))
        for (role in listOf("Payer", "Lightning", "Wallet", "Admin")) assertTrue(role, roleGivesControl(role, roles))
        // What this app cannot read is asked about.
        assertTrue(roleGivesControl("Blank", roles))
        assertTrue(roleGivesControl("Missing", roles))
    }

    @Test
    fun `the webhook host is read as the server reads it`() {
        // .NET reads '\' as '/', so the host is before it, not after the '@'.
        assertEquals("shop.example.com", hostOf("http://shop.example.com\\@192.168.1.1/hook"))
        assertEquals("hook_backend", hostOf("https://user:pw@hook_backend:8080/x"))
    }

    @Test
    fun `a numeric host asks before cleartext unless it is a plain local quad`() {
        assertTrue(isLocalWebhookHost("hook_backend"))
        assertTrue(isLocalWebhookHost("192.168.1.10"))
        assertTrue(isLocalWebhookHost("[::1]"))
        // All public addresses to the server: 8.8.8.8 written other ways.
        for (host in listOf("134744072", "134744072.", "0x08080808", "8.8.8.8")) {
            assertFalse(host, isLocalWebhookHost(host))
        }
        assertFalse("octal 010 is 8", isLocalWebhookHost("010.0.0.1"))
        assertFalse("an escape the server may decode", isLocalWebhookHost("%31%30.0.0.1"))
    }
}
