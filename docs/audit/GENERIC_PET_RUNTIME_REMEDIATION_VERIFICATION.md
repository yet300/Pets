# Generic Pet Runtime — final targeted re-verification

## Gate

**PASS WITH P2.** Confidence: **high**. At the tested HEAD, confirmed P0 = **0** and confirmed P1 = **0**. The four P1 findings in `GENERIC_PET_RUNTIME_INDEPENDENT_REVIEW.md` are closed. The residual items in §11 are P2 and do not defeat the generic runtime contract. This is a targeted re-verification, not a new full architecture audit.

## 1. Target and provenance

- Tested HEAD: `26caa1aca690bfa4b82b5a3b5041d5916a9a7bdf` (`docs(audit): record generic runtime remediation report`). The initial `git status --short` was empty; the working tree started clean.
- Behavioral remediation HEAD: `853e1c2c0a7e6d39f5bae29ca44230362a2fae8c`.
- `phase-4-host^{}` resolves to `b6685b0b5f8a09e625b57a75cfc317262c41ac0a`.
- `git log --oneline --decorate -15` showed, in HEAD ancestry: `3197da8`, `b5dd11a`, `2123d76`, `d0c8e74`, `defc9aa`, `b8ebf66`, `853e1c2`, `26caa1a`. The final HEAD differs from the behavioral HEAD only in `docs/audit/GENERIC_PET_RUNTIME_REMEDIATION_REPORT.md`.
- Source of truth read: the independent review, remediation report, and `docs/spec/PETS_KMP_PACKAGE_V1.md`. Conclusions below are based on current source, tests, local runs, and GitHub run metadata, not on the remediation report's assertions alone.

## 2. P1-01 — generic host intent: CLOSED

`PetHostState.requestedAnimation` is `PetAnimationKey?`; `null` means no explicit request. Both the state constructor and `rememberPetHostState(initialAnimation: PetAnimationKey? = null)` default to null. `effectiveAnimation(definition)` returns the requested key or that definition's `defaultAnimationKey`; it does not mutate intent. No ordinary generic host path injects `PetAnimations.Idle`. The InApp host and both Android and JVM overlay compositions call `player.play(state.effectiveAnimation(definition))`; their pin/resume effects use `pinToDefault`/`resume`.

The 96×80, 32×40 no-idle stand/dance host tests assert initial `requestedAnimation == null`, effective and sampled `stand`, explicit `play(dance)`, pin to the first `stand` frame, and resume of the explicit request. Rebinding the same null-intent state from default `stand` to default `sleep` yields `stand -> sleep` with public intent still null. Other tests cover an explicit request surviving rebind and a request made while pinned. The temporary end-to-end probe in §7 additionally exercised `play(stand)` while pinned and found the visible frame and post-resume request coherent. Codex-specific `pinToIdle` remains only as a deprecated compatibility wrapper.

## 3. P1-02 — optional fallback: CLOSED

`PetAnimation.fallback` is `PetAnimationKey?`. The Pets KMP parser constructs its animations with `fallback = null`, including one-shots. In `samplePetAnimation`, a completed non-looping selected animation with null fallback returns its own final frame under the same key with `nextFrameInNanos == null`. A non-null fallback is evaluated once using the original elapsed clock; the sampler does not recursively follow another fallback.

`GenericOneShotTest` checks one-frame and three-frame tracks at zero, duration-minus-one, duration, duration-plus-one, total-minus-one, total, total-plus-one, and `Long.MAX_VALUE` where applicable. It checks the key, sprite, and next wake-up, plus loop-prefix behavior. Codex source still supplies non-null built-in and custom fallbacks. `PlaybackTest` and `CodexLockTest` cover one hop, the original elapsed clock, `"" -> idle`, and literal whitespace fallback handling. The fresh core JVM suite passed.

## 4. P1-03 — strict Pets KMP integer JSON: CLOSED

The generic parser performs one `JsonElement` pass before DTO decoding. It checks `schemaVersion`, `frame.width`, `frame.height`, `animation.loopStart`, `frame.index`, and `frame.durationMs` for unquoted tokens matching `-?(0|[1-9][0-9]*)`, then checks Int/Long bounds and normal semantic ranges. The lexical scan is linear; parse, decode, and validation failures are returned as typed outcomes. The Codex DTO path is separate and its DTO file is unchanged from `phase-4-host`.

