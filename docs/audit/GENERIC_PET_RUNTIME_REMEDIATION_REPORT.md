# Generic Pet Runtime — Remediation Report

Closes the four confirmed P1 findings of
`docs/audit/GENERIC_PET_RUNTIME_INDEPENDENT_REVIEW.md` plus the specifically
authorized P2 cleanups. No module/artifact renames, no Tamagotchi example,
no Codex V2, no publish, no phase tag, no rewritten history.

## 1. Starting HEAD (remediation base)

- Remediation base (clean `main` HEAD, NOT `phase-4-host`, NOT `114c75b`):
  `4b2791ae5bf5f1c6824333cd61aa58263e6e34d2`
  (`docs(audit): record generic runtime independent review`).
- `git status --short` was empty before any edit.
- `phase-4-host^{}` = `b6685b0b5f8a09e625b57a75cfc317262c41ac0a` (never moved).
- The Kodee asset commit (`868ef94`) and the committed independent-review
  report are intentional history and were kept as-is.

## 2. Remediation commits

| # | SHA (short) | Message |
|---|---|---|
| 1 | `3197da8` | `fix(host): remove Codex idle sentinel from generic intent` |
| 2 | `b5dd11a` | `refactor(core): model optional animation fallback` |
| 3 | `2123d76` | `fix(core): preserve arbitrary Codex animation keys` |
| 4 | `d0c8e74` | `fix(core): enforce Pets KMP integer JSON types` |
| 5 | `defc9aa` | `fix(io): remove generic legacy discovery and fallback identity` |
| 6 | `b8ebf66` | `test: lock generic and Codex remediation contracts` |
| 7 | `853e1c2` | `docs: align generic runtime and package contracts` |
| 8 | (this) | remediation report (evidence only, no behavior change) |

Remediation HEAD: `853e1c2c0a7e6d39f5bae29ca44230362a2fae8c`.

## 3. P1-01 disposition — CLOSED

`PetHostState.requestedAnimation` is now `PetAnimationKey?`: `null` means
"no explicit request — follow the bound definition's default". No
`PetAnimations.Idle` sentinel is used as a default anywhere in the host
model: `PetHostState(animation: PetAnimationKey? = null)` and
`rememberPetHostState(initialAnimation: PetAnimationKey? = null)`, so no
caller needs a definition merely to create state.

Binding resolves `effectiveAnimation = state.requestedAnimation ?:
definition.defaultAnimationKey` (new public pure
`PetHostState.effectiveAnimation`) in the InApp host, the Android
SystemOverlay, and the JVM SystemOverlay. Rendering never mutates
`requestedAnimation`: public state expresses explicit caller intent, not
the resolved player key. Rebinding the same null-intent state from default
`stand` (A) to default `sleep` (B) moves the player `stand -> sleep` while
intent stays `null`; no recreation injects `idle`. `play(dance)` records
`dance` and preserves it across rebinds; pin/resume semantics from the
remediation brief hold exactly (see §7/§12 below).

Production Compose/Host sources contain no `PetAnimations.Idle` outside the
deprecated `pinToIdle` compatibility wrappers.

## 4. P1-02 disposition — CLOSED

`PetAnimation.fallback` is now `PetAnimationKey?`: `null` = no fallback
transition (hold final frame); non-null = exactly one fallback hop. The
generic parser no longer fabricates `fallback = own key`: every generic
animation exposes `fallback == null`. The sampler implements the specified
completion logic — completed non-looping with `fallback == null` returns
the final frame of the SELECTED animation under the same key with
`nextFrameInNanos == null`; otherwise exactly one hop with the same
original elapsed clock, no recursion. The dangling-fallback hardening
(non-null fallback missing from a manually-built definition resolves to the
definition default) is retained.

The Codex V1 adapter behavior is byte-for-byte unchanged in intent: all
built-in and custom animations carry non-null fallbacks, one-hop, same
clock, `"" -> idle`, literal whitespace names, aliases, and custom
animation merging.

## 5. P1-03 disposition — CLOSED

Pets KMP v1 integer fields (`schemaVersion`, `frame.width`/`frame.height`,
`animation.loopStart`, `frame.index`, `frame.durationMs`) MUST be unquoted
JSON integer tokens matching `-?(0|[1-9][0-9]*)`, then satisfy semantic
range validation. `"1"`, `"0"`, `1.0`, `0.0`, `1e0`, `1E0`, `1E+0`,
booleans, objects, arrays, and out-of-range integers are rejected with a
deterministic format-owned `MalformedManifest` ("…(Pets KMP v1 strict
integers)"). Mechanism: one generic-format-only pre-decode `JsonElement`
pass (linear scan, no regex/backtracking, manual range via
`toIntOrNull`/`toLongOrNull` for correct overflow handling, no platform
code, no duplicated parser logic), hooked once in the String trusted
metadata path (ByteArray/raw paths delegate to it). Codex DTO parsing is
untouched. Missing/null members keep their existing typed semantic mapping
(P2 taxonomy, unchanged); nothing crashes.

