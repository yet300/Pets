package com.yet.pets.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Outcome of resolving the effective manifest-relative spritesheet path.
 * Core applies only the pure string default; lexical/filesystem security is
 * the io layer's job.
 */
public sealed interface PetSpritesheetPathOutcome {
    public data class Success(public val path: String) : PetSpritesheetPathOutcome
    public data class Failure(public val error: PetCompatibilityError) : PetSpritesheetPathOutcome
}

/**
 * Outcome of parsing + normalizing a package into a [PetDefinition].
 * Foreign invalid data always yields [Failure], never a crash.
 */
public sealed interface PetParseOutcome {
    public data class Success(
        public val definition: PetDefinition,
        public val spritesheetPath: String,
    ) : PetParseOutcome

    public data class Failure(public val report: PetCompatibilityReport) : PetParseOutcome
}

/**
 * Small stable public facade over the internal manifest DTOs.
 *
 * Phase 2 io calls [spritesheetPathOf] to learn which asset entry/file to
 * resolve, then [parse] with caller-derived identity and io-probed
 * [SpritesheetInfo] facts. Raw DTOs stay internal.
 */
public object PetPackageParser {
    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Effective manifest-relative spritesheet path: trimmed manifest value,
     * or `spritesheet.webp` when absent/blank. No path safety checks here.
     */
    public fun spritesheetPathOf(manifestJson: String): PetSpritesheetPathOutcome =
        try {
            val manifest = json.decodeFromString<CodexPetManifestDto>(manifestJson)
            PetSpritesheetPathOutcome.Success(effectiveSpritesheetPath(manifest))
        } catch (e: SerializationException) {
            PetSpritesheetPathOutcome.Failure(
                PetCompatibilityError.MalformedManifest("invalid pet manifest: ${e.message}"),
            )
        } catch (e: IllegalArgumentException) {
            PetSpritesheetPathOutcome.Failure(
                PetCompatibilityError.MalformedManifest("invalid pet manifest: ${e.message}"),
            )
        }

    /** ByteArray overload; bytes are decoded as UTF-8. */
    public fun spritesheetPathOf(manifestBytes: ByteArray): PetSpritesheetPathOutcome =
        try {
            spritesheetPathOf(manifestBytes.decodeToString())
        } catch (e: Exception) {
            PetSpritesheetPathOutcome.Failure(
                PetCompatibilityError.MalformedManifest("invalid pet manifest bytes: ${e.message}"),
            )
        }

