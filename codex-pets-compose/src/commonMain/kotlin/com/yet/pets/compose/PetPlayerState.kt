package com.yet.pets.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPlaybackSample
import com.yet.pets.core.samplePetAnimation
import com.yet.pets.core.staticIdleSpriteIndex
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
 * Owns: the [PetDefinition][com.yet.pets.core.PetDefinition], the one decoded
 * atlas image, the requested animation intent, the current
 * [sample][currentSample], and the animation-start timestamp. Consumers never
 * mutate samples, frame indices, or timers directly: animation intent flows in
 * through [CodexPet]'s `animation` parameter (adopted synchronously and
 * idempotently), pacing flows out of core's
 * [samplePetAnimation][com.yet.pets.core.samplePetAnimation], and the only
 * public controls are [pinToIdle] and [resume].
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
    initialAnimation: PetAnimationKey = PetAnimations.Idle,
    internal val nowNanos: () -> Long = defaultMonotonicNanos(),
) {
    internal val atlas: ImageBitmap? = decoded.bitmap

    /** Decode outcome for the spritesheet bytes (see [PetAtlasState]). */
    public val atlasState: PetAtlasState = decoded.state

    private var requestedAnimation: PetAnimationKey = initialAnimation
    private var animationStartNanos: Long = nowNanos()

    private var sampleState by mutableStateOf(
        samplePetAnimation(definition, initialAnimation, 0L),
    )

    /**
     * The current playback sample, driven internally by core sampling plus the
     * frame scheduler. Reading it in composition subscribes to frame changes.
     */
    public val currentSample: PetPlaybackSample get() = sampleState

    private var pinnedState by mutableStateOf(false)

    /** Whether [pinToIdle] static mode is active (timer stopped). */
    public val isPinned: Boolean get() = pinnedState

    /**
     * Scheduler epoch: bumped on every intent change, [pinToIdle], and
     * [resume] so the frame loop in [CodexPet] restarts its timer exactly
     * when the animation clock does.
     */
    internal var animationEpoch by mutableIntStateOf(0)

    /**
     * Adopts the latest animation intent. Idempotent: recomposition with the
     * SAME key is a no-op and never restarts the clock; a changed key records
     * the new intent, bumps [animationEpoch] (restarting the scheduler), and
     * restarts elapsed time from zero — unless static mode is active, in which
     * case the intent is recorded but the display stays pinned until [resume].
     *
     * Runs synchronously inside [CodexPet] composition (before drawing), so
     * the first frame already reflects the requested animation.
     */
    internal fun adoptAnimation(key: PetAnimationKey) {
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
     * animation-start timestamp. Pinned state always yields the static idle
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
            animation = PetAnimations.Idle,
            spriteIndex = staticIdleSpriteIndex(definition),
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
     * static idle sprite ([staticIdleSpriteIndex][com.yet.pets.core.staticIdleSpriteIndex];
     * the same frame every host pins). No background frame updates run while
     * pinned. Sticky: animation-intent changes are recorded but do not resume
     * motion — call [resume] to resume.
     *
     * No OS accessibility-preference detection is implemented in v1; this
     * explicit control is the cross-platform reduced-motion API.
     */
    public fun pinToIdle() {
        pinnedState = true
        animationEpoch++
        refreshPinned()
    }

    /**
     * Resumes normal animation after [pinToIdle]: unpins and restarts the
     * currently requested animation from elapsed zero (identical semantics to
     * an animation-key change). Calling [resume] while not pinned restarts the
     * current animation from zero as well — always deterministic.
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
    return (nanos / 1_000_000L).coerceAtLeast(1L)
}
