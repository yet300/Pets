# Example Codex V1 import remediation

Date: 2026-10-02 (Asia/Tbilisi). Tested base HEAD: `edc943718ff8114371eb4099e47afc2b15dc789c`, plus the example changes committed with this report. Confidence: high for automated checks and Android generic import; desktop success is direct user acceptance, with the precise selected external V1 fixture paths unavailable.

## Previous behavior and change

Both preview and final Add previously called only `PetsKmpPackageParser` and displayed “Invalid pet package” on any failure. Valid Codex V1 manifests could not enter the example gallery through their existing library parser.

`example/shared/.../ImportedPet.kt` now owns bounded UTF-8 JSON classification, explicit parser routing, typed example outcomes and concise messages. `ManifestFormat` has `PetsKmpV1`, `CodexV1`, `UnsupportedCodexV2`, and `Unknown`. An explicit unquoted integer `spriteVersionNumber: 2` wins before any other route. Pets KMP requires exact string schema `pets-kmp` and integer token schemaVersion `1`. Foreign schema/version identities remain unknown. Schema-less Codex documents are identified by the verified V1 member vocabulary, explicit version 1, or the empty object (the existing V1 contract deliberately permits `{}`). Identity checks do not repeat semantic or animation validation. Recognized malformed fields go to their owning parser and retain its failure report.

`parseImportedPet` is used by both preview and `PetGalleryState.importPet`; no parser is tried after another parser fails. The generic route passes exactly manifest/image bytes to `PetsKmpPackageParser`. The Codex route passes those bytes plus deterministic manifest-filename stem identity to `CodexPetPackageParser`. Empty stems use `imported-pet`; generic identity remains manifest-owned.

Successful paths create the same `ExamplePet(definition, spritesheetBytes)`. The existing pager, player and floating host remain format-agnostic, and action chips still come from `definition.animationKeys`. No platform picker implementation or filter was changed.

## Typed outcomes and UI

Success exposes the normalized definition. Unsupported V2, unsupported format, invalid manifest, invalid spritesheet, and other parser failure are distinct example outcomes. Parser-origin failures retain `PetsKmpReport` or `PetCompatibilityReport` for diagnostics and tests. The classifier enforces the existing manifest byte limit before decoding; image limits remain parser-owned.

Visible messages: “Invalid pet manifest”, “Invalid spritesheet”, “Unsupported pet format”, and “Codex V2 pets are not supported yet”. Parser/serialization details never appear in the dialog. Failure cannot append a pet; preview failure disables Add.

## Permanent fixtures and regression evidence

- Exact `assets/kodee/pet.json`: SHA-256 `12a356ac934d22aaab3571d7d65ced26c3f1153470125d949902d7ca81937caa`; explicit V2 rejected at example level.
- Exact `assets/kodee/pet.pets-kmp.json`: SHA-256 `bb06ee9935e1bc44c80fe33b81b02506410dc443bc26c165577ae32940f7e70d`; classified generic and imported successfully with bundled Kodee WebP.
- `example/shared/src/commonTest/fixtures/codex-v1/pet.json`: SHA-256 `6ffd948832d58aefeff11f4c658d85f74144a0393b431fd6d68084c38c5c9317`. This deterministic local V1 test manifest uses the library's verified map-based custom-animation contract (frames `[0,1]`, fps 8, loop and idle fallback), with no V2 fields and no identity so fallback can be tested.
- Matching full, decodable 1536×1872 PNG: SHA-256 `979c3e88ccd7000e96a4658b05bb2c5fbeb9a49aed53689ddc89a137e4c0423e`. It contains distinct procedural test markers in 8×9 cells; it is a real encoded test image, not a metadata-only header or downloaded artwork. It is not represented as an upstream-supplied pet.
- `ImportFixtures.kt` embeds those exact bytes for common tests; equality with the checked-in files and original manifests was verified. Common tests load bundled Kodee image bytes through Compose resources.

