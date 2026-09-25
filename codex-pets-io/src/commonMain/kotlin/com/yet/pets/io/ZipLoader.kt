package com.yet.pets.io

import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome

/**
 * ZIP package loading. Shape rules (deterministic; ambiguity is typed failure):
 *
 * - Shape A (root): `pet.json` (or legacy `avatar.json`) at the archive root.
 * - Shape B (nested): exactly one top-level directory containing `pet.json`
 *   (or `avatar.json`).
 * - `pet.json` is preferred over `avatar.json` in the same directory, mirroring
 *   upstream manifest load order.
 * - Root + nested manifests, or manifests under two different top-level
 *   directories, fail with [PetLoadError.AmbiguousPackage]. No manifest at all
 *   fails with [PetLoadError.MissingManifest]. The first package is never
 *   silently chosen.
 *
 * Nothing is ever extracted or materialized to disk: entries are read bounded
 * from the in-memory archive. Directory and ZIP paths compose to equivalent
 * normalized definitions for identical manifest + image bytes (only the
 * `fallbackId` source legitimately differs).
 */
internal fun loadZipBytes(
    bytes: ByteArray,
    fallbackId: String,
    limits: PetPackageLimits,
): PetLoadOutcome = try {
    loadZipOrThrow(bytes, fallbackId, limits)
} catch (e: AbortWith) {
    PetLoadOutcome.Failure(e.error)
} catch (e: Exception) {
    PetLoadOutcome.Failure(PetLoadError.InvalidArchive("cannot read archive: ${e.message}"))
}

private fun loadZipOrThrow(
    bytes: ByteArray,
    fallbackId: String,
    limits: PetPackageLimits,
): PetLoadOutcome {
    limits.invalidReason()?.let { throw AbortWith(it) }
    // Raw cap BEFORE indexing: over-limit blobs fail without central-directory work.
    if (bytes.size.toLong() > limits.maxCompressedArchiveBytes) {
        throw AbortWith(
            PetLoadError.LimitExceeded(
                "compressedArchiveBytes",
                "archive is ${bytes.size} bytes, cap is ${limits.maxCompressedArchiveBytes}",
            ),
        )
    }
    val archive = ZipArchive.parse(bytes, limits)
    val files = archive.entries.filter { !it.isDirectory }
    for (entry in files) {
        if (entry.isSymlink) {
            throw AbortWith(PetLoadError.SymlinkEntry(entry.normalizedPath))
        }
        if (entry.isEncrypted) {
            throw AbortWith(PetLoadError.EncryptedArchive(entry.normalizedPath))
        }
    }
    val byPath = LinkedHashMap<String, ZipEntry>()
    val seenFolded = HashSet<String>()
    for (entry in files) {
        if (byPath.containsKey(entry.normalizedPath) ||
            !seenFolded.add(entry.normalizedPath.lowercase())
        ) {
            // Exact duplicates, plus case variants that would collide on
            // case-insensitive host filesystems.
            throw AbortWith(PetLoadError.DuplicateEntry(entry.normalizedPath))
        }
        byPath[entry.normalizedPath] = entry
    }

    val rootPet = byPath["pet.json"]
    val rootAvatar = byPath["avatar.json"]
    val nestedGroups = byPath.keys
        .mapNotNull { path ->
            val segments = path.split('/')
            if (segments.size == 2 && (segments[1] == "pet.json" || segments[1] == "avatar.json")) {
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
    val packagePrefix: String
    val manifestEntry: ZipEntry
    val resolvedFallback: String
    if (hasRoot) {
        packagePrefix = ""
        manifestEntry = rootPet ?: rootAvatar!!
        resolvedFallback = fallbackId
    } else {
        val group = nestedGroups.entries.singleOrNull()
            ?: throw AbortWith(PetLoadError.MissingManifest("<zip archive>"))
        packagePrefix = group.key
        val paths = group.value
        manifestEntry = if ("${group.key}/pet.json" in paths) {
            byPath.getValue("${group.key}/pet.json")
        } else {
            byPath.getValue("${group.key}/avatar.json")
        }
        resolvedFallback = group.key
    }

    val manifestBytes = readZipEntryData(
        bytes,
        manifestEntry,
        minOf(limits.maxManifestBytes, limits.maxEntryUncompressedBytes),
        "manifestBytes",
    )
    val relPath = when (val pathOutcome = PetPackageParser.spritesheetPathOf(manifestBytes)) {
        is com.yet.pets.core.PetSpritesheetPathOutcome.Success -> pathOutcome.path
        is com.yet.pets.core.PetSpritesheetPathOutcome.Failure ->
            return PetLoadOutcome.Failure(compatibilityFailureOf(pathOutcome.error))
    }
    checkManifestRelativePath(relPath)
    val assetPath = joinPackagePath(packagePrefix, relPath)
    val assetEntry = byPath[assetPath]
        ?: throw AbortWith(PetLoadError.MissingSpritesheet(assetPath))
    if (assetEntry.isDirectory) {
        throw AbortWith(PetLoadError.MissingSpritesheet(assetPath))
    }
    val sheetBytes = readZipEntryData(
        bytes,
        assetEntry,
        minOf(limits.maxSpritesheetBytes, limits.maxEntryUncompressedBytes),
        "spritesheetBytes",
    )
    return finishLoad(manifestBytes, resolvedFallback, sheetBytes)
}
