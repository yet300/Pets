package com.yet.pets.io.internal.zip

import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError

/**
 * Deterministic package-shape resolution over normalized entries.
 *
 * - Shape A (root): `pet.json` (plus legacy `avatar.json` when
 *   [allowLegacyAvatar]) at the archive root.
 * - Shape B (nested): exactly one top-level directory holding `pet.json`
 *   (plus `avatar.json` when [allowLegacyAvatar]). Entries nested deeper than
 *   one level are not candidates (mirrors "no descendant search" for
 *   directories).
 * - `pet.json` is preferred over `avatar.json` in the same directory,
 *   mirroring upstream manifest load order.
 * - Root + nested manifests, or manifests under two different top-level
 *   directories, fail with [PetLoadError.AmbiguousPackage]. The first package
 *   is never silently chosen. No candidate at all fails with
 *   [PetLoadError.MissingManifest].
 *
 * Generic Pets KMP packages pass `allowLegacyAvatar = false`: discovery
 * requires `pet.json` only, and a legacy-only archive reports
 * [PetLoadError.MissingManifest]. Codex V1 passes `true` for compatibility.
 */
internal class ResolvedZipPackage(
    val prefix: String,
    val manifestEntry: ZipEntry,
    val fallbackId: String,
)

internal fun resolveZipPackage(
    filesByPath: Map<String, ZipEntry>,
    fallbackId: String,
    allowLegacyAvatar: Boolean,
): ResolvedZipPackage {
    val rootPet = filesByPath["pet.json"]
    val rootAvatar = if (allowLegacyAvatar) filesByPath["avatar.json"] else null
    val nestedGroups = filesByPath.keys
        .mapNotNull { path ->
            val segments = path.split('/')
            if (segments.size == 2 && isManifestLeaf(segments[1], allowLegacyAvatar)) {
                segments[0] to path
            } else {
                null
            }
        }
        .groupBy({ it.first }, { it.second })
    val hasRoot = rootPet != null || rootAvatar != null
    if (hasRoot && nestedGroups.isNotEmpty()) {
        throw AbortWith(
            PetLoadError.AmbiguousPackage("archive has root and nested pet manifests"),
        )
    }
    if (nestedGroups.size > 1) {
        throw AbortWith(
            PetLoadError.AmbiguousPackage(
                "archive has pet manifests under several directories: ${nestedGroups.keys.sorted().joinToString()}",
            ),
        )
    }
    if (hasRoot) {
        return ResolvedZipPackage(
            prefix = "",
            manifestEntry = rootPet ?: rootAvatar!!,
            fallbackId = fallbackId,
        )
    }
    val group = nestedGroups.entries.singleOrNull()
        ?: throw AbortWith(PetLoadError.MissingManifest("<zip archive>"))
    val manifestPath = if ("${group.key}/pet.json" in filesByPath) {
        "${group.key}/pet.json"
    } else {
        // Reachable only when allowLegacyAvatar is true: generic groups can
        // only contain pet.json entries.
        "${group.key}/avatar.json"
    }
    return ResolvedZipPackage(
        prefix = group.key,
        manifestEntry = filesByPath.getValue(manifestPath),
        fallbackId = group.key,
    )
}

private fun isManifestLeaf(leaf: String, allowLegacyAvatar: Boolean): Boolean =
    leaf == "pet.json" || (allowLegacyAvatar && leaf == "avatar.json")
