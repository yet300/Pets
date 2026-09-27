package com.yet.pets.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetAnimations

/**
 * Owns only host intent: visibility, position in dp, requested animation,
 * and pinned (reduced-motion) state.
 *
 * It does NOT own the decoded bitmap, playback frame index, timer, or
 * geometry formulas. Rendering/playback goes through `rememberPetPlayerState`
 * / `PetPlayerState.play` / `CodexPet` inside the host composition.
 *
 * Coordinates are plain [Float] dp values. No platform coordinate types
 * (`android.graphics.Point`, `WindowManager.LayoutParams`, `java.awt.Point`,
 * `CGPoint`, …) appear in this public API.
 *
 * `isPinned` is exposed alongside the spec's four core properties so callers
 * can distinguish pinned (static idle) from playing states without reaching
 * into the player; `pinToIdle`/`resume` mutate it deterministically.
 */
public class PetHostState internal constructor(
    visible: Boolean = true,
    xDp: Float = 0f,
    yDp: Float = 0f,
    animation: PetAnimationKey = PetAnimations.Idle,
) {
    /** Whether the pet should be visible. */
    public var isVisible: Boolean by mutableStateOf(visible)
        private set

    /** Requested horizontal position in dp. */
    public var xDp: Float by mutableStateOf(xDp)
        private set

    /** Requested vertical position in dp. */
    public var yDp: Float by mutableStateOf(yDp)
        private set

    /** Requested animation intent (applied to the player by the host). */
    public var requestedAnimation: PetAnimationKey by mutableStateOf(animation)
        private set

    /** Whether static-idle (reduced-motion) mode is active. */
    public var isPinned: Boolean by mutableStateOf(false)
        private set

    /** Makes the pet visible (platform host shows its surface). */
    public fun show() {
        isVisible = true
    }

    /** Hides the pet (platform host releases its surface; app keeps running). */
    public fun hide() {
        isVisible = false
    }

    /** Requests a new host position in dp. */
    public fun moveTo(xDp: Float, yDp: Float) {
        this.xDp = xDp
        this.yDp = yDp
    }

    /** Requests a new animation intent (does not decode or restart position). */
    public fun play(animation: PetAnimationKey) {
        requestedAnimation = animation
    }

    /** Enters static-idle mode (display stays pinned until [resume]). */
    public fun pinToIdle() {
        isPinned = true
    }

    /** Leaves static-idle mode (resumes the requested animation from zero). */
    public fun resume() {
        isPinned = false
    }
}

/**
 * Remembers one [PetHostState]. Each call site owns an independent instance:
 * moving/playing one host never affects another (no global mutable state).
 *
 * @param initialXDp starting horizontal position in dp.
 * @param initialYDp starting vertical position in dp.
 * @param initialAnimation starting animation intent.
 * @param initiallyVisible starting visibility.
 */
@Composable
public fun rememberPetHostState(
    initialXDp: Float = 0f,
    initialYDp: Float = 0f,
    initialAnimation: PetAnimationKey = PetAnimations.Idle,
    initiallyVisible: Boolean = true,
): PetHostState = remember {
    PetHostState(
        visible = initiallyVisible,
        xDp = initialXDp,
        yDp = initialYDp,
        animation = initialAnimation,
    )
}

/** Default pet width used by the in-app and overlay hosts (dp). */
internal const val PetHostDefaultPetWidthDp: Float = 96f

/**
 * Clamps a requested host position so the pet rectangle stays inside the
 * available host area.
 *
 * Pure and deterministic: no platform types, no layout, no side effects.
 * Non-finite inputs coerce to zero; negative container/pet sizes coerce to
 * zero; a container smaller than the pet pins the pet at the origin.
 *
 * Desktop overlay windows intentionally do NOT use this global clamp (negative
 * desktop coordinates are legal across monitors); it applies to the in-app
 * host area and to small overlay-window content bounds where the platform
 * requires containment.
 */
internal fun clampHostPosition(
    xDp: Float,
    yDp: Float,
    containerWidthDp: Float,
    containerHeightDp: Float,
    petWidthDp: Float,
    petHeightDp: Float,
): Pair<Float, Float> {
    val safeX = if (xDp.isFinite()) xDp else 0f
    val safeY = if (yDp.isFinite()) yDp else 0f
    val safeContainerW = if (containerWidthDp.isFinite()) containerWidthDp.coerceAtLeast(0f) else 0f
    val safeContainerH = if (containerHeightDp.isFinite()) containerHeightDp.coerceAtLeast(0f) else 0f
    val safePetW = if (petWidthDp.isFinite()) petWidthDp.coerceAtLeast(0f) else 0f
    val safePetH = if (petHeightDp.isFinite()) petHeightDp.coerceAtLeast(0f) else 0f
    val maxX = (safeContainerW - safePetW).coerceAtLeast(0f)
    val maxY = (safeContainerH - safePetH).coerceAtLeast(0f)
    return Pair(safeX.coerceIn(0f, maxX), safeY.coerceIn(0f, maxY))
}
