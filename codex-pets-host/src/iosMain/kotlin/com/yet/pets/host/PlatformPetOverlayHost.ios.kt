package com.yet.pets.host

import androidx.compose.runtime.Composable
import com.yet.pets.core.PetDefinition

/**
 * iOS has no general-purpose API equivalent to Android's
 * `TYPE_APPLICATION_OVERLAY`: a `UIWindow` belongs to the app's
 * `UIWindowScene` and cannot float over other apps. Picture-in-Picture, Live
 * Activities, Dynamic Island, notification UI, and accessibility APIs are
 * deliberately NOT abused to simulate a cross-app overlay.
 *
 * InApp is supported via common code; SystemOverlay is honestly Unsupported.
 */
@Composable
internal actual fun platformSystemOverlayAvailability(): PetSystemOverlayAvailability =
    iosStaticOverlayAvailability()

/**
 * iOS SystemOverlay renders nothing: no hidden UIKit window pretending to be
 * a system-wide overlay. A second `UIWindow` inside our own scene is not
 * equivalent to an Android system overlay and is not presented as one.
 */
@Composable
internal actual fun PlatformPetOverlayHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
) {
    // Intentionally empty: Unsupported is signaled via availability, and the
    // host must not silently convert to InApp.
}
