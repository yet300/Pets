package com.yet.pets.io.internal.zip

import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError
import com.yet.pets.io.PetLoadOutcome
import com.yet.pets.io.PetPackageLimits
import com.yet.pets.io.compatibilityFailureOf
import com.yet.pets.io.finishLoad
import com.yet.pets.io.internal.fs.checkManifestRelativePath
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetSpritesheetPathOutcome

/**
 * ZIP package loading over the structural parser. Collision detection runs
 * over ALL entries (files AND directories) BEFORE directories are discarded:
 * no two entries may normalize — exactly or case-folded — to one path,
 * regardless of kind. No first-wins / last-wins semantics anywhere.
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
    val pkg = resolveZipPackage(byPath, fallbackId)

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
    val assetPath = joinPackagePath(pkg.prefix, relPath)
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
    return finishLoad(manifestBytes, pkg.fallbackId, sheetBytes)
}
