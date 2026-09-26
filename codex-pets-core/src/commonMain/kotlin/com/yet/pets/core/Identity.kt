package com.yet.pets.core

/** Normalized pet identity. No filesystem/ZIP/URL knowledge in core. */
public data class PetIdentity(
    public val id: String,
    public val displayName: String,
    public val description: String,
)

/**
 * Pure identity normalization:
 * - trims all values; blank counts as absent,
 * - manifest id wins over the caller-supplied [fallbackId],
 * - displayName = displayName ?: manifestId ?: fallbackId,
 * - description = trimmed value or `""`,
 * - a blank [fallbackId] deterministically normalizes to `"pet"`.
 *
 * Callers (io layer, or hosts passing bytes directly) derive `fallbackId`
 * from directory names, ZIP layout, or their own transport (e.g. a downloaded
 * filename) and pass it in. The library never parses URLs. No random IDs are
 * ever generated.
 */
public fun normalizePetIdentity(
    manifestId: String?,
    displayName: String?,
    description: String?,
    fallbackId: String,
): PetIdentity {
    val safeFallback = fallbackId.trim().ifEmpty { "pet" }
    val safeManifestId = manifestId?.trim()?.ifEmpty { null }
    val safeDisplayName = displayName?.trim()?.ifEmpty { null }
    return PetIdentity(
        id = safeManifestId ?: safeFallback,
        displayName = safeDisplayName ?: safeManifestId ?: safeFallback,
        description = description?.trim() ?: "",
    )
}
