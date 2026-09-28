package com.yet.pets.host

import androidx.compose.runtime.Composable

/**
 * Public capability query. Returns the platform's current overlay
 * availability (Android checks `Settings.canDrawOverlays`; JVM maps OS name;
 * iOS always returns [PetSystemOverlayAvailability.Unsupported]).
 */
@Composable
public fun rememberPetSystemOverlayAvailability(): PetSystemOverlayAvailability =
    platformSystemOverlayAvailability()