## 6. P1-04 disposition — CLOSED

`PetAnimationKey` accepts arbitrary `String` values again (no non-blank,
no length enforcement); `MAX_KEY_LENGTH` is removed with no replacement
global limit. The Codex V1 adapter constructs custom and fallback keys
directly, restoring Phase 4 acceptance: 65-char, 100-char, and 5000-char
custom names with valid frames/fps/fallback parse (bounded by the 64 KiB
manifest cap, no hidden Codex cap); empty custom names are literal keys;
`fallback == "" -> idle`; whitespace fallback is a literal name failing
with `UnknownFallback` unless present.

Pets KMP keeps its own stricter normalization (trimmed, non-blank after
trim, case-sensitive, no separate length cap), documented in the v1 spec.
`PetDefinition.animation(name)` no longer catches a key exception that
cannot occur.

## 7. Host nullable-intent semantics

`null` = follow `definition.defaultAnimationKey`; non-null = explicit
caller intent. `effectiveAnimation(definition)` is the only resolution
point, used by all three hosts. `play()` sets non-null intent; nothing in
the render path clears or rewrites it. Permanent tests:
`PetHostStateTest.defaultRequestedAnimationIsNull`,
`PetHostJvmTest.hostNullIntentResolvesToDefinitionDefault`,
`PetHostGenericTest` (initial bind, rebind A->B, explicit survival,
unknown-to-new-default, pin/resume null and explicit), and the iOS test.

## 8. Fallback model before/after

Before: `PetAnimation.fallback: PetAnimationKey` (mandatory); generic
one-shots encoded hold as `fallback = own key`, leaking Codex transition
mechanics into the generic ABI.

After: `PetAnimation.fallback: PetAnimationKey?`; generic one-shots carry
`null` with direct final-frame hold in the sampler; Codex animations carry
non-null fallbacks with identical one-hop semantics. `equals`/`hashCode`/
`toString`, ABI dumps, and Swift headers updated (nullable export).

## 9. Strict JSON grammar

`-?(0|[1-9][0-9]*)`, unquoted, per §3.1 of
`docs/spec/PETS_KMP_PACKAGE_V1.md`. Locked by
`PetsKmpStrictIntegersTest` (per-field wrong-syntax matrix, ordinary-token
success, `-1` lexical-accept/semantic-reject split per field, Int/Long
overflow, Long boundaries, null/structure non-crash, leading-zero
rejection).

## 10. Codex long-key proof

`CodexLockTest.customAnimationWith65CharNameAccepted`,
`longCustomNameWithValidFramesFpsFallbackAccepted` (100 chars, explicit
fallback, completion into idle),
`substantiallyLongerKeyStillBoundedByManifestCap` (5000 chars) — all green
on JVM and iOS simulator.

## 11. Codex behavior lock

`CodexLockTest` (+ `CustomAnimationsTest`, `DefaultAnimationsTest`,
`PlaybackTest`, `ManifestParsingTest` unchanged and green): fixed V1
geometry, standard rows, aliases, idle/3x timing, one-hop, same original
elapsed (`codexFallbackHopUsesSameOriginalElapsed`), `loopStart`,
unknown-requested -> idle, empty custom key acceptance, and
empty-vs-whitespace fallback semantics.

## 12. Generic one-shot proof

`GenericOneShotTest`: `fallback == null` exposed; single-frame boundaries
(t=0 with remaining wake-up; duration-1; duration/duration+1/`Long.MAX_VALUE`
held with null wake-up); multi-frame boundaries before/every/exact/total±1/
`Long.MAX_VALUE`; no default appended; looping prefix/suffix intact.

## 13. Generic directory proof

`GenericDirectoryTest` (JVM, real temp dirs, PUBLIC
`PetLoader.loadPetsKmpDirectory`): valid generic package loads (3x2,
capacity 6, manifest identity); `../` traversal rejected
(`InvalidSpritesheetPath`); absent `spritesheetPath` defaults to
`spritesheet.webp`; `avatar.json`-only package fails with
`MissingManifest`; `pet.json` wins when both exist. ZIP-level halves
(root/nested avatar-only rejection, pet-wins) locked in commonTest
`PetsKmpLoadingTest`, which also fixes the previously mislabeled
directory test. Discovery shares all security code (parameterized only by
`allowLegacyAvatar`).

## 14. Kodee generic/original results

- `assets/kodee/pet.json` UNMODIFIED; no V2 support added.
- Generic `assets/kodee/pet.pets-kmp.json` + exact `spritesheet.webp`
  through public `PetLoader.loadPetsKmpZip`: Success, 1536x2288, 8x11,
  88 frames, default idle, all five tracks present.
- Original `pet.json` (`spriteVersionNumber=2`) + exact sheet through
  public `PetLoader.loadPetZip`: `CompatibilityFailure(
  UnsupportedAtlasDimensions(1536, 2288))` as required.
