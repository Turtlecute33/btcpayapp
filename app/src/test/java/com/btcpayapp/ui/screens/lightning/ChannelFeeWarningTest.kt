package com.btcpayapp.ui.screens.lightning

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A fee rate typed with a wrong digit (2000 for 20) warns in the channel open review. */
class ChannelFeeWarningTest {

    private fun warning(feeRate: String, estimate: String?) =
        channelFeeWarning(BigDecimal(feeRate), estimate?.let(::BigDecimal))

    @Test
    fun `above twice the estimate warns, up to twice does not`() {
        assertEquals("This fee rate is more than twice the estimate.", warning("2000", "20"))
        assertEquals("This fee rate is more than twice the estimate.", warning("40.01", "20"))
        assertNull(warning("40", "20"))
        assertNull(warning("12.5", "20"))
    }

    @Test
    fun `with no estimate only a rate above the ceiling warns`() {
        assertEquals("This fee rate is more than 500 sat/vB.", warning("2000", null))
        assertEquals("This fee rate is more than 500 sat/vB.", warning("500.5", null))
        assertNull(warning("500", null))
        assertNull(warning("20", null))
    }

    @Test
    fun `an estimate of zero counts as no estimate`() {
        assertEquals("This fee rate is more than 500 sat/vB.", warning("600", "0"))
        assertNull(warning("1", "0"))
    }
}
