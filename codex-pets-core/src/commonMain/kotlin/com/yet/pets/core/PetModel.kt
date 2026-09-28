package com.yet.pets.core

/**
 * Image container formats relevant to Codex pets.
 *
 * PNG, WEBP, GIF, and JPEG mirror the pinned upstream TUI `image`-crate
 * feature set (`jpeg`, `png`, `gif`, `webp` at `openai/codex @ 55543d8`).
 * Added additively in Phase 2; no other core behavior changes.
 */
public enum class SpritesheetFormat {
    PNG,
    WEBP,
    GIF,
    JPEG,
    UNKNOWN,
}

/**
 * Pure, decoder-independent facts about an encoded spritesheet.
 *
 * Produced by the io-owned probe from image bytes and passed into core
 * validation. Core never decodes images and never owns the probe.
 *
 * The constructor is intentionally total: it performs no validation, so foreign
 * dimensions/formats flow into typed compatibility errors instead of throwing.
 */
public data class SpritesheetInfo(
    public val width: Int,
    public val height: Int,
    public val format: SpritesheetFormat,
)

/**
 * One frame of an animation track: a sprite index into the atlas grid plus its
 * display duration in nanoseconds. Timing only — geometry comes from
 * [AtlasGeometry.sourceRectForOrNull].
 *
 * The constructor is internal: instances are created by the parser/normalizer
 * from validated data. Invariants (`spriteIndex >= 0`, `durationNanos > 0`)
 * fail fast for programmer errors; foreign data never reaches this constructor
 * except through the parser, which converts violations to typed errors.
 */
public class PetFrame internal constructor(
    public val spriteIndex: Int,
    public val durationNanos: Long,
) {
    init {
        require(spriteIndex >= 0) { "spriteIndex must be >= 0, got $spriteIndex" }
        require(durationNanos > 0) { "durationNanos must be > 0, got $durationNanos" }
    }

    override fun equals(other: Any?): Boolean =
        other is PetFrame && spriteIndex == other.spriteIndex && durationNanos == other.durationNanos

    override fun hashCode(): Int = 31 * spriteIndex.hashCode() + durationNanos.hashCode()

    override fun toString(): String =
        "PetFrame(spriteIndex=$spriteIndex, durationNanos=$durationNanos)"
}

/**
 * One named animation track. [loopStart] is a frame-list index: non-null loops
 * the whole track from that index (prefix plays once, suffix loops); null is a
 * one-shot that holds its last frame and then hands off to [fallback].
 *
 * The constructor is internal (see [PetFrame]); the exposed [frames] list is a
 * defensive snapshot: later mutation of caller-owned input lists cannot mutate
 * model state. Snapshot-isolation only — no claim of cast-proof immutability.
 */
public class PetAnimation internal constructor(
    frames: List<PetFrame>,
    public val loopStart: Int?,
    public val fallback: PetAnimationKey,
) {
    public val frames: List<PetFrame> = frames.toList()

    init {
        require(frames.isNotEmpty()) { "animation frames must be non-empty" }
        if (loopStart != null) {
            require(loopStart in frames.indices) {
                "loopStart $loopStart out of range for ${frames.size} frames"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PetAnimation &&
            frames == other.frames &&
            loopStart == other.loopStart &&
            fallback == other.fallback

    override fun hashCode(): Int {
        var result = frames.hashCode()
        result = 31 * result + (loopStart?.hashCode() ?: 0)
        result = 31 * result + fallback.hashCode()
        return result
    }

    override fun toString(): String =
        "PetAnimation(frames=$frames, loopStart=$loopStart, fallback=$fallback)"
}

/**
 * Normalized runtime pet definition. Pure generic data: no diagnostics (those
 * live in [PetCompatibilityReport] / outcomes), no platform types, no Codex
 * version tags, no format tags.
 *
 * Generic runtime owns this type: it is format-agnostic. A Codex V1 package, a
 * Pets KMP generic package, or a future adapter all produce this same model.
 * Codex-specific strings (idle, running-right, …) must not appear as required
 * generic-runtime semantics; they live only in the Codex compatibility adapter.
 *
 * Animation lookup is interop-safe: the internal map is never exposed (a
 * `Map<PetAnimationKey, PetAnimation>` bridges asymmetrically on Apple
 * platforms). [animationKeys] is sorted by key value for determinism;
 * [animation] resolves by key or by plain name.
 *
 * The constructor is internal: validated instances come from parsers, which
 * convert foreign-data violations to typed reports. Structural invariants
 * fail fast for programmer errors.
 *
 * @property defaultAnimationKey the definition-owned default animation. Every
 *   definition has one and it must exist in [animations]. The Codex V1 adapter
 *   sets this to `idle`; generic packages may choose `sleep`, `stand`,
 *   `normal`, `base`, or anything else. Generic playback, pin, and
 *   unknown-key resolution all use this key — never a hard-coded `idle`.
 */
public class PetDefinition internal constructor(
    public val id: String,
    public val displayName: String,
    public val description: String,
    public val geometry: AtlasGeometry,
    public val frameCount: Int,
    animations: Map<PetAnimationKey, PetAnimation>,
    public val defaultAnimationKey: PetAnimationKey = PetAnimations.Idle,
) {
    private val lookup: Map<PetAnimationKey, PetAnimation> = animations.toMap()

    /** All animation keys, sorted by key value. Deterministic ordering. */
    public val animationKeys: List<PetAnimationKey> =
        lookup.keys.sortedBy { it.value }

    /** Animation for [key], or `null` when absent. */
    public fun animation(key: PetAnimationKey): PetAnimation? = lookup[key]

    /** Animation for [name], or `null` when absent. Avoids key allocation for lookups. */
    public fun animation(name: String): PetAnimation? = try {
        lookup[PetAnimationKey(name)]
    } catch (_: IllegalArgumentException) {
        null
    }

    init {
        require(frameCount > 0) { "frameCount must be positive, got $frameCount" }
        require(frameCount.toLong() == geometry.frameCapacity) {
            "frameCount $frameCount must equal grid capacity ${geometry.frameCapacity}"
        }
        require(lookup.isNotEmpty()) { "animations must be non-empty" }
        require(lookup.containsKey(defaultAnimationKey)) {
            "default animation ${defaultAnimationKey.value} must exist in animations"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PetDefinition &&
            id == other.id &&
            displayName == other.displayName &&
            description == other.description &&
            geometry == other.geometry &&
            frameCount == other.frameCount &&
            defaultAnimationKey == other.defaultAnimationKey &&
            lookup == other.lookup

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + geometry.hashCode()
        result = 31 * result + frameCount
        result = 31 * result + defaultAnimationKey.hashCode()
        result = 31 * result + lookup.hashCode()
        return result
    }

    override fun toString(): String =
        "PetDefinition(id=$id, displayName=$displayName, geometry=$geometry, " +
            "frameCount=$frameCount, defaultAnimationKey=${defaultAnimationKey.value}, " +
            "animationKeys=${animationKeys.map { it.value }})"
}
