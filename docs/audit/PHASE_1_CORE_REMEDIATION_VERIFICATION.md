# Phase 1 Core Remediation Verification (short, read-only)

- Role: same independent core reviewer as `PHASE_1_CORE_AUDIT.md`. No production code modified. Phase 2 not begun.
- Date (UTC): 2026-09-25 (~17:58 UTC evidence run).
- Inputs read in full: `docs/audit/PHASE_1_CORE_AUDIT.md`, `docs/audit/PHASE_1_REMEDIATION_REPORT.md`.
- Normative upstream (unchanged): `openai/codex @ 55543d87724bb66bdd51bde65254feb9b4c9ed10`.
- Method: source read of all 9 `commonMain` + 7 `commonTest` files, fresh Gradle runs (no `|| true`, no shadow copies), ABI dump grep, independent recomputation of golden values.

## 1. F-01 status — FIXED

- `gradle/libs.versions.toml`: `coil-compose` (`coil` undeclared) and `androidx-lifecycle-runtimeCompose`
  (`androidx-lifecycle` undeclared) entries are **gone**. Every remaining `version.ref`
  (`kotlin`, `kotlinx-serialization`, `composeMultiplatform`, `agp`, `ktor`,
  `vanniktech-maven-publish`) resolves to a declared `[versions]` key. No dangling refs remain.

## 2. F-02 status — FIXED

- `PetParser.kt:284`: `if (spec.fallback.isEmpty()) Idle else spec.fallback` — exact upstream
  semantics, NO trim/isBlank. The only `trim()` in the parser is `spritesheetPath` (`:103`),
  which IS upstream parity. Identity trims are parity-correct per audit §2.
- Test `CustomAnimationsTest.whitespaceFallbackIsLiteralAndFailsExistence` (`:106-118`) proves
  `" "`, `"   "`, `" idle "` each yield exactly one `UnknownFallback` with the literal
  fallback string preserved (`assertEquals(fallback, error.fallback)`); `emptyFallbackDefaultsToIdle`
  (`:100-103`) proves `"" -> idle`; `explicitIdleFallbackAccepted` (`:122-124`) proves
  `"idle" -> idle`. The old wrong test (`blankFallbackNormalizesToIdle`) is gone.

## 3. F-11 regression-test status — ALL PRESENT WITH REAL ASSERTIONS (not mere counts)

- FPS (`CustomAnimationsTest.fpsToDurationNanosTaxonomy:142-159`): direct `fpsToDurationNanos`
  pins NaN→null, ±Inf→null, `Double.MIN_VALUE` 4.9e-324→null, smallest normal
  2.2250738585072014e-308→null, 24→41_666_667, 59.94→16_683_350, 60→16_666_667, 8→125M.
  JSON-level: `nonNumericFpsLiteralIsMalformedJson` (NaN/Infinity/-Infinity→Malformed),
  `overflowingJsonNumberIsMalformed` (1e999→Malformed), `subnormalFpsYieldsNonFiniteDurationAndIsRejected`.
  Parser-level exact nanos: `sixtyFpsIsAcceptedWithExactNanos`, `fractionalFpsPreservesPrecision`.
- Merge: `aliasOverrideReplacesAliasOnly` (move_right override, canonical untouched),
  `reverseOrderForwardFallbackReference` (b→zebra, reverse sorted order),
  `customIdleOverrideStillLeavesCorrectNormalizedIdle` + `customEntryOverridesDefault`.
- Facade: `ManifestParsingTest.spritesheetPathOf...RejectsInvalidUtf8Bytes:138-142`
  (`0xFF 0xFE`→Failure/MalformedManifest); `parse` ByteArray invalid-UTF-8 also pinned (`:106-114`).
- Playback: `saturatedElapsedHasExactDeterministicValues` (`PlaybackTest:212-223`) asserts
  **exact** `Long.MAX_VALUE` goldens — independently recomputed here with Python integer math
  (not the impl): `MAX % 6_600_000_000 = 4_254_775_807 → idle frame 4, next 425_224_193 ns`;
  running prefix `2_460_000_000 + ((MAX-2_460_000_000) % 6_600_000_000) = 4_254_775_807 → frame 1,
  next 545_224_193 ns`. Both match the test literals exactly. Very-large-elapsed exact goldens:
  `farFutureElapsedStaysInLoop` (10s→frame 3, 440M ns), `sixtyFpsHasNoMillisecondDrift`,
  `fractionalFpsLoopsCorrectly` — all exact sprite+next values.
- Defaults: `ManifestParsingTest.emptyManifestUsesAllDefaults:33` asserts literal
  `AtlasGeometry(1536, 1872, 8, 9, 192, 208)`, independent of `CodexV1.defaultGeometry()`.
  Caller-visible `PetDefinition` lookup pinned by `definitionExposesInteropSafeLookup`
  (sorted keys, 14 keys, key+name lookup, null for unknown).

## 4. Public API/ABI status — ALL HOLD

- No public `kotlin.time.Duration`: grep over `commonMain` finds `Duration` only in **internal**
  `CodexV1`/`fpsToDurationNanos` computation. Dumps (`klib` + `jvm` + `android`) contain zero
  `kotlin.time.Duration`/`kotlin/time/Duration` (only allowed substring: `getDurationNanos`).
