package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Snapshot-style assertions over the pinned CLI default table. Any accidental
 * index/duration/loopStart drift must fail loudly here.
 *
 * Expected values are literal nanoseconds (independent of the implementation's
 * internal Duration math): frame ms × 1_000_000.
 */
class DefaultAnimationsTest {

    private val animations: Map<PetAnimationKey, PetAnimation> =
        buildMap {
            val parsed = parseSuccess("{}")
            assertTrue(parsed is PetParseOutcome.Success)
            for (key in parsed.definition.animationKeys) {
                put(key, parsed.definition.animation(key)!!)
            }
        }

    @Test
    fun exactKeySet() {
        val expected = setOf(
            "idle", "running-right", "running-left", "waving", "jumping",
            "failed", "waiting", "running", "review",
            "move_right", "move_left", "wave", "bounce", "sad",
        )
        assertEquals(expected, animations.keys.map { it.value }.toSet())
    }

    @Test
    fun animationKeysAreSortedDeterministically() {
        val keys = animations.keys.map { it.value }
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun idleTrackIsExact() {
        val idle = animations.getValue(PetAnimations.Idle)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), idle.frames.map { it.spriteIndex })
        assertEquals(
            listOf(1_680_000_000L, 660_000_000L, 660_000_000L, 840_000_000L, 840_000_000L, 1_920_000_000L),
            idle.frames.map { it.durationNanos },
        )
        assertEquals(0, idle.loopStart)
        assertEquals(PetAnimations.Idle, idle.fallback)
    }

    private fun assertRow(
        key: PetAnimationKey,
        row: Int,
        primaryCount: Int,
        frameMs: Int,
        finalMs: Int,
    ) {
        val animation = animations.getValue(key)
        val primary = (0 until primaryCount).map { row * 8 + it }
        val primaryNanos = (0 until primaryCount).map {
            (if (it == primaryCount - 1) finalMs else frameMs) * 1_000_000L
        }
        val idleNanos = listOf(1_680_000_000L, 660_000_000L, 660_000_000L, 840_000_000L, 840_000_000L, 1_920_000_000L)
        val expectedIndices = primary + primary + primary + listOf(0, 1, 2, 3, 4, 5)
        val expectedNanos = primaryNanos + primaryNanos + primaryNanos + idleNanos
        assertEquals(expectedIndices, animation.frames.map { it.spriteIndex }, "indices for ${key.value}")
        assertEquals(expectedNanos, animation.frames.map { it.durationNanos }, "nanos for ${key.value}")
        assertEquals(primaryCount * 3, animation.loopStart, "loopStart for ${key.value}")
        assertEquals(PetAnimations.Idle, animation.fallback)
    }

    @Test
    fun rowTracksAreExact() {
        assertRow(PetAnimations.RunningRight, 1, 8, 120, 220)
        assertRow(PetAnimations.RunningLeft, 2, 8, 120, 220)
        assertRow(PetAnimations.Waving, 3, 4, 140, 280)
        assertRow(PetAnimations.Jumping, 4, 5, 140, 280)
        assertRow(PetAnimations.Failed, 5, 8, 140, 240)
        assertRow(PetAnimations.Waiting, 6, 6, 150, 260)
        assertRow(PetAnimations.Running, 7, 6, 120, 220)
        assertRow(PetAnimations.Review, 8, 6, 150, 280)
    }

    @Test
    fun aliasesShareReferenceTracks() {
        assertEquals(animations.getValue(PetAnimations.RunningRight), animations.getValue(PetAnimations.MoveRight))
        assertEquals(animations.getValue(PetAnimations.RunningLeft), animations.getValue(PetAnimations.MoveLeft))
        assertEquals(animations.getValue(PetAnimations.Waving), animations.getValue(PetAnimations.Wave))
        assertEquals(animations.getValue(PetAnimations.Jumping), animations.getValue(PetAnimations.Bounce))
        assertEquals(animations.getValue(PetAnimations.Failed), animations.getValue(PetAnimations.Sad))
    }

    @Test
    fun parsedDefaultsContainIdleWithSixFrames() {
        val parsed = parseSuccess("{}")
        assertTrue(parsed is PetParseOutcome.Success)
        val idle = parsed.definition.animation(PetAnimations.Idle)
        assertTrue(idle != null && idle.frames.size == 6 && idle.loopStart == 0)
    }

    @Test
    fun internalDurationMathAgreesWithLiteralNanos() {
        // Cross-check only: stdlib ms->ns conversion must equal the literals above.
        assertEquals(1_680_000_000L, 1680.milliseconds.inWholeNanoseconds)
        assertEquals(125_000_000L, (1.0 / 8.0).seconds.inWholeNanoseconds)
    }
}