The initial regression first asserted that the V1 fixture succeeds directly in `CodexPetPackageParser`, then failed at the old gallery import assertion. It passed after routing was fixed. Shared coverage verifies both supported classifications/imports, custom `hello` normalization and keys, filename fallback, empty V1 manifest defaults, exact original/generic Kodee distinction, malformed JSON/UTF-8, readable unknown schemas, V2 precedence, invalid/truncated/mismatched images, retained typed parser diagnostics, and no gallery mutation on failure. Gallery tests exercise selected custom action and floating-host model handoff.

## Fresh verification

Commands (all through `rtk`):

```sh
./gradlew --no-configuration-cache -Pcompose.desktop.packaging.checkJdkVendor=false :example:shared:jvmTest :pets-core:jvmTest :pets-core:checkKotlinAbi :example:desktopApp:createDistributable :example:androidApp:assembleDebug
./gradlew :example:shared:jvmTest :example:shared:iosSimulatorArm64Test
xcodebuild -project example/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=79BF70F8-7FD9-4B15-95C5-3D83BC8E8920' -derivedDataPath /tmp/pets-codex-v1-ios-derived CODE_SIGNING_ALLOWED=NO build
```

All succeeded. Shared example JVM: 11 tests; shared example iOS simulator: 11 tests; core JVM: 173 tests. Zero failures/errors. Core ABI check passed. Existing AGP host-test warnings and Gradle deprecation warnings remain; they are not test failures. Xcode first failed to access simulator services in the sandbox; the elevated retry exited 0 with `BUILD SUCCEEDED`. No library source/API file changed.

## Desktop actual example

Rebuilt and launched `example/desktopApp/build/compose/binaries/main/app/com.pets.example.app`. Direct tool observation confirmed original V2 rejection text with disabled Add, and subsequently a successfully added “Josuke & Crazy Diamond” pet with Codex action chips. The user supplied “all is work now” in response to the explicit successful-import, animations/actions, and floating-pet check, then reiterated “now its work(v1)”. Record these successful desktop runtime checks as user-confirmed, rather than independent captured floating-window evidence. The exact external V1 selected paths were requested but not supplied; no downloaded package/container identity is inferred. Shared permanent fixtures supply reproducible parser evidence for both supported formats.

## Android actual generic regression

Fresh APK installed and launched on `emulator-5554` (API 36.1). Re-ran the existing `/tmp/pets-acceptance-fixtures/pet.json` + `spritesheet.webp` package through actual JSON and image pickers (Downloads/PetsAcceptance, image picker More → Browse). “Acceptance Pet” animated preview appeared and Add succeeded. The pager showed Acceptance Pet with Happy/Hello/Idle/Jump/Rest keys. Hello selection was exercised. This establishes the previously passing generic import route after the shared change. A V1-specific Android pair was copied to Downloads/PetsV1RemediationCodex for further checks; direct V1 Android selection is not claimed in this report. Shared router is platform-independent and its JVM/Native tests exercise the V1 route.

## iOS

Fresh simulator application build succeeded and shared Native tests passed. Direct iOS document-picker UI remains unverified; the prior Simulator UI access limitation is not represented as a build failure. No platform-specific Codex routing was added.

## Remaining P2 and final gate

`.codex-pet` container import remains unverified until an exact file is available for inspection. No claim is made about whether the reported codex-pets.net download is V1, V2, or an archive. Import continues to accept a separate manifest and spritesheet; no archive implementation, filter expansion, or V2 parser was added in this remediation.

Direct iOS picker UI and physical multi-monitor verification remain unavailable/unverified P2 checks. Direct Codex V1 Android selection was practical preparation only, not a recorded runtime pass.

**Final gate: PASS WITH P2**, relying on direct user acceptance for desktop supported imports/animation/floating host, fresh Android generic import, green shared/core tests and iOS build, explicit example V2 rejection, explicit parser ownership, and unchanged library semantics/ABI. Automated and direct-tool evidence are separated from user acceptance above.

The user subsequently authorized committing this V1 remediation and a separate V2 library feature. That later work does not alter this report's V1-only scope or evidence. No publication or tag.