- Compose `KodeeGenericUiTest`: exact bytes decode to Ready 1536x2288,
  idle sprite 0 renders 192x208, `play(wave)` reaches sprite 24.
- Track labels `rest`/`happy` left untouched (P2 example-data quality for
  the example stage; no production behavior depends on them).

## 15. iOS no-idle result

Permanent `HostIosInAppUiTest.genericNoIdleInAppReadyDefaultPlayPinResume`:
real 96x80 decoded PNG, definition default `stand` with no `idle` —
Ready reached, initial explicit request `null`, rendered/sample animation
`stand`, `play(dance)`, pin -> `stand` frame 0, resume -> `dance`. Green
in `iosSimulatorArm64Test`.

## 16. Swift consumer result

`codex-pets-apple/swift-tests/Consumer.swift` now also compiles generic
usage: `PetsKmpPackageParser` parse outcome, `PetLoader.loadPetsKmpZip`
(no fallback id), `PetDefinition.defaultAnimationKey`, nullable
`PetAnimation.fallback`, optional `PetHostState.requestedAnimation`,
`pinToDefault`, and one representative generic error inspection
(`PetsKmpErrorMalformedManifest.message`). `verifySwiftConsumer` green;
single `CodexPets` umbrella unchanged.

## 17. ABI diff

Intentional (ABI dumps regenerated, `checkKotlinAbi` green, no forbidden
leaks — no platform types, `Map`, `JsonElement`, Okio, or DTOs):

- `PetAnimation.fallback`: `PetAnimationKey` -> `PetAnimationKey?`
- `PetHostState.requestedAnimation`: `PetAnimationKey` -> `PetAnimationKey?`
- `+ PetHostState.effectiveAnimation(PetDefinition): PetAnimationKey`
- `rememberPetHostState.initialAnimation`: `PetAnimationKey` -> `PetAnimationKey?`
  (composable; not present in the ABI dump surface)
- `- PetAnimationKey.Companion.MAX_KEY_LENGTH` (companion removed)
- Generic `fallbackId` overload params removed:
  `PetsKmpPackageParser.parse`, both `parseTrustedMetadata` overloads,
  `PetLoader.loadPetsKmpZip`
- Codex APIs unchanged.

## 18. Android API24/API36

Local, with granted overlay app-op, real overlays executing (not skipped):

- API 24 (`pets_api24`): Compose 14/14 OK, Host 14/14 OK via
  `am instrument` (gradle `connectedCheck` reinstalls wipe the runtime
  grant on this image, so install -> grant -> verify -> instrument was
  used; same test APKs, same assertions).
- API 36 (`Resizable_Experimental`): Compose 14/14 OK, Host 14/14 OK.

## 19. Hosted CI exact HEAD

- Run: `https://github.com/yet300/Pets/actions/runs/36429540162`
  (Java CI with Gradle, push of remediation HEAD `853e1c2`).
- Result: all configured jobs green (core/io/compose/host JVM on
  ubuntu+windows, iOS simulator legs on macOS, assemble, ABI checks,
  Swift consumer, Android API 24 real-overlay job).
- The old `114c75b` run (`36399897131`) is NOT used as remediation
  evidence.

## 20. Remaining P2

- Kodee row labels (`rest`/`waiting`, `happy`/`review`) and stale research
  hash: example-stage data quality; spec now notes the post-research
  spritesheet change without rewriting history.
- `frameCount` kept and documented as atlas capacity; a later API
  simplification may remove it.
- `CodexPet` wrapper, `pinToIdle` wrappers, internal `CodexPetTag`:
  deprecated-compat surface retained with no new adoption.
- Null-`animations`/missing-member error taxonomy asymmetry: unchanged.
- No new malicious-ZIP corpus beyond existing fuzz/consistency suites;
  shared security code unchanged in behavior.

## 21. Final gate recommendation

**PASS.** P0 = 0, P1 = 0. All four findings are independently
demonstrably closed:

- P1-01: default no-idle hosts expose `requestedAnimation == null` while
  the player uses the definition default (JVM + iOS tests).
- P1-02: generic one-shots publicly carry `fallback == null` with correct
  final-frame hold; Codex fallback behavior unchanged (parser + sampler +
  validator tests).
- P1-03: quoted/decimal/exponent integer fields reject; ordinary integer
  tokens succeed (per-field matrix).
- P1-04: Codex custom keys >64 chars accepted again; generic safety via
  the manifest cap and its own trim/non-blank normalization.

Additionally verified: original Kodee Codex package still rejects,
generic Kodee and synthetic non-Codex packages load, IO security remains
shared, real overlays green on API 24 and API 36, the iOS no-idle test is
permanent, the Swift generic consumer compiles, and hosted CI at the exact
remediation HEAD is green.

No modules renamed. No example shells resumed. No tag created.
