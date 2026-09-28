package com.yet.pets.core

/**
 * Typed failures for Pets KMP Package Format v1.
 *
 * Pure generic-format facts only — JSON/schema errors, geometry, supplied
 * spritesheet dimension/format facts, frame indices, durations, loop model,
 * and default-animation invariants. Filesystem/archive failures belong to the
 * io layer and never appear here.
 */
public sealed interface PetsKmpError {
    public val message: String

    /** Raw input exceeds the bounded public parser policy. */
    public data class InputLimitExceeded(override val message: String) : PetsKmpError

    /** Encoded image metadata is malformed, unsupported, or animated. */
    public data class InvalidSpritesheetBytes(override val message: String) : PetsKmpError

    /** Manifest bytes are not parseable JSON or do not match the schema types. */
    public data class MalformedManifest(override val message: String) : PetsKmpError

    /** `schema` is missing or is not `"pets-kmp"`. */
    public data class InvalidSchema(override val message: String) : PetsKmpError

    /** `schemaVersion` is missing or is not integer `1`. */
    public data class UnsupportedSchemaVersion(override val message: String) : PetsKmpError

    /** `id` is missing or blank. */
    public data class MissingId(override val message: String) : PetsKmpError

    /** `displayName` is missing or blank. */
    public data class MissingDisplayName(override val message: String) : PetsKmpError

    /** `frame.width`/`frame.height` are missing or not positive integers. */
    public data class InvalidFrameSize(override val message: String) : PetsKmpError

    /** Cell size does not divide the atlas exactly or capacity is unsafe. */
    public data class InvalidGrid(override val message: String) : PetsKmpError

    /** Grid cell count exceeds the safe maximum (256, same envelope as Codex V1). */
    public data class FrameCapacityExceeded(
        public val count: Long,
        public val maximum: Int,
    ) : PetsKmpError {
        override val message: String = "frame capacity $count exceeds maximum $maximum"
    }

    /** An animation key is blank, overlong, or otherwise invalid. */
    public data class InvalidAnimationKey(override val message: String) : PetsKmpError

    /** Two animations declare the same key. */
    public data class DuplicateAnimationKey(
        public val key: String,
    ) : PetsKmpError {
        override val message: String = "duplicate animation key $key"
    }

    /** No animations declared. */
    public data class EmptyAnimations(override val message: String) : PetsKmpError

    /** An animation declares no frames. */
    public data class EmptyAnimationFrames(
        public val animation: String,
    ) : PetsKmpError {
        override val message: String = "animation $animation must include at least one frame"
    }

    /** A frame index is negative or at/above grid capacity. */
    public data class InvalidFrameIndex(
        public val animation: String,
        public val index: Int,
        public val capacity: Long,
    ) : PetsKmpError {
        override val message: String =
            "animation $animation references frame index $index, but capacity is $capacity"
    }

    /** A frame duration is zero, negative, or overflows nanos conversion. */
    public data class InvalidDuration(
        public val animation: String,
        override val message: String,
    ) : PetsKmpError

    /** `loopStart` is out of range for its animation. */
    public data class InvalidLoopStart(
        public val animation: String,
        override val message: String,
    ) : PetsKmpError

    /** `defaultAnimation` is missing, blank, or names no declared animation. */
    public data class UnknownDefaultAnimation(override val message: String) : PetsKmpError

    /** Supplied spritesheet format is outside the static PNG/JPEG/GIF/WebP set. */
    public data class UnsupportedSpritesheetFormat(
        public val format: SpritesheetFormat,
    ) : PetsKmpError {
        override val message: String =
            "unsupported spritesheet format $format, expected JPEG, PNG, GIF, or WEBP"
    }

    /**
     * Defensive catch-all: a model invariant fired during normalization of
     * foreign input. Keeps the parser total against future refactors.
     */
    public data class NormalizationFailed(override val message: String) : PetsKmpError
}

/**
 * Result of Pets KMP generic validation. [errors] empty means compatible.
 */
public class PetsKmpReport(
    errors: List<PetsKmpError>,
) {
    public val errors: List<PetsKmpError> = errors.toList()

    /** True when [errors] is empty. */
    public val isCompatible: Boolean
        get() = errors.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is PetsKmpReport && errors == other.errors

    override fun hashCode(): Int = errors.hashCode()

    override fun toString(): String = "PetsKmpReport(errors=$errors)"
}
