# Codex V1 regression fixture

This is a deterministic local test package using the verified Codex V1 contract, not a downloaded Codex pet or a V2 conversion. `pet.json` uses the existing map-based custom-animation contract exercised by `pets-core`'s `CodexLockTest`: integer frame indices, fps, loop and fallback. Identity is deliberately absent to test caller fallback identity. The fully encoded PNG has 1536×1872 RGBA pixels (8×9 cells of 192×208), with distinguishable colored circles in each cell for actual playback verification.

The common shared tests embed the exact JSON and PNG bytes in `ImportFixtures.kt`, so JVM, Android and Native tests require no filesystem-specific fixture loader. The import regression first calls `CodexPetPackageParser.parse` directly on those same bytes and asserts success. The fixture must never be described as a pet downloaded from codex-pets.net or as an upstream-provided artwork fixture.
