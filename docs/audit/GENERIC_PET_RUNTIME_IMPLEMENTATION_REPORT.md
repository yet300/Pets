# Generic Pet Runtime — Implementation Report

## 1. Starting HEAD/tag

- Base (clean `main` HEAD, NOT `phase-4-host`): `c86830ca01f8a59f847693fcac6c2360ee103480`
  (`docs: record Kodee compatibility research`).
- `phase-4-host`: `b6685b0b5f8a09e625b57a75cfc317262c41ac0a` (exists, never moved).
- Prior inputs on base: `1531073 feat(assets): add Kodee custom pet package`
  plus the research/audit docs commit. The unfinished example prototype was
  preserved on `wip/tamagotchi-example` before this work started.

## 2. Commits

| # | SHA (short) | Message |
|---|---|---|
| 1 | `9821f05` | `refactor(core): extract generic pet runtime semantics` |
| 2 | `954e890` | `feat(core): add Pets KMP package format v1` |
| 3 | `7385d0c` | `feat(io): load generic pet packages through secure loader` |
| 4 | `f6c8cac` | `refactor(compose,host): expose format-agnostic pet renderer` |
| 5 | `045e262` | `test: add generic and Kodee package regressions` |
| 6 | (this) | `docs: define Pets KMP package v1` (spec, README, ARCHITECTURE, ABI dumps, this report) |

No Phase 1–4 tags/history rewritten. No new phase tag created.

## 3. Architecture before/after

Before: the runtime was hard-coded around Codex — `PetDefinition` required
`idle`, playback/pin resolved to `Idle`, `CodexPet` was the only renderer,
host pinned to idle, and the only parser/loader was Codex-named.

After:

```text
generic pet runtime (core: PetDefinition, PetAnimationKey, PetAnimation,
PetFrame, AtlasGeometry, playback, generic schema/validation)
    ^
    |
Codex V1 compatibility adapter (manifest interpretation, fixed 1536x1872
8x9 geometry, built-in table, aliases, fallback/loop quirks)
```

Compose owns generic spritesheet decoding/rendering (`Pet`); host owns
generic in-app/system-overlay hosting. Neither inspects schema, Codex
version, package format, or animation names.

## 4. Generic model changes

- `PetAnimationKey`: regular class (not enum/value class); arbitrary valid
  keys supported; added `MAX_KEY_LENGTH = 64`, non-blank enforcement,
  deterministic equality/hashCode, no platform-dependent behavior. Codex
  whitespace-fallback semantics preserved via parser-level `UnknownFallback`
  mapping (no exception leakage).
- `PetDefinition`: added `defaultAnimationKey` (defaults to `Idle` for
  source compatibility); removed the hard `idle` presence requirement;
  requires the default key to exist; `equals`/`hashCode`/`toString` include
  it. `animation(name)` returns null (not throw) for invalid names.
- `PetAnimation.fallback` retained as internal compatibility mechanics for
  Codex; generic v1 does not expose a JSON fallback field.
- `AtlasGeometry`: added exact-cover requires (`columns*cellWidth ==
  atlasWidth`, `rows*cellHeight == atlasHeight`) with overflow-safe math;
  arbitrary rectangular atlases supported; Codex adapter still enforces its
  exact constants.

## 5. Codex adapter boundary

- `CodexV1` internal profile unchanged (1536x1872, 192x208, 8x9, rows,
  counts, timings, 3x-plus-idle chain, aliases).
- `PetPackageParser` documented as the Codex V1 adapter; sets
  `defaultAnimationKey = idle`; preserves strict validation, image handling,
  custom-animation merge, fallback/loop/one-hop behavior.
- New explicit `CodexPetPackageParser` delegates to the same Codex semantics
  (explicit entry point; historic name preserved for compatibility).
- `PetDefinition` carries no `isCodex`/`codexVersion`/format tags.

## 6. Generic package schema

Pets KMP Package Format v1 (`docs/spec/PETS_KMP_PACKAGE_V1.md`): `schema =
"pets-kmp"`, `schemaVersion = 1`, required `id`/`displayName`, optional
`description`, optional `spritesheetPath` (defaults to `spritesheet.webp`
with path confinement), required `frame.width`/`frame.height` positive ints,
required `defaultAnimation` referencing an existing animation, required
non-empty unique `animations`. Frames carry integer `index`/`durationMs`;
`loopStart` null = one-shot hold, else `0 <= N < frames.size`. No generic
`fallback` field. DTOs internal; public model independent of JSON.

## 7. Generic parser

