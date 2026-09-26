package com.yet.pets.io

import com.yet.pets.core.PetCompatibilityReport

/**
 * Typed package-loading failures. Every foreign-package failure crosses public
 * boundaries as one of these cases — never as a raw `IOException`, platform
 * exception, or bare string. All constructors are total (no `require`), so
 * foreign Swift callers cannot trigger process termination by constructing them.
 */
public sealed interface PetLoadError {
    public val message: String

    /** No `pet.json` (or legacy `avatar.json`) found at the package location. */
    public data class MissingManifest(public val packageRoot: String) : PetLoadError {
        override val message: String = "missing pet.json or avatar.json in $packageRoot"
    }

    /** Package layout is ambiguous (e.g. root + nested manifests, two nested roots). */
    public data class AmbiguousPackage(override val message: String) : PetLoadError

    /** The manifest-resolved spritesheet asset does not exist in the package. */
    public data class MissingSpritesheet(public val path: String) : PetLoadError {
        override val message: String = "missing spritesheet $path"
    }

    /** The manifest `spritesheetPath` is lexically unsafe (absolute, `..`, drive prefix). */
    public data class InvalidSpritesheetPath(
        public val path: String,
        override val message: String,
    ) : PetLoadError

    /** A symlink (or symlinked parent) resolves outside the package root. */
    public data class PathEscape(public val path: String) : PetLoadError {
        override val message: String = "path escapes package root: $path"
    }

    /** A symlink target does not exist. */
    public data class DanglingSymlink(public val path: String) : PetLoadError {
        override val message: String = "dangling symlink: $path"
    }

    /** A manifest symlink target does not exist. Never classified as [MissingManifest]. */
    public data class DanglingManifest(public val path: String) : PetLoadError {
        override val message: String = "dangling manifest symlink: $path"
    }

    /** Malformed, truncated, or structurally unsupported archive. */
    public data class InvalidArchive(override val message: String) : PetLoadError

    /** Two archive entries normalize to the same package path. */
    public data class DuplicateEntry(public val path: String) : PetLoadError {
        override val message: String = "duplicate archive entry: $path"
    }

    /** ZIP entries must never be symlinks (no accepted in-package mode). */
    public data class SymlinkEntry(public val path: String) : PetLoadError {
        override val message: String = "archive symlink entry is not supported: $path"
    }

    /** Encrypted entries are unsupported and fail deterministically. */
    public data class EncryptedArchive(public val path: String) : PetLoadError {
        override val message: String = "encrypted archive entry is not supported: $path"
    }

    /**
     * A resource limit tripped. [limit] names the stable limit key
     * (e.g. `"manifestBytes"`, `"zipEntries"`, `"compressionRatio"`).
     */
    public data class LimitExceeded(
        public val limit: String,
        override val message: String,
    ) : PetLoadError

    /** The supplied limits themselves are not sane (validated at load time). */
    public data class InvalidLimits(override val message: String) : PetLoadError

    /** Encoded image bytes are unrecognized or structurally invalid. */
    public data class UnsupportedImageFormat(override val message: String) : PetLoadError

    /** Core semantic compatibility rejected the package; carries the full report. */
    public data class CompatibilityFailure(public val report: PetCompatibilityReport) : PetLoadError {
        override val message: String =
            "package is not Codex-compatible: ${report.errors.firstOrNull()?.message}"
    }

    /**
     * Final normalized filesystem/platform error boundary. Carries a diagnostic
     * message only — never a raw exception type.
     */
    public data class IoFailure(override val message: String) : PetLoadError
}
