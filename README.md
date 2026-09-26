# codex-pets-kmp

Unofficial Kotlin Multiplatform library for loading Codex-compatible animated
pets. Pre-release (`0.1.0` development snapshots, not published).

## Current state

- `:codex-pets-core` — pure-Kotlin normalized pet model, CLI V1 compatibility
  profile, validation, and deterministic playback. No I/O, no platform types.
- `:codex-pets-io` — filesystem + ZIP package loading with path confinement,
  symlink policy, resource limits, and header-only image probing. Depends on
  core + Okio only.

```kotlin
when (val outcome = PetLoader.loadPetZip(zipBytes, fallbackId = "bella")) {
    is PetLoadOutcome.Success -> render(outcome.definition, outcome.spritesheetBytes)
    is PetLoadOutcome.Failure -> showErrors(outcome.errors) // typed, never thrown
}
```

Compose rendering and Desktop UI are future phases (Phase 3+) and do
not exist yet. There is deliberately NO network module: transport is
host-owned — codex-pets-kmp does not own transport. Applications obtain
manifest/spritesheet/package bytes through app resources, the filesystem, a
database, Ktor, OkHttp, URLSession, Firebase, GitHub, a custom backend, or any
other source, then pass those bytes/data into the appropriate core/io/compose
API. The library never fetches from the network and never parses URLs.
See `docs/research/CODEX_COMPATIBILITY.md` for the pinned
upstream contract, `docs/architecture/` for design, `docs/audit/` for audit
history, and `SECURITY.md` for security boundaries and reporting.
