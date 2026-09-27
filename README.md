# codex-pets-kmp

Unofficial Kotlin Multiplatform library for Codex-compatible animated pets.
Pre-release (`0.1.0` is not published).

## Modules

- `:codex-pets-core` parses bounded raw manifest/image pairs, inspects static
  encoded image metadata, validates CLI V1 geometry, and samples playback.
  It has no filesystem, decoder, UI, or network dependency.
- `:codex-pets-io` optionally loads directory and ZIP packages with path,
  archive, and resource checks. It delegates image metadata inspection to core.
- `:codex-pets-compose` decodes one atlas in a lifecycle-owned background job
  and draws frame subregions. It depends on core, not IO.
- `:codex-pets-apple` builds the single supported Swift-facing `CodexPets`
  framework, exporting core, IO, and Compose API types with one identity.

The host owns transport. The library never fetches URLs and includes no
network or image-loading framework.

## Raw manifest and spritesheet bytes

```kotlin
val parsed = PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId = "bella")
when (parsed) {
    is PetParseOutcome.Success -> {
        val state = rememberPetPlayerState(parsed.definition, spritesheetBytes)
        CodexPet(state)
        // On a UI event: state.play(PetAnimations.Waving)
    }
    is PetParseOutcome.Failure -> showErrors(parsed.report.errors)
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
        CodexPet(state)
    }
    is PetLoadOutcome.Failure -> showErrors(outcome.errors)
}
```

`PetLoadOutcome.Success` hands its bounded encoded `ByteArray` to the caller.
Keep it unchanged while Compose snapshots it in the background. A new array
instance signals replacement. `PetAtlasState` moves from `Loading` to `Ready`
or `Failed`; Compose checks its own 8 MiB byte cap and requires decoded atlas
dimensions to equal the definition before drawing.

One `PetPlayerState` owns one playback timeline. Multiple `CodexPet(state)`
renderers show that timeline; use separate states for independent animations.
Call `play`, `pinToIdle`, and `resume` from the Compose/UI thread.

The v1 atlas is static: PNG, JPEG, GIF first frame, and static WebP VP8,
VP8L, or VP8X are supported. Animated WebP containers are rejected before
decode on every target. This is a deliberate restriction from the pinned Codex
CLI dependency, which accepts animated WebP metadata and opens its first frame.

See [security boundaries](SECURITY.md), [architecture](docs/architecture/ARCHITECTURE.md),
and [audit history](docs/audit/).
