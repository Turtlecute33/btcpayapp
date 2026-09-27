package com.btcpayapp.data.api

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [attempt] catches failures as `runCatching` does, but never a cancellation. */
class AttemptTest {

    @Test
    fun `a value and a failure come back as a result`() {
        assertEquals(Result.success(1), attempt { 1 })
        val failure = IllegalStateException("broken")
        assertEquals(failure, attempt<Int> { throw failure }.exceptionOrNull())
    }

    @Test
    fun `a cancellation is thrown on`() {
        val cancellation = CancellationException("left the screen")
        val thrown = runCatching { attempt<Int> { throw cancellation } }.exceptionOrNull()
        assertTrue(thrown === cancellation)
    }
}
