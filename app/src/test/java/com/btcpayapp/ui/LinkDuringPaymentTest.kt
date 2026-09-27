package com.btcpayapp.ui

import com.btcpayapp.ui.nav.InvoiceDetailRoute
import com.btcpayapp.ui.nav.NotificationsRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which links must wait while a payment runs or its result is on screen: a
 * store switch closes the payment's screen, and the Scan shortcut could start
 * a second payment of the same code.
 */
class LinkDuringPaymentTest {

    @Test
    fun `the scan shortcut waits for a payment`() {
        assertTrue(closesScreens(LinkAction.Scan, activeStoreId = "s1"))
    }

    @Test
    fun `a link into another store closes screens`() {
        assertTrue(closesScreens(LinkAction.Open(InvoiceDetailRoute("inv1"), storeId = "s2"), activeStoreId = "s1"))
    }

    @Test
    fun `a link into the active store only pushes a screen`() {
        assertFalse(closesScreens(LinkAction.Open(InvoiceDetailRoute("inv1"), storeId = "s1"), activeStoreId = "s1"))
        assertFalse(closesScreens(LinkAction.Open(NotificationsRoute), activeStoreId = "s1"))
    }

    @Test
    fun `the terminal shortcut, a web link and a dropped link close nothing`() {
        assertFalse(closesScreens(LinkAction.Terminal, activeStoreId = "s1"))
        assertFalse(closesScreens(LinkAction.Web("https://pay.mystore.com/"), activeStoreId = "s1"))
        assertFalse(closesScreens(LinkAction.Drop, activeStoreId = "s1"))
    }
}
