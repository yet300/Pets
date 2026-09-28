# codex-pets-kmp

A generic cross-platform animated pet runtime with Codex Pets V1
compatibility. Pre-release (`0.1.0` is not published).

Three different concepts (never blurred):

1. Pets KMP Package Format v1 — project-owned, stable candidate
   (`docs/spec/PETS_KMP_PACKAGE_V1.md`).
2. Codex V1 compatibility — verified against OpenAI public TUI
   (`CodexPetPackageParser`, fixed 1536x1872 geometry, built-in table).
3. Codex Desktop V2 — research only / unsupported
   (`docs/research/CODEX_V2_COMPATIBILITY_RESEARCH.md`).

The original `assets/kodee/pet.json` (`spriteVersionNumber=2`, 1536x2288) is
NOT accepted by the Codex V1 adapter by design, while a SEPARATE generic
manifest (`assets/kodee/pet.pets-kmp.json`) describes the same spritesheet as
a generic pet.

## Modules

- `:codex-pets-core` owns the generic pet runtime (`PetDefinition`,
  `PetAnimationKey`, `PetAnimation`, `PetFrame`, `AtlasGeometry`, playback,
  generic package schema/validation) plus the Codex V1 compatibility adapter
  (manifest interpretation, fixed geometry, built-in table, aliases,
  fallback/loop quirks). It has no filesystem, decoder, UI, or network
  dependency.
- `:codex-pets-io` optionally loads directory and ZIP packages with path,
  archive, and resource checks (shared secure implementation for both Codex
  V1 and generic packages). It delegates image metadata inspection to core.
- `:codex-pets-compose` decodes one atlas in a lifecycle-owned background job
  and draws frame subregions via the generic `Pet` renderer (`CodexPet`
  remains as a thin wrapper). It depends on core, not IO, and never inspects
  package format.
- `:codex-pets-host` (Phase 4: Cross-platform Pet Host) answers where a pet is
  rendered: `PetHostMode.InApp` (supported on all v1 UI targets) vs.
  `PetHostMode.SystemOverlay` (platform capability, not a universal KMP
  guarantee — Android: supported with explicit overlay permission; Desktop:
  supported / Linux best effort; iOS: unsupported). Depends on
  `core <- compose <- host`, never on IO. Host is format-agnostic and uses
  definition default semantics. No network, no foreground service,
  no autonomous behavior.
- `:codex-pets-apple` builds the single supported Swift-facing `CodexPets`
  framework, exporting core, IO, Compose, and Host API types with one identity.

The host owns transport. The library never fetches URLs and includes no
network or image-loading framework.

## Raw manifest and spritesheet bytes

Codex V1:

```kotlin
val parsed = CodexPetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId = "bella")
when (parsed) {
    is PetParseOutcome.Success -> {
        val state = rememberPetPlayerState(parsed.definition, spritesheetBytes)
        Pet(state)
        // On a UI event: state.play(PetAnimations.Waving)
    }
    is PetParseOutcome.Failure -> showErrors(parsed.report.errors)
}
```

Generic Pets KMP v1:

```kotlin
val parsed = PetsKmpPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId = "kodee")
when (parsed) {
    is PetsKmpParseOutcome.Success -> {
        val state = rememberPetPlayerState(parsed.definition, spritesheetBytes)
        Pet(state)
    }
    is PetsKmpParseOutcome.Failure -> showErrors(parsed.report.errors)
}
```

The raw parser checks manifest bytes ≤ 64 KiB and encoded image bytes ≤ 8 MiB,
probes format and dimensions, then validates the definition. Callers do not
construct `SpritesheetInfo` for this path. `parseTrustedMetadata` is an expert
method when a caller already has trusted image facts; its manifest input is
still bounded.

## ZIP or directory packages

```kotlin
when (val outcome = PetLoader.loadPetZip(zipBytes, fallbackId = "bella")) {
    is PetLoadOutcome.Success -> {
        val state = rememberPetPlayerState(outcome.definition, outcome.spritesheetBytes)
        Pet(state)
    }
    is PetLoadOutcome.Failure -> showErrors(outcome.errors)
}
```

Generic: `PetLoader.loadPetsKmpZip` / `loadPetsKmpDirectory`. Codex and
generic loaders are explicitly distinct; no format sniffing.

`PetLoadOutcome.Success` hands its bounded encoded `ByteArray` to the caller.
Keep it unchanged while Compose snapshots it in the background. A new array
instance signals replacement. `PetAtlasState` moves from `Loading` to `Ready`
or `Failed`; Compose checks its own 8 MiB byte cap and requires decoded atlas
dimensions to equal the definition before drawing.

One `PetPlayerState` owns one playback timeline. Multiple `Pet(state)`
renderers show that timeline; use separate states for independent animations.
Call `play`, `pinToDefault`, and `resume` from the Compose/UI thread
(`pinToIdle` remains only as a thin deprecated Codex wrapper).

The v1 atlas is static: PNG, JPEG, GIF first frame, and static WebP VP8,
VP8L, or VP8X are supported. Animated WebP containers are rejected before
decode on every target. This is a deliberate restriction from the pinned Codex
CLI dependency, which accepts animated WebP metadata and opens its first frame.

## Pet hosting

```kotlin
val host = rememberPetHostState()
val availability = rememberPetSystemOverlayAvailability()
PetHost(definition, spritesheetBytes, host, PetHostMode.InApp)
// host.show()/hide()/moveTo()/play()/pinToDefault()/resume()
```

`InApp` is supported on Android/JVM/iOS. Floating outside the app is a
platform capability: Android needs `SYSTEM_ALERT_WINDOW`
(`PermissionRequired` until granted, no silent fallback); Desktop uses a small
transparent undecorated floating window (Linux best effort); iOS reports
`Unsupported` and never converts overlay to in-app.

See [security boundaries](SECURITY.md), [architecture](docs/architecture/ARCHITECTURE.md),
and [audit history](docs/audit/).
