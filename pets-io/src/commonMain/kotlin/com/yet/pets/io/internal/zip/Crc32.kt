package com.yet.pets.io.internal.zip

/**
 * Minimal table-driven CRC-32 (IEEE 802.3, polynomial 0xEDB88320) for ZIP entry
 * integrity checks. Format-specific glue only — not a general hashing API.
 */
internal object Crc32 {
    private val table: IntArray = IntArray(256) { index ->
        var entry = index
        repeat(8) {
            entry = if (entry and 1 != 0) (entry ushr 1) xor 0xEDB88320.toInt() else entry ushr 1
        }
        entry
    }

    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
        var crc = 0xFFFFFFFF.toInt()
        val end = offset + length
        for (index in offset until end) {
            crc = table[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
    }
}
