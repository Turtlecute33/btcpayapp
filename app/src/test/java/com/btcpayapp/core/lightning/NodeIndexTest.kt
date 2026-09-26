package com.btcpayapp.core.lightning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDate

/**
 * The shipped asset is data, and data that nobody reads goes wrong quietly.
 * These run against the real `assets/lnnodes.bin` — not a fixture — because the
 * failure this is guarding against is a regenerated file that the app can no
 * longer read, or that carries a name onto the wrong row.
 *
 * `scripts/build-node-directory.rb` already refuses to write a file that
 * violates most of this. Asserting it again here is deliberate: the script runs
 * on one machine, months apart, and the asset is what actually ships.
 */
class NodeIndexTest {

    private val index: NodeIndex = run {
        // Gradle runs unit tests with the module directory as the working
        // directory; an IDE occasionally uses the repository root instead.
        val candidates = listOf(
            File("src/main/assets/lnnodes.bin"),
            File("app/src/main/assets/lnnodes.bin"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("lnnodes.bin not found; run scripts/build-node-directory.rb")
        NodeIndex.parse(ByteBuffer.wrap(file.readBytes()))
            ?: error("the shipped directory does not parse")
    }

    // --- The shipped asset -------------------------------------------------

    @Test
    fun `the directory is large and plausibly dated`() {
        assertTrue("suspiciously small: ${index.size}", index.size > 1_000)
        assertTrue("suspiciously large: ${index.size}", index.size < 100_000)
        assertTrue("generated before the app existed", index.generatedAt > LocalDate.of(2025, 1, 1))
        assertFalse("generated in the future", index.generatedAt > LocalDate.now().plusDays(1))
    }

    @Test
    fun `keys are unique and ascending`() {
        // Both properties matter for a different reason. Ascending is what makes
        // the binary search correct at all; unique is what stops two different
        // nodes from sharing a truncated key, and so a name.
        var previous: String? = null
        for (position in 0 until index.size) {
            val key = index.entryAt(position).keyPrefix
            assertTrue("not lowercase hex: $key", key.matches(Regex("^[0-9a-f]{32}$")))
            previous?.let { assertTrue("out of order at $position: $it then $key", key > it) }
            previous = key
        }
    }

    @Test
    fun `every name is something a human can read`() {
        val hex = Regex("^[0-9a-f]{8,}$")
        for (position in 0 until index.size) {
            val entry = index.entryAt(position)
            val name = entry.name
            assertNotNull("missing name at $position", name)
            name!!
            assertTrue("blank name at ${entry.keyPrefix}", name.isNotBlank())
            assertTrue("over-long name: $name", name.length <= 40)
            assertFalse("hex masquerading as a name: $name", hex.matches(name.lowercase()))
            // Control and format characters are how a name renders as something
            // other than what it is — a right-to-left override turns "safe" into
            // "efas" on screen.
            assertFalse(
                "control or format character in: ${name.map { it.code }}",
                name.any { Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt() },
            )
        }
    }

    @Test
    fun `every row can be found by searching for it`() {
        // Walks the whole table through the public entry point, so a search that
        // is subtly wrong at one end of the range cannot pass unnoticed.
        for (position in 0 until index.size) {
            val entry = index.entryAt(position)
            val pubkey = entry.keyPrefix.padEnd(66, '0')
            assertEquals("row $position not reachable", entry.name, index.name(pubkey))
        }
    }

    @Test
    fun `known nodes resolve and strangers do not`() {
        assertEquals("Boltz", index.name("026165850492521f4ac8abd9bd8088123446d126f648ca35e60f88177dc149ceb2"))
        assertEquals("River Financial 1", index.name("03037dc08e9ac63b82581f79b662a4d0ceca8a8ca162b1af3551595b8f2d97b70a"))
        assertEquals("Voltage", index.name("031f2669adab71548fad4432277a0d90233e3bc07ac29cfb0b3e01bd3fb26cb9fa"))

        assertNull(index.name("02" + "ab".repeat(32)))
        assertNull(index.name("03" + "ff".repeat(32)))
    }

    @Test
    fun `a malformed pubkey is a miss, not a crash`() {
        assertNull(index.name(""))
        assertNull(index.name("02ab"))
        assertNull(index.name("not a pubkey at all, not even close to hex zz"))
    }

    // --- The parser --------------------------------------------------------

    @Test
    fun `a synthetic table round-trips`() {
        val built = build(
            "00112233445566778899aabbccddeeff" to "First",
            "ff00000000000000000000000000ffff" to "Last",
            "8000000000000000000000000000ffff" to "Middle",
        )
        assertEquals(3, built.size)
        assertEquals("First", built.name("00112233445566778899aabbccddeeff".padEnd(66, '0')))
        assertEquals("Middle", built.name("8000000000000000000000000000ffff".padEnd(66, '0')))
        assertEquals("Last", built.name("ff00000000000000000000000000ffff".padEnd(66, '0')))
        assertNull(built.name("7f00000000000000000000000000ffff".padEnd(66, '0')))
    }

    @Test
    fun `a corrupt file loads as no directory rather than throwing`() {
        val good = bytes(
            "00112233445566778899aabbccddeeff" to "First",
        )

        assertNull("empty", NodeIndex.parse(ByteBuffer.allocate(0)))
        assertNull("short header", NodeIndex.parse(ByteBuffer.wrap(good.copyOf(12))))

        val wrongMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull("bad magic", NodeIndex.parse(ByteBuffer.wrap(wrongMagic)))

        val wrongVersion = good.copyOf().also { it[4] = 9.toByte() }
        assertNull("bad version", NodeIndex.parse(ByteBuffer.wrap(wrongVersion)))

        // A count that does not match the declared names offset means the file
        // was truncated or written by a different generator.
        val wrongCount = good.copyOf().also { it[11] = 7.toByte() }
        assertNull("bad count", NodeIndex.parse(ByteBuffer.wrap(wrongCount)))
    }

    // --- Helpers -----------------------------------------------------------

    private fun build(vararg entries: Pair<String, String>): NodeIndex =
        NodeIndex.parse(ByteBuffer.wrap(bytes(*entries)))
            ?: error("the test's own writer produced something unreadable")

    /** The format that `build-node-directory.rb` writes, in miniature. */
    private fun bytes(vararg entries: Pair<String, String>): ByteArray {
        val keyBytes = 16
        val sorted = entries.sortedBy { it.first }
        val names = ArrayList<Byte>()
        val offsets = HashMap<String, Int>()
        for ((_, name) in sorted) {
            offsets.getOrPut(name) {
                val encoded = name.toByteArray(Charsets.UTF_8)
                val offset = names.size
                names.add(encoded.size.toByte())
                encoded.forEach(names::add)
                offset
            }
        }

        val indexOffset = 24
        val namesOffset = indexOffset + sorted.size * (keyBytes + 4)
        val buffer = ByteBuffer.allocate(namesOffset + names.size)
        buffer.put("LNND".toByteArray(Charsets.US_ASCII))
        buffer.put(1.toByte()).put(keyBytes.toByte()).put(0.toByte()).put(0.toByte())
        buffer.putInt(sorted.size)
        buffer.putInt(LocalDate.now().toEpochDay().toInt())
        buffer.putInt(indexOffset)
        buffer.putInt(namesOffset)
        for ((key, name) in sorted) {
            for (i in 0 until keyBytes) {
                buffer.put(key.substring(i * 2, i * 2 + 2).toInt(16).toByte())
            }
            buffer.putInt(offsets.getValue(name))
        }
        for (byte in names) buffer.put(byte)
        return buffer.array()
    }
}
