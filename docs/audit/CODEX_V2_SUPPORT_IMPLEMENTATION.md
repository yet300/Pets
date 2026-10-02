# Codex V2 support implementation evidence

Date: 2026-10-02. Status: **PASS WITH P2**. All three implementation tasks
passed independent specification and code-quality reviews; the final whole-feature
review approved the change. Confidence: high in the automated and directly
observed results below. Desktop acceptance is explicitly user-confirmed.

## Scope and evidence boundaries

The user approved the explicit V2 recommendation with “Yes, use your
recommendation”. Baseline V1 remediation is commit
`195c31e9a78457e61554ee1ad6f8c10d2c68cd6a`. V2 work uses the
`codex/codex-v2-support` branch in `/tmp/pets-codex-v2`. The initially created
managed worktree was archived without source changes after sandbox writes
were blocked. Nothing has been published or tagged.

First-party artwork source: installed Work Pets plugin **0.1.6**, file
`/Users/yet/.codex/plugins/cache/openai-curated-remote/work-pets/0.1.6/references/sprite-sheet-contract.md`.
SHA-256: `88d05bfbc76c3a2a64d128cafe5792cc43f78170fee85496d50cb3925934d093`.
It specifies transparent PNG/WebP, 1536x2288, an 8x11 grid, 192x208 cells,
nine standard rows and sixteen clockwise poses in rows 9–10. Its artwork
authoring limit is 20 MiB. The library retains its existing **8 MiB** encoded
image and **64 KiB** manifest caps.

Standard timing and nearest-sector look selection are separately observed,
build-specific Desktop runtime evidence recorded in
[historical research](../research/CODEX_V2_COMPATIBILITY_RESEARCH.md).
They are not a published Desktop manifest API. The implemented policy requires
literal JSON integer version 2, fixed geometry, static PNG/WebP (rejecting
APNG/animated WebP), ignores unknown members and rejects `frame`/`animations`
even when null. Validation inspects image metadata and definition geometry;
it does not validate decoded artwork pixels, alpha, blank cells or pose
quality, and does not claim official Desktop-equivalent validation.

## Implementation

- Core commit `61d9d8e79229e95d1070bd4776cda47d9ca1e939` adds `CodexV2`,
  `CodexV2PetPackageParser`, `CodexV2ParseOutcome`, `CodexV2Report` and typed
  errors. The normalized definition retains standard tracks and indices 72–87
  as sixteen held `look-*` animations. The pure offset selector performs no
  point collection or global cursor tracking. V1 source semantics are preserved.
- IO commit `38de2b0a840aec23af21eedb72dc1e7572515616` adds explicit
  `loadCodexV2Directory`/`loadCodexV2Zip` and
  `PetLoadError.CodexV2CompatibilityFailure`. They require `pet.json`, reuse
  existing secure package transport and retain the final raw V2 image checks.
  Core and IO each passed separate independent specification and quality reviews.
- Shared imports route literal version 2 first through the dedicated V2
  parser. Preview and Add keep the same `parseImportedPet` function. Short
  messages retain full typed V2 reports, unsuccessful imports never add pets,
  and existing definition-derived actions/player/host handle the look keys.
  Platform picker, gallery UI and host implementations are unchanged.

Shared implementation/docs commit: `f0ba0fcab4ece3024ec5d9638ca6e88c41ce9346`.
This is the tested code HEAD for platform acceptance. The subsequent acceptance
report commit changes documentation only.

Exact current Kodee assets used in tests:

| Asset | SHA-256 |
|---|---|
| `assets/kodee/pet.json` | `12a356ac934d22aaab3571d7d65ced26c3f1153470125d949902d7ca81937caa` |
| `assets/kodee/spritesheet.webp` | `6688c1dc52bfd0b602a39448ee86545bb1e098aa21f25beb500cfeb4665de75a` |

Shared tests use `ImportFixtures.originalKodeeManifest` and
`Res.readBytes("files/kodee/spritesheet.webp")`, assert the same normalized
definition as the direct V2 parser, all sixteen exact keys and frame indices,
and preview/Add/gallery/overlay action parity. V1 custom animations, generic
Kodee and invalid imports remain covered. Geometry, malformed metadata,
oversize and invalid image failures and forbidden overrides keep typed reports.
Quoted/decimal/exponent versions do not select V2. No failure-driven fallback
was introduced.

## Automated verification

The shared RED gate compiled and ran 12 tests: three failed because the old
router rejected V2 (exact Kodee, explicit V2 geometry routing, gallery import).
The GREEN gate passed after implementation. The final shared suites include
an additional typed-failure test and pass on JVM and Native.

The following complete command exited 0, `BUILD SUCCESSFUL in 19s`, with
198 actionable tasks (106 executed, 29 from cache, 63 up-to-date):

