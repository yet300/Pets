package com.yet.pets.core

/** Dedicated V2 failures; all compatibility rules here are explicit library policy. */
public sealed interface CodexV2Error {
    public val message: String
    public data class MalformedManifest(override val message: String) : CodexV2Error
    public data class UnsupportedVersion(override val message: String) : CodexV2Error
    public data class UnsupportedOverride(public val member: String) : CodexV2Error {
        override val message: String = "Codex V2 library profile does not support $member overrides"
    }
    public data class GeometryMismatch(public val width: Int, public val height: Int) : CodexV2Error {
        override val message: String = "Codex V2 spritesheet dimensions ${width}x$height, expected 1536x2288"
    }
    public data class UnsupportedSpritesheetFormat(public val format: SpritesheetFormat) : CodexV2Error {
        override val message: String = "unsupported Codex V2 spritesheet format $format, expected PNG or WEBP"
    }
    public data class InvalidSpritesheetBytes(override val message: String) : CodexV2Error
    public data class InputLimitExceeded(override val message: String) : CodexV2Error
    public data class NormalizationFailed(override val message: String) : CodexV2Error
}

/** Defensive snapshot of typed V2 errors. */
public class CodexV2Report(errors: List<CodexV2Error>) {
    public val errors: List<CodexV2Error> = errors.toList()
    public val isCompatible: Boolean get() = errors.isEmpty()
    override fun equals(other: Any?): Boolean = other is CodexV2Report && errors == other.errors
    override fun hashCode(): Int = errors.hashCode()
    override fun toString(): String = "CodexV2Report(errors=$errors)"
}
