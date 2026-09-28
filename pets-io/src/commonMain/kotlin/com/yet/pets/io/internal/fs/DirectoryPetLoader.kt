package com.yet.pets.io.internal.fs

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
import com.yet.pets.io.petsKmpFailureOf
import okio.FileSystem
import okio.Path

/**
 * Filesystem directory package loader.
 *
 * Manifest discovery is deterministic: `pet.json` is preferred, with legacy
 * `avatar.json` as fallback only when `pet.json` is ABSENT. Security policy:
 * if the preferred `pet.json` exists as a filesystem entry but cannot be
 * safely resolved (dangling, escaping, otherwise unsafe), the package FAILS —
 * discovery never silently falls back to `avatar.json`, so a malicious
 * preferred manifest cannot alter discovery semantics.
 *
 * Every followed package file — manifest AND spritesheet — passes canonical
 * containment: the manifest leaf is canonicalized and required to stay inside
 * the canonical root, exactly like the asset. A manifest symlink pointing
 * outside yields [PetLoadError.PathEscape]; a dangling manifest symlink yields
 * [PetLoadError.DanglingManifest] (never [PetLoadError.MissingManifest]).
 */
internal fun loadPetDirectory(
    fileSystem: FileSystem,
    root: Path,
    limits: PetPackageLimits,
): PetLoadOutcome = try {
    loadPetDirectoryOrThrow(fileSystem, root, limits)
} catch (e: AbortWith) {
    PetLoadOutcome.Failure(e.error)
} catch (e: Exception) {
    PetLoadOutcome.Failure(PetLoadError.IoFailure("cannot load directory $root: ${e.message}"))
}

/**
 * Pets KMP generic v1 directory entry point. Shares the identical secure
 * directory implementation (manifest discovery, canonical containment,
 * symlink policy, bounded reads); only path extraction and final parsing
 * differ. No second directory implementation exists.
 */
internal fun loadPetsKmpDirectoryFromFs(
    fileSystem: FileSystem,
    root: Path,
    limits: PetPackageLimits,
): PetLoadOutcome = try {
    loadPetsKmpDirectoryOrThrow(fileSystem, root, limits)
} catch (e: AbortWith) {
    PetLoadOutcome.Failure(e.error)
} catch (e: Exception) {
    PetLoadOutcome.Failure(PetLoadError.IoFailure("cannot load directory $root: ${e.message}"))
}

private fun loadPetDirectoryOrThrow(
    fileSystem: FileSystem,
    root: Path,
    limits: PetPackageLimits,
): PetLoadOutcome {
    val shared = readSharedDirectoryPackage(fileSystem, root, limits, allowLegacyAvatar = true)
    val relPath = when (val pathOutcome = PetPackageParser.spritesheetPathOf(shared.manifestBytes)) {
        is PetSpritesheetPathOutcome.Success -> pathOutcome.path
        is PetSpritesheetPathOutcome.Failure ->
            return PetLoadOutcome.Failure(compatibilityFailureOf(pathOutcome.error))
    }
    checkManifestRelativePath(relPath)
    val sheetBytes = readSharedDirectoryAsset(fileSystem, shared, relPath, limits)
    return finishLoad(shared.manifestBytes, shared.fallbackId, sheetBytes)
}

private fun loadPetsKmpDirectoryOrThrow(
    fileSystem: FileSystem,
    root: Path,
    limits: PetPackageLimits,
): PetLoadOutcome {
    // Generic packages are OUR new format: discovery requires pet.json only,
    // never the Codex legacy avatar.json.
    val shared = readSharedDirectoryPackage(fileSystem, root, limits, allowLegacyAvatar = false)
    val relPath = when (val pathOutcome = PetsKmpPackageParser.spritesheetPathOf(shared.manifestBytes)) {
        is PetsKmpSpritesheetPathOutcome.Success -> pathOutcome.path
        is PetsKmpSpritesheetPathOutcome.Failure ->
            return PetLoadOutcome.Failure(petsKmpFailureOf(pathOutcome.error))
    }
    checkManifestRelativePath(relPath)
    val sheetBytes = readSharedDirectoryAsset(fileSystem, shared, relPath, limits)
    return finishPetsKmpLoad(shared.manifestBytes, sheetBytes)
}

/**
 * Shared secure directory package reader: limits check, canonical root,
 * deterministic manifest discovery, and bounded manifest read. Single
 * canonical implementation for both Codex V1 and Pets KMP generic packages;
 * only the manifest discovery policy ([allowLegacyAvatar]) differs by format.
 * All traversal, symlink, canonical-containment, and bounded-read security is
 * shared, never duplicated.
 */
internal class SharedDirectoryPackage(
    val manifestBytes: ByteArray,
    val fallbackId: String,
    val canonicalRoot: Path,
)

