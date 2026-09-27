package com.btcpayapp.ui.screens.lightning

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [newestPerHash] replaced paging with `offsetIndex`, which LND and CLN read as
 * a filter and not a cursor, so the same payments came back page after page.
 */
class LightningPaymentsTest {
    private data class Row(val hash: String, val time: Long?, val tag: String)

    private fun dedupe(vararg rows: Row) = newestPerHash(rows.toList(), Row::hash, Row::time).map(Row::tag)

    @Test
    fun `a repeated hash keeps only its newest row`() {
        // CLN lists a retried payment once per attempt; the newest says what happened.
        assertEquals(
            listOf("a-retry"),
            dedupe(Row("a", 100, "a-first"), Row("a", 300, "a-retry"), Row("a", 200, "a-second")),
        )
    }

    @Test
    fun `the result is newest first whatever order the node sent`() {
        // LND sends its list oldest first.
        assertEquals(listOf("c", "b", "a"), dedupe(Row("a", 1, "a"), Row("b", 2, "b"), Row("c", 3, "c")))
    }

    @Test
    fun `blank hashes match nothing and every such row stays`() {
        assertEquals(listOf("x", "y", "z"), dedupe(Row("", 3, "x"), Row("", 2, "y"), Row(" ", 1, "z")))
    }

    @Test
    fun `a row without a time sorts last`() {
        assertEquals(listOf("dated", "undated"), dedupe(Row("b", null, "undated"), Row("a", 5, "dated")))
    }
}
