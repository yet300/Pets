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

Network loading, Compose rendering, and Desktop UI are future phases and do
not exist yet. See `docs/research/CODEX_COMPATIBILITY.md` for the pinned
upstream contract, `docs/architecture/` for design, `docs/audit/` for audit
history, and `SECURITY.md` for security boundaries and reporting.
