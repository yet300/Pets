package com.yet.pets.core

/**
 * Typed compatibility failures. Covers pure facts only — JSON/schema errors,
 * geometry, supplied spritesheet dimension/format facts, frame counts,
 * animation indices, FPS, loop/fallback invariants.
 *
 * Filesystem/archive failures (missing files, path escapes, ZIP traversal,
 * size limits) belong to the io layer and never appear here.
 */
public sealed interface PetCompatibilityError {
    public val message: String

    /** Manifest bytes are not parseable JSON or do not match the schema types. */
    public data class MalformedManifest(override val message: String) : PetCompatibilityError

    /** Supplied spritesheet dimensions match no known Codex profile. */
    public data class UnsupportedAtlasDimensions(
        public val width: Int,
        public val height: Int,
    ) : PetCompatibilityError {
        override val message: String =
            "unsupported spritesheet dimensions ${width}x$height, expected " +
                "${CodexV1.ATLAS_WIDTH}x${CodexV1.ATLAS_HEIGHT}"
    }

    /** Supplied spritesheet format is not PNG or WebP. */
    public data class UnsupportedSpritesheetFormat(
        public val format: SpritesheetFormat,
    ) : PetCompatibilityError {
        override val message: String =
            "unsupported spritesheet format $format, expected JPEG, PNG, GIF, or WEBP"
    }

    /** Custom `frame` has non-positive values or does not exactly cover the atlas. */
    public data class InvalidFrameGrid(override val message: String) : PetCompatibilityError

    /** Grid cell count exceeds the profile maximum (256 for CLI V1). */
    public data class FrameCountExceeded(
        public val count: Long,
        public val maximum: Int,
    ) : PetCompatibilityError {
        override val message: String = "frame count $count exceeds maximum $maximum"
    }

    /** An animation entry declares no frames. */
    public data class EmptyAnimationFrames(
        public val animation: String,
    ) : PetCompatibilityError {
        override val message: String = "animation $animation must include at least one frame"
    }

    /** An animation references a sprite index outside the grid. */
    public data class SpriteIndexOutOfRange(
        public val animation: String,
        public val index: Int,
        public val frameCount: Int,
    ) : PetCompatibilityError {
        override val message: String =
            "animation $animation references sprite index $index, but pet has $frameCount frames"
    }

    /** FPS is NaN/infinite/non-positive/above 60, or yields a non-finite duration. */
    public data class InvalidFps(
        public val animation: String,
        public val fps: Double,
    ) : PetCompatibilityError {
        override val message: String =
            "animation $animation fps must be finite and between 0 and " +
                "${CodexV1.MAX_FPS}, got $fps"
    }

    /** An animation fallback names an animation absent from the final table. */
    public data class UnknownFallback(
        public val animation: String,
        public val fallback: String,
    ) : PetCompatibilityError {
        override val message: String =
            "animation $animation fallback $fallback does not exist"
    }

    /**
     * Defensive catch-all: a model invariant fired during normalization of
     * foreign input. Unreachable for today's rules (all construction inputs are
     * pre-validated), but it keeps [PetPackageParser.parse] total against
     * future refactors. Never thrown for programmer errors on the Kotlin side.
     */
    public data class NormalizationFailed(override val message: String) : PetCompatibilityError
}

/**
 * Result of compatibility validation. [errors] empty means compatible.
 * [warnings] is reserved for non-failing observations (empty in Phase 1).
 */
public class PetCompatibilityReport(
    errors: List<PetCompatibilityError>,
    warnings: List<String> = emptyList(),
) {
    public val errors: List<PetCompatibilityError> = errors.toList()
    public val warnings: List<String> = warnings.toList()

    /** True when [errors] is empty. */
    public val isCompatible: Boolean
        get() = errors.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is PetCompatibilityReport && errors == other.errors && warnings == other.warnings

    override fun hashCode(): Int = 31 * errors.hashCode() + warnings.hashCode()

    override fun toString(): String =
        "PetCompatibilityReport(errors=$errors, warnings=$warnings)"
}
