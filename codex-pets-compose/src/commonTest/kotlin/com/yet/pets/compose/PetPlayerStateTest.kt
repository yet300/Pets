package com.yet.pets.compose

import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import com.yet.pets.core.samplePetAnimation
import com.yet.pets.core.staticIdleSpriteIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure player-state tests: no composition, no real sleeping, no decoder.
 * A fake monotonic clock drives [PetPlayerState] deterministically.
 */
class PetPlayerStateTest {

    private fun definition(json: String = "{}"): PetDefinition {
        val outcome = PetPackageParser.parse(
            json,
            "test",
            SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        assertIs<PetParseOutcome.Success>(outcome, "expected parse success, got $outcome")
        return outcome.definition
    }

    private val noAtlas = DecodedAtlas(null, PetAtlasState.Failed("test-only"))

    private class FakeClock(var now: Long = 0L) {
        fun advance(deltaNanos: Long) {
            now += deltaNanos
        }
    }

    private fun state(
        definition: PetDefinition,
        clock: FakeClock,
        initial: PetAnimationKey = PetAnimations.Idle,
    ): PetPlayerState = PetPlayerState(definition, noAtlas, initial) { clock.now }

    @Test
    fun startsOnRequestedAnimationFrameZero() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock, PetAnimations.Waving)
        assertEquals(samplePetAnimation(definition, PetAnimations.Waving, 0L), state.currentSample)
        assertEquals(0, state.animationEpoch)
        assertFalse(state.isPinned)
    }

    @Test
    fun sameKeyAdoptDoesNotRestartClock() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock)
        clock.advance(5_000_000_000L)
        state.refresh(clock.now)
        val atFiveSeconds = samplePetAnimation(definition, PetAnimations.Idle, 5_000_000_000L)
        assertEquals(atFiveSeconds, state.currentSample)
        // Recomposition with the SAME key: no-op, clock untouched.
        state.adoptAnimation(PetAnimations.Idle)
        assertEquals(atFiveSeconds, state.currentSample)
        assertEquals(0, state.animationEpoch)
    }

    @Test
    fun animationKeyChangeRestartsElapsedClock() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock)
        clock.advance(5_000_000_000L)
        state.refresh(clock.now)
        state.adoptAnimation(PetAnimations.Waving)
        assertEquals(1, state.animationEpoch)
        assertEquals(samplePetAnimation(definition, PetAnimations.Waving, 0L), state.currentSample)
    }

    @Test
    fun nextFrameDelaySchedulesNextSample() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock)
        // Idle frame 0 lasts 1_680_000_000 ns (CLI V1 table).
        assertEquals(1_680L, delayMillisForNextFrame(state.currentSample))
        // A static sample parks instead of polling.
        state.pinToIdle()
        assertEquals(null, delayMillisForNextFrame(state.currentSample))
    }

    @Test
    fun delayHelperEdgeCases() {
        val definition = definition()
        val loopingIdle = samplePetAnimation(definition, PetAnimations.Idle, 0L)
        assertTrue(loopingIdle.nextFrameInNanos != null)
        assertEquals(null, delayMillisForNextFrame(loopingIdle.copy(nextFrameInNanos = null)))
        // Degenerate non-positive inputs re-check soon instead of busy-looping or
        // throwing; sub-millisecond remainders round up to 1 ms.
        assertEquals(1L, delayMillisForNextFrame(loopingIdle.copy(nextFrameInNanos = 0L)))
        assertEquals(1L, delayMillisForNextFrame(loopingIdle.copy(nextFrameInNanos = -5L)))
        assertEquals(1L, delayMillisForNextFrame(loopingIdle.copy(nextFrameInNanos = 999_999L)))
        assertEquals(1L, delayMillisForNextFrame(loopingIdle.copy(nextFrameInNanos = 1_000_000L)))
        assertEquals(1_680L, delayMillisForNextFrame(loopingIdle.copy(nextFrameInNanos = 1_680_000_000L)))
    }

    @Test
    fun fallbackTransitionOccursViaCore() {
        val definition = definition(
            """{"animations": {"custom": {"frames": [1, 2], "fps": 2.0, "loop": false, "fallback": "idle"}}}""",
        )
        val clock = FakeClock()
        val state = state(definition, clock, PetAnimationKey("custom"))
        // Pre-completion: second frame, 100 ms of the 500 ms frame remaining.
        clock.advance(900_000_000L)
        state.refresh(clock.now)
        assertEquals(2, state.currentSample.spriteIndex)
        assertEquals(100_000_000L, state.currentSample.nextFrameInNanos)
        // Past the 1 s total: the single fallback hop applies (same clock).
        clock.advance(500_000_000L)
        state.refresh(clock.now)
        assertEquals(
            samplePetAnimation(definition, PetAnimationKey("custom"), 1_400_000_000L),
            state.currentSample,
        )
        assertEquals(PetAnimations.Idle, state.currentSample.animation)
    }

    @Test
    fun loopContinuesCorrectly() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock)
        // Idle total is 6_600_000_000 ns; a full cycle restarts cleanly.
        clock.advance(6_600_000_000L)
        state.refresh(clock.now)
        assertEquals(samplePetAnimation(definition, PetAnimations.Idle, 6_600_000_000L), state.currentSample)
        assertEquals(0, state.currentSample.spriteIndex)
    }

    @Test
    fun oneShotHoldsThenHops() {
        val definition = definition(
            """{"animations": {"once": {"frames": [4, 5], "fps": 10.0, "loop": false, "fallback": "idle"}}}""",
        )
        val clock = FakeClock()
        val state = state(definition, clock, PetAnimationKey("once"))
        // 100 ms frames; at 150 ms the second frame holds with 50 ms left.
        clock.advance(150_000_000L)
        state.refresh(clock.now)
        assertEquals(5, state.currentSample.spriteIndex)
        assertEquals(50_000_000L, state.currentSample.nextFrameInNanos)
        assertEquals(PetAnimationKey("once"), state.currentSample.animation)
    }

    @Test
    fun pinToIdleStopsUpdates() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock, PetAnimations.Waving)
        state.pinToIdle()
        assertTrue(state.isPinned)
        val pinned = state.currentSample
        assertEquals(PetAnimations.Idle, pinned.animation)
        assertEquals(staticIdleSpriteIndex(definition), pinned.spriteIndex)
        assertEquals(null, pinned.nextFrameInNanos)
        // Later clock values change nothing while pinned.
        clock.advance(60_000_000_000L)
        state.refresh(clock.now)
        assertEquals(pinned, state.currentSample)
    }

    @Test
    fun resumeAfterPinRestartsRequestedAnimation() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock, PetAnimations.Waving)
        clock.advance(3_000_000_000L)
        state.refresh(clock.now)
        state.pinToIdle()
        assertTrue(state.isPinned)
        val epochAfterPin = state.animationEpoch
        state.resume()
        assertFalse(state.isPinned)
        assertTrue(state.animationEpoch > epochAfterPin)
        assertEquals(samplePetAnimation(definition, PetAnimations.Waving, 0L), state.currentSample)
        // The scheduler continues from the resumed clock (absolute, no drift).
        clock.advance(140_000_000L)
        state.refresh(clock.now)
        assertEquals(samplePetAnimation(definition, PetAnimations.Waving, 140_000_000L), state.currentSample)
    }

    @Test
    fun intentChangeWhilePinnedRecordsButStaysStatic() {
        val definition = definition()
        val clock = FakeClock()
        val state = state(definition, clock)
        state.pinToIdle()
        state.adoptAnimation(PetAnimations.Running)
        // Still pinned: static idle on screen, no motion.
        assertTrue(state.isPinned)
        assertEquals(staticIdleSpriteIndex(definition), state.currentSample.spriteIndex)
        assertEquals(null, state.currentSample.nextFrameInNanos)
        // Resume picks up the NEW intent from frame zero.
        state.resume()
        assertEquals(samplePetAnimation(definition, PetAnimations.Running, 0L), state.currentSample)
    }

    @Test
    fun backwardClockClampsToZero() {
        val definition = definition()
        val clock = FakeClock(now = 10_000_000_000L)
        val state = state(definition, clock)
        clock.now = 1L
        state.refresh(clock.now)
        assertEquals(samplePetAnimation(definition, PetAnimations.Idle, 0L), state.currentSample)
    }
}
