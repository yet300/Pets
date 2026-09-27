# Full-project remediation report

Date: 2026-09-27. Scope: the whole-repository adversarial audit at
`docs/audit/FULL_PROJECT_ADVERSARIAL_AUDIT.md`. This report records remediation
after that audit; it does not revise the immutable Phase 1 or Phase 2 tags.

## Baseline and change record

- Audited HEAD: `b7ac3dac33568ecf9e14bd63498577226b3d6086` on `main`.
- `phase-1-core`: `2818fa3577b0e31ffdc64a027f38056ab901960c`.
- `phase-2-io`: `49484c1186807f9daa9581c42b308588426beb37`.
- The checkout already contained an unstaged Android device harness and two
  untracked historical audit files. The harness was extended and committed;
  `FULL_PROJECT_ADVERSARIAL_AUDIT.md` and
  `PHASE_3_ANDROID_VERIFICATION.md` were preserved without edits.

Remediation commits:

| SHA | Change |
| --- | --- |
| `bc6eae0621cbf9fc741dea0979c2a2b005618e68` | Pure core image probe, bounded raw parser, IO delegation |
| `47e5f40af1f6f4bbdc6104e73fbc656cbbe555ab` | Exact raw-DEFLATE stream and declared-byte consumption |
| `7de980377cf781b7a14b824c63c41918767ad9c2` | Compose atlas limits, async decode, playback ownership, platform regressions |
| `b1971d8f5f0fb48774a5a9833ad5b7581d4b6925` | Single Apple framework and external Swift typecheck |
| `af137e25e3b8cbb3d14b3bee0e33f0f8e55d85a7` | API 24, Windows JVM, and Swift CI legs |

The documentation and this report are committed separately after those source
changes. Neither immutable tag moved; `phase-3-compose` was not created.

## Finding disposition

| Finding | Priority | Status | Evidence and remaining condition |
| --- | --- | --- | --- |
| F-01 | P1 | **FIXED** | `PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)` probes image facts internally; the public raw-pair-to-Compose integration test reaches the correct 192×208 first-frame source rectangle without IO or manual metadata. |
| F-02 | P1 | **FIXED** | Compose rejects encoded bytes above 8 MiB before decode and requires decoded width/height to equal `PetDefinition.geometry` before `Ready`. Public state regressions include 1×1 vs 1536×1872, wrong width, wrong height, empty/corrupt data, and size boundaries. |
| F-03 | P1 | **FIXED** | Initial state is `Loading`; a composition-owned `LaunchedEffect` dispatches copy and decode to `Dispatchers.Default`. Android's legal 1536×1872 atlas reaches `Ready`, and the injected decoder observes a non-main thread. JVM/iOS Skia decoding on that dispatcher passes. |
| F-04 | P1 | **FIXED** | `CodexPet(state)` no longer sets animation during composition. `PetPlayerState.play(key)` owns intent; two renderers of one state observe one timeline, while two states remain independent. Public controls are documented for UI-thread use. |
| F-05 | P1 | **FIXED** | Swift consumers use only `CodexPets.xcframework`, which exports core, IO, and Compose together. The release artifact has iOS arm64 and arm64-simulator slices; its header uses one `CodexPetsPetDefinition` in IO results, core sampling, and Compose-facing state. External `swiftc -typecheck` passes without model conversion. Separately linked layer frameworks are not the supported Swift topology. |
| F-06 | P1 | **FIXED** | Core metadata inspection rejects animated WebP (VP8X animation flag and animation chunks). IO and Compose delegate to that check, so Android API 24 and 36 deterministically fail before platform decode; JVM/iOS regressions pin the same policy. |
| F-07 | P2 | **FIXED** | Raw DEFLATE must reach stream end, consume exactly the declared compressed range, remain within the expansion cap, and match output size/CRC. JVM/Android `Inflater` and iOS zlib implementations expose consumed-byte counts behind one common seam. Valid, trailing 1/multiple bytes, concatenated streams, truncation, and padding are tested. |
| F-08 | P2 | **FIXED** | Normal public raw parser entry points limit UTF-8 manifest bytes to 64 KiB; the raw pair also limits the sheet to 8 MiB. Oversize and malformed inputs return typed errors. `parseTrustedMetadata` names the expert seam and states that image facts are caller trusted. |
| F-09 | P2 | **DEFERRED** | Workflow now has API 24 emulator, Windows core/IO/Compose JVM tests, and macOS Swift typecheck jobs. YAML parses locally and the API 24 suite passes locally. Hosted CI execution, especially Windows and the hosted emulator, has not occurred, so continuous coverage is not yet proven. |
| F-10 | P2 | **FIXED** | README, SECURITY, architecture, public API proposal, test strategy, and affected KDocs now describe the actual four modules, three separate input boundaries, static image variants, async decode, Apple topology, and host-owned transport. Historical reports were not rewritten. |
| F-11 | P2 | **DEFERRED** | Fresh iOS metadata compilation still warns about duplicate KLIB `unique_name` values. The resolved Compose 1.12.0 graph contains `androidx.lifecycle` 2.11.0 alongside `org.jetbrains.androidx.lifecycle` 2.9.6, with similar savedstate/collection families. No safe exclusion was established and no proof of absence of duplicate runtime classes was obtained. Do not suppress the warning or call this release-ready. |

