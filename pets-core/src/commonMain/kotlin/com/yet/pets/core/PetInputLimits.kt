package com.yet.pets.core

/** Bounds enforced by the public raw-package and Compose byte boundaries. */
public object PetInputLimits {
    public const val MAX_MANIFEST_BYTES: Int = 64 * 1024
    public const val MAX_SPRITESHEET_BYTES: Int = 8 * 1024 * 1024
}
