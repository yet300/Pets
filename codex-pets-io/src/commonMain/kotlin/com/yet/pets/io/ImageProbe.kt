package com.yet.pets.io

import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo

/**
 * Owner of encoded spritesheet metadata probing. Determines format + dimensions
 * from bounded encoded bytes WITHOUT decoding pixel data, using small
 * format-specific header parsers.
 *
 * Supported set mirrors the pinned upstream TUI `image`-crate features
 * (`jpeg`, `png`, `gif`, `webp` at `openai/codex @ 55543d8`).
 * Returns `null` for unrecognized/truncated/malformed input; never throws for
 * foreign bytes.
 */
internal object ImageProbe {
    fun probe(bytes: ByteArray): SpritesheetInfo? {
        if (bytes.isEmpty()) return null
        return try {
            probePng(bytes) ?: probeGif(bytes) ?: probeJpeg(bytes) ?: probeWebP(bytes)
        } catch (_: Exception) {
            null
        }
    }

    // -- PNG: 8-byte signature, then IHDR (length 13) must come first. --
    private fun probePng(bytes: ByteArray): SpritesheetInfo? {
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(sig)) return null
        var at = 8
        if (bytes.size < at + 8) return null
        val length = be32(bytes, at)
        val type = bytes.copyOfRange(at + 4, at + 8)
        if (!type.contentEquals("IHDR".encodeToByteArray()) || length != 13L) return null
        if (bytes.size < at + 8 + 13) return null
        val width = be32(bytes, at + 8)
        val height = be32(bytes, at + 12)
        if (width <= 0L || height <= 0L || width > 100_000L || height > 100_000L) return null
        return SpritesheetInfo(width.toInt(), height.toInt(), SpritesheetFormat.PNG)
    }

    // -- GIF: GIF87a/GIF89a + logical screen descriptor width/height (LE16). --
    private fun probeGif(bytes: ByteArray): SpritesheetInfo? {
        if (bytes.size < 10) return null
        val header = bytes.copyOfRange(0, 6).decodeToString()
        if (header != "GIF87a" && header != "GIF89a") return null
        val width = le16(bytes, 6)
        val height = le16(bytes, 8)
        if (width <= 0 || height <= 0) return null
        return SpritesheetInfo(width, height, SpritesheetFormat.GIF)
    }

    // -- JPEG: SOI, then segment scan to a valid SOF carrying dimensions. --
    private fun probeJpeg(bytes: ByteArray): SpritesheetInfo? {
        if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var at = 2
        var iterations = 0
        while (at < bytes.size) {
            if (++iterations > 512) return null
            if (bytes[at] != 0xFF.toByte()) return null
            // Skip fill bytes; the marker is the first non-FF byte.
            while (at < bytes.size && bytes[at] == 0xFF.toByte()) at++
            if (at >= bytes.size) return null
            val marker = bytes[at].toInt() and 0xFF
            at++
            when (marker) {
                0xD8 -> Unit // SOI (only valid at start; tolerate repeats)
                0xD9 -> return null // EOI without SOF
                0x01, in 0xD0..0xD7 -> Unit // TEM / RSTn: standalone
                else -> {
                    if (at + 2 > bytes.size) return null
                    val length = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
                    if (length < 2 || at + length > bytes.size) return null
                    if (marker in SOF_MARKERS) {
                        if (length < 7) return null
                        // precision(1) + height(2 BE) + width(2 BE).
                        val height = ((bytes[at + 3].toInt() and 0xFF) shl 8) or (bytes[at + 4].toInt() and 0xFF)
                        val width = ((bytes[at + 5].toInt() and 0xFF) shl 8) or (bytes[at + 6].toInt() and 0xFF)
                        if (width <= 0 || height <= 0) return null
                        return SpritesheetInfo(width, height, SpritesheetFormat.JPEG)
                    }
                    at += length
                }
            }
        }
        return null
    }

    private val SOF_MARKERS: Set<Int> = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )

    // -- WebP: RIFF....WEBP, then VP8 / VP8L / VP8X dimension chunks. --
    private fun probeWebP(bytes: ByteArray): SpritesheetInfo? {
        if (bytes.size < 12) return null
        if (!bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray())) return null
        if (!bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray())) return null
        val riffSize = le32(bytes, 4)
        // RIFF size covers everything after the first 8 bytes; reject truncation claims.
        if (riffSize < 4 || 8L + riffSize > bytes.size) return null
        val end = (8L + riffSize).toInt()
        var at = 12
        var iterations = 0
        while (at + 8 <= end) {
            if (++iterations > 64) return null
            val tag = bytes.copyOfRange(at, at + 4).decodeToString()
            val size = le32(bytes, at + 4)
            if (size < 0 || at + 8 + size > end) return null
            val dataAt = at + 8
            when (tag) {
                "VP8 " -> {
                    // 3-byte frame tag + 0x9D012A start code + 14-bit LE dims.
                    if (size < 10) return null
                    if (bytes[dataAt + 3] != 0x9D.toByte() ||
                        bytes[dataAt + 4] != 0x01.toByte() ||
                        bytes[dataAt + 5] != 0x2A.toByte()
                    ) {
                        return null
                    }
                    val width = le16(bytes, dataAt + 6) and 0x3FFF
                    val height = le16(bytes, dataAt + 8) and 0x3FFF
                    if (width <= 0 || height <= 0) return null
                    return SpritesheetInfo(width, height, SpritesheetFormat.WEBP)
                }
                "VP8L" -> {
                    // 0x2F + 14-bit (width-1) + 14-bit (height-1) packed LE.
                    if (size < 5) return null
                    if (bytes[dataAt] != 0x2F.toByte()) return null
                    val bits = le32(bytes, dataAt + 1)
                    val width = ((bits and 0x3FFFL).toInt()) + 1
                    val height = (((bits ushr 14) and 0x3FFFL).toInt()) + 1
                    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null
                    return SpritesheetInfo(width, height, SpritesheetFormat.WEBP)
                }
                "VP8X" -> {
                    // flags(1) + reserved(3) + canvas width-1 (LE24) + height-1 (LE24).
                    // Layout verified against a real animated sample (300x225).
                    if (size < 10) return null
                    val width = le24(bytes, dataAt + 4) + 1
                    val height = le24(bytes, dataAt + 7) + 1
                    if (width <= 0 || height <= 0 || width > 16383 || height > 16383) return null
                    return SpritesheetInfo(width, height, SpritesheetFormat.WEBP)
                }
                else -> Unit // ANIM/ANMF/ALPH/EXIF/etc: dimension chunks may follow.
            }
            at = dataAt + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    private fun be32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le24(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16)

    private fun le32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
}
