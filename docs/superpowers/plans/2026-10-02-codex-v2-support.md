# Codex V2 Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development for implementation and two-stage reviews.

**Goal:** Add explicit Codex V2 package support while preserving the existing V1 and generic contracts.
**Architecture:** Add V2 parser/profile and typed reports in core, reuse the existing secure package interpreter seam in IO, then route example V2 imports to the new parser. The same normalized definition drives every renderer.
**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Okio, Compose, Gradle ABI validation.

## Task 1 — Core

Create `pets-core/src/commonMain/kotlin/com/yet/pets/core/CodexV2.kt`, `CodexV2Parser.kt`, `CodexV2Error.kt` and common tests.

- [x] Write failing tests before production code: required literal integer version 2, exact 1536×2288 geometry, metadata/fallback, original Kodee manifest, all standard timings/indices/loops, held look indices 72–87, clockwise nearest-sector helper with <=1 neutral radius/non-finite rejection, invalid/animated/wrong-format images, input limits, forbidden frame/animations overrides. Run `rtk ./gradlew :pets-core:jvmTest` for the red gate.
- [x] Add public `CodexV2`, `CodexV2PetPackageParser`, typed V2 outcome/report/errors. Parser has raw `parse(manifestBytes, spritesheetBytes, fallbackId="pet")`, bounded trusted-metadata and spritesheet-path seams matching existing patterns for IO. Require explicit version2, fixed geometry88, PNG/WebP only, library policy ignores harmless unknown members and rejects frame/animations overrides. Reuse existing internal standard animation table without changing any V1 source. Look keys `look-000` through `look-337.5` hold one frame with positive duration and loopStart0.
- [x] Add optional pure `lookAnimationKeyForOffset(dx: Double, dy: Double): PetAnimationKey?`, documenting screen axes and caller-owned point collection. Test normal frame playback for all look keys. Run JVM/iOS core tests and additive ABI dump/check; commit after self-review.
- [x] Obtain independent spec review, then independent code-quality review; resolve findings before next task.

## Task 2 — IO

Modify `pets-io/.../PetLoader.kt`, `PetLoadError.kt`, and the existing internal package interpreter seam; add common secure loader tests.

- [x] Test V2 directory and ZIP successes (root/nested), typed semantic failure, malicious paths/traversal/duplicates/CRC/limits using current test infrastructure. Observe failing tests first.
- [x] Add explicit `loadCodexV2Directory(path, limits)` and `loadCodexV2Zip(bytes, fallbackId, limits)`, plus typed V2 compatibility error. Reuse transport implementation unchanged; no alternate archive code or `.codex-pet` claim.
- [x] Run IO JVM/Native suites, dump/check additive ABI. Commit after self-review, then obtain independent spec/quality reviews.

## Task 3 — Example, docs and acceptance

Modify shared `ImportedPet.kt` and shared import/gallery tests; update README/API compatibility docs and create `docs/audit/CODEX_V2_SUPPORT_IMPLEMENTATION.md`.

- [x] Add failing shared tests: exact original Kodee pair imports and retains all sixteen look keys; preserve V1/generic tests.
- [x] Route explicit V2 to new parser in the existing single preview/Add router. Retain concise errors and V2 parser reports. Use existing gallery/player/host and action-key chips; no platform-global cursor tracking.
- [x] Run JVM and Native core/IO/example suites, ABI checks, desktop distributable and Android builds. Build iOS sequentially after Gradle.
- [x] Verify actual desktop/Android V2 imports/actions/look poses/floating host where accessible and distinguish direct tool evidence from user confirmation. Report exact commits and unavailable checks. No publication/tagging.
- [x] Obtain independent spec/quality review; integrate verified work into original checkout. Preserve pre-existing untracked reports.
