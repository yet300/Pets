package com.yet.pets.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Outcome of resolving the effective manifest-relative spritesheet path for a
 * Pets KMP generic package. Core applies only the pure string default;
 * lexical/filesystem security is the io layer's job.
 */
public sealed interface PetsKmpSpritesheetPathOutcome {
    public data class Success(public val path: String) : PetsKmpSpritesheetPathOutcome
    public data class Failure(public val error: PetsKmpError) : PetsKmpSpritesheetPathOutcome
}

/**
 * Outcome of parsing a Pets KMP generic package into a [PetDefinition].
 * Foreign invalid data always yields [Failure], never a crash.
 */
public sealed interface PetsKmpParseOutcome {
    public data class Success(
        public val definition: PetDefinition,
        public val spritesheetPath: String,
    ) : PetsKmpParseOutcome

    public data class Failure(public val report: PetsKmpReport) : PetsKmpParseOutcome
}

/**
 * Public parser for OUR OWN package format: Pets KMP Package Format v1.
 *
 * This is NOT a Codex format. The manifest MUST self-identify with
 * `"schema": "pets-kmp"` and `"schemaVersion": 1`. Never infer generic format
 * merely because Codex parsing failed — use this explicit entry point.
 *
 * For Codex V1 packages use [CodexPetPackageParser] (or the historic
 * [PetPackageParser] name, which carries identical Codex V1 semantics).
 */
public object PetsKmpPackageParser {
    /** Expected `schema` value for Pets KMP Package Format v1. */
    public const val SCHEMA: String = "pets-kmp"

    /** Expected `schemaVersion` for Pets KMP Package Format v1. */
    public const val SCHEMA_VERSION: Int = 1

    /** Default spritesheet path when the manifest omits it or leaves it blank. */
    public const val DEFAULT_SPRITESHEET_PATH: String = "spritesheet.webp"

    /** Safe maximum grid capacity (same conservative envelope as Codex V1). */
    public const val MAX_FRAME_CAPACITY: Int = 256

    private val json: Json = Json { ignoreUnknownKeys = true }

    private fun manifestTooLarge(size: Int): PetsKmpError.InputLimitExceeded =
        PetsKmpError.InputLimitExceeded(
            "manifest is $size bytes; maximum is ${PetInputLimits.MAX_MANIFEST_BYTES}",
        )

    /**
     * Bounded public package path for host-owned manifest and image bytes.
     * Enforces the manifest cap, the spritesheet byte cap, probes image
     * dimensions/format through the authoritative core probe, then parses and
     * validates the generic manifest.
     */
    public fun parse(
        manifestBytes: ByteArray,
        spritesheetBytes: ByteArray,
        fallbackId: String = "pet",
    ): PetsKmpParseOutcome {
        if (manifestBytes.size > PetInputLimits.MAX_MANIFEST_BYTES) {
            return failure(manifestTooLarge(manifestBytes.size))
        }
        if (spritesheetBytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES) {
            return failure(
                PetsKmpError.InputLimitExceeded(
                    "spritesheet is ${spritesheetBytes.size} bytes; " +
                        "maximum is ${PetInputLimits.MAX_SPRITESHEET_BYTES}",
                ),
            )
        }
        val info = EncodedSpritesheetProbe.probe(spritesheetBytes)
            ?: return failure(
                PetsKmpError.InvalidSpritesheetBytes(
                    "unrecognized, malformed, or animated spritesheet bytes",
                ),
            )
        return parseTrustedMetadata(manifestBytes, fallbackId, info)
    }

    /**
     * Effective manifest-relative spritesheet path from ≤64 KiB of UTF-8 JSON:
     * trimmed manifest value or `spritesheet.webp` when absent/blank. No path
     * confinement checks here. Requires a self-identifying Pets KMP manifest;
     * a non-generic document yields [PetsKmpSpritesheetPathOutcome.Failure].
     */
    public fun spritesheetPathOf(manifestJson: String): PetsKmpSpritesheetPathOutcome =
        try {
            if (manifestJson.length > PetInputLimits.MAX_MANIFEST_BYTES) {
                PetsKmpSpritesheetPathOutcome.Failure(manifestTooLarge(manifestJson.length))
            } else {
                val encodedSize = manifestJson.encodeToByteArray().size
                if (encodedSize > PetInputLimits.MAX_MANIFEST_BYTES) {
                    PetsKmpSpritesheetPathOutcome.Failure(manifestTooLarge(encodedSize))
                } else {
                    val manifest = json.decodeFromString<PetsKmpManifestDto>(manifestJson)
                    val schemaError = checkSchemaIdentity(manifest)
                    if (schemaError != null) {
                        PetsKmpSpritesheetPathOutcome.Failure(schemaError)
                    } else {
                        PetsKmpSpritesheetPathOutcome.Success(effectiveSpritesheetPath(manifest))
                    }
                }
            }
        } catch (e: SerializationException) {
            PetsKmpSpritesheetPathOutcome.Failure(
                PetsKmpError.MalformedManifest("invalid pet manifest: ${e.message}"),
            )
        } catch (e: IllegalArgumentException) {
            PetsKmpSpritesheetPathOutcome.Failure(
                PetsKmpError.MalformedManifest("invalid pet manifest: ${e.message}"),
            )
        }

