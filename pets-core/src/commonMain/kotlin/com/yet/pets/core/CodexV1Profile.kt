package com.yet.pets.core

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Confirmed Codex CLI V1 profile constants and default animation table.
 *
 * Normative source: `codex-rs/tui/src/pets/model.rs` + `catalog.rs` at pinned
 * commit `55543d8`. Timings are exact per-frame nanosecond values derived from
 * the reference millisecond constants — never normalized to integer FPS, never
 * exposed as `kotlin.time.Duration` (internal computation only; the public ABI
 * carries explicitly named nanos `Long` values so Swift callers see plain time).
 */
internal object CodexV1 {
    const val ATLAS_WIDTH: Int = 1536
    const val ATLAS_HEIGHT: Int = 1872
    const val COLUMNS: Int = 8
    const val ROWS: Int = 9
    const val CELL_WIDTH: Int = 192
    const val CELL_HEIGHT: Int = 208
    const val FRAME_COUNT: Int = COLUMNS * ROWS // 72
    const val MAX_FRAMES: Int = 256
    const val MAX_FPS: Double = 60.0
    const val DEFAULT_FPS: Double = 8.0
    const val DEFAULT_SPRITESHEET_PATH: String = "spritesheet.webp"

    fun defaultGeometry(): AtlasGeometry = AtlasGeometry(
        atlasWidth = ATLAS_WIDTH,
        atlasHeight = ATLAS_HEIGHT,
        columns = COLUMNS,
        rows = ROWS,
        cellWidth = CELL_WIDTH,
        cellHeight = CELL_HEIGHT,
    )

    /** Reference idle track: indices 0-5, per-frame ms 1680/660/660/840/840/1920. */
    fun idleFrames(): List<PetFrame> = listOf(
        PetFrame(0, 1680.milliseconds.inWholeNanoseconds),
        PetFrame(1, 660.milliseconds.inWholeNanoseconds),
        PetFrame(2, 660.milliseconds.inWholeNanoseconds),
        PetFrame(3, 840.milliseconds.inWholeNanoseconds),
        PetFrame(4, 840.milliseconds.inWholeNanoseconds),
        PetFrame(5, 1920.milliseconds.inWholeNanoseconds),
    )

    fun idleAnimation(): PetAnimation = PetAnimation(
        frames = idleFrames(),
        loopStart = 0,
        fallback = PetAnimations.Idle,
    )

    /**
     * Reference `app_state_animation`: primary row frames played 3x, then the
     * idle track appended; [loopStart] points at the appended idle section so
     * the row plays three times and settles into the idle loop.
     */
    fun appStateAnimation(
        rowIndex: Int,
        frameCount: Int,
        frameDurationMs: Int,
        finalFrameDurationMs: Int,
    ): PetAnimation {
        val primary = (0 until frameCount).map { column ->
            val ms = if (column == frameCount - 1) finalFrameDurationMs else frameDurationMs
            PetFrame(
                spriteIndex = rowIndex * COLUMNS + column,
                durationNanos = ms.milliseconds.inWholeNanoseconds,
            )
        }
        return PetAnimation(
            frames = primary + primary + primary + idleFrames(),
            loopStart = primary.size * 3,
            fallback = PetAnimations.Idle,
        )
    }

    /** Full reference `default_animations()` table, including aliases. */
    fun defaultAnimations(): Map<PetAnimationKey, PetAnimation> {
        val idle = idleAnimation()
        val runningRight = appStateAnimation(1, 8, 120, 220)
        val runningLeft = appStateAnimation(2, 8, 120, 220)
        val waving = appStateAnimation(3, 4, 140, 280)
        val jumping = appStateAnimation(4, 5, 140, 280)
        val failed = appStateAnimation(5, 8, 140, 240)
        val waiting = appStateAnimation(6, 6, 150, 260)
        val running = appStateAnimation(7, 6, 120, 220)
        val review = appStateAnimation(8, 6, 150, 280)
        return mapOf(
            PetAnimations.Idle to idle,
            PetAnimations.RunningRight to runningRight,
            PetAnimations.RunningLeft to runningLeft,
            PetAnimations.Waving to waving,
            PetAnimations.Jumping to jumping,
            PetAnimations.Failed to failed,
            PetAnimations.Waiting to waiting,
            PetAnimations.Running to running,
            PetAnimations.Review to review,
            PetAnimations.MoveRight to runningRight,
            PetAnimations.MoveLeft to runningLeft,
            PetAnimations.Wave to waving,
            PetAnimations.Bounce to jumping,
            PetAnimations.Sad to failed,
        )
    }
}

/**
 * Converts an animation `fps` value to per-frame nanoseconds.
 *
 * Returns `null` when the value is outside the profile (`!finite`, `<= 0`,
 * `> 60`) or when the derived duration is unrepresentable.
 *
 * Deliberate safety hardening over upstream (category B): the reference checks
 * the same range but then converts unconditionally, panicking on overflow
 * (e.g. subnormal `4.9e-324`, smallest normal `2.225e-308`, or any fps whose
 * `1/fps` seconds exceed the duration range). This library returns `null`
 * (mapped to typed `InvalidFps`) instead. Practical frame rates
 * (8 / 24 / 59.94 / 60) are unaffected.
 */
internal fun fpsToDurationNanos(fps: Double): Long? {
    if (!fps.isFinite() || fps <= 0.0 || fps > CodexV1.MAX_FPS) return null
    val duration = (1.0 / fps).seconds
    if (!duration.isFinite()) return null
    return duration.inWholeNanoseconds
}
