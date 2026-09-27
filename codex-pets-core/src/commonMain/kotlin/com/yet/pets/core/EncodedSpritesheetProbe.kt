package com.yet.pets.core

import com.yet.pets.core.internal.image.ImageProbe

/** Pure, bounded metadata inspection. This does not decode pixels. */
public object EncodedSpritesheetProbe {
    /** Returns format and dimensions for a supported static image, or null. */
    public fun probe(bytes: ByteArray): SpritesheetInfo? =
        if (bytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES) null else ImageProbe.probe(bytes)
}
