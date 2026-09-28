package com.yet.pets.io

import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.PetsKmpParseOutcome
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.EncodedSpritesheetProbe
import com.yet.pets.core.SpritesheetInfo
import com.yet.pets.io.internal.fs.loadPetDirectory as loadPetDirectoryFromFs
import com.yet.pets.io.internal.fs.platformFileSystem
import com.yet.pets.io.internal.zip.loadZipBytes
import com.yet.pets.io.internal.zip.loadPetsKmpZipBytes
import okio.Path.Companion.toPath

/**
 * Public entry point for package loading. Filesystem and ZIP packages compose
 * here with `:codex-pets-core`, which remains the only manifest semantic
 * normalizer and compatibility authority: io establishes facts (bytes, paths,
 * limits, image facts) and core judges them.
 *
 * Two explicit formats, no sniffing:
 * - Codex V1: [loadPetDirectory]/[loadPetZip] via [PetPackageParser].
 * - Pets KMP generic v1: [loadPetsKmpDirectory]/[loadPetsKmpZip] via
 *   [PetsKmpPackageParser].
 *
 * The public boundary uses `String` paths and `ByteArray`s only — no Okio
 * types — so Swift consumers never touch filesystem abstractions. Okio remains
 * the only filesystem implementation, used internally.
 */
public object PetLoader {
    /**
     * Loads a directory package. The manifest is `pet.json`, with legacy
     * `avatar.json` as fallback only when `pet.json` is absent (`pet.json`
     * wins when both exist, mirroring upstream load order; an unsafe preferred
     * manifest fails the package instead of falling back).
     */
    public fun loadPetDirectory(
        path: String,
        limits: PetPackageLimits = PetPackageLimits.Default,
    ): PetLoadOutcome = try {
        loadPetDirectoryFromFs(platformFileSystem(), path.toPath(), limits)
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
     *
     * Codex V1 semantics. For generic packages use [loadPetsKmpZip].
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

    /**
     * Loads a Pets KMP generic v1 directory package. Same secure
     * directory/ZIP implementation as the Codex path (traversal protection,
     * symlink confinement, CRC checks, DEFLATE consumption, overlap checks,
     * resource limits, duplicate handling); only manifest interpretation
     * differs. No format sniffing.
     */
    public fun loadPetsKmpDirectory(
        path: String,
        limits: PetPackageLimits = PetPackageLimits.Default,
    ): PetLoadOutcome = try {
        com.yet.pets.io.internal.fs.loadPetsKmpDirectoryFromFs(
            platformFileSystem(),
            path.toPath(),
            limits,
        )
    } catch (e: AbortWith) {
        PetLoadOutcome.Failure(e.error)
    } catch (e: Exception) {
        PetLoadOutcome.Failure(PetLoadError.IoFailure("cannot load directory $path: ${e.message}"))
    }

    /**
     * Loads a Pets KMP generic v1 ZIP package from bytes. Explicit generic
     * entry point; Codex packages must use [loadPetZip].
     */
    public fun loadPetsKmpZip(
        bytes: ByteArray,
        fallbackId: String = "pet",
        limits: PetPackageLimits = PetPackageLimits.Default,
    ): PetLoadOutcome = try {
        loadPetsKmpZipBytes(bytes, fallbackId, limits)
    } catch (e: AbortWith) {
        PetLoadOutcome.Failure(e.error)
    } catch (e: Exception) {
        PetLoadOutcome.Failure(PetLoadError.InvalidArchive("cannot read archive: ${e.message}"))
    }
}

/**
 * Shared tail for Codex V1: probe image facts, then let core parse + validate.
 * Identity fallback is the caller-derived name (directory basename or ZIP rule).
 */
internal fun finishLoad(
    manifestBytes: ByteArray,
    fallbackId: String,
    sheetBytes: ByteArray,
): PetLoadOutcome {
    val info: SpritesheetInfo = EncodedSpritesheetProbe.probe(sheetBytes)
        ?: return PetLoadOutcome.Failure(
            PetLoadError.UnsupportedImageFormat("unrecognized or malformed image data"),
        )
    return when (val parsed = PetPackageParser.parseTrustedMetadata(manifestBytes, fallbackId, info)) {
        is PetParseOutcome.Success -> PetLoadOutcome.Success(parsed.definition, sheetBytes)
        is PetParseOutcome.Failure -> PetLoadOutcome.Failure(
            PetLoadError.CompatibilityFailure(parsed.report),
        )
    }
}

/**
 * Shared tail for Pets KMP generic v1: probe image facts, then let the generic
 * core parser validate. Same transport/security as [finishLoad]; only manifest
 * interpretation differs.
 */
internal fun finishPetsKmpLoad(
    manifestBytes: ByteArray,
    fallbackId: String,
    sheetBytes: ByteArray,
): PetLoadOutcome {
    val info: SpritesheetInfo = EncodedSpritesheetProbe.probe(sheetBytes)
        ?: return PetLoadOutcome.Failure(
            PetLoadError.UnsupportedImageFormat("unrecognized or malformed image data"),
        )
    return when (val parsed = PetsKmpPackageParser.parseTrustedMetadata(manifestBytes, fallbackId, info)) {
        is PetsKmpParseOutcome.Success -> PetLoadOutcome.Success(parsed.definition, sheetBytes)
        is PetsKmpParseOutcome.Failure -> PetLoadOutcome.Failure(
            PetLoadError.PetsKmpCompatibilityFailure(parsed.report),
        )
    }
}

internal fun compatibilityFailureOf(error: com.yet.pets.core.PetCompatibilityError): PetLoadError =
    PetLoadError.CompatibilityFailure(com.yet.pets.core.PetCompatibilityReport(listOf(error)))

internal fun petsKmpFailureOf(error: com.yet.pets.core.PetsKmpError): PetLoadError =
    PetLoadError.PetsKmpCompatibilityFailure(com.yet.pets.core.PetsKmpReport(listOf(error)))