    /** ByteArray overload; bytes are decoded as UTF-8. */
    public fun spritesheetPathOf(manifestBytes: ByteArray): PetsKmpSpritesheetPathOutcome =
        try {
            if (manifestBytes.size > PetInputLimits.MAX_MANIFEST_BYTES) {
                PetsKmpSpritesheetPathOutcome.Failure(manifestTooLarge(manifestBytes.size))
            } else {
                spritesheetPathOf(manifestBytes.decodeToString(throwOnInvalidSequence = true))
            }
        } catch (e: Exception) {
            PetsKmpSpritesheetPathOutcome.Failure(
                PetsKmpError.MalformedManifest("invalid pet manifest bytes: ${e.message}"),
            )
        }

    /**
     * Expert seam for already-probed, caller-trusted metadata. This does not
     * prove that [spritesheet] describes any actual image bytes. Ordinary
     * callers should use the bounded raw-pair [parse] overload instead.
     * Manifest input remains bounded to 64 KiB here.
     */
    public fun parseTrustedMetadata(
        manifestJson: String,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetsKmpParseOutcome {
        if (manifestJson.length > PetInputLimits.MAX_MANIFEST_BYTES) {
            return failure(manifestTooLarge(manifestJson.length))
        }
        val encodedSize = manifestJson.encodeToByteArray().size
        if (encodedSize > PetInputLimits.MAX_MANIFEST_BYTES) {
            return failure(manifestTooLarge(encodedSize))
        }
        val manifest = try {
            json.decodeFromString<PetsKmpManifestDto>(manifestJson)
        } catch (e: SerializationException) {
            return failure(PetsKmpError.MalformedManifest("invalid pet manifest: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return failure(PetsKmpError.MalformedManifest("invalid pet manifest: ${e.message}"))
        }
        return parseManifest(manifest, fallbackId, spritesheet)
    }

    /** ByteArray variant of [parseTrustedMetadata]; manifest bytes are bounded. */
    public fun parseTrustedMetadata(
        manifestBytes: ByteArray,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetsKmpParseOutcome =
        try {
            if (manifestBytes.size > PetInputLimits.MAX_MANIFEST_BYTES) {
                failure(manifestTooLarge(manifestBytes.size))
            } else {
                parseTrustedMetadata(
                    manifestBytes.decodeToString(throwOnInvalidSequence = true),
                    fallbackId,
                    spritesheet,
                )
            }
        } catch (e: Exception) {
            failure(PetsKmpError.MalformedManifest("invalid pet manifest bytes: ${e.message}"))
        }

    private fun failure(error: PetsKmpError): PetsKmpParseOutcome =
        PetsKmpParseOutcome.Failure(PetsKmpReport(listOf(error)))

    internal fun effectiveSpritesheetPath(manifest: PetsKmpManifestDto): String {
        val raw = manifest.spritesheetPath?.trim()
        return if (raw.isNullOrEmpty()) DEFAULT_SPRITESHEET_PATH else raw
    }

    private fun checkSchemaIdentity(manifest: PetsKmpManifestDto): PetsKmpError? {
        if (manifest.schema != SCHEMA) {
            return PetsKmpError.InvalidSchema(
                "schema must equal \"$SCHEMA\", got ${manifest.schema}",
            )
        }
        if (manifest.schemaVersion != SCHEMA_VERSION) {
            return PetsKmpError.UnsupportedSchemaVersion(
                "schemaVersion must equal $SCHEMA_VERSION, got ${manifest.schemaVersion}",
            )
        }
        return null
    }

    internal fun parseManifest(
        manifest: PetsKmpManifestDto,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetsKmpParseOutcome {
        val errors = mutableListOf<PetsKmpError>()

        // Format identity first: never infer generic format.
        checkSchemaIdentity(manifest)?.let { return failure(it) }

        // Image format gate (dimensions gate comes after cell-size parsing).
        if (spritesheet.format != SpritesheetFormat.PNG &&
            spritesheet.format != SpritesheetFormat.WEBP &&
            spritesheet.format != SpritesheetFormat.GIF &&
            spritesheet.format != SpritesheetFormat.JPEG
        ) {
            errors += PetsKmpError.UnsupportedSpritesheetFormat(spritesheet.format)
        }

        // Identity.
        val rawId = manifest.id?.trim()
        val rawDisplayName = manifest.displayName?.trim()
        if (rawId.isNullOrEmpty()) {
            errors += PetsKmpError.MissingId("id is required and must be non-blank")
        }
        if (rawDisplayName.isNullOrEmpty()) {
            errors += PetsKmpError.MissingDisplayName("displayName is required and must be non-blank")
        }

        // Cell size.
        val cellWidth = manifest.frame?.width
        val cellHeight = manifest.frame?.height
        if (cellWidth == null || cellWidth <= 0 || cellHeight == null || cellHeight <= 0) {
            errors += PetsKmpError.InvalidFrameSize(
                "frame.width and frame.height are required positive integers",
            )
        }

        // Animations: required, at least one, unique keys.
        if (manifest.animations.isEmpty()) {
            errors += PetsKmpError.EmptyAnimations("animations must declare at least one animation")
        }
        val seenKeys = HashSet<String>()
        for (entry in manifest.animations) {
            val rawKey = entry.key?.trim()
            if (rawKey.isNullOrEmpty()) {
                errors += PetsKmpError.InvalidAnimationKey(
                    "animation key is required and must be non-blank",
                )
                continue
            }
            if (rawKey.length > PetAnimationKey.MAX_KEY_LENGTH) {
                errors += PetsKmpError.InvalidAnimationKey(
                    "animation key must be at most ${PetAnimationKey.MAX_KEY_LENGTH} characters",
                )
                continue
            }
            try {
                PetAnimationKey(rawKey)
            } catch (_: IllegalArgumentException) {
                errors += PetsKmpError.InvalidAnimationKey(
                    "invalid animation key $rawKey",
                )
                continue
            }
            if (!seenKeys.add(rawKey)) {
                errors += PetsKmpError.DuplicateAnimationKey(rawKey)
            }
        }
        if (errors.isNotEmpty()) {
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }

        // Geometry from actual atlas dimensions + declared cell size.
        val cw = cellWidth!!
        val ch = cellHeight!!
        val geometry: AtlasGeometry
        val frameCapacity: Long
        if (spritesheet.width <= 0 || spritesheet.height <= 0) {
            errors += PetsKmpError.InvalidGrid(
                "spritesheet dimensions must be positive, got ${spritesheet.width}x${spritesheet.height}",
            )
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }
        if (spritesheet.width % cw != 0 || spritesheet.height % ch != 0) {
            errors += PetsKmpError.InvalidGrid(
                "cell ${cw}x$ch does not divide atlas ${spritesheet.width}x${spritesheet.height} exactly",
            )
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }
        val columns = spritesheet.width / cw
        val rows = spritesheet.height / ch
        frameCapacity = columns.toLong() * rows.toLong()
        if (frameCapacity > MAX_FRAME_CAPACITY) {
            errors += PetsKmpError.FrameCapacityExceeded(frameCapacity, MAX_FRAME_CAPACITY)
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }
        try {
            geometry = AtlasGeometry(
                atlasWidth = spritesheet.width,
                atlasHeight = spritesheet.height,
                columns = columns,
                rows = rows,
                cellWidth = cw,
                cellHeight = ch,
            )
        } catch (e: IllegalArgumentException) {
            errors += PetsKmpError.InvalidGrid("invalid atlas grid: ${e.message}")
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }
        val capacityInt = frameCapacity.toInt()

        // Default animation.
        val rawDefault = manifest.defaultAnimation?.trim()
        if (rawDefault.isNullOrEmpty()) {
            errors += PetsKmpError.UnknownDefaultAnimation(
                "defaultAnimation is required and must be non-blank",
            )
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }
        val defaultKey: PetAnimationKey
        try {
            defaultKey = PetAnimationKey(rawDefault)
        } catch (_: IllegalArgumentException) {
            errors += PetsKmpError.UnknownDefaultAnimation(
                "defaultAnimation is invalid: $rawDefault",
            )
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }
        val declaredKeys = manifest.animations.mapNotNull { it.key?.trim() }.toSet()
        if (!declaredKeys.contains(rawDefault)) {
            errors += PetsKmpError.UnknownDefaultAnimation(
                "defaultAnimation $rawDefault does not match any declared animation",
            )
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }

        // Frames per animation.
        val animations = LinkedHashMap<PetAnimationKey, PetAnimation>()
        for (entry in manifest.animations) {
            val keyString = entry.key!!.trim()
            val key = PetAnimationKey(keyString)
            if (entry.frames.isEmpty()) {
                errors += PetsKmpError.EmptyAnimationFrames(keyString)
                continue
            }
            val frames = ArrayList<PetFrame>(entry.frames.size)
            var framesValid = true
            for (frameDto in entry.frames) {
                val index = frameDto.index
                val durationMs = frameDto.durationMs
                if (index == null || index < 0 || index.toLong() >= frameCapacity) {
                    errors += PetsKmpError.InvalidFrameIndex(
                        keyString,
                        index ?: -1,
                        frameCapacity,
                    )
                    framesValid = false
                    break
                }
                if (durationMs == null || durationMs <= 0L) {
                    errors += PetsKmpError.InvalidDuration(
                        keyString,
                        "frame durationMs must be a positive integer, got $durationMs",
                    )
                    framesValid = false
                    break
                }
                // Checked ms -> nanos conversion; reject overflow.
                if (durationMs > Long.MAX_VALUE / 1_000_000L) {
                    errors += PetsKmpError.InvalidDuration(
                        keyString,
                        "frame durationMs $durationMs overflows nanoseconds",
                    )
                    framesValid = false
                    break
                }
                val nanos = durationMs * 1_000_000L
                if (nanos <= 0L) {
                    errors += PetsKmpError.InvalidDuration(
                        keyString,
                        "frame durationMs must be a positive integer, got $durationMs",
                    )
                    framesValid = false
                    break
                }
                try {
                    frames.add(PetFrame(index, nanos))
                } catch (e: IllegalArgumentException) {
                    errors += PetsKmpError.InvalidDuration(
                        keyString,
                        "invalid frame: ${e.message}",
                    )
                    framesValid = false
                    break
                }
            }
            if (!framesValid) continue
            val loopStart = entry.loopStart
            if (loopStart != null && (loopStart < 0 || loopStart >= frames.size)) {
                errors += PetsKmpError.InvalidLoopStart(
                    keyString,
                    "loopStart $loopStart out of range for ${frames.size} frames",
                )
                continue
            }
            // Generic v1 has no fallback field: one-shot holds its final frame.
            // Model the hold with a self-fallback so the shared one-hop player
            // holds without appending any default animation.
            try {
                animations[key] = PetAnimation(
                    frames = frames,
                    loopStart = loopStart,
                    fallback = key,
                )
            } catch (e: IllegalArgumentException) {
                errors += PetsKmpError.NormalizationFailed(
                    "invalid animation $keyString: ${e.message}",
                )
            }
        }
        if (errors.isNotEmpty()) {
            return PetsKmpParseOutcome.Failure(PetsKmpReport(errors))
        }

        return try {
            val identity = normalizePetIdentity(
                manifestId = rawId,
                displayName = rawDisplayName,
                description = manifest.description,
                fallbackId = fallbackId,
            )
            val definition = PetDefinition(
                id = identity.id,
                displayName = identity.displayName,
                description = identity.description,
                geometry = geometry,
                frameCount = capacityInt,
                animations = animations,
                defaultAnimationKey = defaultKey,
            )
            PetsKmpParseOutcome.Success(definition, effectiveSpritesheetPath(manifest))
        } catch (e: IllegalArgumentException) {
            PetsKmpParseOutcome.Failure(
                PetsKmpReport(
                    errors + PetsKmpError.NormalizationFailed(
                        "invariant violation during normalization: ${e.message}",
                    ),
                ),
            )
        }
    }
}

/**
 * Explicit Codex V1 compatibility entry point.
 *
 * Identical semantics to the historic [PetPackageParser]: Codex V1 manifest
 * interpretation, fixed V1 geometry, built-in animation table, aliases, and
 * fallback/loop compatibility quirks. Use this name (instead of the
 * generic-looking historic name) when the Codex nature must be explicit.
 * For generic packages use [PetsKmpPackageParser]; the two loaders never
 * auto-detect by trying one format and falling back to the other.
 */
public object CodexPetPackageParser {
    public fun parse(
        manifestBytes: ByteArray,
        spritesheetBytes: ByteArray,
        fallbackId: String = "pet",
    ): PetParseOutcome = PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)

    public fun spritesheetPathOf(manifestJson: String): PetSpritesheetPathOutcome =
        PetPackageParser.spritesheetPathOf(manifestJson)

    public fun spritesheetPathOf(manifestBytes: ByteArray): PetSpritesheetPathOutcome =
        PetPackageParser.spritesheetPathOf(manifestBytes)

    public fun parseTrustedMetadata(
        manifestJson: String,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetParseOutcome =
        PetPackageParser.parseTrustedMetadata(manifestJson, fallbackId, spritesheet)

    public fun parseTrustedMetadata(
        manifestBytes: ByteArray,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetParseOutcome =
        PetPackageParser.parseTrustedMetadata(manifestBytes, fallbackId, spritesheet)
}
