package com.yet.pets.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPlaybackSample
import com.yet.pets.core.samplePetAnimation
import com.yet.pets.core.staticDefaultSpriteIndex
import kotlin.time.TimeSource

/**
 * Creates the monotonic nanosecond clock used by [PetPlayerState] by default:
 * elapsed nanoseconds since creation, measured on [TimeSource.Monotonic].
 * Wall-clock date/time is never used for playback.
 */
internal fun defaultMonotonicNanos(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeNanoseconds }
}

/**
 * Single coherent owner of one pet's rendering/player state.
 *
 * Generic and format-agnostic: consumes only [PetDefinition] and spritesheet
 * bytes. Never inspects schema, Codex version, package format, or animation
 * names. Generic fallback/default behavior uses
 * [PetDefinition.defaultAnimationKey].
 *
 * Owns: the [PetDefinition][com.yet.pets.core.PetDefinition], the loading or
 * decoded atlas image, the requested animation intent, the current
 * [sample][currentSample], and the animation-start timestamp. Consumers never
 * mutate samples, frame indices, or timers directly: animation intent flows in
 * through [play], which is called on the Compose/UI thread; pacing flows out of core's
 * [samplePetAnimation][com.yet.pets.core.samplePetAnimation], and the only
 * public controls are [play], [pinToDefault], and [resume].
 *
 * Timing model: elapsed time is measured against a monotonic animation-start
 * timestamp taken from [nowNanos] (monotonic by default). Delays in the
 * scheduler are wake-up hints only — every sample re-measures elapsed time
 * absolutely, so scheduling jitter can never accumulate into drift. A clock
 * that runs backwards is clamped to zero elapsed rather than producing
 * negative playback.
 *
 * Lifecycle: created via [rememberPetPlayerState]; there is deliberately no
 * public constructor (it would have to take the internal atlas type) and no
 * `rememberSaveable` support — the state holds a decoded GPU-backed image, so
 * process death recreates it and the animation restarts from frame zero.
 */
