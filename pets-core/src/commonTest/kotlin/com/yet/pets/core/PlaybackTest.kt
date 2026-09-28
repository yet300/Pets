package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTest {

    private val definition: PetDefinition = run {
        val outcome = parseSuccess("{}")
        assertTrue(outcome is PetParseOutcome.Success)
        outcome.definition
    }

    private fun sample(key: PetAnimationKey, elapsedNanos: Long): PetPlaybackSample =
        samplePetAnimation(definition, key, elapsedNanos)

    private fun customDefinition(vararg entries: Pair<String, PetAnimation>): PetDefinition {
        val animations = CodexV1.defaultAnimations().toMutableMap()
        for ((name, animation) in entries) animations[PetAnimationKey(name)] = animation
        return PetDefinition(
            "test",
            "Test",
            "",
            CodexV1.defaultGeometry(),
            CodexV1.FRAME_COUNT,
            animations,
            defaultAnimationKey = PetAnimations.Idle,
        )
    }

    private fun oneShot(vararg sprites: Int, frameNanos: Long = 100_000_000L, fallback: String = "idle"): PetAnimation =
        PetAnimation(
            sprites.map { PetFrame(it, frameNanos) },
            loopStart = null,
            fallback = PetAnimationKey(fallback),
        )

    private fun looping(vararg sprites: Int, frameNanos: Long = 100_000_000L, loopStart: Int = 0): PetAnimation =
        PetAnimation(
            sprites.map { PetFrame(it, frameNanos) },
            loopStart = loopStart,
            fallback = PetAnimations.Idle,
        )

    @Test
    fun idleProgressionUsesPerFrameNanos() {
        // idle: 1680/660/660/840/840/1920 ms, total 6_600_000_000 ns.
        assertEquals(
            PetPlaybackSample(PetAnimations.Idle, 0, 1_680_000_000L),
            sample(PetAnimations.Idle, 0L),
        )
        assertEquals(
            PetPlaybackSample(PetAnimations.Idle, 0, 680_000_000L),
            sample(PetAnimations.Idle, 1_000_000_000L),
        )
        assertEquals(
            PetPlaybackSample(PetAnimations.Idle, 1, 660_000_000L),
            sample(PetAnimations.Idle, 1_680_000_000L),
        )
        assertEquals(
            PetPlaybackSample(PetAnimations.Idle, 5, 1_920_000_000L),
            sample(PetAnimations.Idle, 4_680_000_000L),
        )
    }

    @Test
    fun exactFrameBoundaryAdvances() {
        // At exactly 1_680_000_000 ns the first frame is over: second frame, full delay.
        val at = sample(PetAnimations.Idle, 1_680_000_000L)
        assertEquals(1, at.spriteIndex)
        assertEquals(660_000_000L, at.nextFrameInNanos)
    }

    @Test
    fun regularLoopRestartsCleanly() {
        assertEquals(
            PetPlaybackSample(PetAnimations.Idle, 0, 1_680_000_000L),
            sample(PetAnimations.Idle, 6_600_000_000L),
        )
        assertEquals(
            PetPlaybackSample(PetAnimations.Idle, 0, 1_680_000_000L),
            sample(PetAnimations.Idle, 13_200_000_000L),
        )
    }

    @Test
    fun prefixLoopSectionWithNonZeroLoopStart() {
        // prefix [10:100ms], loop [11:100ms, 12:200ms] from index 1; total 400ms.
        val def = customDefinition(
            "x" to PetAnimation(
                listOf(PetFrame(10, 100_000_000L), PetFrame(11, 100_000_000L), PetFrame(12, 200_000_000L)),
                loopStart = 1,
                fallback = PetAnimations.Idle,
            ),
        )
        val key = PetAnimationKey("x")
        assertEquals(10, samplePetAnimation(def, key, 50_000_000L).spriteIndex)
        assertEquals(11, samplePetAnimation(def, key, 150_000_000L).spriteIndex)
        assertEquals(12, samplePetAnimation(def, key, 350_000_000L).spriteIndex)
        // t=400ms: prefix + (300ms % 300ms) = 100ms -> frame 11 again (prefix plays once).
        val looped = samplePetAnimation(def, key, 400_000_000L)
        assertEquals(11, looped.spriteIndex)
        assertEquals(100_000_000L, looped.nextFrameInNanos)
        // t=650ms: 100ms + (550ms % 300ms)=350ms -> frame 12 with 50ms left.
        val mid = samplePetAnimation(def, key, 650_000_000L)
        assertEquals(12, mid.spriteIndex)
        assertEquals(50_000_000L, mid.nextFrameInNanos)
    }

    @Test
    fun builtInThreeXPrefixSettlesIntoIdleLoop() {
        // running: 6 primary frames (5x120ms + 220ms = 820ms) x3 = 2460ms, then idle.
        val running = definition.animation(PetAnimations.Running)!!
        assertEquals(18, running.loopStart)
        assertEquals(56, sample(PetAnimations.Running, 0L).spriteIndex)
        assertEquals(56, sample(PetAnimations.Running, 820_000_000L).spriteIndex)
        assertEquals(56, sample(PetAnimations.Running, 1_640_000_000L).spriteIndex)
        val settled = sample(PetAnimations.Running, 2_460_000_000L)
        assertEquals(PetAnimations.Running, settled.animation)
        assertEquals(0, settled.spriteIndex)
        assertEquals(1_680_000_000L, settled.nextFrameInNanos)
        // Deep inside the appended idle section.
        assertEquals(3, sample(PetAnimations.Running, 5_460_000_000L).spriteIndex)
    }

    @Test
    fun unknownKeyResolvesToIdle() {
        val at = sample(PetAnimationKey("nope"), 1_000_000_000L)
        assertEquals(PetAnimations.Idle, at.animation)
        assertEquals(sample(PetAnimations.Idle, 1_000_000_000L), at)
    }

    @Test
    fun oneShotBeforeCompletion() {
        val def = customDefinition("a" to oneShot(4, 5))
        val key = PetAnimationKey("a")
        val at = samplePetAnimation(def, key, 150_000_000L)
        assertEquals(key, at.animation)
        assertEquals(5, at.spriteIndex)
        assertEquals(50_000_000L, at.nextFrameInNanos)
    }

    @Test
    fun oneShotExactlyAtCompletionHops() {
        val def = customDefinition("a" to oneShot(4, 5))
        // Total 200ms; at exactly 200ms the hop fires, idle evaluated at 200ms.
        val at = samplePetAnimation(def, PetAnimationKey("a"), 200_000_000L)
        assertEquals(PetAnimations.Idle, at.animation)
        assertEquals(0, at.spriteIndex)
        assertEquals(1_480_000_000L, at.nextFrameInNanos)
    }

    @Test
    fun oneHopAtoBEvaluatedAtSameClock() {
        // A one-shot [0:100ms] -> B looping [5,6:100ms]; t=250ms -> B at 50ms -> sprite 5.
        val def = customDefinition(
            "a" to oneShot(0, fallback = "b"),
            "b" to looping(5, 6),
        )
        val at = samplePetAnimation(def, PetAnimationKey("a"), 250_000_000L)
        assertEquals(PetAnimationKey("b"), at.animation)
        assertEquals(5, at.spriteIndex)
        assertEquals(50_000_000L, at.nextFrameInNanos)
    }

    @Test
    fun twoHopsRemainOnBDoesNotAdvanceToC() {
        // A -> B -> C, A and B non-looping; elapsed far beyond both: stays on B.
        val def = customDefinition(
            "a" to oneShot(0, fallback = "b"),
            "b" to oneShot(7, fallback = "c"),
            "c" to looping(9),
        )
        val at = samplePetAnimation(def, PetAnimationKey("a"), 5_000_000_000L)
        assertEquals(PetAnimationKey("b"), at.animation)
        // B evaluated at 5000ms (same clock, not reset): past its end -> holds last frame.
        assertEquals(7, at.spriteIndex)
        assertNull(at.nextFrameInNanos)
    }

    @Test
    fun singleFrameOneShotReportsRemainingThenHops() {
        // Deliberate improvement over the TUI host quirk (which schedules nothing).
        val def = customDefinition("a" to oneShot(3, frameNanos = 250_000_000L))
        val before = samplePetAnimation(def, PetAnimationKey("a"), 100_000_000L)
        assertEquals(3, before.spriteIndex)
        assertEquals(150_000_000L, before.nextFrameInNanos)
        val after = samplePetAnimation(def, PetAnimationKey("a"), 250_000_000L)
        assertEquals(PetAnimations.Idle, after.animation)
        assertEquals(0, after.spriteIndex)
    }

    @Test
    fun singleFrameLoopingIsStatic() {
        val def = customDefinition("a" to looping(3, frameNanos = 250_000_000L))
        val at = samplePetAnimation(def, PetAnimationKey("a"), 10_000_000_000L)
        assertEquals(3, at.spriteIndex)
        assertNull(at.nextFrameInNanos)
    }

    @Test
    fun farFutureElapsedStaysInLoop() {
        // idle total 6.6s; t=10s -> 10_000_000_000 % 6_600_000_000 = 3_400_000_000
        // -> frame 3 (3000..3840ms), 440ms left.
        val at = sample(PetAnimations.Idle, 10_000_000_000L)
        assertEquals(3, at.spriteIndex)
        assertEquals(440_000_000L, at.nextFrameInNanos)
    }

    @Test
    fun saturatedElapsedHasExactDeterministicValues() {
        // Long.MAX_VALUE ns on the idle loop (total 6_600_000_000):
        // 9223372036854775807 % 6600000000 = 4254775807 -> frame 4, 425_224_193 ns left.
        // Computed with independent integer math (see task audit method), not the impl.
        val idle = sample(PetAnimations.Idle, Long.MAX_VALUE)
        assertEquals(PetPlaybackSample(PetAnimations.Idle, 4, 425_224_193L), idle)
        // Same instant on the 3x-prefix running track: prefix 2_460_000_000 ns, then
        // the appended idle loop: 2460000000 + ((MAX-2460000000) % 6600000000)
        // = 4254775807 -> idle-relative 1794775807 -> frame 1, 545_224_193 ns left.
        val running = sample(PetAnimations.Running, Long.MAX_VALUE)
        assertEquals(PetPlaybackSample(PetAnimations.Running, 1, 545_224_193L), running)
    }

    @Test
    fun negativeElapsedCoercesToZero() {
        // Negative is impossible on the reference monotonic clock; clamping is hardening.
        assertEquals(sample(PetAnimations.Idle, 0L), sample(PetAnimations.Idle, -500_000_000L))
        assertEquals(sample(PetAnimations.Idle, 0L), sample(PetAnimations.Idle, Long.MIN_VALUE))
    }

    @Test
    fun nextFrameInNanosIsNeverNegative() {
        val keys = listOf(
            PetAnimations.Idle, PetAnimations.Running, PetAnimations.Failed,
            PetAnimationKey("missing"),
        )
        for (key in keys) {
            for (t in listOf(0L, 1L, 999_999L, 1_680_000_000L, 6_600_000_000L, 10_000_000_000L, Long.MAX_VALUE)) {
                val at = sample(key, t)
                val next = at.nextFrameInNanos
                assertTrue(next == null || next > 0L, "key=${key.value} t=$t -> $at")
            }
        }
    }

    @Test
    fun sixtyFpsHasNoMillisecondDrift() {
        val frame = 16_666_667L
        val def = customDefinition(
            "fast" to PetAnimation((0..5).map { PetFrame(it, frame) }, 0, PetAnimations.Idle),
        )
        val key = PetAnimationKey("fast")
        // Exact boundary: frame 3.
        assertEquals(3, samplePetAnimation(def, key, frame * 3).spriteIndex)
        // One nanosecond before: still frame 2.
        assertEquals(2, samplePetAnimation(def, key, frame * 3 - 1L).spriteIndex)
        // 10s: (10_000_000_000 / 16_666_667) % 6 = 599 % 6 = 5.
        assertEquals(5, samplePetAnimation(def, key, 10_000_000_000L).spriteIndex)
        // A millisecond-truncating implementation would use 16ms or 17ms frames.
        assertTrue(frame != 16_000_000L && frame != 17_000_000L)
    }

    @Test
    fun fractionalFpsLoopsCorrectly() {
        val frame = 16_683_350L
        val def = customDefinition(
            "ntsc" to PetAnimation((0..7).map { PetFrame(it, frame) }, 0, PetAnimations.Idle),
        )
        val key = PetAnimationKey("ntsc")
        assertEquals(2, samplePetAnimation(def, key, frame * 5994).spriteIndex) // 5994 % 8 == 2
        assertEquals(1, samplePetAnimation(def, key, frame * 5994 - 1L).spriteIndex)
    }

    @Test
    fun staticIdleIsFirstIdleFrame() {
        assertEquals(0, staticIdleSpriteIndex(definition))
    }

    @Test
    fun missingFallbackResolvesToIdleToStayTotal() {
        // Manually-built definitions may carry dangling fallbacks (the parser and
        // validator reject them for foreign data); the player stays total by
        // resolving to idle. Deliberate hardening, not upstream parity.
        val animations = CodexV1.defaultAnimations().toMutableMap()
        animations[PetAnimationKey("a")] = oneShot(0, fallback = "ghost")
        val def = PetDefinition(
            "t",
            "T",
            "",
            CodexV1.defaultGeometry(),
            CodexV1.FRAME_COUNT,
            animations,
            defaultAnimationKey = PetAnimations.Idle,
        )
        val at = samplePetAnimation(def, PetAnimationKey("a"), 500_000_000L)
        assertEquals(PetAnimations.Idle, at.animation)
    }
}
