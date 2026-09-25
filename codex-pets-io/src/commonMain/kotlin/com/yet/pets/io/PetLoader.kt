package com.yet.pets.io

import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetInfo
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Public entry point for package loading. Filesystem and ZIP packages compose
 * here with `:codex-pets-core`, which remains the only manifest semantic
 * normalizer and compatibility authority: io establishes facts (bytes, paths,
 * limits, image facts) and core judges them.
 *
 * The public boundary uses `String` paths and `ByteArray`s only — no Okio
 * types — so Swift consumers never touch filesystem abstractions
 * (see the Phase 2 Apple-interop preflight record). Okio remains the only
 * filesystem implementation, used internally.
 */
public object PetLoader {
    /**
     * Loads a directory package. The manifest is `pet.json`, with legacy
     * `avatar.json` as fallback when `pet.json` is absent (`pet.json` wins
     * when both exist, mirroring upstream load order).
     */
    public fun loadPetDirectory(
        path: String,
        limits: PetPackageLimits = PetPackageLimits.Default,
    ): PetLoadOutcome = try {
        loadPetDirectory(FileSystem.SYSTEM, path.toPath(), limits)
    } catch (e: AbortWith) {
        PetLoadOutcome.Failure(e.error)
    } catch (e: Exception) {
        PetLoadOutcome.Failure(PetLoadError.IoFailure("cannot load directory $path: ${e.message}"))
    }

    /**
     * Loads a ZIP package from bytes. `fallbackId` supplies identity for
     * root-layout archives (default `"pet"`); nested archives derive it from
     * the top-level directory name. Delegates to the same parser as directory
     * loading after bounded in-memory extraction (never to disk).
     */
    public fun loadPetZip(
        bytes: ByteArray,
        fallbackId: String = "pet",
        limits: PetPackageLimits = PetPackageLimits.Default,
    ): PetLoadOutcome = try {
        loadZipBytes(bytes, fallbackId, limits)
    } catch (e: AbortWith) {
        PetLoadOutcome.Failure(e.error)
    } catch (e: Exception) {
        PetLoadOutcome.Failure(PetLoadError.InvalidArchive("cannot read archive: ${e.message}"))
    }
}

/**
 * Internal filesystem-injectable directory loader (tests supply fakes).
 * Total: all failures surface as [PetLoadOutcome.Failure].
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

private fun loadPetDirectoryOrThrow(
    fileSystem: FileSystem,
    root: Path,
    limits: PetPackageLimits,
): PetLoadOutcome {
    limits.invalidReason()?.let { return PetLoadOutcome.Failure(it) }
    val canonicalRoot = try {
        fileSystem.canonicalize(root)
    } catch (e: Exception) {
        return PetLoadOutcome.Failure(PetLoadError.IoFailure("cannot resolve package root $root: ${e.message}"))
    }

    val petJson = canonicalRoot / "pet.json"
    val avatarJson = canonicalRoot / "avatar.json"
    val manifestPath = when {
        existsQuietly(fileSystem, petJson) -> petJson
        existsQuietly(fileSystem, avatarJson) -> avatarJson
        else -> return PetLoadOutcome.Failure(PetLoadError.MissingManifest(canonicalRoot.toString()))
    }

    val manifestBytes = readBoundedFile(
        fileSystem,
        manifestPath,
        limits.maxManifestBytes,
        "manifestBytes",
        onMissing = { PetLoadError.MissingManifest(canonicalRoot.toString()) },
    )
    val relPath = when (val pathOutcome = PetPackageParser.spritesheetPathOf(manifestBytes)) {
        is com.yet.pets.core.PetSpritesheetPathOutcome.Success -> pathOutcome.path
        is com.yet.pets.core.PetSpritesheetPathOutcome.Failure ->
            return PetLoadOutcome.Failure(compatibilityFailureOf(pathOutcome.error))
    }
    checkManifestRelativePath(relPath)
    val sheetPath = resolveContainedChild(fileSystem, canonicalRoot, relPath)
    val sheetBytes = readBoundedFile(
        fileSystem,
        sheetPath,
        limits.maxSpritesheetBytes,
        "spritesheetBytes",
        onMissing = { path -> PetLoadError.MissingSpritesheet(path.toString()) },
    )
    return finishLoad(manifestBytes, canonicalRoot.name, sheetBytes)
}

private fun existsQuietly(fileSystem: FileSystem, path: Path): Boolean =
    try {
        fileSystem.exists(path)
    } catch (_: Exception) {
        false
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

/**
 * Shared tail: probe image facts, then let core parse + validate.
 * Identity fallback is the caller-derived name (directory basename or ZIP rule).
 */
internal fun finishLoad(
    manifestBytes: ByteArray,
    fallbackId: String,
    sheetBytes: ByteArray,
): PetLoadOutcome {
    val info: SpritesheetInfo = ImageProbe.probe(sheetBytes)
        ?: return PetLoadOutcome.Failure(
            PetLoadError.UnsupportedImageFormat("unrecognized or malformed image data"),
        )
    return when (val parsed = PetPackageParser.parse(manifestBytes, fallbackId, info)) {
        is PetParseOutcome.Success -> PetLoadOutcome.Success(parsed.definition, sheetBytes)
        is PetParseOutcome.Failure -> PetLoadOutcome.Failure(
            PetLoadError.CompatibilityFailure(parsed.report),
        )
    }
}

internal fun compatibilityFailureOf(error: com.yet.pets.core.PetCompatibilityError): PetLoadError =
    PetLoadError.CompatibilityFailure(com.yet.pets.core.PetCompatibilityReport(listOf(error)))
