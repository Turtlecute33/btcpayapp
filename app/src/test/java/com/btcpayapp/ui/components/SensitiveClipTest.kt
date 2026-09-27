package com.btcpayapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a sensitive clip is due to go. The check can run long after the copy
 * (on the next start, after the process died), so it goes by the clip's own
 * stamp, and a clock set back makes the clip due at once.
 */
class SensitiveClipTest {

    private val stamp = 1_700_000_000_000L

    @Test
    fun `a fresh clip waits the full minute`() {
        assertEquals(60_000L, sensitiveClipLeft(stamp, now = stamp))
    }

    @Test
    fun `a clip waits only for the rest of its minute`() {
        assertEquals(15_000L, sensitiveClipLeft(stamp, now = stamp + 45_000L))
    }

    @Test
    fun `a clip is due once its minute is up, also long after`() {
        assertEquals(0L, sensitiveClipLeft(stamp, now = stamp + 60_000L))
        assertTrue(sensitiveClipLeft(stamp, now = stamp + 86_400_000L) < 0)
    }

    @Test
    fun `a clock set back makes the clip due at once`() {
        assertTrue(sensitiveClipLeft(stamp, now = stamp - 3_600_000L) <= 0)
        assertTrue(sensitiveClipLeft(stamp, now = stamp - 1L) <= 0)
    }
}
