package com.btcpayapp.ui.screens.apps

import com.btcpayapp.data.api.dto.CrowdfundAppData
import com.btcpayapp.data.api.dto.PointOfSaleAppData
import com.btcpayapp.ui.components.isPublicCleartext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new notification or redirect URL asks first and passes the spend gate;
 * other edits save as before, so the review does not come on every save.
 */
class AppTargetsTest {

    private val none = emptyList<String>()

    private val pos = PointOfSaleEditState().withLoaded(
        PointOfSaleAppData(
            appName = "Shop",
            notificationUrl = "https://shop.example/n",
            redirectUrl = "https://shop.example/r",
        ),
    )

    @Test
    fun `a loaded point of sale and edits to other fields need no review`() {
        assertEquals(none, pos.newTargets())
        assertEquals(none, pos.copy(title = "Till 2", redirectAutomatically = true).newTargets())
        assertEquals("spaces are not a change", none, pos.copy(notificationUrl = " https://shop.example/n ").newTargets())
        assertEquals("a cleared URL sends nothing", none, pos.copy(redirectUrl = "").newTargets())
    }

    @Test
    fun `a changed notification or redirect URL needs review`() {
        assertEquals(listOf("https://other.example/n"), pos.copy(notificationUrl = "https://other.example/n").newTargets())
        assertEquals(listOf("https://other.example/r"), pos.copy(redirectUrl = " https://other.example/r").newTargets())
        assertEquals(
            listOf("https://a.example/n", "https://b.example/r"),
            pos.copy(notificationUrl = "https://a.example/n", redirectUrl = "https://b.example/r").newTargets(),
        )
    }

    @Test
    fun `each URL of a new app needs review`() {
        assertEquals(listOf("https://shop.example/n"), PointOfSaleEditState(notificationUrl = "https://shop.example/n").newTargets())
        assertEquals(listOf("https://shop.example/n"), CrowdfundEditState(notificationUrl = "https://shop.example/n").newTargets())
    }

    @Test
    fun `a crowdfund asks only for a changed notification URL`() {
        val crowdfund = CrowdfundEditState().withLoaded(
            CrowdfundAppData(appName = "Roof", notificationUrl = "https://shop.example/n"),
        )
        assertEquals(none, crowdfund.copy(title = "New roof").newTargets())
        assertEquals(listOf("http://203.0.113.7/n"), crowdfund.copy(notificationUrl = "http://203.0.113.7/n").newTargets())
    }

    @Test
    fun `only http to a host on the internet is cleartext`() {
        assertTrue(isPublicCleartext("http://shop.example/n"))
        assertTrue(isPublicCleartext("HTTP://203.0.113.7/n"))
        assertFalse(isPublicCleartext("https://shop.example/n"))
        assertFalse(isPublicCleartext("http://192.168.1.10/n"))
        assertFalse(isPublicCleartext("http://shoptest.onion/n"))
    }
}
