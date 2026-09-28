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
func controlHostState(_ state: PetHostState, key: PetAnimationKey) -> PetAnimationKey? {
    state.play(animation: key)
    state.moveTo(xDp: 10, yDp: 20)
    return state.requestedAnimation
}

func hostCapability(_ mode: PetHostMode) -> String {
    if mode == PetHostMode.inapp { return "inapp" }
    if mode == PetHostMode.systemoverlay { return "overlay" }
    return "unknown"
}

// Generic Pets KMP v1 consumer: explicit parse entry point, definition-owned
// default, nullable fallback/intent, and generic error inspection.
func loadGenericPet(_ manifest: KotlinByteArray, sheet: KotlinByteArray) -> PetDefinition? {
    let parsed = PetsKmpPackageParser.shared.parse(manifestBytes: manifest, spritesheetBytes: sheet)
    guard let success = parsed as? PetsKmpParseOutcomeSuccess else { return nil }
    return success.definition
}

func loadGenericZip(_ bytes: KotlinByteArray) -> PetLoadOutcome {
    return PetLoader.shared.loadPetsKmpZip(bytes: bytes, limits: PetPackageLimits.companion.Default)
}

func genericDefaultKey(_ definition: PetDefinition) -> PetAnimationKey {
    return definition.defaultAnimationKey
}

func genericAnimationFallback(_ definition: PetDefinition, name: String) -> PetAnimationKey? {
    // Generic one-shots expose fallback == nil (hold final frame, no
    // transition); Codex definitions keep their single-hop fallbacks.
    return definition.animation(key: PetAnimationKey(value: name))?.fallback
}

func hostEffectiveKey(_ state: PetHostState, definition: PetDefinition) -> PetAnimationKey {
    // requestedAnimation is nil until play() expresses explicit intent; the
    // host resolves nil to the definition default without rewriting it.
    let requested: PetAnimationKey? = state.requestedAnimation
    return requested ?? definition.defaultAnimationKey
}

func pinAndResumeHost(_ state: PetHostState) {
    state.pinToDefault()
    state.resume()
}

func inspectGenericError(_ error: PetsKmpError) -> String {
    if let malformed = error as? PetsKmpErrorMalformedManifest { return malformed.message }
    return error.message
}
