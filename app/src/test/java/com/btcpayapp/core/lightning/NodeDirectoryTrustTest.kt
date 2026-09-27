package com.btcpayapp.core.lightning

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * [NodeDirectory.trustedName] is what a payment confirmation may say about the
 * payee. A bundled alias is whatever the node's owner chose to call it, so it
 * must never come back from here, even with the generated table installed.
 */
class NodeDirectoryTrustTest {

    private val acinq = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"

    /** Named only by the generated table, not by the curated list. */
    private val boltz = "026165850492521f4ac8abd9bd8088123446d126f648ca35e60f88177dc149ceb2"

    @After
    fun uninstall() {
        NodeDirectory.install(null)
    }

    @Test
    fun `a nickname the operator set is trusted`() {
        NodeDirectory.install(shippedIndex())
        assertEquals("Swaps", NodeDirectory.trustedName(boltz, mapOf(boltz to "Swaps")))
        assertEquals("Our peer", NodeDirectory.trustedName(acinq, mapOf(acinq to "  Our peer ")))
    }

    @Test
    fun `a curated name is trusted`() {
        assertEquals("ACINQ", NodeDirectory.trustedName(acinq))
        assertEquals("ACINQ", NodeDirectory.trustedName("${acinq.uppercase()}@3.33.236.230:9735"))
        assertEquals("ACINQ", NodeDirectory.trustedName(acinq, mapOf(acinq to " ")))
    }

    @Test
    fun `a self-declared bundled alias is not`() {
        NodeDirectory.install(shippedIndex())
        assertNotNull("would not be testing the bundled layer", NodeDirectory.wellKnownName(boltz))
        assertNull(NodeDirectory.trustedName(boltz))
    }

    @Test
    fun `an unknown node has no trusted name`() {
        assertNull(NodeDirectory.trustedName("02" + "ab".repeat(32)))
        assertNull(NodeDirectory.trustedName(""))
    }

    private fun shippedIndex(): NodeIndex {
        val file = listOf(
            File("src/main/assets/lnnodes.bin"),
            File("app/src/main/assets/lnnodes.bin"),
        ).firstOrNull { it.isFile } ?: error("lnnodes.bin not found")
        return NodeIndex.parse(ByteBuffer.wrap(file.readBytes())) ?: error("unreadable directory")
    }
}