Remaining confirmed findings: **P0 0, P1 0, P2 2 (F-09, F-11)**. Confidence is
**high** for closure of F-01 through F-08 and F-10 on the tested targets;
**moderate** for Apple integration beyond the external typecheck; **unknown**
for the new hosted Windows/emulator CI legs until their first run.

## Contracts and architecture

The dependency direction remains `core <- io` and `core <- compose`; IO remains
optional. The host owns transport. No network client, image-loading framework,
filesystem dependency in core, or IO dependency in Compose was added.

Core owns the single pure encoded-image metadata probe, moved from IO. It
recognizes PNG (including IHDR CRC), JPEG, GIF, and static WebP VP8/VP8L/VP8X
with bounded reads and valid dimensions. IO calls that probe; no duplicate
independent image inspector remains. Normal raw consumption is:

```kotlin
val parsed = PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)
// Success.definition -> rememberPetPlayerState(definition, spritesheetBytes)
// -> CodexPet(state)
```

The parser checks the 64 KiB manifest and 8 MiB encoded sheet limits before
probing or parsing, normalizes the manifest, validates compatibility, and
returns `PetParseOutcome` with typed failures. Direct String and path extraction
entry points apply the manifest UTF-8 byte limit. `parseTrustedMetadata` is
available for already-probed expert integrations; it cannot authenticate the
metadata against image bytes. The core boundary does not provide ZIP, path, or
filesystem checks.

Compose independently enforces its 8 MiB encoded-byte cap and probes supported
static image metadata before platform decode. The caller owns the input
`ByteArray` and keeps it stable until the background job snapshots it. Compose
then owns one transient encoded copy, which dies after decode, and one decoded
atlas while the player state lives. A new array instance requests new content.
The state starts `Loading`; an effect bound to that state performs the copy and
decode on `Dispatchers.Default`. Leaving composition or replacing an input
cancels the old effect. A deterministic A/B latch regression releases the old
decoder after B has published and verifies that A cannot overwrite B. `Ready`
is published only after decoded atlas dimensions equal definition geometry;
otherwise the state is `Failed` and no draw parameters are produced.

Animation intent is explicitly changed through `PetPlayerState.play(key)` on
the Compose/UI thread. `CodexPet(state)` only observes state and renders it.
Two renderers sharing the state display its one timeline; independent playback
requires separate states. The scheduler rounds a positive submillisecond frame
delay up to 1 ms without overflowing a nanosecond value.

### Animated WebP decision and upstream check

