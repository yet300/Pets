package com.yet.pets.host

import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetAnimationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic host-intent tests: no composition, no real delays, no decoder.
 */
class PetHostStateTest {

    @Test
    fun showAndHideToggleVisibility() {
        val state = PetHostState(visible = true)
        assertTrue(state.isVisible)
        state.hide()
        assertFalse(state.isVisible)
        state.show()
        assertTrue(state.isVisible)
    }

    @Test
    fun initiallyHiddenStaysHiddenUntilShown() {
        val state = PetHostState(visible = false)
        assertFalse(state.isVisible)
        state.show()
        assertTrue(state.isVisible)
    }

    @Test
    fun moveToUpdatesPosition() {
        val state = PetHostState(xDp = 0f, yDp = 0f)
        assertEquals(0f, state.xDp)
        assertEquals(0f, state.yDp)
        state.moveTo(12.5f, -3.25f)
        assertEquals(12.5f, state.xDp)
        assertEquals(-3.25f, state.yDp)
    }

    @Test
    fun playUpdatesRequestedAnimation() {
        val state = PetHostState(animation = PetAnimations.Idle)
        assertEquals(PetAnimations.Idle, state.requestedAnimation)
        state.play(PetAnimations.Waving)
        assertEquals(PetAnimations.Waving, state.requestedAnimation)
        state.play(PetAnimationKey("custom-dance"))
        assertEquals(PetAnimationKey("custom-dance"), state.requestedAnimation)
    }

    @Test
    fun pinAndResumeTogglePinned() {
        val state = PetHostState()
        assertFalse(state.isPinned)
        state.pinToIdle()
        assertTrue(state.isPinned)
        // Playing while pinned records intent but stays pinned.
        state.play(PetAnimations.Running)
        assertEquals(PetAnimations.Running, state.requestedAnimation)
        assertTrue(state.isPinned)
        state.resume()
        assertFalse(state.isPinned)
        assertEquals(PetAnimations.Running, state.requestedAnimation)
    }

    @Test
    fun clampNegativeToOrigin() {
        val (x, y) = clampHostPosition(
            xDp = -10f, yDp = -20f,
            containerWidthDp = 300f, containerHeightDp = 400f,
            petWidthDp = 96f, petHeightDp = 104f,
        )
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun clampTooLargeToMax() {
        val (x, y) = clampHostPosition(
            xDp = 1_000f, yDp = 2_000f,
            containerWidthDp = 300f, containerHeightDp = 400f,
            petWidthDp = 96f, petHeightDp = 104f,
        )
        assertEquals(204f, x)
        assertEquals(296f, y)
    }

    @Test
    fun clampNormalContainerKeepsInside() {
        val (x, y) = clampHostPosition(
            xDp = 50f, yDp = 60f,
            containerWidthDp = 300f, containerHeightDp = 400f,
            petWidthDp = 96f, petHeightDp = 104f,
        )
        assertEquals(50f, x)
        assertEquals(60f, y)
    }

    @Test
    fun clampSmallContainerPinsToOrigin() {
        // Container smaller than the pet: only origin is representable.
        val (x, y) = clampHostPosition(
            xDp = 10f, yDp = 10f,
            containerWidthDp = 50f, containerHeightDp = 40f,
            petWidthDp = 96f, petHeightDp = 104f,
        )
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun clampNonFiniteCoercesToZero() {
        val (x, y) = clampHostPosition(
            xDp = Float.NaN, yDp = Float.POSITIVE_INFINITY,
            containerWidthDp = 300f, containerHeightDp = 400f,
            petWidthDp = 96f, petHeightDp = 104f,
        )
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun twoHostsAreIndependent() {
        val a = PetHostState(xDp = 0f, yDp = 0f, animation = PetAnimations.Idle)
        val b = PetHostState(xDp = 0f, yDp = 0f, animation = PetAnimations.Idle)
        a.moveTo(100f, 200f)
        a.play(PetAnimations.Waving)
        a.pinToIdle()
        a.hide()
        // B untouched.
        assertEquals(0f, b.xDp)
        assertEquals(0f, b.yDp)
        assertEquals(PetAnimations.Idle, b.requestedAnimation)
        assertFalse(b.isPinned)
        assertTrue(b.isVisible)
        // A holds its intent.
        assertEquals(100f, a.xDp)
        assertEquals(200f, a.yDp)
        assertEquals(PetAnimations.Waving, a.requestedAnimation)
        assertTrue(a.isPinned)
        assertFalse(a.isVisible)
    }

    @Test
    fun moveToDoesNotChangeAnimationOrPinned() {
        val state = PetHostState(animation = PetAnimations.Waving)
        state.pinToIdle()
        state.moveTo(33f, 44f)
        // Position moves; animation intent and pinned state are untouched.
        assertEquals(33f, state.xDp)
        assertEquals(44f, state.yDp)
        assertEquals(PetAnimations.Waving, state.requestedAnimation)
        assertTrue(state.isPinned)
    }
}