`PetsKmpStrictIntegersTest` applies the same rejection matrix to **each** integer field: `"1"`, `"0"`, `1.0`, `0.0`, `1e0`, `1E0`, `1E+0`, `true`, `{}`, `[]`; it also checks overflow, malformed structures, and no exception leakage. Ordinary unquoted integer fields parse. The grammar accepts `0` and `1` lexically; a field's subsequent semantic rule may reject zero (for example, `schemaVersion` must equal 1 and dimensions/durations must be positive). This distinction prevents a false claim that every field accepts zero as a valid value. Fresh core JVM tests passed.

## 5. P1-04 — arbitrary Codex keys: CLOSED

`PetAnimationKey` now stores its exact `String` with no global blank or length guard. The Codex adapter constructs custom names and fallback keys without a generic-format cap. `CodexLockTest` parses custom names of 65, 100, and 5000 characters within the 64 KiB manifest limit, an empty custom key, empty fallback mapping to idle, and literal whitespace fallback semantics. The 100-character case also checks completion into idle. Those tests passed in the fresh core JVM suite; hosted iOS core tests passed at final HEAD.

The Pets KMP manifest parser separately trims animation names and `defaultAnimation`, rejects blank-after-trim names, and compares them case-sensitively. That format normalization is not a restriction on the normalized `PetAnimationKey` model.

## 6. Generic API and security cleanup

`PetDefinition` has no constructor default for `defaultAnimationKey`; both Codex and Pets KMP adapters supply it explicitly. Public generic parser, ZIP loader, and directory loader APIs have no `fallbackId` argument. A private shared identity helper still has a fallback argument; for a valid generic manifest its required id wins, so this does not create a public generic fallback identity.

Generic ZIP and directory discovery call shared helpers with `allowLegacyAvatar = false`: `pet.json` is required and an `avatar.json`-only package fails. The Codex entry points retain legacy discovery. Both formats use the same ZIP structure, bounded read, CRC, path, duplicate, and directory confinement implementation, parameterized by discovery policy. No duplicated generic security parser was found. Existing traversal and avatar-only tests passed in the fresh IO JVM suite.

## 7. Kodee and synthetic non-Codex proof

The current Kodee inputs were used, with SHA-256: original `pet.json` `12a356ac934d22aaab3571d7d65ced26c3f1153470125d949902d7ca81937caa`; generic `pet.pets-kmp.json` `bb06ee9935e1bc44c80fe33b81b02506410dc443bc26c165577ae32940f7e70d`; shared `spritesheet.webp` `6688c1dc52bfd0b602a39448ee86545bb1e098aa21f25beb500cfeb4665de75a`.

`KodeeGenericTest` sends the original manifest plus exact sheet through the public Codex ZIP loader and asserts a `CompatibilityFailure` with `UnsupportedAtlasDimensions`; the sheet dimensions are 1536×2288. It sends the separate generic manifest plus the same sheet through the public generic ZIP loader and asserts 1536×2288, 8×11 cells, capacity 88, default idle, and the declared tracks. `KodeeGenericUiTest` parses the same files, decodes the image to Compose `Ready`, renders sprite 0, and moves to sprite 24 on `wave`. These tests passed in the freshly rerun IO and Compose JVM suites. Source inspection found no production Kodee, 2288, 11-row, or `spriteVersionNumber=2` generic special case.

For independent genericity proof, a **temporary, subsequently removed** JVM test used one exact PNG fixture: atlas 96×80, cells 32×40, tracks `stand`/`dance`, default `stand`, no idle. `PetsKmpPackageParser.parse` succeeded; `PetLoader.loadPetsKmpDirectory` returned an equal definition with 3×2 geometry; core sampled dance; the Compose host/player reached `Ready`, initially sampled stand with null public intent, played dance, pinned to stand, accepted `play(stand)` while pinned, and resumed stand. Its XML result was 1 test, 0 skips, 0 failures, 0 errors. The permanent core, IO, host, and iOS fixtures separately lock these paths. The temporary test source and test-only dependency were removed after execution; no production code was edited.

## 8. iOS, Swift ABI, and local suites

