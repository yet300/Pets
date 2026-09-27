package com.yet.pets.host

import androidx.compose.runtime.Composable
import com.yet.pets.core.PetDefinition

/**
 * Small internal expect/actual seam for system-overlay hosting.
 *
 * The public API ([PetHostMode], [PetSystemOverlayAvailability], [PetHostState],
 * [PetHost]) lives in common. Platform source sets contain only unavoidable OS
 * integration behind this seam. Platform implementations stay internal and
 * never leak `WindowManager`, `Context`, `UIWindow`, `NSWindow`,
 * `java.awt.Window`, `CoroutineScope`, or bitmap types into the public ABI.
 */
@Composable
internal expect fun platformSystemOverlayAvailability(): PetSystemOverlayAvailability

/**
 * Renders [PetHostMode.SystemOverlay] for the current platform.
 *
 * Contract: never silently converts to in-app. When the platform reports
 * anything other than [PetSystemOverlayAvailability.Available] or
 * [PetSystemOverlayAvailability.BestEffort], renders nothing (no window, no
 * crash). Hiding/disposal releases the platform surface exactly once.
 */
@Composable
internal expect fun PlatformPetOverlayHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
)