- `PetFrame.durationNanos: Long` ✓ (klib `:359-360`, jvm `:244`), `samplePetAnimation(..., elapsedNanos: Long)` ✓
  (klib `:469`, jvm `:341`), `PetPlaybackSample.nextFrameInNanos: Long?` ✓ (klib `:393-394`, jvm `:309`).
- Timing semantics preserved by tests with exact assertions: one-hop (`twoHopsRemainOnBDoesNotAdvanceToC`),
  same-clock (`oneHopAtoBEvaluatedAtSameClock`, B at 250 ms not frame 0), exact boundaries
  (`exactFrameBoundaryAdvances`, `oneShotExactlyAtCompletionHops`), prefix/loop
  (`prefixLoopSectionWithNonZeroLoopStart`, `builtInThreeXPrefixSettlesIntoIdleLoop`,
  `regularLoopRestartsCleanly`), 24/59.94/60 precision (FPS + no-drift tests above).
- `PetAnimationKey`: regular immutable class (`PetAnimationKey.kt:14`), value-based
  equals/hashCode, public `init(String)` with no validation (test pins `"   "` accepted).
  No value/inline class in source or dumps (`final class` + `constructor <init>(kotlin/String)`).
- `PetDefinition`: NO public `Map<PetAnimationKey, PetAnimation>` — private `lookup`; public surface is
  `animationKeys: List<PetAnimationKey>` (sorted) + `animation(PetAnimationKey)` + `animation(String)`
  (klib `:337-356`, jvm `:228-240`). Lookup correctness: idle/alias/custom/unknown all tested (§3).
- Totality: `AtlasGeometry`, `PetFrame`, `PetAnimation`, `PetDefinition` ctors are all `internal`
  (no `<init>` for any of them in klib or JVM dumps); `SpritesheetInfo` ctor total by design.
  `NormalizationFailed` exists (`CompatibilityReport.kt:87`) and the parser tail catches
  `IllegalArgumentException` → typed Failure (`PetParser.kt:254-263`); decode/byte boundaries also
  catch IAE. Per task brief, no artificially-triggered unreachable-branch test is required.
- Deviations explicitly labeled (category B, never parity): tiny-FPS `InvalidFps`
  (`CodexV1Profile.kt:108-120` + `PetParser.kt:122-130` Int/UInt taxonomy note),
  sorted multi-error (`PetParser.kt:181-184`), negative clamp + saturation + single-frame
  wake-up + dangling-fallback→idle (`Playback.kt:27-46`), snapshot-isolation wording
  (`PetModel.kt:58-60`), `custom:` Phase 2 decision (`ARCHITECTURE.md` §7.1 area, line ~278).
  Unicode-trim F-12 accepted as-recorded P2 (no action per brief).
- ABI dumps: absent — public Duration, value-class key, public animation Map, raw DTOs
  (`CodexPetManifestDto` etc.), `CodexV1`/`fpsToDurationNanos`/`effectiveSpritesheetPath`/`parseManifest`/
  `sourceRectFor` helper, V2 types, IO/platform/coroutine/compose/okio/ktor symbols.
  Present — exactly the intended contract (keys, frames nanos, lookups, outcomes, validator, identity,
  sampler) + additive `NormalizationFailed`.
- `PetParser.kt:168,175` constructs `AtlasGeometry(...)`/`PetFrame` etc. inside the IAE-caught
  normalization tail — reachable-input safety unchanged (covering-grid + fps-capped pre-validation).

## 5. Fresh Gradle evidence (actual checkout, 2026-09-25 ~17:58 UTC)

- `:codex-pets-core:jvmTest --rerun-tasks` — BUILD SUCCESSFUL; fresh XML:
  Custom 25 + Default 7 + Geometry 10 + Identity 8 + Manifest 18 + Model 11 + Playback 20
  = **99 tests, 0 failures, 0 errors, 0 skipped**.
- `:codex-pets-core:iosSimulatorArm64Test --rerun-tasks` — BUILD SUCCESSFUL; fresh XML:
  **99 tests, 0/0/0** (same per-class split).
- `:codex-pets-core:assemble` — BUILD SUCCESSFUL.
- `:codex-pets-core:checkKotlinAbi` — BUILD SUCCESSFUL (dumps current, intentionally regenerated).
- 82 → 99 tests: +17 net new, none of the 82 deleted (old wrong-behavior fallback test replaced, per mandate).

## 6. Remaining P0/P1/P2

- P0: none. P1: none.
- P2 (documentation cleanup, does not gate): `ARCHITECTURE.md:73` still says io is the
  "both validation layers' enforcement point for runtime rules" while authoring-QA
  layering is deferred — stale wording per task §9; cleanup only.
- P2 accepted/residual (recorded, no action): Unicode-trim F-12; `NormalizationFailed` catch
  defensive-unreachable by construction (declared in KDoc); JVM cast-shallow-copy caveat
  documented as snapshot-isolation; `description_`/`KotlinInt?` Swift cosmetic notes per
  remediation report §10 (Apple-header/Swift-consumer claims in that report §8–9 were
  NOT re-executed here — outside this verification's scope and not required by the brief).

## 7. Final recommendation

**PASS** — F-01 fixed (all four gates green on fresh evidence), F-02 fixed (exact `isEmpty`
semantics with literal-whitespace tests), every F-11 regression test present with inspected
real assertions (including independently recomputed `Long.MAX_VALUE` goldens), public
time migration + key/model API + constructor totality + labeled deviations + ABI surface
all confirmed. Only P2 documentation cleanup remains.
