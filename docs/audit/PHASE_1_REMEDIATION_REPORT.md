# Phase 1 Remediation Report — `:codex-pets-core`

- Scope: Phase 1 only. No io/ZIP/Ktor/Compose/decoding/Desktop work started.
- Inputs: `docs/audit/PHASE_1_CORE_AUDIT.md`, `docs/audit/PHASE_1_APPLE_INTEROP_AUDIT.md`
  (both read in full; no finding dismissed silently), pinned upstream
  `openai/codex @ 55543d8` (unchanged; research docs untouched).
- Pre-release ABI: previous dumps were NOT frozen commitments; the surface below
  intentionally breaks the old one to remove proven P0 defects.

## 1. Exact files changed

Build/config:

- `gradle/libs.versions.toml` — removed dangling `androidx-lifecycle-runtimeCompose`
  (`androidx-lifecycle` undeclared) and `coil-compose` (`coil` undeclared); kept
  otherwise intact (remaining compose/ktor entries are non-dangling future-phase
  reservations).
- `.github/workflows/gradle.yml` — unchanged in this pass (already
  jvmTest/iosSimulatorArm64Test/assemble/checkKotlinAbi, no linuxX64 leg).

`commonMain` (package `com.yet.pets.core`):

- `PetAnimationKey.kt` — value class → regular immutable class.
- `PetModel.kt` — `durationNanos: Long`; internal ctors for
  `PetFrame`/`PetAnimation`/`PetDefinition`; `PetDefinition` map replaced by
  private lookup + `animationKeys`/`animation()`; `SpritesheetInfo` ctor made total.
- `Geometry.kt` — `AtlasGeometry` internal ctor.
- `Playback.kt` — `elapsedNanos: Long`, `nextFrameInNanos: Long?`, same algorithms.
- `CodexV1Profile.kt` — nanos tables + internal `fpsToDurationNanos()`.
- `PetParser.kt` — exact `isEmpty()` fallback rule, nanos conversion, IAE totality
  catch, taxonomy/hardening KDoc.
- `CompatibilityReport.kt` — added `NormalizationFailed`.
- `ManifestDto.kt`, `Identity.kt` — unchanged.

`commonTest`: `CustomAnimationsTest`, `PlaybackTest`, `ModelInvariantsTest`,
`ManifestParsingTest` rewritten/extended; `GeometryTest`, `IdentityTest`,
`DefaultAnimationsTest` extended. 82 → 99 tests.

Docs: `ARCHITECTURE.md`, `PUBLIC_API_PROPOSAL.md`, `TEST_STRATEGY.md` carry the
**Phase 1 Apple Interop Amendment** + `custom:` Phase 2 decision item (§7.1).
`codex-pets-core/api/` dumps intentionally regenerated.

## 2. Public API before → after

| Before (broken) | After |
|---|---|
| `PetFrame(spriteIndex, duration: Duration)` — exported packed `rawValue` | `PetFrame(spriteIndex, durationNanos: Long)` (internal ctor) |
| `samplePetAnimation(def, key, elapsed: Duration)` — 2×/unit-flip misread | `samplePetAnimation(def, key, elapsedNanos: Long)` — literal ns |
| `nextFrameIn: Duration?` — opaque box | `nextFrameInNanos: Long?` — Swift `Int64?`, exact |
| `@JvmInline value class PetAnimationKey` — erased to `id`, unconstructible | `class PetAnimationKey(val value: String)` — typed, `init(value:)`, value equality |
| `PetDefinition.animations: Map<…>` — asymmetric box/NSString bridge, SIGABRT rebuilds | `animationKeys: List<PetAnimationKey>` (sorted) + `animation(key)`/`animation(name)` |
| Public throwing ctors (`AtlasGeometry`, `PetFrame`, `PetAnimation`, `PetDefinition`) | Internal; `SpritesheetInfo` ctor total; foreign data → typed outcomes only |

No public `Duration`, no value class, no public map, no raw DTOs, no V2, no
platform types, no accidentally-public helpers (verified in dumps).

## 3. Findings remediated by audit ID

- Core F-01 / Apple P2-3 (P0): catalog fixed; fresh `jvmTest` 99/99,
  `checkKotlinAbi` green — no stale XML relied upon.
- Core F-02 (P1): fallback is now exact `isEmpty()`; `" "`, `"   "`, `" idle "`
  → literal → `UnknownFallback`; old wrong test replaced.
- Apple P0-1: public Duration eliminated (nanos `Long`, units in names).
- Apple P0-2/P1-2: key is a real class; map gone; keys typed end-to-end.
- Apple P0-3: invariant ctors internal; negative Swift compile test proves
  `AtlasGeometry`/`PetFrame` unconstructible from Swift; invalid data → `Failure`.
- Apple P1-1: `nextFrameInNanos` readable, exact values proven from Swift.
- Core F-06/F-07/F-12 + task §9 A–E: hardenings kept and labeled (tiny-FPS
  `InvalidFps`, sorted multi-errors, negative-clamp, single-frame wake-up,
  dangling-fallback→idle) in KDoc and docs, never called parity.
- Core F-10: IAE catch → `NormalizationFailed` at the parser boundary.
- Core F-03/§11: Int DTOs kept; taxonomy deviation documented in code + pinned
  by tests (negatives→semantic errors; >Int.MAX_VALUE→`MalformedManifest`).
