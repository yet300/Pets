package com.yet.pets.core.internal.image

/**
 * Tiny bounded byte-window shared by the format probes. Out-of-bounds reads
 * return `null` (callers fail closed); no exceptions for foreign bytes.
 */
internal class ByteWindow(val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun u8(at: Int): Int? {
        if (at < 0 || at >= size) return null
        return bytes[at].toInt() and 0xFF
    }

    fun bytesMatch(at: Int, expected: ByteArray): Boolean {
        if (at < 0 || at > size - expected.size) return false
        for (i in expected.indices) {
            if (bytes[at + i] != expected[i]) return false
        }
        return true
    }

    fun slice(at: Int, length: Int): ByteArray? {
        if (at < 0 || length < 0 || at > size - length) return null
        return bytes.copyOfRange(at, at + length)
    }

    fun u16be(at: Int): Int? {
        val a = u8(at) ?: return null
        val b = u8(at + 1) ?: return null
        return (a shl 8) or b
    }

    fun u32be(at: Int): Long? {
        val a = u8(at) ?: return null
        val b = u8(at + 1) ?: return null
        val c = u8(at + 2) ?: return null
        val d = u8(at + 3) ?: return null
        return (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()
    }

    fun u16le(at: Int): Int? {
        val a = u8(at) ?: return null
        val b = u8(at + 1) ?: return null
        return a or (b shl 8)
    }

    fun u24le(at: Int): Int? {
        val a = u8(at) ?: return null
        val b = u8(at + 1) ?: return null
        val c = u8(at + 2) ?: return null
        return a or (b shl 8) or (c shl 16)
    }

    fun u32le(at: Int): Long? {
        val a = u8(at) ?: return null
        val b = u8(at + 1) ?: return null
        val c = u8(at + 2) ?: return null
        val d = u8(at + 3) ?: return null
        return a.toLong() or (b.toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24)
    }
}
