package com.yet.pets.io

internal const val BYTES_PER_KIB: Long = 1024L
internal const val BYTES_PER_MIB: Long = 1024L * 1024L

/**
 * Resource limits for package loading. All size arithmetic uses overflow-safe
 * `Long` math.
 *
 * The constructor is intentionally total (no `require`): foreign callers —
 * including Swift — cannot trigger process termination through it. Sanity is
 * enforced at load time instead: loaders return
 * [PetLoadError.InvalidLimits] for non-positive bounds or a ratio below 1.
 */
public data class PetPackageLimits(
    /** Maximum manifest (`pet.json`/`avatar.json`) bytes. */
    public val maxManifestBytes: Long = 64L * BYTES_PER_KIB,
    /** Maximum encoded spritesheet bytes. */
    public val maxSpritesheetBytes: Long = 8L * BYTES_PER_MIB,
    /** Maximum ZIP central-directory entry count. */
    public val maxZipEntries: Int = 64,
    /** Maximum raw ZIP archive bytes (checked before indexing). */
    public val maxCompressedArchiveBytes: Long = 16L * BYTES_PER_MIB,
    /** Maximum summed uncompressed bytes across entries. */
    public val maxUncompressedArchiveBytes: Long = 32L * BYTES_PER_MIB,
    /** Maximum uncompressed bytes of a single entry. */
    public val maxEntryUncompressedBytes: Long = 32L * BYTES_PER_MIB,
    /** Maximum accepted uncompressed:compressed ratio (where meaningful). */
    public val maxCompressionRatio: Double = 100.0,
) {
    public companion object {
        /** Approved v1 defaults. */
        public val Default: PetPackageLimits = PetPackageLimits()
    }

    /** Non-null when these limits are not sane; loaders fail before touching data. */
    internal fun invalidReason(): PetLoadError.InvalidLimits? {
        if (maxManifestBytes <= 0L) return PetLoadError.InvalidLimits("maxManifestBytes must be > 0")
        if (maxSpritesheetBytes <= 0L) return PetLoadError.InvalidLimits("maxSpritesheetBytes must be > 0")
        if (maxZipEntries <= 0) return PetLoadError.InvalidLimits("maxZipEntries must be > 0")
        if (maxCompressedArchiveBytes <= 0L) {
            return PetLoadError.InvalidLimits("maxCompressedArchiveBytes must be > 0")
        }
        if (maxUncompressedArchiveBytes <= 0L) {
            return PetLoadError.InvalidLimits("maxUncompressedArchiveBytes must be > 0")
        }
        if (maxEntryUncompressedBytes <= 0L) {
            return PetLoadError.InvalidLimits("maxEntryUncompressedBytes must be > 0")
        }
        if (!maxCompressionRatio.isFinite() || maxCompressionRatio < 1.0) {
            return PetLoadError.InvalidLimits("maxCompressionRatio must be finite and >= 1")
        }
        return null
    }
}