```sh
rtk ./gradlew :pets-core:jvmTest :pets-io:jvmTest :example:shared:jvmTest \
  :pets-core:iosSimulatorArm64Test :pets-io:iosSimulatorArm64Test \
  :example:shared:iosSimulatorArm64Test :pets-core:checkKotlinAbi \
  :pets-io:checkKotlinAbi :example:desktopApp:createDistributable \
  :example:androidApp:assembleDebug --no-configuration-cache \
  -Pcompose.desktop.packaging.checkJdkVendor=false
```

Exact XML totals were inspected under each module's
`build/test-results/{jvmTest,iosSimulatorArm64Test}`:

| Module | JVM tests | iOS simulator tests | Failures/errors/skipped |
|---|---:|---:|---|
| core | 185 | 180 | 0 / 0 / 0 |
| IO | 181 | 154 | 0 / 0 / 0 |
| example shared | 13 | 13 | 0 / 0 / 0 |

Core adds 12 tests (8 common, 4 JVM); IO adds 17 tests (15 common, 2 JVM).
Core/IO ABI changes are additive; both ABI checks pass. Final additional
shared assertions for full-report equality and strict version routing were
rerun with `:example:shared:jvmTest :example:shared:iosSimulatorArm64Test`:
exit 0, `BUILD SUCCESSFUL in 6s`.

Gradle emits existing Android host-test configuration and deprecation warnings;
these successful tasks do not establish Android unit-test execution.

Built artifacts:

- `/tmp/pets-codex-v2/example/desktopApp/build/compose/binaries/main/app/com.pets.example.app`
- `/tmp/pets-codex-v2/example/androidApp/build/outputs/apk/debug/androidApp-debug.apk`

## Platform acceptance and remaining checks

Desktop: launched the rebuilt app from the artifact path above. Automation
observed an imported **Clawd Chef** with standard actions and `Look 000`
through `Look 090` visible in the existing action row. The user explicitly
confirmed **“All four work”** for import, standard animations, look poses and
floating pet. Exact selected desktop manifest/sheet paths were not supplied;
this is user acceptance, not a claim of an independently inspected package.

Android: installed the rebuilt APK on `emulator-5554` (API 36.1). Actual file
pickers selected `/sdcard/Download/PetsV2Kodee/pet.json` (`msf:182`) and
`spritesheet.webp` (`msf:180`), copied from the exact original Kodee assets.
The dialog showed a Kodee preview and enabled Add; Add succeeded and the pager
exposed standard and look actions. Selected Jumping; the later screenshot
shows its idle fallback, so this single screenshot is not a continuous timing
measurement. Selected `Look 000` and opened the floating pet, then scrolled
the action row and selected `Look 090`. Visually inspected screenshots show
the expected upward and rightward eyes in both the gallery and transparent
floating surface. Closed the test overlay and verified Open pet returned.
All sixteen indices/held playback are covered by JVM/Native tests; the direct
Android check samples two poses rather than claiming sixteen manual checks.

Screenshots (local verification artifacts):

- `/tmp/pets-v2-android-jumping.png`
- `/tmp/pets-v2-android-look-up-overlay.png`
- `/tmp/pets-v2-android-look-right-overlay.png`

Sequential iOS app build after Gradle exited 0 with **BUILD SUCCEEDED**:

```sh
rtk proxy xcodebuild -project example/iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug \
  -destination 'platform=iOS Simulator,id=79BF70F8-7FD9-4B15-95C5-3D83BC8E8920' \
  -derivedDataPath /tmp/pets-codex-v2-ios-derived CODE_SIGNING_ALLOWED=NO build
```

Direct iOS picker interaction, downloaded `.codex-pet` transport and physical
multi-monitor behavior remain unverified. ZIP tests prove the explicit ZIP API,
not the format of an unavailable downloaded container.

Prior V1 evidence is kept separate: the user confirmed desktop V1 checks;
direct Android V1 pickers imported the committed fixture, selected Hello and
opened its floating surface. `/tmp/pets-v1-android-codex.png` records the test
marker in gallery and transparent floating host. Existing generic Android
import also passed before the V2 extension; preservation is covered by the
current shared tests. No publication or tags were created.

## Local integration

Fast-forwarded the original checkout's `main` from `195c31e` to `877b0b9`
(the acceptance report commit). Rechecked JVM core/IO/shared tests, both ABI
checks, desktop distributable and Android APK in that checkout: exit 0,
**BUILD SUCCESSFUL in 8s**, 142 tasks (20 executed, 26 from cache,
96 up-to-date). Cached test results are distinguished from fresh executions;
the fresh worktree suites and independent XML review are recorded above.
The two pre-existing untracked audit reports remain untracked and their SHA-256
hashes are unchanged. The temporary feature worktree remains available because
the currently tested desktop app was launched from its build directory.