Contract: v1 accepts static PNG, JPEG, GIF (first frame), and WebP VP8, VP8L,
and VP8X; it rejects animated WebP containers consistently. The pinned Codex
source checkout was `55543d87724bb66bdd51bde65254feb9b4c9ed10`, whose
`Cargo.lock` pins Rust `image` **0.25.9**. An independently generated 16×12
animated WebP fixture from the device vector suite was tested with that exact
crate. `image::image_dimensions` returned `Ok((16, 12))`, and `image::open`
returned an image with dimensions `(16, 12)`. Thus the rejection is a
deliberate cross-platform restriction relative to the pinned CLI's first-frame
behavior: Android API 24 cannot decode the container, while adding an animated
WebP decoder would expand the v1 static-atlas dependency and maintenance scope.
Core, IO, Compose, and Android device tests pin the restriction.

### ZIP and Apple details

Okio's inflater source did not expose reliable exact input consumption across
the supported targets: its prefetch obscured trailing bytes and an attempted
prefix check did not establish stream end on iOS. The final small
`inflateRawExact` seam uses each platform's inflater stream status and
consumed-byte counter. Common ZIP reading still verifies declared output size
and CRC. The extra compressed bytes from the audit's F-07 reproducer now fail.

The supported Apple package is one `CodexPets` XCFramework. Core/IO/Compose
symbols are exported once with `transitiveExport = false`; this avoids exposing
unrelated transitive Compose/Okio types. The release XCFramework was assembled
with a 6 GiB Gradle heap and one worker, and its `Info.plist` lists `ios-arm64`
and `ios-arm64-simulator`. Inspection found one Objective-C declaration of
`CodexPetsPetDefinition`; `PetLoadOutcomeSuccess.definition` and the core
sampler use it. `swift-tests/Consumer.swift` imports only `CodexPets`, obtains
a definition from IO, passes it to core sampling, and calls the Compose-facing
`PetPlayerState.play`; `verifySwiftConsumer` typechecks it successfully.

## Verification and ABI

All 12 requested module gates passed in one Gradle invocation with
`--max-workers=1 -Dorg.gradle.jvmargs=-Xmx6g`: `jvmTest`,
`iosSimulatorArm64Test`, `assemble`, and `checkKotlinAbi` for each of core,
IO, and Compose. The run completed in 3m 16s. Test-result XML counts were:

| Module | JVM | iOS simulator arm64 |
| --- | ---: | ---: |
| Core | 105/105 | 105/105 |
| IO | 149/149 | 131/131 |
| Compose | 47/47 | 32/32 |

The Android `androidConnectedCheck` reran 14/14 tests on API 36
(`Resizable_Experimental`) and 14/14 on API 24 (`pets_api24`). The device smoke
builds a genuine 1536×1872 atlas, verifies eventual `Ready`, and observes the
decoder off the Android main thread. The fixed animated WebP test expects
`Failed` on both APIs. Independent common/JVM/iOS codec vectors cover PNG,
JPEG, GIF, VP8, VP8L, and VP8X; the old mislabeled JVM "lossless" vector was
corrected. Public raw-pair and ZIP/directory-to-renderer integrations check
geometry and first-frame source rectangles. No physical iOS device or Windows
runtime was executed locally.

The release `assembleCodexPetsReleaseXCFramework` and separate
`verifySwiftConsumer` tasks passed. The Swift compile is an external consumer
typecheck, not an iOS app runtime test. The 6 GiB heap is material: the audit
already recorded an unrelated release framework linker OOM with the default
2 GiB heap.

The ABI dumps were regenerated and inspected. Intentional core changes are
`EncodedSpritesheetProbe`, `PetInputLimits`, typed raw input errors, the
bounded byte-pair parser, and the clearly named trusted-metadata overloads.
Intentional Compose changes are `PetAtlasState.Loading`,
`PetPlayerState.play`, and `CodexPet(state)` in place of the animation
parameter. IO's Kotlin public ABI is unchanged. No decoder, `ImageBitmap`,
Okio, coroutine, platform bitmap, or internal probe type was added to the
core/Compose public ABI. These are pre-0.1.0 changes, before a Phase 3 tag.

## Release recommendation

**PASS WITH P2 for the audited Phase 3 functionality; do not publish 0.1.0
yet.** All six P1 findings are closed on the tested paths. F-09 requires the
new hosted jobs to run, and F-11 requires a resolved or rigorously proven safe
iOS dependency graph before release. No Phase 4 work, `phase-3-compose` tag,
or publication was performed.
