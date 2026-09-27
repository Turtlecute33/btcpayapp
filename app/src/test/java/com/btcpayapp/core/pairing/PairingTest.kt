package com.btcpayapp.core.pairing

import com.btcpayapp.data.session.Permissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authorize URL is the one place the app tells a server what access it
 * wants. A mistake here either asks for too much — alarming the user and
 * over-privileging the key — or too little, producing 403s halfway through a
 * screen. Both are worth a test.
 */
class PairingTest {

    @Test
    fun `permissions are repeated, not comma-joined`() {
        val url = Pairing.authorizeUrl(
            baseUrl = BASE,
            redirectUri = "http://127.0.0.1:41234/abc",
            permissions = listOf("btcpay.store.canviewinvoices", "btcpay.store.cancreateinvoice"),
        )

        assertTrue(url.contains("permissions=btcpay.store.canviewinvoices"))
        assertTrue(url.contains("permissions=btcpay.store.cancreateinvoice"))
        assertFalse("a comma-joined list is silently ignored by the server", url.contains("%2C"))
    }

    @Test
    fun `redirect is percent-encoded so the colon and slashes survive`() {
        val url = Pairing.authorizeUrl(
            baseUrl = BASE,
            redirectUri = "http://127.0.0.1:41234/abc",
            permissions = emptyList(),
        )

        assertTrue(url.contains("redirect=http%3A%2F%2F127.0.0.1%3A41234%2Fabc"))
    }

    @Test
    fun `the consent page lets the user untick and pick stores`() {
        val url = Pairing.authorizeUrl(BASE, "http://127.0.0.1:1/x", emptyList())
        val manual = Pairing.manualAuthorizeUrl(BASE, Pairing.DEFAULT_PERMISSIONS)

        // strict=true draws every requested box disabled, so the user could
        // only take the whole list; selectiveStores=true lets the
        // user scope the key to one store.
        assertTrue(url.contains("strict=false"))
        assertTrue(url.contains("selectiveStores=true"))
        assertTrue(manual.contains("strict=false"))
        assertFalse(manual.contains("strict=true"))
    }

    @Test
    fun `the point of sale set can take payments and nothing more`() {
        val pos = Pairing.POINT_OF_SALE_PERMISSIONS
        assertTrue(Permissions.covers(pos, "btcpay.store.cancreateinvoice"))
        assertTrue(Permissions.covers(pos, "btcpay.store.cancreatelightninginvoice"))
        // Opening a notification marks it seen, a PUT the view permission does not allow.
        assertTrue(Permissions.covers(pos, "btcpay.user.canmanagenotificationsforuser"))
        val forbidden = listOf(
            "btcpay.store.canmodifystoresettings",
            "btcpay.store.canmanagewallets",
            "btcpay.store.canmanagewalletsettings",
            "btcpay.store.canmanagewallettransactions",
            "btcpay.store.cancreatetransactions",
            "btcpay.store.cansigntransactions",
            "btcpay.store.canbroadcasttransactions",
            "btcpay.store.canuselightningnode",
            "btcpay.store.canmanagepayouts",
            "btcpay.store.canmanagepullpayments",
            "btcpay.store.canmodifyinvoices",
            "btcpay.store.canmodifypaymentrequests",
            "btcpay.store.webhooks.canmodifywebhooks",
        )
        assertEquals(emptyList<String>(), forbidden.filter { Permissions.covers(pos, it) })
        assertTrue(pos.none { it.startsWith("btcpay.server.") })
    }

    @Test
    fun `the consent page shows the launcher label`() {
        // Must equal app_name in res/values/strings.xml.
        assertEquals("BtcPayServer", Pairing.APPLICATION_NAME)
    }

    @Test
    fun `a trailing slash on the base url does not produce a double slash`() {
        val url = Pairing.authorizeUrl("$BASE/", "http://127.0.0.1:1/x", emptyList())
        assertTrue(url.startsWith("$BASE/api-keys/authorize?"))
    }

    @Test
    fun `the default permission set does not ask for server administration`() {
        // Asking for server settings on a merchant terminal makes the consent
        // screen frightening for no benefit; it is a separate opt-in.
        assertTrue(Pairing.DEFAULT_PERMISSIONS.none { it.startsWith("btcpay.server.") })
        assertFalse(Pairing.DEFAULT_PERMISSIONS.contains("unrestricted"))
    }

    @Test
    fun `the read-only set contains no mutating permission`() {
        val mutating = Pairing.READ_ONLY_PERMISSIONS.filter { permission ->
            permission.contains("canmodify") ||
                permission.contains("cancreate") ||
                permission.contains("canmanage") ||
                permission.contains("cansign") ||
                permission.contains("canbroadcast")
        }
        assertEquals(emptyList<String>(), mutating)
    }

    @Test
    fun `login hand-off keeps the authorize page as the return target`() {
        val authorize = Pairing.authorizeUrl(BASE, "http://127.0.0.1:1/x", listOf("btcpay.user.canviewprofile"))
        val url = Pairing.loginThenAuthorizeUrl(BASE, "deadbeef", authorize)

        assertTrue(url.startsWith("$BASE/login?LoginCode=deadbeef&returnUrl="))
        // The return target must be a path, not an absolute URL, or the server
        // treats it as an open redirect and drops it.
        assertTrue(url.contains("returnUrl=%2Fapi-keys%2Fauthorize"))
    }

    @Test
    fun `checkout url is built from the invoice id`() {
        assertEquals("$BASE/i/inv123", Pairing.checkoutUrl(BASE, "inv123"))
    }

    private companion object {
        const val BASE = "https://pay.example.com"
    }
}