    /**
     * Full parse + normalize + validate. [fallbackId] is caller-derived
     * identity (directory/ZIP/URL source); blank normalizes to `"pet"`.
     * [spritesheet] holds io-probed dimension/format facts.
     */
    public fun parse(
        manifestJson: String,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetParseOutcome {
        val manifest = try {
            json.decodeFromString<CodexPetManifestDto>(manifestJson)
        } catch (e: SerializationException) {
            return failure(PetCompatibilityError.MalformedManifest("invalid pet manifest: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return failure(PetCompatibilityError.MalformedManifest("invalid pet manifest: ${e.message}"))
        }
        return parseManifest(manifest, fallbackId, spritesheet)
    }

    /** ByteArray overload; bytes are decoded as UTF-8. */
    public fun parse(
        manifestBytes: ByteArray,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetParseOutcome =
        try {
            parse(manifestBytes.decodeToString(), fallbackId, spritesheet)
        } catch (e: Exception) {
            failure(PetCompatibilityError.MalformedManifest("invalid pet manifest bytes: ${e.message}"))
        }

    private fun failure(error: PetCompatibilityError): PetParseOutcome =
        PetParseOutcome.Failure(PetCompatibilityReport(listOf(error)))

    internal fun effectiveSpritesheetPath(manifest: CodexPetManifestDto): String {
        val raw = manifest.spritesheetPath?.trim()
        return if (raw.isNullOrEmpty()) CodexV1.DEFAULT_SPRITESHEET_PATH else raw
    }

    internal fun parseManifest(
        manifest: CodexPetManifestDto,
        fallbackId: String,
        spritesheet: SpritesheetInfo,
    ): PetParseOutcome {
        val errors = mutableListOf<PetCompatibilityError>()

        // Atlas dimensions gate everything: unknown geometry is invalid.
        if (spritesheet.width != CodexV1.ATLAS_WIDTH || spritesheet.height != CodexV1.ATLAS_HEIGHT) {
            errors += PetCompatibilityError.UnsupportedAtlasDimensions(spritesheet.width, spritesheet.height)
        }
        if (spritesheet.format != SpritesheetFormat.PNG && spritesheet.format != SpritesheetFormat.WEBP &&
            spritesheet.format != SpritesheetFormat.GIF && spritesheet.format != SpritesheetFormat.JPEG
        ) {
            errors += PetCompatibilityError.UnsupportedSpritesheetFormat(spritesheet.format)
        }

        // Grid: defaults or exact-cover custom frame.
        // NOTE on numeric taxonomy (documented P2 deviation): DTOs use KMP-idiomatic
        // `Int`, while upstream decodes `u32`/`usize`. Negative frame dimensions or
        // indices therefore decode successfully here and fail semantic validation
        // (`InvalidFrameGrid` / `SpriteIndexOutOfRange`) instead of failing JSON
        // decoding (`MalformedManifest`). Accept/reject outcomes match upstream;
        // only the typed-error variant differs. Values above `Int.MAX_VALUE` fail
        // JSON decoding (`MalformedManifest`) where upstream `u32` would accept and
        // then reject them semantically — unreachable for covering grids.
        val frameSpec = manifest.frame
        val geometry: AtlasGeometry?
        val frameCount: Int
        if (frameSpec == null) {
            geometry = CodexV1.defaultGeometry()
            frameCount = CodexV1.FRAME_COUNT
        } else {
            val w = frameSpec.width
            val h = frameSpec.height
            val c = frameSpec.columns
            val r = frameSpec.rows
            if (w <= 0 || h <= 0 || c <= 0 || r <= 0) {
                errors += PetCompatibilityError.InvalidFrameGrid(
                    "pet frame dimensions and grid counts must be non-zero",
                )
                geometry = null
                frameCount = 0
            } else {
                // Long arithmetic: inherently overflow-safe.
                val totalWidth = w.toLong() * c.toLong()
                val totalHeight = h.toLong() * r.toLong()
                val count = c.toLong() * r.toLong()
                if (totalWidth != CodexV1.ATLAS_WIDTH.toLong() ||
                    totalHeight != CodexV1.ATLAS_HEIGHT.toLong()
                ) {
                    errors += PetCompatibilityError.InvalidFrameGrid(
                        "pet frame grid must cover spritesheet exactly: expected " +
                            "${CodexV1.ATLAS_WIDTH}x${CodexV1.ATLAS_HEIGHT}, " +
                            "got ${totalWidth}x$totalHeight",
                    )
                    geometry = null
                    frameCount = 0
                } else if (count > CodexV1.MAX_FRAMES) {
                    errors += PetCompatibilityError.FrameCountExceeded(count, CodexV1.MAX_FRAMES)
                    geometry = null
                    frameCount = 0
                } else {
                    geometry = AtlasGeometry(
                        atlasWidth = CodexV1.ATLAS_WIDTH,
                        atlasHeight = CodexV1.ATLAS_HEIGHT,
                        columns = c,
                        rows = r,
                        cellWidth = w,
                        cellHeight = h,
                    )
                    frameCount = count.toInt()
                }
            }
        }

        // Animations: custom entries merge over defaults (sorted for determinism).
        // Deterministic multi-error collection is a deliberate improvement over
        // upstream: the reference iterates a HashMap and bails on the first error;
        // this library reports all errors in sorted key order.
        val animations = CodexV1.defaultAnimations().toMutableMap()
        var animationsValid = true
        for (name in manifest.animations.keys.sorted()) {
            val spec = manifest.animations.getValue(name)
            val normalized = normalizeCustomAnimation(name, spec, frameCount, errors)
            if (normalized == null) {
                animationsValid = false
            } else {
                animations[PetAnimationKey(name)] = normalized
            }
        }
        if (geometry == null || !animationsValid) {
            return PetParseOutcome.Failure(PetCompatibilityReport(errors))
        }
        if (!animations.containsKey(PetAnimations.Idle)) {
            animations[PetAnimations.Idle] = CodexV1.idleAnimation()
        }

        // Fallback existence against the final table (single-hop model: no traversal).
        for (key in animations.keys.sortedBy { it.value }) {
            val animation = animations.getValue(key)
            if (!animations.containsKey(animation.fallback)) {
                errors += PetCompatibilityError.UnknownFallback(key.value, animation.fallback.value)
            }
        }

        // Default-table indices must also fit custom grids (CLI validates merged table).
        for (key in animations.keys.sortedBy { it.value }) {
            val animation = animations.getValue(key)
            for (spriteIndex in animation.frames.map { it.spriteIndex }) {
                if (spriteIndex >= frameCount) {
                    errors += PetCompatibilityError.SpriteIndexOutOfRange(
                        key.value,
                        spriteIndex,
                        frameCount,
                    )
                    break
                }
            }
        }

        if (errors.isNotEmpty()) {
            return PetParseOutcome.Failure(PetCompatibilityReport(errors))
        }

        // Foreign-data totality: every construction input above is pre-validated,
        // but any future rule that opens an invariant path must surface as Failure,
        // never as an uncaught exception.
        return try {
            val identity = normalizePetIdentity(
                manifestId = manifest.id,
                displayName = manifest.displayName,
                description = manifest.description,
                fallbackId = fallbackId,
            )
            val definition = PetDefinition(
                id = identity.id,
                displayName = identity.displayName,
                description = identity.description,
                geometry = geometry,
                frameCount = frameCount,
                animations = animations,
            )
            val report = CodexCompatibilityValidator.validate(definition, spritesheet)
            if (report.isCompatible) {
                PetParseOutcome.Success(definition, effectiveSpritesheetPath(manifest))
            } else {
                PetParseOutcome.Failure(report)
            }
        } catch (e: IllegalArgumentException) {
            PetParseOutcome.Failure(
                PetCompatibilityReport(
                    errors +
                        PetCompatibilityError.NormalizationFailed(
                            "invariant violation during normalization: ${e.message}",
                        ),
                ),
            )
        }
    }

    private fun normalizeCustomAnimation(
        name: String,
        spec: AnimationSpecDto,
        frameCount: Int,
        errors: MutableList<PetCompatibilityError>,
    ): PetAnimation? {
        if (spec.frames.isEmpty()) {
            errors += PetCompatibilityError.EmptyAnimationFrames(name)
            return null
        }
        for (spriteIndex in spec.frames) {
            if (spriteIndex < 0 || (frameCount in 1..spriteIndex)) {
                errors += PetCompatibilityError.SpriteIndexOutOfRange(name, spriteIndex, frameCount)
                return null
            }
        }
        // Exact upstream semantics: only "" defaults to idle; whitespace is
        // literal and fails fallback-existence validation unless such a key exists.
        val fallbackName = spec.fallback.ifEmpty { PetAnimations.Idle.value }
        val durationNanos = fpsToDurationNanos(spec.fps ?: CodexV1.DEFAULT_FPS)
        if (durationNanos == null) {
            errors += PetCompatibilityError.InvalidFps(name, spec.fps ?: CodexV1.DEFAULT_FPS)
            return null
        }
        return PetAnimation(
            frames = spec.frames.map { PetFrame(it, durationNanos) },
            loopStart = if (spec.loop != false) 0 else null,
            fallback = PetAnimationKey(fallbackName),
        )
    }
}

/**
 * Standalone pure validator for already-built definitions plus supplied
 * spritesheet facts. Used as the final gate inside [PetPackageParser.parse] and
 * available to later layers for re-checking definitions.
 */
public object CodexCompatibilityValidator {
    public fun validate(
        definition: PetDefinition,
        spritesheet: SpritesheetInfo,
    ): PetCompatibilityReport {
        val errors = mutableListOf<PetCompatibilityError>()
        if (spritesheet.width != definition.geometry.atlasWidth ||
            spritesheet.height != definition.geometry.atlasHeight
        ) {
            errors += PetCompatibilityError.UnsupportedAtlasDimensions(
                spritesheet.width,
                spritesheet.height,
            )
        }
        if (spritesheet.format != SpritesheetFormat.PNG && spritesheet.format != SpritesheetFormat.WEBP &&
            spritesheet.format != SpritesheetFormat.GIF && spritesheet.format != SpritesheetFormat.JPEG
        ) {
            errors += PetCompatibilityError.UnsupportedSpritesheetFormat(spritesheet.format)
        }
        val geometry = definition.geometry
        if (geometry.columns.toLong() * geometry.cellWidth != geometry.atlasWidth.toLong() ||
            geometry.rows.toLong() * geometry.cellHeight != geometry.atlasHeight.toLong()
        ) {
            errors += PetCompatibilityError.InvalidFrameGrid(
                "pet frame grid must cover spritesheet exactly",
            )
        }
        if (definition.frameCount > CodexV1.MAX_FRAMES) {
            errors += PetCompatibilityError.FrameCountExceeded(
                definition.frameCount.toLong(),
                CodexV1.MAX_FRAMES,
            )
        }
        for (key in definition.animationKeys) {
            val animation = definition.animation(key) ?: continue
            if (animation.frames.isEmpty()) {
                errors += PetCompatibilityError.EmptyAnimationFrames(key.value)
                continue
            }
            for (frame in animation.frames) {
                if (frame.spriteIndex >= definition.frameCount) {
                    errors += PetCompatibilityError.SpriteIndexOutOfRange(
                        key.value,
                        frame.spriteIndex,
                        definition.frameCount,
                    )
                    break
                }
            }
            if (definition.animation(animation.fallback) == null) {
                errors += PetCompatibilityError.UnknownFallback(key.value, animation.fallback.value)
            }
        }
        return PetCompatibilityReport(errors)
    }
}
