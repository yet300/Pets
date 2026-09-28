package com.yet.pets.io.internal.zip

import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetSpritesheetPathOutcome
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpSpritesheetPathOutcome
import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError
import com.yet.pets.io.PetLoadOutcome
import com.yet.pets.io.PetPackageLimits
import com.yet.pets.io.compatibilityFailureOf
import com.yet.pets.io.finishLoad
import com.yet.pets.io.finishPetsKmpLoad
import com.yet.pets.io.internal.fs.checkManifestRelativePath
import com.yet.pets.io.petsKmpFailureOf

/**
 * ZIP package loading over the structural parser. Collision detection runs
 * over ALL entries (files AND directories) BEFORE directories are discarded:
 * no two entries may normalize — exactly or case-folded — to one path,
 * regardless of kind. No first-wins / last-wins semantics anywhere.
 *
 * Codex V1 entry point. For generic packages use [loadPetsKmpZipBytes], which
 * shares the identical secure archive implementation (traversal protection,
 * CRC checks, DEFLATE consumption, overlap checks, limits, duplicates);
 * only manifest interpretation differs.
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

/**
 * Pets KMP generic v1 ZIP entry point. Shares the identical secure archive
 * implementation with [loadZipBytes]; only path extraction and final parsing
 * differ. No second ZIP implementation exists. Generic identity is
 * manifest-owned, so there is no `fallbackId` parameter.
 */
internal fun loadPetsKmpZipBytes(
    bytes: ByteArray,
    limits: PetPackageLimits,
): PetLoadOutcome = try {
    loadPetsKmpZipOrThrow(bytes, limits)
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
    val pkg = readSharedZipPackage(bytes, fallbackId, limits, allowLegacyAvatar = true)
    val manifestBytes = readZipEntryData(
        bytes,
        pkg.manifestEntry,
        minOf(limits.maxManifestBytes, limits.maxEntryUncompressedBytes),
        "manifestBytes",
    )
    val relPath = when (val pathOutcome = PetPackageParser.spritesheetPathOf(manifestBytes)) {
        is PetSpritesheetPathOutcome.Success -> pathOutcome.path
        is PetSpritesheetPathOutcome.Failure ->
            return PetLoadOutcome.Failure(compatibilityFailureOf(pathOutcome.error))
    }
    checkManifestRelativePath(relPath)
    val sheetBytes = readSharedZipAsset(bytes, pkg, relPath, pkg.byPath, limits)
    return finishLoad(manifestBytes, pkg.resolved.fallbackId, sheetBytes)
}

private fun loadPetsKmpZipOrThrow(
    bytes: ByteArray,
    limits: PetPackageLimits,
): PetLoadOutcome {
    // Generic packages are OUR new format: discovery requires pet.json only
    // (never the Codex legacy avatar.json), and identity is manifest-owned.
    // The shared resolver still takes a root-shape label for its
    // Codex-oriented result type; it is never used for generic identity.
    val pkg = readSharedZipPackage(bytes, "pet", limits, allowLegacyAvatar = false)
    val manifestBytes = readZipEntryData(
        bytes,
        pkg.manifestEntry,
        minOf(limits.maxManifestBytes, limits.maxEntryUncompressedBytes),
        "manifestBytes",
    )
    val relPath = when (val pathOutcome = PetsKmpPackageParser.spritesheetPathOf(manifestBytes)) {
        is PetsKmpSpritesheetPathOutcome.Success -> pathOutcome.path
        is PetsKmpSpritesheetPathOutcome.Failure ->
            return PetLoadOutcome.Failure(petsKmpFailureOf(pathOutcome.error))
    }
    checkManifestRelativePath(relPath)
    val sheetBytes = readSharedZipAsset(bytes, pkg, relPath, pkg.byPath, limits)
    return finishPetsKmpLoad(manifestBytes, sheetBytes)
}

/**
 * Shared secure ZIP package resolution: raw cap, structural parse, collision
 * and symlink/encryption checks, and package-shape resolution. Single
 * canonical implementation for both Codex V1 and Pets KMP generic packages;
 * only the manifest discovery policy ([allowLegacyAvatar]) differs by format.
 * All traversal, CRC, overlap, limit, and duplicate security is shared, never
 * duplicated.
 */
internal class SharedZipPackage(
    val resolved: ResolvedZipPackage,
    val manifestEntry: ZipEntry,
    val byPath: Map<String, ZipEntry>,
)

internal fun readSharedZipPackage(
    bytes: ByteArray,
    fallbackId: String,
    limits: PetPackageLimits,
    allowLegacyAvatar: Boolean,
): SharedZipPackage {
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
    // Collision model first, over every entry kind.
    val seenFolded = HashSet<String>()
    for (entry in archive.entries) {
        if (!seenFolded.add(entry.normalizedPath.lowercase())) {
            // Exact duplicates, case variants (case-insensitive-host safety),
            // and file/directory normalized collisions alike.
            throw AbortWith(PetLoadError.DuplicateEntry(entry.normalizedPath))
        }
    }
    val files = archive.entries.filter { !it.isDirectory }
    for (entry in files) {
        // Symlink rejection is hygiene/defense-in-depth: archives are never
        // extracted or materialized, so even an undetected (non-Unix)
        // representation stays inert payload bytes that still must parse as
        // manifest/image to matter. Unix-flagged entries are rejected outright.
        if (entry.isSymlink) {
            throw AbortWith(PetLoadError.SymlinkEntry(entry.normalizedPath))
        }
        if (entry.isEncrypted) {
            throw AbortWith(PetLoadError.EncryptedArchive(entry.normalizedPath))
        }
    }
    val byPath = LinkedHashMap<String, ZipEntry>()
    for (entry in files) {
        byPath[entry.normalizedPath] = entry
    }
    val resolved = resolveZipPackage(byPath, fallbackId, allowLegacyAvatar)
    return SharedZipPackage(resolved, resolved.manifestEntry, byPath)
}

internal fun readSharedZipAsset(
    bytes: ByteArray,
    pkg: SharedZipPackage,
    relPath: String,
    byPath: Map<String, ZipEntry>,
    limits: PetPackageLimits,
): ByteArray {
    val assetPath = joinPackagePath(pkg.resolved.prefix, relPath)
    val assetEntry = byPath[assetPath]
        ?: throw AbortWith(PetLoadError.MissingSpritesheet(assetPath))
    if (assetEntry.isDirectory) {
        throw AbortWith(PetLoadError.MissingSpritesheet(assetPath))
    }
    return readZipEntryData(
        bytes,
        assetEntry,
        minOf(limits.maxSpritesheetBytes, limits.maxEntryUncompressedBytes),
        "spritesheetBytes",
    )
}
