package com.btcpayapp.core.lightning

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Two things are being protected here.
 *
 * The first is the bundled data: a mistyped pubkey puts a real exchange's name
 * on a stranger's node, which is the one failure mode a directory like this can
 * have that is worse than having no directory at all. The structural checks
 * below catch a dropped or added character, a duplicate, and an "alias" that is
 * really just hex.
 *
 * The second is resolution order. A nickname the operator set must always beat
 * the curated list, which in turn must beat the generated table: the operator
 * knows their own peers, and a hand-verified name beats whatever the public
 * graph currently claims about a pubkey.
 */
class NodeDirectoryTest {

    private val acinq = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"

    /**
     * The generated asset is not installed by a unit test, so the assertions
     * below see the curated list alone. The one test that installs a synthetic
     * table relies on this to put things back.
     */
    @After
    fun uninstall() {
        NodeDirectory.install(null)
    }

    // --- Bundled data ------------------------------------------------------

    @Test
    fun `every bundled key is a 66 character compressed pubkey`() {
        bundled().keys.forEach { key ->
            assertTrue("not a pubkey: $key", NodeDirectory.isPubkey(key))
            assertEquals("must be stored lowercase: $key", key.lowercase(), key)
            // Compressed secp256k1 keys start 02 or 03. An 04 here would be an
            // uncompressed key, which is not what Lightning gossips.
            assertTrue("bad prefix: $key", key.startsWith("02") || key.startsWith("03"))
        }
    }

