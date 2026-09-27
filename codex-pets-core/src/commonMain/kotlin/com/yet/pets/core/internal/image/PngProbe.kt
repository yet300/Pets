package com.yet.pets.core.internal.image

import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo

private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
private val PNG_IHDR = "IHDR".encodeToByteArray()

/**
 * PNG: 8-byte signature, then an IHDR chunk (length 13) must come first.
 * The IHDR CRC32 (over chunk type + 13 payload bytes) is verified, matching
 * the pinned upstream dimension probe's strictness: a bad CRC fails the probe
 * instead of loading a package whose renderer decode would fail later.
 */
internal fun probePng(window: ByteWindow): SpritesheetInfo? {
    if (!window.bytesMatch(0, PNG_SIGNATURE)) return null
    var at = 8
    val length = window.u32be(at) ?: return null
    if (length != 13L) return null
    if (!window.bytesMatch(at + 4, PNG_IHDR)) return null
    val payload = window.slice(at + 8, 13) ?: return null
    val width = ((payload[0].toLong() and 0xFF) shl 24) or
        ((payload[1].toLong() and 0xFF) shl 16) or
        ((payload[2].toLong() and 0xFF) shl 8) or
        (payload[3].toLong() and 0xFF)
    val height = ((payload[4].toLong() and 0xFF) shl 24) or
        ((payload[5].toLong() and 0xFF) shl 16) or
        ((payload[6].toLong() and 0xFF) shl 8) or
        (payload[7].toLong() and 0xFF)
    if (width <= 0 || height <= 0 || width > 100_000L || height > 100_000L) return null
    val stored = window.slice(at + 8 + 13, 4) ?: return null
    val computed = pngCrc32(PNG_IHDR + payload)
    val storedValue = ((stored[0].toLong() and 0xFF) shl 24) or
        ((stored[1].toLong() and 0xFF) shl 16) or
        ((stored[2].toLong() and 0xFF) shl 8) or
        (stored[3].toLong() and 0xFF)
    if (computed != storedValue) return null
    return SpritesheetInfo(width.toInt(), height.toInt(), SpritesheetFormat.PNG)
}

private fun pngCrc32(bytes: ByteArray): Long {
    var crc = 0xFFFFFFFF.toInt()
    for (byte in bytes) {
        crc = crc xor (byte.toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
        }
    }
    return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
}
