package com.yet.pets.host

/**
 * Static iOS availability value, separated from the composable seam so
 * `iosTest` can pin it without a composition.
 */
internal fun iosStaticOverlayAvailability(): PetSystemOverlayAvailability =
    PetSystemOverlayAvailability.Unsupported
