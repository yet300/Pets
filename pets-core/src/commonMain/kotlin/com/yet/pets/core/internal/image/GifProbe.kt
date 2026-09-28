package com.yet.pets.core.internal.image

import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo

/** GIF: `GIF87a`/`GIF89a` header + logical screen descriptor width/height (LE16). */
internal fun probeGif(window: ByteWindow): SpritesheetInfo? {
    if (window.size < 10) return null
    val header = window.slice(0, 6) ?: return null
    val text = header.decodeToString()
    if (text != "GIF87a" && text != "GIF89a") return null
    val width = window.u16le(6) ?: return null
    val height = window.u16le(8) ?: return null
    if (width <= 0 || height <= 0) return null
    return SpritesheetInfo(width, height, SpritesheetFormat.GIF)
}
