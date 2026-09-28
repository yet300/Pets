package com.yet.pets.core.internal.image

import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo

/**
 * Owner of encoded spritesheet metadata probing. Determines format + dimensions
 * from bounded encoded bytes WITHOUT decoding pixel data, using small
 * format-specific header parsers.
 *
 * Contract: ImageProbe validates enough encoded structure to provide load-time
 * dimensions with the compatibility strictness required by the pinned Codex
 * implementation (`image::image_dimensions` at `openai/codex @ 55543d8`). It is
 * NOT full image decoding, and a successful probe is NOT a guarantee that all
 * pixel data is renderable. However, structural integrity properties checked by
 * the pinned upstream dimension probe that we have explicitly verified — such
 * as PNG IHDR CRC — are respected here.
 *
 * Supported set mirrors the pinned upstream TUI `image`-crate features
 * (`jpeg`, `png`, `gif`, `webp`). Returns `null` for unrecognized, truncated,
 * or malformed input; never throws for foreign bytes.
 */
internal object ImageProbe {
    fun probe(bytes: ByteArray): SpritesheetInfo? {
        if (bytes.isEmpty()) return null
        val window = ByteWindow(bytes)
        return try {
            probePng(window) ?: probeGif(window) ?: probeJpeg(window) ?: probeWebP(window)
        } catch (_: Exception) {
            null
        }
    }
}
