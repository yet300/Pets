package com.yet.pets.host

/** Actual system overlay resource state, separate from [PetHostState.isVisible] intent. */
public enum class PetHostPlatformState {
    Hidden,
    Showing,
    PermissionRequired,
    PlatformRejected,
    Unsupported,
}