- Fresh `:codex-pets-host:iosSimulatorArm64Test --rerun-tasks`: **BUILD SUCCESSFUL**, 37 tasks executed. The generated `HostIosInAppUiTest` XML contains `genericNoIdleInAppReadyDefaultPlayPinResume[iosSimulatorArm64]`; the class reports 2 tests, 0 skipped, 0 failed, 0 errors. Its source waits for `Ready`, checks null request and stand sample, then dance, pinned stand, and resumed dance.
- Fresh forced JVM run of `:codex-pets-core:jvmTest`, `:codex-pets-io:jvmTest`, `:codex-pets-compose:jvmTest`, and `:codex-pets-host:jvmTest`: **BUILD SUCCESSFUL**, 32 tasks executed. This includes desktop host and overlay-controller regressions.
- Ran all four `checkKotlinAbi` tasks, `:codex-pets-apple:assembleCodexPetsReleaseXCFramework`, and `:codex-pets-apple:verifySwiftConsumer`: **BUILD SUCCESSFUL**. The XCFramework task reused its current artifact; ABI checks and Swift consumer verification executed. `Consumer.swift` compiles optional animation fallback and host request, `defaultAnimationKey`, `PetsKmpPackageParser`, generic loader and error, and `pinToDefault` via the single `CodexPets` umbrella. No duplicate `PetDefinition` identity appears in that consumer.
- Local API 24 (`pets_api24`, confirmed SDK 24): installed test APKs, granted overlay app-op, then ran `:codex-pets-compose:androidConnectedCheck :codex-pets-host:androidConnectedCheck`. Compose **14/14** and host **14/14**; XML reports zero failures/errors/skips, including real overlay lifecycle and permission transitions.
- Local API 36 (`Resizable_Experimental`, confirmed SDK 36): same install/grant/run sequence. Compose **14/14** and host **14/14**; XML reports zero failures/errors/skips, including real overlay lifecycle and permission transitions.

These local device runs cover Phase 4 resource/lifecycle behavior with actual overlay windows; the JVM controller suite covers desktop behavior. iOS system-wide overlay remains unsupported by design.

## 9. Hosted CI, verified from GitHub metadata

| Run | Head SHA | Result | Actual returned jobs |
|---|---|---|---:|
| [36429540162](https://github.com/yet300/Pets/actions/runs/36429540162) | `853e1c2c0a7e6d39f5bae29ca44230362a2fae8c` | completed, success; no failed jobs | **22/22** |
| [36431327983](https://github.com/yet300/Pets/actions/runs/36431327983) | `26caa1aca690bfa4b82b5a3b5041d5916a9a7bdf` | completed, success; no failed jobs | **22/22** |

Counts come from the GitHub Actions `jobs` arrays, not from the remediation report. The configured matrix has 21 build entries plus one Android API 24 job. Neither run returned 23 jobs. The final run covers the exact final HEAD; the behavioral run covers the exact remediation commit.

## 10. Evidence limits

The original independent review describes failures at an earlier HEAD and is historical evidence, not a current failure. Hosted CI is evidence for its recorded SHAs; local commands above were run at the tested final HEAD. The temporary probe's first attempt used a Java-created ZIP rejected by this loader's ZIP structural policy; the corrected probe used the public directory loader and passed. This probe-construction failure was not a runtime regression.

## 11. Remaining P2 and final decision

- Kodee's example track labels (`rest`/waiting and `happy`/review) are not conclusively aligned to the artwork; the older Kodee research hash refers to a prior sheet. Neither affects generic parsing or playback.
- `frameCount` duplicates atlas capacity in the normalized model; its documented meaning is cell capacity, not used animation frames.
- `CodexPet`, `pinToIdle`, and internal `CodexPetTag` are retained compatibility/naming debt; the generic rendering and pin paths use `Pet` and `pinToDefault`.
- Missing versus explicit-null malformed manifest members still differ in error taxonomy. They produce typed failures without exception leakage.
- No newly expanded malicious-ZIP corpus was added; the shared security implementation and existing regressions were inspected and rerun. This is a coverage improvement candidate, not evidence of a current P1 bypass.

None of these items meets P1 severity on the current evidence. The final gate is **PASS WITH P2**: P0 = 0, P1 = 0, all four prior P1 findings independently closed. No tag, publish, module/artifact rename, or Tamagotchi work was performed.
