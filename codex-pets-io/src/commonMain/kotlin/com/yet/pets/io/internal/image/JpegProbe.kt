package com.yet.pets.io.internal.image

import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo

private val JPEG_SOF_MARKERS: Set<Int> = setOf(
    0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
)

/**
 * JPEG: SOI, then a defensive segment scan to a valid Start-Of-Frame marker
 * carrying dimensions. Segment lengths are bounds-checked; standalone markers
 * (TEM, RSTn) carry no length; the scan guarantees progress and is
 * iteration-capped. APP/metadata segments before SOF are skipped, not parsed.
 */
internal fun probeJpeg(window: ByteWindow): SpritesheetInfo? {
    if (window.u8(0) != 0xFF || window.u8(1) != 0xD8) return null
    var at = 2
    var iterations = 0
    while (at < window.size) {
        if (++iterations > 512) return null
        if (window.u8(at) != 0xFF) return null
        // Skip fill bytes; the marker is the first non-FF byte.
        while (at < window.size && window.u8(at) == 0xFF) at++
        val marker = window.u8(at) ?: return null
        at++
        when (marker) {
            0xD8 -> Unit // SOI (only valid at start; tolerate repeats)
            0xD9 -> return null // EOI without SOF
            0x01 -> Unit // TEM: standalone
            in 0xD0..0xD7 -> Unit // RSTn: standalone
            else -> {
                val length = window.u16be(at) ?: return null
                if (length < 2 || at + length > window.size) return null
                if (marker in JPEG_SOF_MARKERS) {
                    if (length < 7) return null
                    // precision(1) + height(2 BE) + width(2 BE).
                    val height = window.u16be(at + 3) ?: return null
                    val width = window.u16be(at + 5) ?: return null
                    if (width <= 0 || height <= 0) return null
                    return SpritesheetInfo(width, height, SpritesheetFormat.JPEG)
                }
                at += length
            }
        }
    }
    return null
}
