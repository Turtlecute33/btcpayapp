package com.btcpayapp.ui.screens.notifications

import com.btcpayapp.ui.nav.InvoiceDetailRoute
import com.btcpayapp.ui.nav.PayoutsRoute
import com.btcpayapp.ui.nav.PullPaymentDetailRoute
import com.btcpayapp.ui.nav.PullPaymentsRoute
import com.btcpayapp.ui.nav.ServerUsersRoute
import com.btcpayapp.ui.nav.StoreListRoute
import com.btcpayapp.ui.nav.StoreUsersRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each link below is what BTCPay's own link generator writes for one of the six
 * notification types it defines — see `Services/Notifications/Blobs/`. Get one
 * wrong and the app either opens a browser for a page it already has, or opens
 * its own screen against the wrong store and shows nothing.
 */
class NotificationLinkTest {

    private val base = "https://pay.example.com"

    private fun target(link: String?, baseUrl: String? = base) = notificationTarget(link, baseUrl)

    private fun inApp(link: String?, baseUrl: String? = base) =
        target(link, baseUrl) as NotificationTarget.InApp

    @Test
    fun `invoice event opens the invoice`() {
        // InvoiceEventNotification: UIInvoiceController.Invoice.
        val resolved = inApp("/invoices/AbC123")
        assertEquals(InvoiceDetailRoute("AbC123"), resolved.route)
        assertEquals(null, resolved.storeId)
    }

    @Test
    fun `store-scoped invoice carries its store`() {
        val resolved = inApp("/stores/store-7/invoices/AbC123")
        assertEquals(InvoiceDetailRoute("AbC123"), resolved.route)
        assertEquals("store-7", resolved.storeId)
    }

    @Test
    fun `the public checkout link is still an invoice`() {
        assertEquals(InvoiceDetailRoute("AbC123"), inApp("/i/AbC123").route)
    }

    @Test
    fun `an invoice named in the query is still an invoice`() {
        assertEquals(InvoiceDetailRoute("AbC123"), inApp("/somewhere?invoiceId=AbC123").route)
    }

    @Test
    fun `payout awaiting approval opens that store's payouts`() {
        // PayoutNotification: UIStorePullPaymentsController.Payouts.
        val resolved = inApp("/stores/store-7/payouts?payoutMethodId=BTC-CHAIN")
        assertEquals(PayoutsRoute, resolved.route)
        assertEquals("store-7", resolved.storeId)
    }

    @Test
    fun `an external payout transaction opens the same list`() {
        val resolved = inApp("/stores/store-7/payouts?payoutMethodId=BTC-CHAIN&payoutState=AwaitingPayment")
        assertEquals(PayoutsRoute, resolved.route)
        assertEquals("store-7", resolved.storeId)
    }

    @Test
    fun `a pull payment's payouts are payouts, not the pull payment`() {
        // Ordering matters: this path also matches the pull-payment rule.
        val resolved = inApp("/stores/store-7/pull-payments/pp-1/payouts")
        assertEquals(PayoutsRoute, resolved.route)
        assertEquals("store-7", resolved.storeId)
    }

    @Test
    fun `a pull payment opens its own screen`() {
        assertEquals(PullPaymentDetailRoute("pp-1"), inApp("/stores/store-7/pull-payments/pp-1").route)
        assertEquals(PullPaymentDetailRoute("pp-1"), inApp("/pull-payments/pp-1").route)
        assertEquals(PullPaymentsRoute, inApp("/stores/store-7/pull-payments").route)
    }

    @Test
    fun `a user awaiting approval opens the server user list`() {
        // NewUserRequiresApprovalNotification: UIServerController.User. The app
        // has no page per user, and approving happens in the list.
        assertEquals(ServerUsersRoute, inApp("/server/users/user-9").route)
    }

    @Test
    fun `a store invitation with no url opens the store list`() {
        // StoreInvitationNotification falls back to UIUserStores.ListStores.
        assertEquals(StoreListRoute, inApp("/stores").route)
        assertEquals(StoreUsersRoute, inApp("/stores/store-7/users").route)
    }

    @Test
    fun `a new version release stays on the web`() {
        val link = "https://github.com/btcpayserver/btcpayserver/releases/tag/v2.0.0"
        assertEquals(NotificationTarget.Web(link), target(link))
    }

    @Test
    fun `an absolute link to this server is read as a path`() {
        assertEquals(InvoiceDetailRoute("AbC123"), inApp("$base/invoices/AbC123").route)
    }

    @Test
    fun `an install under a root path still resolves`() {
        val rooted = "https://pay.example.com/btcpay"
        val resolved = inApp("$rooted/stores/store-7/invoices/AbC123", baseUrl = rooted)
        assertEquals(InvoiceDetailRoute("AbC123"), resolved.route)
        assertEquals("store-7", resolved.storeId)
    }

    @Test
    fun `a host that merely starts the same is not the same host`() {
        // A string-prefix test would navigate in-app for this.
        val link = "https://pay.example.com.evil.test/invoices/AbC123"
        assertEquals(NotificationTarget.Web(link), target(link))
    }

    @Test
    fun `a different port is a different server`() {
        val link = "https://pay.example.com:8443/invoices/AbC123"
        assertEquals(NotificationTarget.Web(link), target(link))
    }

    @Test
    fun `an unrecognised path on this server falls back to the browser`() {
        assertEquals(
            NotificationTarget.Web("$base/server/maintenance"),
            target("/server/maintenance"),
        )
    }

    @Test
    fun `no link and nothing to open`() {
        assertEquals(NotificationTarget.None, target(null))
        assertEquals(NotificationTarget.None, target("  "))
        // Relative, unrecognised, and no server to make it absolute against.
        assertEquals(NotificationTarget.None, target("/server/maintenance", baseUrl = null))
    }
}
