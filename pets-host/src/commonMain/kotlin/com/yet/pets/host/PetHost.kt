package com.yet.pets.host

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yet.pets.compose.Pet
import com.yet.pets.compose.PetPlayerState
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetDefinition

/** Internal test observation only; never part of the host ABI. */
internal object PetHostPlayerTestProbe {
    var onPlayer: ((PetPlayerState) -> Unit)? = null
}

/**
 * Hosts a pet in the requested [mode]. Generic and format-agnostic: receives
 * [PetDefinition], spritesheet bytes, and host state, and works identically
 * for Codex V1 and Pets KMP pets. Never inspects package format. Default-idle
 * assumptions are replaced with definition default semantics.
 *
 * - [PetHostMode.InApp]: common Compose implementation that works on
 *   Android, JVM, and iOS. Transparent surrounding area, position from
 *   [PetHostState], drag gesture updates host position, hide/show and
 *   animation/pin intents flow to the player.
 * - [PetHostMode.SystemOverlay]: delegates to the internal platform seam
 *   ([PlatformPetOverlayHost]). Never silently falls back to in-app: on
 *   unsupported/permission-missing platforms nothing is rendered.
 *
 * Input safety comes from the existing Compose path: [spritesheetBytes] are
 * passed unchanged to `rememberPetPlayerState`, retaining its 8 MiB cap,
 * static-format validation, async decode, atlas-dimension check, and
 * Loading/Ready/Failed states. The host performs no defensive copy and no
 * playback math.
 *
 * @param definition normalized pet definition.
 * @param spritesheetBytes encoded spritesheet; caller keeps the array stable
 *   until the background snapshot (same contract as the player).
 * @param state host intent (visibility, position, animation, pinned).
 * @param mode where the pet is rendered.
 * @param modifier applied to the in-app container (ignored for system overlay,
 *   whose surface lives outside the current composition).
 */
@Composable
public fun PetHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
    mode: PetHostMode,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        PetHostMode.InApp -> PetInAppHost(
            definition = definition,
            spritesheetBytes = spritesheetBytes,
            state = state,
            modifier = modifier,
        )
        PetHostMode.SystemOverlay -> PlatformPetOverlayHost(
            definition = definition,
            spritesheetBytes = spritesheetBytes,
            state = state,
        )
    }
}

/**
 * Common in-app host: visible pet, transparent surroundings, drag moves the
 * host position, intent flows to exactly one player.
 *
 * Internal: platform source sets need no override for basic in-app hosting.
 */
@Composable
internal fun PetInAppHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
    modifier: Modifier = Modifier,
) {
    // One player per host. The ByteArray instance is passed through unchanged:
    // Compose remains the image ownership/decode boundary (no extra copy here).
    val player = rememberPetPlayerState(definition, spritesheetBytes)
    PetHostPlayerTestProbe.onPlayer?.invoke(player)

    // Host intent -> player. Effects only; drawing never mutates intent.
    // A null request follows the definition default (never a foreign sentinel);
    // the public requestedAnimation value is never rewritten here.
    // Same-key plays are no-ops inside the player, so drag/position updates
    // never restart the animation.
    LaunchedEffect(player, state.requestedAnimation, state.isPinned) {
        player.play(state.effectiveAnimation(definition))
        if (state.isPinned) {
            player.pinToDefault()
        } else if (player.isPinned) {
            player.resume()
        }
    }

    if (!state.isVisible) {
        // Hidden: no pet, no surface. Transparent container keeps layout stable
        // without drawing or decoding anything new.
        Box(modifier = modifier.fillMaxSize().background(Color.Transparent))
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(Color.Transparent),
    ) {
        val containerWidthDp = maxWidth.value
        val containerHeightDp = maxHeight.value
        val petWidthDp = PetHostDefaultPetWidthDp
        val cellAspect =
            definition.geometry.cellWidth.toFloat() / definition.geometry.cellHeight.toFloat()
        val petHeightDp = if (cellAspect.isFinite() && cellAspect > 0f) {
            petWidthDp / cellAspect
        } else {
            petWidthDp
        }

        val (clampedXDp, clampedYDp) = clampHostPosition(
            state.xDp,
            state.yDp,
            containerWidthDp,
            containerHeightDp,
            petWidthDp,
            petHeightDp,
        )
        val density = LocalDensity.current
        Box(modifier = Modifier.fillMaxSize()) {
            Pet(
                state = player,
                modifier = Modifier
                    .size(petWidthDp.dp, petHeightDp.dp)
                    .offset(clampedXDp.dp, clampedYDp.dp)
                    .pointerInput(containerWidthDp, containerHeightDp, petWidthDp, petHeightDp) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // Only writes on actual pointer movement (no per-frame writes).
                            val dxDp = with(density) { dragAmount.x.toDp().value }
                            val dyDp = with(density) { dragAmount.y.toDp().value }
                            val (nextX, nextY) = clampHostPosition(
                                state.xDp + dxDp,
                                state.yDp + dyDp,
                                containerWidthDp,
                                containerHeightDp,
                                petWidthDp,
                                petHeightDp,
                            )
                            state.moveTo(nextX, nextY)
                        }
                    },
            )
        }
    }
}
