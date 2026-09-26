package com.btcpayapp.core.lightning

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

/**
 * Random access into `assets/lnnodes.bin`, the bundled pubkey → alias table
 * built by `scripts/build-node-directory.rb`.
 *
 * Six thousand names will not fit in a Kotlin source file without paying for it
 * twice: once in the DEX, and again at class-init, where the map would be built
 * entry by entry on the thread that first drew a channel row. So the table is
 * an asset, sorted by key and searched in place. Nothing is parsed at startup
 * and nothing is held on the heap — [NodeDirectory] answers a lookup with about
 * thirteen absolute reads into a memory-mapped file.
 *
 * Layout, big-endian throughout:
 *
 * ```
 *   0   magic "LNND"
 *   4   version, keyBytes, 2 bytes reserved
 *   8   count            u32
 *  12   generatedAt      u32, days since the epoch
 *  16   indexOffset      u32
 *  20   namesOffset      u32
 *       count records of (keyBytes key, u32 offset into the names blob),
 *       ascending by key
 *       names: each a u8 byte length followed by UTF-8
 * ```
 *
 * Only the first [keyBytes] of each pubkey are stored. At sixteen that is 128
 * bits: two of the six thousand listed nodes colliding is not something that
 * happens, and grinding a key to inherit another node's name costs 2^128. The
 * generator verifies the absence of a collision in the data it actually ships,
 * so the truncation can never put a name on the wrong row.
 *
 * Every read is absolute, so one instance is safe to share across threads.
 */
class NodeIndex private constructor(
    private val buffer: ByteBuffer,
    private val keyBytes: Int,
    private val indexOffset: Int,
    private val namesOffset: Int,
    val size: Int,
    val generatedAt: LocalDate,
) {

    private val stride = keyBytes + 4

    /**
     * The alias for [pubkey], which must already be lowercase hex — callers come
     * through [NodeDirectory.normalise]. Null when the node is not listed.
     */
    fun name(pubkey: String): String? {
        val needle = ByteArray(keyBytes)
        if (pubkey.length < keyBytes * 2) return null
        for (i in 0 until keyBytes) {
            val high = digit(pubkey[i * 2])
            val low = digit(pubkey[i * 2 + 1])
            if (high < 0 || low < 0) return null
            needle[i] = ((high shl 4) or low).toByte()
        }

        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = compareAt(middle, needle)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return nameAt(middle)
            }
        }
        return null
    }

    /**
     * One row of the table, for the test that walks the shipped asset end to
     * end. Only the stored prefix of the key survives, which is all the
     * structural assertions need.
     */
    internal data class Entry(val keyPrefix: String, val name: String?)

    internal fun entryAt(position: Int): Entry {
        val record = indexOffset + position * stride
        val hex = StringBuilder(keyBytes * 2)
        for (i in 0 until keyBytes) {
            val byte = buffer.get(record + i).toInt() and 0xFF
            hex.append(HEX[byte shr 4]).append(HEX[byte and 0x0F])
        }
        return Entry(hex.toString(), nameAt(position))
    }

    /** The record at [position] against [needle], as a comparator would. */
    private fun compareAt(position: Int, needle: ByteArray): Int {
        val record = indexOffset + position * stride
        for (i in 0 until keyBytes) {
            val stored = buffer.get(record + i).toInt() and 0xFF
            val wanted = needle[i].toInt() and 0xFF
            if (stored != wanted) return stored - wanted
        }
        return 0
    }

    private fun nameAt(position: Int): String? {
        val offset = buffer.getInt(indexOffset + position * stride + keyBytes)
        // A negative or out-of-range offset means the asset is corrupt. It is
        // still not worth an exception on a channel row: no name is fine.
        if (offset < 0 || namesOffset + offset >= buffer.limit()) return null
        val length = buffer.get(namesOffset + offset).toInt() and 0xFF
        val start = namesOffset + offset + 1
        if (start + length > buffer.limit()) return null
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buffer.get(start + i)
        return String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    private fun digit(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        else -> -1
    }

    companion object {

        private const val HEX = "0123456789abcdef"
        private const val MAGIC = 0x4C4E4E44 // "LNND"
        private const val VERSION = 1
        private const val HEADER_BYTES = 24

        /**
         * Null on anything unexpected rather than an exception: a directory that
         * fails to load costs shortened pubkeys, and that is the fallback the app
         * is built around anyway. Every field is checked here so that [name] can
         * do arithmetic without re-validating on each lookup.
         */
        fun parse(source: ByteBuffer): NodeIndex? {
            // Read-only so a caller cannot mutate the table underneath a lookup,
            // and explicitly big-endian because `slice`/`duplicate` are not
            // required to carry the source's byte order across.
            val buffer = source.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
            if (buffer.limit() < HEADER_BYTES) return null
            if (buffer.getInt(0) != MAGIC) return null
            if ((buffer.get(4).toInt() and 0xFF) != VERSION) return null

            val keyBytes = buffer.get(5).toInt() and 0xFF
            if (keyBytes !in 8..33) return null

            val count = buffer.getInt(8)
            val generatedDays = buffer.getInt(12)
            val indexOffset = buffer.getInt(16)
            val namesOffset = buffer.getInt(20)
            if (count <= 0 || generatedDays <= 0) return null
            if (indexOffset != HEADER_BYTES) return null

            val indexBytes = count.toLong() * (keyBytes + 4)
            if (indexOffset + indexBytes != namesOffset.toLong()) return null
            if (namesOffset <= 0 || namesOffset > buffer.limit()) return null

            return NodeIndex(
                buffer = buffer,
                keyBytes = keyBytes,
                indexOffset = indexOffset,
                namesOffset = namesOffset,
                size = count,
                generatedAt = LocalDate.ofEpochDay(generatedDays.toLong()),
            )
        }
    }
}