public class PetPlayerState internal constructor(
    internal val definition: PetDefinition,
    decoded: DecodedAtlas,
    initialAnimation: PetAnimationKey? = null,
    internal val nowNanos: () -> Long = defaultMonotonicNanos(),
) {
    internal var atlas: ImageBitmap? by mutableStateOf(decoded.bitmap)
        private set

    /** Decode outcome for the spritesheet bytes (see [PetAtlasState]). */
    public var atlasState: PetAtlasState by mutableStateOf(decoded.state)
        private set

    /** Publishes a decode only for this state; cancelled effects never call it. */
    internal fun completeDecode(decoded: DecodedAtlas) {
        val bitmap = decoded.bitmap
        if (decoded.state == PetAtlasState.Ready && bitmap != null &&
            (bitmap.width != definition.geometry.atlasWidth ||
                bitmap.height != definition.geometry.atlasHeight)
        ) {
            atlas = null
            atlasState = PetAtlasState.Failed(
                "spritesheet dimensions ${bitmap.width}x${bitmap.height} do not match " +
                    "${definition.geometry.atlasWidth}x${definition.geometry.atlasHeight}",
            )
        } else {
            atlas = bitmap
            atlasState = decoded.state
        }
    }

    private var requestedAnimation: PetAnimationKey = initialAnimation ?: definition.defaultAnimationKey
    private var animationStartNanos: Long = nowNanos()

    private var sampleState by mutableStateOf(
        samplePetAnimation(definition, initialAnimation ?: definition.defaultAnimationKey, 0L),
    )

    /**
     * The current playback sample, driven internally by core sampling plus the
     * frame scheduler. Reading it in composition subscribes to frame changes.
     */
    public val currentSample: PetPlaybackSample get() = sampleState

    private var pinnedState by mutableStateOf(false)

    /** Whether [pinToDefault] static mode is active (timer stopped). */
    public val isPinned: Boolean get() = pinnedState

    /**
     * Scheduler epoch: bumped on every intent change, [pinToDefault], and
     * [resume] so the frame loop in [Pet] restarts its timer exactly
     * when the animation clock does.
     */
    internal var animationEpoch by mutableIntStateOf(0)

    /**
     * Changes this state's animation intent. Idempotent: repeating the
     * SAME key is a no-op and never restarts the clock; a changed key records
     * the new intent, bumps [animationEpoch] (restarting the scheduler), and
     * restarts elapsed time from zero — unless static mode is active, in which
     * case the intent is recorded but the display stays pinned until [resume].
     *
     * Call from the Compose/UI thread, as for other snapshot state controls.
     */
    public fun play(key: PetAnimationKey) {
        if (key == requestedAnimation) return
        requestedAnimation = key
        animationEpoch++
        if (pinnedState) {
            refreshPinned()
        } else {
            animationStartNanos = nowNanos()
            sampleState = samplePetAnimation(definition, key, 0L)
        }
    }

    /**
     * Re-samples the current animation at [nowNanos] against the monotonic
     * animation-start timestamp. Pinned state always yields the static default
     * sprite. Pure core owns all playback math (frame durations, loops,
     * `loopStart`, fallback, one-hop semantics) — this only supplies elapsed
     * time.
     */
    internal fun refresh(nowNanos: Long) {
        if (pinnedState) {
            refreshPinned()
            return
        }
        val elapsed = (nowNanos - animationStartNanos).coerceAtLeast(0L)
        sampleState = samplePetAnimation(definition, requestedAnimation, elapsed)
    }

    private fun refreshPinned() {
        sampleState = PetPlaybackSample(
            animation = definition.defaultAnimationKey,
            spriteIndex = staticDefaultSpriteIndex(definition),
            nextFrameInNanos = null,
        )
    }

    /**
     * Sprite aspect ratio (cell width over cell height) for placeholder
     * layout when no frame can be drawn.
     */
    internal val cellAspect: Float
        get() = definition.geometry.cellWidth.toFloat() / definition.geometry.cellHeight.toFloat()

    /**
     * Resolves [spriteIndex] to concrete draw parameters: the atlas source
     * region from the single geometry source of truth
     * ([sourceRectForOrNull][com.yet.pets.core.AtlasGeometry.sourceRectForOrNull])
     * plus the destination aspect ratio.
     *
     * Returns `null` when there is no atlas (decode failed) or the index is
     * invalid — the renderer draws an empty placeholder instead of crashing.
     * No image is allocated or copied here: the result references the single
     * decoded atlas.
     */
    internal fun drawParamsFor(spriteIndex: Int): PetDrawParams? {
        val bitmap = atlas ?: return null
        val rect = definition.geometry.sourceRectForOrNull(spriteIndex) ?: return null
        return PetDrawParams(
            atlas = bitmap,
            srcLeft = rect.left,
            srcTop = rect.top,
            srcWidth = rect.width,
            srcHeight = rect.height,
            aspect = rect.width.toFloat() / rect.height.toFloat(),
        )
    }

    /**
     * Reduced-motion / static mode: stops the active timer and displays the
     * first frame of [PetDefinition.defaultAnimationKey]
     * ([staticDefaultSpriteIndex][com.yet.pets.core.staticDefaultSpriteIndex];
     * the same frame every host pins). No background frame updates run while
     * pinned. Sticky: animation-intent changes are recorded but do not resume
     * motion — call [resume] to resume.
     *
     * Semantic requirement: display the first frame of the definition default
     * and pause ordinary playback intent. Resume restores the most recently
     * requested animation.
     *
     * No OS accessibility-preference detection is implemented in v1; this
     * explicit control is the cross-platform reduced-motion API. Call from
     * the Compose/UI thread.
     */
    public fun pinToDefault() {
        pinnedState = true
        animationEpoch++
        refreshPinned()
    }

    /**
     * Codex-compatibility wrapper: delegates to [pinToDefault]. For Codex V1
     * definitions the default animation is `idle`, so behavior is identical.
     * Generic callers should use [pinToDefault]. No duplicated behavior.
     */
    @Deprecated(
        "Codex-specific wrapper; use pinToDefault for generic pets.",
        ReplaceWith("pinToDefault()"),
    )
    public fun pinToIdle() {
        pinToDefault()
    }

    /**
     * Resumes normal animation after [pinToDefault]: unpins and restarts the
     * currently requested animation from elapsed zero (identical semantics to
     * an animation-key change). Calling [resume] while not pinned restarts the
     * current animation from zero as well — always deterministic.
     * Call from the Compose/UI thread.
     */
    public fun resume() {
        pinnedState = false
        animationStartNanos = nowNanos()
        sampleState = samplePetAnimation(definition, requestedAnimation, 0L)
        animationEpoch++
    }
}

/**
 * Maps a playback sample to the millisecond delay before the next resample.
 *
 * Returns `null` when the sample is static ([PetPlaybackSample.nextFrameInNanos]
 * `null`) so the scheduler parks instead of polling. Core never emits negative
 * values; a zero or negative input (defensive only) yields a 1 ms re-check, and
 * sub-millisecond remainders round up to 1 ms — harmless because elapsed time
 * is always re-measured absolutely. Pure and unit-tested; no real sleeping.
 */
internal fun delayMillisForNextFrame(sample: PetPlaybackSample): Long? {
    val nanos = sample.nextFrameInNanos ?: return null
    if (nanos <= 0L) return 1L
    return (nanos / 1_000_000L) + if (nanos % 1_000_000L == 0L) 0L else 1L
}