- Core F-04/§12: `custom:` recorded as Phase 2 decision item (`ARCHITECTURE.md`
  §7.1); no `CODEX_HOME` behavior added.
- Core F-09/§13: snapshot-isolation wording (no cast-proof claims).
- Core F-11/§14: all missing tests added — `fpsToDurationNanos` direct taxonomy
  (NaN/±Inf/smallest-normal/subnormal/24/59.94/60), JSON NaN/Inf literals, alias
  override, reverse forward-ref, custom-idle normalization, invalid-UTF-8
  `spritesheetPathOf`, exact `Long.MAX_VALUE` goldens (independently computed),
  literal-geometry manifest test, interop lookup tests.
- Core F-05/F-08: documented/no-action as directed.

## 4. Intentional deviations retained (labeled, not parity)

tiny-FPS `InvalidFps` (upstream panics); sorted multi-error collection (upstream
single nondeterministic bail); negative→0 clamp (impossible upstream);
single-frame wake-up (TUI quirk not copied); dangling-fallback→idle (unreachable
via parser); Int-vs-unsigned error taxonomy (same accept/reject); ASCII `trim()`
(F-12, negligible).

## 5–6. Fresh test counts + Gradle results (actual checkout, no `|| true`)

- `:codex-pets-core:jvmTest` — **99 tests, 0 failures/errors/skips** (fresh XML).
- `:codex-pets-core:iosSimulatorArm64Test` — **99, 0/0/0** (fresh XML).
- `:codex-pets-core:assemble` — BUILD SUCCESSFUL.
- `:codex-pets-core:checkKotlinAbi` — BUILD SUCCESSFUL (dumps intentionally updated).
- `explicitApi` enforced at compile; dependency graph = stdlib + serialization-json.

## 7. New ABI dump review

klib + JVM + Android dumps inspected: `final class PetAnimationKey` with public
`init(String)`; `durationNanos`/`elapsedNanos`/`nextFrameInNanos` as
`Long`/`Long?`; `animationKeys: List<PetAnimationKey>` + two `animation()`
overloads; **zero** `kotlin.time.Duration`, zero value classes, zero public
`Map<PetAnimationKey,…>`, zero DTO/CodexV1/probe symbols, zero public ctors on
`PetDefinition`/`PetAnimation`/`PetFrame`/`AtlasGeometry`, plus additive-only
`NormalizationFailed`. (`Duration` grep hits are the substring in
`getDurationNanos` only.)

## 8. Generated Apple header excerpts (fresh release framework, clean worktree)

```objc
@interface CPCPetAnimationKey : CPCBase
- (instancetype)initWithValue:(NSString *)value ...;
@property (readonly) NSString *value ...;
+ (CPCPetPlaybackSample *)samplePetAnimationDefinition:(CPCPetDefinition *)definition
    requestedAnimation:(CPCPetAnimationKey *)requestedAnimation
    elapsedNanos:(int64_t)elapsedNanos ...;
@property (readonly) NSArray<CPCPetAnimationKey *> *animationKeys ...;
- (instancetype)initWithSpriteIndex:(int32_t)spriteIndex durationNanos:(int64_t)durationNanos ...;
@property (readonly) int64_t durationNanos ...;
@property (readonly) CPCLong * _Nullable nextFrameInNanos ...;
```

No `duration:(int64_t)` raw, no `requestedAnimation:(id)`, no
`NSDictionary<id, …>`. Fail-equivalents absent.

## 9. Swift consumer source + result

`/tmp/pets-swift-remediation/consumer.swift` (28 checks, A–G): typed key
construction/read, built-in equality, custom `dance` parse→enumerate→lookup→sample
exact (`@750ms` → sprite 1, next 250M ns), literal-ns sweep (1ns→`1_679_999_999`,
1s→sprite 0/next 680M ns), `nextFrameInNanos` as Swift `Int64` exact, sealed
outcomes navigable, invalid packages → `Failure`. **Compiled clean, ran on booted
iPhone 17 simulator: 28/28 PASS, exit 0.** Type probe: `nextFrameInNanos`
bridges to `Int64?` directly (`KotlinLong.int64Value` identical). Negative
compile test confirms invariant ctors unreachable from Swift.

## 10. Remaining items

- P0: none. - P1: none (former key/Duration/construction blockers closed by proof).
- P2: (a) `description` property surfaces as `description_` (NSObject clash,
  cosmetic); (b) `loopStart` is boxed `KotlinInt?` (usable); (c) F-12 Unicode
  trim deviation (negligible, recorded); (d) `NormalizationFailed` catch is
  defensive-unreachable (no trigger test possible by construction — declared,
  not hidden); (e) `checkLegacyAbi`/`updateLegacyAbi` tasks exist but are not
  part of the gate.

## 11. Gate recommendation

**PASS.** Every remediation-gate condition holds by fresh evidence: clean-checkout
builds, exact fallback whitespace, zero public Duration, natural Swift keys with
working custom names, no public animation map, literal-nanos semantics both
directions, no Swift SIGABRT path, typed/total parser boundary, labeled
hardenings, all audit-mandated tests added, 99/99 JVM + iOS-sim, assemble green,
intentionally updated ABI dumps checked, header + simulator consumer proven.
Phase 2 must not begin until approved.
