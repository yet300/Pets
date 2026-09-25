# Phase 1 Apple Remediation Verification (`:codex-pets-core`)

Mode: short read-only verification. No production Kotlin code modified. No Phase 2 begun.
Inputs read: `docs/audit/PHASE_1_APPLE_INTEROP_AUDIT.md`, `docs/audit/PHASE_1_REMEDIATION_REPORT.md`.

## 1. Generated signature summary (fresh framework from real checkout)

- Build: `./gradlew :codex-pets-core:linkReleaseFrameworkIosSimulatorArm64` from the actual
  repository checkout (no shadow copy, no stale prebuilt). Exit 0.
- Framework: `codex-pets-core/build/bin/iosSimulatorArm64/releaseFramework/CodexPetsCore.framework`
- Header: `.../CodexPetsCore.framework/Headers/CodexPetsCore.h`, 1028 lines,
  SHA-256 `2ee2d571ca838a9ae84a4c555c5c9394a0cc7f9e2c2bf4b92c28e66a726a2b04`.
- Public time concepts (literal nanoseconds, units in names):
  - `CPCPetFrame.durationNanos: int64_t` (readonly; no `duration:(int64_t)` raw remains)
  - `+samplePetAnimationDefinition:requestedAnimation:(CPCPetAnimationKey *)elapsedNanos:(int64_t)`
    (Swift `PlaybackKt.samplePetAnimation(definition:requestedAnimation:elapsedNanos:)`)
  - `CPCPetPlaybackSample.nextFrameInNanos: CPCLong * _Nullable` (Kotlin `Long?` normal boxed
    form; Swift consumes via `KotlinLong.int64Value`; no packed-Duration semantics)
- Key type (real, exported): `CPCPetAnimationKey : CPCBase` with
  `initWithValue:(NSString *)` (Swift `PetAnimationKey(value:)`), readonly `value: NSString *`,
  value equality (`isEqual`/`hash`); 25 header mentions (was 0). All 14 `PetAnimations`
  constants typed `CPCPetAnimationKey *` (were `id`).
- Definition surface: `animationKeys: NSArray<CPCPetAnimationKey *> *` (sorted),
  `-animationKey:(CPCPetAnimationKey *)` (Swift `animation(key:)`),
  `-animationName:(NSString *)` (Swift `animation(name:)`). No public animation map.
- Invariant-heavy constructors not visible: `CPCAtlasGeometry`, `CPCPetFrame`,
  `CPCPetAnimation`, `CPCPetDefinition` expose no `init...` in their `@interface` blocks
  (only `isEqual`/`hash`/`description`/readonly props plus typed lookup methods).
- Header fail-pattern search: `requestedAnimation:(id)` absent; `NSDictionary<id, CPCPetAnimation *>`
  absent (`NSDictionary` import line only); `nextFrameIn:(id` absent; `duration:(int64_t)`
  absent; `KotlinDuration` absent. No public time member carries packed-Duration semantics.

## 2. Swift compile/runtime evidence

- Consumer: external `/tmp/pets-verify-20260925/consumer.swift` (temp dir, outside prod sources),
  compiled `xcrun swiftc -sdk iphonesimulator -target arm64-apple-ios27.0-simulator
  -F <fresh releaseFramework> -framework CodexPetsCore` — clean (exit 0; only benign
  sysroot-target warning). Ran on booted simulator `iPhone 17, iOS 27.0` via
  `xcrun simctl spawn booted` with `@executable_path` rpath to the fresh framework copy.
- Result: **33/33 checks PASS, exit 0**, covering: custom typed key, built-in typed key,
  custom key lookup (`animation(key:)` + `animation(name:)`), custom animation playback
  (`dance` @750ms → sprite 2, next 250M ns), literal-nanos sweep (0/1/1M/500M/1B/1680M),
  `nextFrameInNanos` exact numeric output, Success outcome, Failure outcome, no crash.
- Negative compile test: `PetFrame(spriteIndex:durationNanos:)` → Swift typecheck error
  `argument passed to call that takes no arguments` (exit 1), proving the invariant ctor
  is unreachable from Swift.

## 3. Old P0-1 status — GONE

Duration packing is gone from public API. Literal-nanos proof on idle (frame 0 = 1680ms):
`0 → (0, 1680000000)`; `1 → (0, 1679999999)` (1 = 1ns); `1000000 → (0, 1679000000)`
(1M = 1ms); `500000000 → (0, 1180000000)`; `1000000000 → (0, 680000000)` (1B = 1s);
`1680000000 → (1, 660000000)` exact frame advance. No 2× doubling, no odd-value unit flip.

## 4. Old P0-2 status — GONE

`PetAnimationKey` is a real exported type. Swift compiled and ran:
`let dance = PetAnimationKey(value: "dance"); assert(dance.value == "dance")`,
built-in `PetAnimations.shared.Idle.value == "idle"`, value equality both directions,
typed `animationKeys` enumeration with `key.value` reads, no `Any`/`id` casts in normal use.

## 5. Old P0-3 status — GONE

The old `PetDefinition(animations: ["idle": ...])` → `require` → uncaught exception →
SIGABRT path no longer exists: the constructor is internal (no ObjC init), the public
map is removed, and invalid manifest input (`frames: []`, `frames: [9999]`) returns typed
`PetParseOutcomeFailure` with `report.errors[0]` of type
`CPCPetCompatibilityErrorEmptyAnimationFrames` etc. Process survived all invalid inputs.

## 6. Old P1-1/P1-2 status — GONE

- P1-1: `nextFrameInNanos` read as optional numeric (`KotlinLong.int64Value`), exact values
  asserted (`1680000000`, `1679999999`, `250000000`, …). No `Any?`, no opaque Duration box,
  no string-description workaround.
- P1-2: map asymmetry gone with the map; keys have one typed identity end-to-end
  (direct members and collection positions both `CPCPetAnimationKey`).

## 7. Remaining P0/P1/P2

- P0: none. P1: none.
- P2 (cosmetic/accepted): (a) `description` surfaces as `description_` (NSObject clash);
  (b) `loopStart` is boxed `KotlinInt?` (usable); (c) F-12 ASCII-trim deviation (recorded);
  (d) `NormalizationFailed` defensive-unreachable catch (declared, not hidden);
  (e) `checkLegacyAbi`/`updateLegacyAbi` tasks exist outside the gate. Sealed outcomes
  reconfirmed unregressed (`as? PetParseOutcomeSuccess/Failure`, `definition`/`report`,
  typed `errors` incl. downcast to `EmptyAnimationFrames`).

## 8. Final gate

**PASS** — all previously demonstrated Apple P0/P1 failures are gone on a fresh
framework built from the real checkout, proven by header inspection plus a 33-check
Swift simulator run with exit 0. Phase 2 not begun.
