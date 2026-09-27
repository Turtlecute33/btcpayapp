package com.btcpayapp.ui

import com.btcpayapp.ui.nav.AccountsRoute
import com.btcpayapp.ui.nav.CreateInvoiceRoute
import com.btcpayapp.ui.nav.InvoiceDetailRoute
import com.btcpayapp.ui.nav.NotificationsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

/**
 * The rules for `btcpayapp://` links. Any installed app can send one, so these
 * are what stands between another app and the active store or the browser.
 */
class DeepLinkTest {

    private val base = "https://pay.mystore.com"
    private val stores = listOf("s1", "s2")

    private fun resolve(
        link: String,
        active: String? = "a1",
        settled: Boolean = true,
        hasAccounts: Boolean = true,
    ) = resolveLink(link, hasAccounts, active, base, stores, settled)

    /** A feed entry as the app's own notification builds it. */
    private fun feed(link: String, account: String? = "a1") =
        "btcpayapp://notification/n1?" + listOfNotNull(
            account?.let { "account=$it" },
            "link=" + URLEncoder.encode(link, "UTF-8"),
        ).joinToString("&")

    @Test
    fun `a link that names its account opens in the store it names`() {
        assertEquals(
            LinkAction.Open(InvoiceDetailRoute("inv1"), storeId = "s2"),
            resolve("btcpayapp://invoice/inv1?account=a1&store=s2"),
        )
    }

    @Test
    fun `a link that does not name its account cannot move the store`() {
        assertEquals(LinkAction.Open(InvoiceDetailRoute("inv1")), resolve("btcpayapp://invoice/inv1?store=s2"))
        // Nor can a feed entry without one, and it is not marked read.
        assertEquals(
            LinkAction.Open(InvoiceDetailRoute("inv9")),
            resolve(feed("/stores/s2/invoices/inv9", account = null)),
        )
    }

    @Test
    fun `another account's link waits for the account switch`() {
        assertEquals(LinkAction.Wait, resolve("btcpayapp://invoice/inv1?account=a2&store=s2"))
    }

    @Test
    fun `a store link waits while the store list loads`() {
        assertEquals(LinkAction.Wait, resolve("btcpayapp://invoice/inv1?account=a1&store=s2", settled = false))
    }

    @Test
    fun `a store missing from a settled list drops the link`() {
        assertEquals(LinkAction.Drop, resolve("btcpayapp://invoice/inv1?account=a1&store=s9"))
        // A feed entry is shown in the list instead, and not marked read.
        assertEquals(LinkAction.Open(NotificationsRoute), resolve(feed("/stores/s9/invoices/inv9")))
    }

    @Test
    fun `a feed entry opens in the store its link names and is marked read`() {
        assertEquals(
            LinkAction.Open(InvoiceDetailRoute("inv9"), storeId = "s2", markRead = "n1"),
            resolve(feed("/stores/s2/invoices/inv9")),
        )
    }

    @Test
    fun `a web address needs a yes`() {
        val url = "https://github.com/btcpayserver/btcpayserver/releases"
        assertEquals(LinkAction.Web(url, markRead = "n1"), resolve(feed(url)))
    }

    @Test
    fun `a web address that hides its host is not offered`() {
        for (url in listOf(
            "https://pay.mystore.com@evil.example/login",
            "https://pay.mystore.com%2Finvoices%2F" + "x".repeat(2000) + "@evil.example/",
        )) {
            assertEquals(url, LinkAction.Open(NotificationsRoute, markRead = "n1"), resolve(feed(url)))
        }
    }

    @Test
    fun `the host shown is the host the browser opens`() {
        assertEquals("evil.example", webHost("https://evil.example/pay.mystore.com"))
        assertEquals("mynode.local", webHost("http://mynode.local/invoices"))
        assertNull(webHost("https://pay.mystore.com@evil.example/"))
        // Browsers read a backslash as a slash; a strict parser refuses it.
        assertNull(webHost("https://evil.example\\@pay.mystore.com/"))
        assertNull(webHost("javascript:alert(1)"))
        assertNull(webHost("intent://evil#Intent;end"))
        assertNull(webHost("not a url"))
    }

    @Test
    fun `links that are not this app's are dropped`() {
        assertEquals(LinkAction.Drop, resolve("https://pay.mystore.com/invoices/inv1"))
        assertEquals(LinkAction.Drop, resolve("btcpayapp://shortcut/terminal", hasAccounts = false))
        assertEquals(LinkAction.Drop, resolve("btcpayapp://shortcut/unknown"))
    }

    @Test
    fun `launcher shortcuts and the settings link`() {
        assertEquals(LinkAction.Terminal, resolve("btcpayapp://shortcut/terminal"))
        assertEquals(LinkAction.Scan, resolve("btcpayapp://shortcut/scan"))
        assertEquals(LinkAction.Open(CreateInvoiceRoute()), resolve("btcpayapp://shortcut/new-invoice"))
        assertEquals(LinkAction.Open(AccountsRoute), resolve("btcpayapp://settings/connections"))
    }

    @Test
    fun `query values decode as Android decodes them, first one wins`() {
        val link = parseDeepLink("btcpayapp://invoice/inv1?account=a1&account=a2&note=a+b%2Bc")!!
        assertEquals("a1", link.param("account"))
        assertEquals("a b+c", link.param("note"))
        assertEquals(listOf("inv1"), link.path)
        assertNull(parseDeepLink("other://invoice/inv1"))
    }

    @Test
    fun `the account rule`() {
        assertEquals(AccountStep.Reached, accountStep("a1", activeAccountId = "a1", reachedBefore = false, known = true))
        assertEquals(AccountStep.Switch, accountStep("a2", activeAccountId = "a1", reachedBefore = false, known = true))
        // The user moved to another account after the link had reached its own.
        assertEquals(AccountStep.Drop, accountStep("a2", activeAccountId = "a1", reachedBefore = true, known = true))
        assertEquals(AccountStep.Drop, accountStep("a9", activeAccountId = "a1", reachedBefore = false, known = false))
    }
}
