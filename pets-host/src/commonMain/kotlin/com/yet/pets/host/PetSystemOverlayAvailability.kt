package com.yet.pets.host

/**
 * Platform capability for [PetHostMode.SystemOverlay].
 *
 * - [Available]: the OS permits a separate overlay surface now.
 * - [PermissionRequired]: the OS permits it after an explicit user grant
 *   (Android `SYSTEM_ALERT_WINDOW` missing). No overlay window is added and
 *   nothing crashes; the host application owns the permission request flow.
 * - [BestEffort]: the overlay is implemented with the normal floating-window
 *   path but the window manager may not guarantee always-on-top/positioning
 *   identically (JVM Linux under some Wayland compositors).
 * - [Unsupported]: no general-purpose cross-app overlay exists (iOS). Never
 *   reported as [Available] on iOS.
 */
public enum class PetSystemOverlayAvailability {
    Available,
    PermissionRequired,
    BestEffort,
    Unsupported,
}
