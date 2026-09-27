package com.yet.pets.host

/**
 * Where a pet is rendered.
 *
 * [InApp] lives inside the application's current UI surface (supported on
 * all v1 UI targets). [SystemOverlay] lives in a separate system/window-manager
 * surface where the OS permits it. A [SystemOverlay] request is never silently
 * converted into [InApp]: check [rememberPetSystemOverlayAvailability] and
 * branch explicitly.
 */
public enum class PetHostMode {
    /** Pet lives inside the application's current UI surface. */
    InApp,

    /** Pet lives in a separate system/window-manager surface where permitted. */
    SystemOverlay,
}
