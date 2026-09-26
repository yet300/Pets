package com.yet.pets.io.internal.image

import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo

/**
 * WebP: `RIFF....WEBP`, then dimension-bearing chunks. Supported variants:
 * - `VP8 ` (lossy): 3-byte frame tag + `9D 01 2A` start code + 14-bit LE dims.
 * - `VP8L` (lossless): `0x2F` + packed 14-bit (width-1) / 14-bit (height-1).
 * - `VP8X` (extended): flags(1) + reserved(3) + canvas width-1 (LE24) +
 *   height-1 (LE24). Layout verified against a real animated sample
 *   (300x225 ground truth).
 * Other chunks (ANIM/ANMF/ALPH/EXIF/…) are skipped; dimension chunks may
 * follow them, and odd-sized chunks carry one padding byte.
 */
internal fun probeWebP(window: ByteWindow): SpritesheetInfo? {
    if (window.size < 12) return null
    if (!window.bytesMatch(0, "RIFF".encodeToByteArray())) return null
    if (!window.bytesMatch(8, "WEBP".encodeToByteArray())) return null
    val riffSize = window.u32le(4) ?: return null
    // RIFF size covers everything after the first 8 bytes; reject truncation claims.
    if (riffSize < 4 || 8L + riffSize > window.size) return null
    val end = (8L + riffSize).toInt()
    var at = 12
    var iterations = 0
    while (at + 8 <= end) {
        if (++iterations > 64) return null
        val tag = window.slice(at, 4) ?: return null
        val size = window.u32le(at + 4) ?: return null
        if (size < 0 || at + 8 + size > end) return null
        val dataAt = at + 8
        when (tag.decodeToString()) {
            "VP8 " -> {
                if (size < 10) return null
                if (window.u8(dataAt + 3) != 0x9D ||
                    window.u8(dataAt + 4) != 0x01 ||
                    window.u8(dataAt + 5) != 0x2A
                ) {
                    return null
                }
                val width = (window.u16le(dataAt + 6) ?: return null) and 0x3FFF
                val height = (window.u16le(dataAt + 8) ?: return null) and 0x3FFF
                if (width <= 0 || height <= 0) return null
                return SpritesheetInfo(width, height, SpritesheetFormat.WEBP)
            }
            "VP8L" -> {
                if (size < 5) return null
                if (window.u8(dataAt) != 0x2F) return null
                val bits = window.u32le(dataAt + 1) ?: return null
                val width = ((bits and 0x3FFFL).toInt()) + 1
                val height = (((bits ushr 14) and 0x3FFFL).toInt()) + 1
                if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null
                return SpritesheetInfo(width, height, SpritesheetFormat.WEBP)
            }
            "VP8X" -> {
                if (size < 10) return null
                val width = (window.u24le(dataAt + 4) ?: return null) + 1
                val height = (window.u24le(dataAt + 7) ?: return null) + 1
                if (width <= 0 || height <= 0 || width > 16383 || height > 16383) return null
                return SpritesheetInfo(width, height, SpritesheetFormat.WEBP)
            }
            else -> Unit
        }
        at = dataAt + size.toInt() + (size.toInt() and 1)
    }
    return null
}
