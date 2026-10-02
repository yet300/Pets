package com.yet.pets.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.PI

/**
 * Explicit library profile for the Work Pets V2 artwork contract (1536x2288,
 * sixteen clockwise look poses). Standard tracks reuse the V1 timings also
 * observed in Desktop research. Override rejection and metadata defaults are
 * library policies, not a claim of full Desktop manifest/runtime equivalence.
 */
public object CodexV2 {
    public const val ATLAS_WIDTH: Int = 1536
    public const val ATLAS_HEIGHT: Int = 2288
    public const val COLUMNS: Int = 8
    public const val ROWS: Int = 11
    public const val CELL_WIDTH: Int = 192
    public const val CELL_HEIGHT: Int = 208
    public const val FRAME_COUNT: Int = 88
    public const val DEFAULT_SPRITESHEET_PATH: String = "spritesheet.webp"

    private val lookKeys = listOf(
        "000", "022.5", "045", "067.5", "090", "112.5", "135", "157.5",
        "180", "202.5", "225", "247.5", "270", "292.5", "315", "337.5",
    ).map { PetAnimationKey("look-$it") }

    /**
     * Optional pure host helper: screen dx is right, dy is down. Nearest 22.5°
     * sector clockwise from up; exact ties round clockwise. Offsets of radius
     * <= 1 or containing non-finite values return null (neutral). No cursor IO.
     */
    public fun lookAnimationKeyForOffset(dx: Double, dy: Double): PetAnimationKey? {
        if (!dx.isFinite() || !dy.isFinite()) return null
        // Square only values bounded by one: extreme finite coordinates cannot overflow.
        if (abs(dx) <= 1.0 && abs(dy) <= 1.0 && dx * dx + dy * dy <= 1.0) return null
        val degrees = atan2(dx, -dy) * (180.0 / PI)
        val clockwise = if (degrees < 0) degrees + 360.0 else degrees
        val sector = floor(clockwise / 22.5 + 0.5).toInt() % 16
        return lookKeys[sector]
    }

    internal fun geometry(): AtlasGeometry = AtlasGeometry(
        ATLAS_WIDTH, ATLAS_HEIGHT, COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT,
    )

    internal fun animations(): Map<PetAnimationKey, PetAnimation> =
        CodexV1.defaultAnimations() + lookKeys.mapIndexed { index, key ->
            // A positive model duration preserves PetFrame invariants. Single-frame
            // looping tracks are static in the generic sampler, with no wake-up.
            key to PetAnimation(listOf(PetFrame(72 + index, 1_000_000_000L)), 0, null)
        }
}
