import CodexPets

// Compiled outside Kotlin/Native: one IO result definition is directly usable
// by the core sampler and Compose player state surface in this single module.
func loadAndSample(_ bytes: KotlinByteArray) -> PetPlaybackSample? {
    let result = PetLoader.shared.loadPetZip(
        bytes: bytes,
        fallbackId: "pet",
        limits: PetPackageLimits.companion.Default
    )
    guard let success = result as? PetLoadOutcomeSuccess else { return nil }
    let definition: PetDefinition = success.definition
    let key = PetAnimationKey(value: "idle")
    return PlaybackKt.samplePetAnimation(
        definition: definition,
        requestedAnimation: key,
        elapsedNanos: 0
    )
}

func controlComposeState(_ state: PetPlayerState, key: PetAnimationKey) -> PetPlaybackSample {
    state.play(key: key)
    return state.currentSample
}

// Host API flows through the same umbrella (no separate CodexPetsHost
// framework): one PetDefinition identity, host intent, and overlay capability.
func controlHostState(_ state: PetHostState, key: PetAnimationKey) -> PetAnimationKey {
    state.play(animation: key)
    state.moveTo(xDp: 10, yDp: 20)
    return state.requestedAnimation
}

func hostCapability(_ mode: PetHostMode) -> String {
    if mode == PetHostMode.inapp { return "inapp" }
    if mode == PetHostMode.systemoverlay { return "overlay" }
    return "unknown"
}
