# Pets KMP

A generic cross-platform animated pet runtime with explicit Codex Pets V1 and V2
compatibility. Pre-release (`0.1.0` is not published).

Three different concepts (never blurred):

1. Pets KMP Package Format v1 — project-owned, stable candidate
   (`docs/spec/PETS_KMP_PACKAGE_V1.md`).
2. Codex V1 compatibility — verified against OpenAI public TUI
   (`CodexPetPackageParser`, fixed 1536x1872 geometry, built-in table).
3. Codex V2 library profile — explicit parser/loader for the first-party
   Work Pets artwork layout; library validation policy, with observed runtime
   behavior documented separately (`docs/spec/CODEX_V2_SUPPORT_DESIGN.md`).

The original `assets/kodee/pet.json` (`spriteVersionNumber=2`, 1536x2288)
is accepted by `CodexV2PetPackageParser`. It remains rejected by the Codex V1
adapter. The separate generic manifest `assets/kodee/pet.pets-kmp.json`
describes the same sheet using the project-owned schema.

## Modules

- `:pets-core` owns the generic pet runtime (`PetDefinition`,
  `PetAnimationKey`, `PetAnimation`, `PetFrame`, `AtlasGeometry`, playback,
  generic package schema/validation) plus the Codex V1 compatibility adapter
  (manifest interpretation, fixed geometry, built-in table, aliases,
  fallback/loop quirks), and the explicit V2 profile (fixed 8x11 geometry and
  sixteen held look poses). It has no filesystem, decoder, UI, or network
  dependency.
- `:pets-io` optionally loads directory and ZIP packages with path,
  archive, and resource checks (shared secure implementation for Codex
  V1, V2, and generic packages). It delegates image metadata inspection to core.
- `:pets-compose` decodes one atlas in a lifecycle-owned background job
  and draws frame subregions via the generic `Pet` renderer (`CodexPet`
  remains as a thin wrapper). It depends on core, not IO, and never inspects
  package format.
- `:pets-host` (Phase 4: Cross-platform Pet Host) answers where a pet is
  rendered: `PetHostMode.InApp` (supported on all v1 UI targets) vs.
  `PetHostMode.SystemOverlay` (platform capability, not a universal KMP
  guarantee — Android: supported with explicit overlay permission; Desktop:
  supported / Linux best effort; iOS: unsupported). Depends on
  `core <- compose <- host`, never on IO. Host is format-agnostic and uses
  definition default semantics. No network, no foreground service,
  no autonomous behavior.
- `:pets-apple` builds the single supported Swift-facing `Pets`
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

Codex V2 (explicit entry point):

```kotlin
when (val parsed = CodexV2PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId = "kodee")) {
    is CodexV2ParseOutcome.Success -> {
        val state = rememberPetPlayerState(parsed.definition, spritesheetBytes)
        Pet(state)
        // Caller supplies screen offsets; null means no look override.
        CodexV2.lookAnimationKeyForOffset(dx, dy)?.let { state.play(it) }
    }
    is CodexV2ParseOutcome.Failure -> showErrors(parsed.report.errors)
}
```

V2 requires the literal JSON integer `spriteVersionNumber: 2` and exactly
1536x2288 pixels: 8 columns, 11 rows, 192x208 cells. Standard animations use
the observed runtime timings; indices 72–87 are held single-frame poses:
`look-000`, `look-022.5`, `look-045`, `look-067.5`, `look-090`, `look-112.5`,
`look-135`, `look-157.5`, `look-180`, `look-202.5`, `look-225`, `look-247.5`,
`look-270`, `look-292.5`, `look-315`, `look-337.5`.
The pure selector uses screen dx right/dy down, nearest clockwise 22.5-degree
sector from up, clockwise ties, and no override for radius <=1 or non-finite
input. Applications own point collection and restoring standard animation;
the library installs no global cursor tracking.

V2 accepts static PNG/WebP, rejects APNG/animated WebP, and retains the
64 KiB manifest/8 MiB encoded image caps. It checks metadata and geometry,
not decoded pixels, alpha, unused-cell transparency, or pose quality.
Unknown members are ignored; `frame` and `animations` are rejected even
when null. These are **library policies**, not an official Desktop-equivalent
validator. The first-party Work Pets 0.1.6 artwork contract specifies geometry
and clockwise pose order (and its own 20 MiB authoring cap); runtime timing
and selection evidence come from the separately recorded Desktop research.
See [implementation evidence](docs/audit/CODEX_V2_SUPPORT_IMPLEMENTATION.md).

Generic Pets KMP v1:

```kotlin
val parsed = PetsKmpPackageParser.parse(manifestBytes, spritesheetBytes)
when (parsed) {
    is PetsKmpParseOutcome.Success -> {
        val state = rememberPetPlayerState(parsed.definition, spritesheetBytes)
        Pet(state)
    }
    is PetsKmpParseOutcome.Failure -> showErrors(parsed.report.errors)
}
```

Generic identity is manifest-owned (`id` is required), so the generic APIs
take no caller fallback id. Generic integer fields use strict JSON syntax
(unquoted `-?(0|[1-9][0-9]*)` tokens), unknown fields are ignored, manifest
keys are trimmed (non-blank, case-sensitive), and generic discovery requires
`pet.json` — see `docs/spec/PETS_KMP_PACKAGE_V1.md`.

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

Codex V1: `PetLoader.loadPetZip` / `loadPetDirectory`.
Codex V2: `PetLoader.loadCodexV2Zip(bytes, fallbackId, limits)` /
`loadCodexV2Directory(path, limits)`; requires `pet.json`. V2 semantic failures
retain `PetLoadError.CodexV2CompatibilityFailure.report`.
Generic: `PetLoader.loadPetsKmpZip` / `loadPetsKmpDirectory`.
All three loaders are explicit; no format sniffing or failure-driven fallback.
V2 reuses the bounded path/ZIP security implementation. Unknown downloaded
`.codex-pet` containers have not been verified by this implementation.

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
val host = rememberPetHostState() // no explicit animation request (null)
val availability = rememberPetSystemOverlayAvailability()
PetHost(definition, spritesheetBytes, host, PetHostMode.InApp)
// host.show()/hide()/moveTo()/play()/pinToDefault()/resume()
```

A null host request follows the bound definition's default animation
(`host.effectiveAnimation(definition)`); rendering never rewrites public
intent. Generic one-shots (`fallback == null`) hold their final frame;
Codex V1 definitions keep their exact single-fallback-hop behavior.

`InApp` is supported on Android/JVM/iOS. Floating outside the app is a
platform capability: Android needs `SYSTEM_ALERT_WINDOW`
(`PermissionRequired` until granted, no silent fallback); Desktop uses a small
transparent undecorated floating window (Linux best effort); iOS reports
`Unsupported` and never converts overlay to in-app.

See [security boundaries](SECURITY.md), [architecture](docs/architecture/ARCHITECTURE.md),
and [audit history](docs/audit/).

## Future publication coordinates

```kotlin
implementation("io.github.yet300.pets:pets-core:<version>")
implementation("io.github.yet300.pets:pets-io:<version>")
implementation("io.github.yet300.pets:pets-compose:<version>")
implementation("io.github.yet300.pets:pets-host:<version>")
```

IO optional:

```kotlin
implementation("io.github.yet300.pets:pets-io:<version>")
```