`PetsKmpPackageParser` (core): enforces manifest (64 KiB) and spritesheet
(8 MiB) caps, probes image via the authoritative core probe, parses the
generic manifest, derives geometry from actual atlas dims + cell size,
validates divisibility/capacity/animations/default/indices, converts
`durationMs` to nanos with checked arithmetic (rejects zero/negative/
overflow), models one-shot hold with self-fallback, produces ordinary
`PetDefinition`, returns typed `PetsKmpParseOutcome` failures. No exception
leakage; never infers generic format from Codex failure.

## 8. Generic IO path

- Secure ZIP/directory implementation shared, not duplicated:
  `readSharedZipPackage`/`readSharedZipAsset` and
  `readSharedDirectoryPackage`/`readSharedDirectoryAsset` are the single
  canonical implementations (traversal, symlink confinement, CRC, exact
  DEFLATE consumption, overlap, limits, duplicates).
- Explicit `PetLoader.loadPetsKmpZip` / `loadPetsKmpDirectory`; existing
  Codex loaders unchanged; no sniffing.
- New `PetLoadError.PetsKmpCompatibilityFailure` (distinct message from the
  Codex case).

## 9. Renderer changes

- New generic `Pet(state, modifier)`; `CodexPet` remains as a thin wrapper
  delegating to the same implementation (no duplication).
- `PetPlayerState`: generic (no format inspection); `initialAnimation`
  nullable defaulting to `definition.defaultAnimationKey`;
  `pinToDefault()` added; `pinToIdle()` retained only as a deprecated thin
  wrapper; `refreshPinned` uses `staticDefaultSpriteIndex` + default key;
  `resume` restores the most recently requested animation.
- Host production code (common + Android + JVM overlay) uses `Pet` and
  `pinToDefault`.

## 10. Host/default-animation changes

- `PetHostState`: added `pinToDefault()`; `pinToIdle()` deprecated wrapper.
- `PetHost`/`PetInAppHost`/platform overlays: format-agnostic; pin flows to
  `player.pinToDefault()`; no Codex animation assumptions; verified overlay
  lifecycle untouched.

## 11. Public ABI diff

Intentional pre-0.1.0 additions/changes (ABI dumps updated, `checkKotlinAbi`
green):

- `PetDefinition.getDefaultAnimationKey`; `PetAnimationKey` companion +
  `MAX_KEY_LENGTH`; `staticDefaultSpriteIndex`; `CodexPetPackageParser`;
  `PetsKmpError*` + `PetsKmpReport` + `PetsKmpParseOutcome*` +
  `PetsKmpSpritesheetPathOutcome*` + `PetsKmpPackageParser` (+ constants);
  `Pet(...)` composable; `PetPlayerState.pinToDefault` (+ deprecated
  `pinToIdle` retained); `PetHostState.pinToDefault` (+ deprecated
  `pinToIdle` retained); `PetLoader.loadPetsKmpZip/loadPetsKmpDirectory`;
  `PetLoadError.PetsKmpCompatibilityFailure`.
- Behavior tightening: `PetAnimationKey` rejects blank/overlong;
  `AtlasGeometry` requires exact cover; `PetDefinition` requires default
  presence (not idle presence).
- NOT exposed: DTOs (internal), `JsonElement`, `Map`, Okio, `ImageBitmap`,
  platform image types, parser internals (`parseManifest`,
  `effectiveSpritesheetPath` internal), ZIP internals (internal).

## 12. Arbitrary-key proof

`PetsKmpParserTest.arbitraryValidGridSucceeds` + `arbitraryKeysLoopingAndOneShotHold`
(blink/dance), `GenericDefaultTest` (stand/walk), `GenericPlayerStateTest`,
`PetHostGenericTest` (stand/dance, no `idle`): arbitrary keys work with no
library change; `PetAnimationKey` is not an enum/value class.

## 13. Arbitrary-geometry proof

Synthetic 96x80 atlas with 32x40 cells → 3x2 grid, capacity 6
(`PetsKmpParserTest`); index 5 accepted, index 6 rejected; non-divisible
100x80 rejected. Kodee 1536x2288/192x208 → 8x11, capacity 88.

## 14. Non-idle-default proof

`GenericDefaultTest` (default `stand`): initial sample uses stand, unknown
keys resolve to stand (not idle), pin uses stand, one-shot walk holds without
appending default. `GenericPlayerStateTest` proves the same through
`PetPlayerState` (`pinToDefault`, deprecated `pinToIdle` delegation).
`PetHostGenericTest` proves host + sampling with no `idle` animation.

## 15. Kodee generic manifest

`assets/kodee/pet.pets-kmp.json` (SEPARATE file; originals untouched):
schema `pets-kmp`/1, 192x208 cells, default `idle`, explicit animations
`idle` (0–5; 1680/660/660/840/840/1920 ms), `wave` (24–27),
`jump` (32–36), `happy` (64–69), `rest` (48–53). Rows 9–10 unused. No
`spriteVersionNumber` handling, no V2 constants, no look-direction support.
Research timings used as DATA for this one demo manifest only.

