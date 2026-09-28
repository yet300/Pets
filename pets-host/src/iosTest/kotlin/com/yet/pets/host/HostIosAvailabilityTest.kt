package com.yet.pets.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS pins SystemOverlay to Unsupported (no PiP/Live Activities abuse, no
 * hidden cross-app window). Runs on iosSimulatorArm64Test via the shared
 * iosTest intermediate source set.
 */
class HostIosAvailabilityTest {

    @Test
    fun systemOverlayIsUnsupported() {
        assertEquals(
            PetSystemOverlayAvailability.Unsupported,
            iosStaticOverlayAvailability(),
        )
    }

    @Test
    fun inAppStateLogicRunsOnIos() {
        // Common host intent works on iOS (InApp is supported via common code).
        val state = PetHostState()
        state.moveTo(10f, 20f)
        state.play(com.yet.pets.core.PetAnimations.Waving)
        assertEquals(10f, state.xDp)
        assertEquals(20f, state.yDp)
        assertEquals(com.yet.pets.core.PetAnimations.Waving, state.requestedAnimation)
        val (x, y) = clampHostPosition(
            -5f, 9999f, 300f, 400f, 96f, 104f,
        )
        assertEquals(0f, x)
        assertEquals(296f, y)
    }
}