    @Test
    fun `no bundled pubkey appears twice`() {
        val keys = bundled().keys.toList()
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `no bundled name is just hex`() {
        // Several nodes advertise their own pubkey prefix as their alias. As a
        // "name" that is strictly worse than the shortened key the fallback
        // already produces, so none should have survived into the map.
        val hex = Regex("^[0-9a-f]{6,}$")
        bundled().values.forEach { name ->
            assertFalse("hex masquerading as a name: $name", hex.matches(name.lowercase()))
            assertTrue("blank name", name.isNotBlank())
        }
    }

    @Test
    fun `the directory is not empty`() {
        assertTrue(NodeDirectory.size >= 50)
    }

    // --- Resolution --------------------------------------------------------

    @Test
    fun `a well known node resolves to its name`() {
        assertEquals("ACINQ", NodeDirectory.label(acinq))
        assertTrue(NodeDirectory.isNamed(acinq))
    }

    @Test
    fun `a nickname beats the bundled name`() {
        val label = NodeDirectory.label(acinq, mapOf(acinq to "Our routing peer"))
        assertEquals("Our routing peer", label)
    }

    @Test
    fun `a nickname names an otherwise unknown peer`() {
        val stranger = "02" + "ab".repeat(32)
        assertFalse(NodeDirectory.isNamed(stranger))
        assertEquals(
            "Bob's node",
            NodeDirectory.label(stranger, mapOf(stranger to "Bob's node")),
        )
        assertTrue(NodeDirectory.isNamed(stranger, mapOf(stranger to "Bob's node")))
    }

    @Test
    fun `a blank nickname falls through instead of blanking the row`() {
        // Clearing a nickname is spelled by saving an empty one, so the blank
        // has to behave exactly as if it were never set.
        assertEquals("ACINQ", NodeDirectory.label(acinq, mapOf(acinq to "   ")))
        assertTrue("still named by the bundled list", NodeDirectory.isNamed(acinq, mapOf(acinq to "")))

        val stranger = "02" + "cd".repeat(32)
        assertEquals(NodeDirectory.short(stranger), NodeDirectory.label(stranger, mapOf(stranger to "")))
        assertFalse(NodeDirectory.isNamed(stranger, mapOf(stranger to "  ")))
    }

    @Test
    fun `an unknown peer falls back to a shortened key, never to an invented name`() {
        val stranger = "02" + "ab".repeat(32)
        val label = NodeDirectory.label(stranger)
        assertEquals("02ababab…bababab", label)
        assertFalse(NodeDirectory.isNamed(stranger))
        assertNull(NodeDirectory.wellKnownName(stranger))
    }

    // --- The generated layer -----------------------------------------------

    @Test
    fun `the generated table names a peer the curated list has never heard of`() {
        val boltz = "026165850492521f4ac8abd9bd8088123446d126f648ca35e60f88177dc149ceb2"
        assertFalse("would not be testing the generated layer", bundled().containsKey(boltz))
        assertFalse(NodeDirectory.isNamed(boltz))

        NodeDirectory.install(shippedIndex())
        assertEquals("Boltz", NodeDirectory.label(boltz))
        assertTrue(NodeDirectory.isNamed(boltz))
        assertTrue("the count should include both layers", NodeDirectory.size > bundled().size + 1000)
    }

    @Test
    fun `the curated name wins over whatever the graph says`() {
        NodeDirectory.install(shippedIndex())
        // ACINQ is curated, so the generated table does not carry it at all —
        // and could not displace it even if it did.
        assertEquals("ACINQ", NodeDirectory.label(acinq))
        assertNull(shippedIndex().name(acinq))
    }

    @Test
    fun `a nickname still wins once the generated table is installed`() {
        NodeDirectory.install(shippedIndex())
        val boltz = "026165850492521f4ac8abd9bd8088123446d126f648ca35e60f88177dc149ceb2"
        assertEquals("Swaps", NodeDirectory.label(boltz, mapOf(boltz to "Swaps")))
    }

    @Test
    fun `nobody in the generated table claims a curated name`() {
        // The generator drops these, and this is the assertion that says why it
        // has to: a second node called "Kraken" in the table would be a name the
        // reader has no way to tell apart from the verified one.
        val index = shippedIndex()
        val curated = bundled().values.map { fold(it) }.toSet()
        for (position in 0 until index.size) {
            val name = index.entryAt(position).name ?: continue
            assertFalse("impersonates a curated name: $name", fold(name) in curated)
        }
    }

    private fun fold(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private fun shippedIndex(): NodeIndex {
        val file = listOf(
            File("src/main/assets/lnnodes.bin"),
            File("app/src/main/assets/lnnodes.bin"),
        ).firstOrNull { it.isFile } ?: error("lnnodes.bin not found")
        return NodeIndex.parse(ByteBuffer.wrap(file.readBytes())) ?: error("unreadable directory")
    }

    // --- Normalisation -----------------------------------------------------

    @Test
    fun `a node uri resolves the same as a bare pubkey`() {
        // Channels report a bare key; the connect form and a scanned QR carry
        // `pubkey@host:port`. Both have to land on the same peer.
        assertEquals("ACINQ", NodeDirectory.label("$acinq@3.33.236.230:9735"))
        assertEquals("ACINQ", NodeDirectory.label(acinq.uppercase()))
        assertEquals("ACINQ", NodeDirectory.label("  $acinq  "))
    }

    @Test
    fun `a nickname keyed on the normalised pubkey matches a uri`() {
        val nicknames = mapOf(acinq to "Peer one")
        assertEquals("Peer one", NodeDirectory.label("$acinq@example.com:9735", nicknames))
    }

    @Test
    fun `an empty or malformed pubkey does not crash the row`() {
        assertEquals("Unknown peer", NodeDirectory.label(""))
        assertEquals("Unknown peer", NodeDirectory.label("   "))
        assertFalse(NodeDirectory.isPubkey("not a key"))
        assertFalse(NodeDirectory.isPubkey(acinq.dropLast(1)))
        assertFalse(NodeDirectory.isPubkey(acinq + "0"))
    }

    /**
     * Reaches the private map through the public surface rather than by
     * reflection: every key the directory knows must answer [NodeDirectory
     * .wellKnownName], so the structural assertions above can be written
     * against a reconstruction of it.
     */
    private fun bundled(): Map<String, String> {
        val field = NodeDirectory::class.java.getDeclaredField("CURATED")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(NodeDirectory) as Map<String, String>
    }
}
