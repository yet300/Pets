# Example remediation report

## Decision

**BLOCKED: supplied Kodee asset requires unsupported V2 contract.** Work
stopped at the requested asset-compatibility gate. This is not a passing
example application. No V2 interpretation or replacement pet was introduced.

## Core V2 changes reverted

The example work had added `spriteVersionNumber` to the manifest DTO, accepted
1536×2288/8×11 geometry for a version 2 marker, changed default frame count,
and added a V2 test. Those changes and the related existing-test rename have
all been reverted. `git diff -- codex-pets-core` is empty. The core production
parser remains restricted to the independently verified CLI V1 1536×1872
atlas. The existing `ManifestParsingTest` passed after restoration.

## Exact library diff against `phase-4-host`

The following required commands were run after the revert. Each produced no
diff (zero changed lines):

```text
git diff phase-4-host..HEAD -- codex-pets-core       empty
git diff phase-4-host..HEAD -- codex-pets-io         empty
git diff phase-4-host..HEAD -- codex-pets-compose    empty
git diff phase-4-host..HEAD -- codex-pets-host       empty
```

`git diff phase-4-host -- codex-pets-core codex-pets-io
codex-pets-compose codex-pets-host` was also empty, covering the current
working tree as well as `HEAD`. No production library behavior change remains
for the example.

## Asset result

The repository source files are `assets/kodee/pet.json` and
`assets/kodee/spritesheet.webp`. The ZIP in
`example/src/commonMain/composeResources/files/kodee.zip` contains those exact
files. SHA-256 for the manifest is
`12a356ac934d22aaab3571d7d65ced26c3f1153470125d949902d7ca81937caa`;
for the spritesheet it is
`c414c906b3536c4d0c3e166c00d9b852b247a033a0d0ee566c4396ce226cba4a`.
The extracted ZIP members matched both source hashes.

With restored V1 core, `:example:jvmTest` failed exactly at the public
`PetLoader.loadPetZip` call:

```text
Failure(errors=[CompatibilityFailure(report=PetCompatibilityReport(
  errors=[UnsupportedAtlasDimensions(width=1536, height=2288)],
  warnings=[]))])
```

The manifest's `spriteVersionNumber = 2` is not a verified V2 contract in this
library. The supplied atlas is 1536×2288, whereas the verified V1 atlas is
1536×1872. The example cannot reach Ready or render this pet through the
existing public loader. The UI test that expects Ready fails for that reason.

## Final project structure at stop point

```text
:example             shared KMP module, with commonMain UI, assets, and controls
  androidMain         current Activity/settings glue from the draft
  jvmMain             current desktop bootstrap from the draft
  iosMain             current UIViewController bootstrap from the draft
:example-android     not created
:example-desktop     not created
iosApp/              not created
```

The requested shell extraction was not started because the instruction says to
stop when the exact asset fails the verified loader. There are no duplicate
platform UI screens. Of the current example production Kotlin source, 442 of
504 lines (87.7%) are in `commonMain`; the Tamagotchi screen, actions, loading
flow, and host selection are all common. The only `expect/actual` seam is the
Android overlay-settings action.

## Runnable artifacts and runtime results

| Platform | Artifact/result | Acceptance status |
| --- | --- | --- |
| Android | No installable APK path. An AAR exists from the earlier draft, but no device/emulator run occurred. | Not met. |
| Desktop | The earlier `:example:run` process launched before V2 was reverted; it is not a valid Kodee run. No post-revert Ready/overlay runtime is possible. | Not met. |
| iOS | No simulator `.app` or simulator run. Only the earlier KMP framework build exists. | Not met. |

## Remaining issues

1. The supplied asset requires an independently specified and verified V2
   compatibility contract, or a different user-approved V1 asset. Neither was
   authorized here.
2. After resolving asset compatibility separately, move Android packaging to
   `:example-android`, desktop bootstrap to `:example-desktop`, and add a small
   `iosApp/` host; then build and run APK, desktop app, and simulator app.
3. The current demo stats show Mood, Energy, and Fun; Hunger remains to add
   when implementation resumes.
4. The example tests expecting Kodee Ready remain red under the verified V1
   contract. This is the blocker, not a library regression.