## 16. Kodee generic load/render result

- `KodeeGenericTest.genericKodeeManifestLoadsExactSpritesheet` (io, PUBLIC
  `PetLoader.loadPetsKmpZip`): 1536x2288, 8x11, 88 frames, idle default,
  all five animations present. PASS.
- `KodeeGenericUiTest` (compose, PUBLIC `PetsKmpPackageParser.parse` +
  `rememberPetPlayerState` + `Pet`): exact bytes decode to Ready 1536x2288,
  idle sprite 0 renders with 192x208 source, `play(wave)` reaches sprite 24
  with a different source region. PASS. (No pixel-color assertion; sprite
  indices + Ready + source regions prove the public render path.)

## 17. Original Kodee Codex rejection result

`KodeeGenericTest.originalKodeePackageStillRejectedByStrictCodexLoader`:
exact `pet.json` + exact `spritesheet.webp` via PUBLIC
`PetLoader.loadPetZip` → `Failure(CompatibilityFailure(
UnsupportedAtlasDimensions(1536, 2288)))`. Intentional. PASS. `CodexLockTest`
additionally locks 1536x2288 + `spriteVersionNumber=2` rejection and
no-V2-profile activation.

## 18. Codex regression counts

Baseline (pre-refactor, JVM): core 105, io 149 (corrected count; earlier
146 was a miscount — actual pre-change suites sum to 149).
After: core 137 (105 + CodexLock 6 + PetsKmpParser 22 + GenericDefault 4),
io 157 (149 + PetsKmpLoading 6 + KodeeGeneric 2). All pre-existing suites
green on JVM and iOS simulator; timings/aliases/one-hop/validation locked.

## 19. Generic test counts

- Core: `PetsKmpParserTest` 22, `GenericDefaultTest` 4, `CodexLockTest` 6.
- IO: `PetsKmpLoadingTest` 6, `KodeeGenericTest` 2.
- Compose: `GenericPlayerStateTest` 3, `KodeeGenericUiTest` 1.
- Host: `PetHostGenericTest` 3.
- Total new: 47 tests; all green on JVM; common suites also green on iOS
  simulator (core 11 suites, io 10, compose 6, host 3 result files present).

## 20. Android device results

- API 36 (`Resizable_Experimental`): Compose 14/14, Host 14/14, zero
  failures (after `installAndroidDeviceTest` + app-op grant, per CI).
- API 24 (`pets_api24`, mandatory): Compose 14/14, Host 14/14, zero
  failures (both devices together run also green).
- Real overlay/permission tests executed (not skipped); grant flow per
  `.github/workflows/gradle.yml`.

## 21. iOS result

`iosSimulatorArm64Test` green for core, io, compose, host (result XML
present for all suites incl. new generic/lock tests). Host iOS InApp +
availability suites ran.

## 22. Apple result

- `assembleCodexPetsReleaseXCFramework`: PASS.
- `verifySwiftConsumer`: PASS.
- Single `CodexPets` umbrella topology retained; no second framework; no
  duplicate `PetDefinition` identity; new generic APIs export via the same
  umbrella (ABI KLIB dumps updated).

## 23. Remaining risks

1. `PetAnimationKey` tightening (blank/overlong rejection) is intentional
   but technically breaking pre-0.1.0; Codex observable behavior preserved
   via `UnknownFallback`/`MalformedManifest` mapping — review the two new
   branches in `PetParser`.
2. Generic one-shot hold modeled as self-fallback: shares the one-hop player
   without new branches, but a future fallback/transition schema will need
   to revisit this encoding (documented in the spec).
3. Android overlay grant remains a manual pre-step locally (CI-scripted);
   no silent fallback — by design.
4. Example shells (`:example-android`, `:example-desktop`, `iosApp/`) NOT
   resumed per instructions; the shared example stays on
   `wip/tamagotchi-example`.
5. No phase tag created; Sol review still required.

## 24. Final recommendation

**PASS** — all acceptance conditions hold:

- `PetDefinition` format-agnostic; arbitrary keys/geometry work;
  definition-owned default (not hard-coded idle); generic pin uses default;
  generic renderer/host contain no Codex assumptions; v1 format explicit and
  documented; loaders explicitly distinct; ZIP/security shared not
  duplicated; Codex V1 green; original Kodee V2 still rejects; separate
  generic Kodee manifest loads the exact spritesheet to Ready with
  idle→wave frame change; synthetic non-Codex fixture passes; no
  id/2288/11-row production special case; ABI review passes (no forbidden
  leaks); Apple umbrella passes; lower Phase 1–4 tests green; example shells
  not resumed.

No P0/P1 or fundamental architecture problem remains known. Recommend
independent Sol review before module/artifact renaming and before resuming
executable example shells.