internal fun readSharedDirectoryPackage(
    fileSystem: FileSystem,
    root: Path,
    limits: PetPackageLimits,
    allowLegacyAvatar: Boolean,
): SharedDirectoryPackage {
    limits.invalidReason()?.let { throw AbortWith(it) }
    val canonicalRoot = try {
        fileSystem.canonicalize(root)
    } catch (e: Exception) {
        throw AbortWith(PetLoadError.IoFailure("cannot resolve package root $root: ${e.message}"))
    }

    val manifestPath = when (val pet = locateManifest(fileSystem, canonicalRoot, "pet.json")) {
        is ManifestLocation.Found -> pet.path
        is ManifestLocation.Absent -> {
            if (!allowLegacyAvatar) {
                throw AbortWith(PetLoadError.MissingManifest(canonicalRoot.toString()))
            }
            when (val avatar = locateManifest(fileSystem, canonicalRoot, "avatar.json")) {
                is ManifestLocation.Found -> avatar.path
                is ManifestLocation.Absent ->
                    throw AbortWith(PetLoadError.MissingManifest(canonicalRoot.toString()))
            }
        }
    }

    val manifestBytes = readBoundedFile(
        fileSystem,
        manifestPath,
        limits.maxManifestBytes,
        "manifestBytes",
        onMissing = { PetLoadError.MissingManifest(canonicalRoot.toString()) },
    )
    return SharedDirectoryPackage(manifestBytes, canonicalRoot.name, canonicalRoot)
}

internal fun readSharedDirectoryAsset(
    fileSystem: FileSystem,
    shared: SharedDirectoryPackage,
    relPath: String,
    limits: PetPackageLimits,
): ByteArray {
    val sheetPath = resolveContainedChild(fileSystem, shared.canonicalRoot, relPath)
    return readBoundedFile(
        fileSystem,
        sheetPath,
        limits.maxSpritesheetBytes,
        "spritesheetBytes",
        onMissing = { path -> PetLoadError.MissingSpritesheet(path.toString()) },
    )
}

internal sealed interface ManifestLocation {
    class Found(val path: Path) : ManifestLocation
    object Absent : ManifestLocation
}

/**
 * Locates one manifest leaf under the canonical root, enforcing the same
 * canonical containment as package assets. Throws [AbortWith] for unsafe
 * leaves ([PetLoadError.PathEscape], [PetLoadError.DanglingManifest]); returns
 * [ManifestLocation.Absent] only when no such entry exists.
 *
 * Two filesystem behaviors are covered: hosts whose canonicalize throws for
 * dangling links (caught below), and hosts that resolve without existence
 * checks (caught by verifying the resolved target stats). A file deleted
 * between listing and stat is reported identically to a dangling link —
 * best-effort, documented; both are unresolvable-leaf failures.
 */
internal fun locateManifest(
    fileSystem: FileSystem,
    canonicalRoot: Path,
    name: String,
): ManifestLocation {
    val child = canonicalRoot / name
    val listed = try {
        fileSystem.list(canonicalRoot).any { it.name == name }
    } catch (_: Exception) {
        false
    }
    val metadata = try {
        fileSystem.metadataOrNull(child)
    } catch (_: Exception) {
        null
    }
    if (!listed && metadata == null) return ManifestLocation.Absent
    val canonical = try {
        fileSystem.canonicalize(child)
    } catch (_: Exception) {
        // `metadataOrNull` stats the link itself on several hosts, so a null
        // metadata is not a reliable link detector: consult symlinkTarget
        // directly. Loops classify as dangling (fail closed, documented).
        val linkTarget = try {
            fileSystem.metadataOrNull(child)?.symlinkTarget
        } catch (_: Exception) {
            null
        }
        if (linkTarget != null || metadata == null) {
            throw AbortWith(PetLoadError.DanglingManifest(child.toString()))
        }
        throw AbortWith(PetLoadError.IoFailure("cannot resolve manifest $child"))
    }
    val targetMetadata = try {
        fileSystem.metadataOrNull(canonical)
    } catch (_: Exception) {
        null
    }
    if (targetMetadata == null) {
        throw AbortWith(PetLoadError.DanglingManifest(child.toString()))
    }
    if (!isStrictDescendant(canonicalRoot, canonical)) {
        throw AbortWith(PetLoadError.PathEscape(child.toString()))
    }
    return ManifestLocation.Found(canonical)
}

/**
 * Reads a file with a strict bound: the declared size is checked first where
 * available, and the actual stream is capped during reading. Never allocates
 * beyond `cap + 1` for this file. [onMissing] builds the error when the file
 * vanishes between discovery and read.
 */
internal fun readBoundedFile(
    fileSystem: FileSystem,
    path: Path,
    cap: Long,
    capName: String,
    onMissing: (Path) -> PetLoadError,
): ByteArray {
    val declared = try {
        fileSystem.metadata(path).size
    } catch (_: Exception) {
        null
    }
    if (declared != null && declared > cap) {
        throw AbortWith(
            PetLoadError.LimitExceeded(capName, "$path declares $declared bytes, cap is $cap"),
        )
    }
    try {
        var result: ByteArray? = null
        fileSystem.read(path) {
            val out = okio.Buffer()
            var total = 0L
            while (true) {
                val read = read(out, 8192)
                if (read == -1L) break
                total += read
                if (total > cap) {
                    throw AbortWith(
                        PetLoadError.LimitExceeded(capName, "$path exceeds $cap byte cap"),
                    )
                }
            }
            result = out.readByteArray()
        }
        return result ?: throw AbortWith(onMissing(path))
    } catch (e: AbortWith) {
        throw e
    } catch (e: Exception) {
        // A missing file here means it vanished between discovery and read.
        throw AbortWith(onMissing(path))
    }
}
