# Phase 3 Android Execution Verification

Narrow on-device verification of the committed Phase 3 Android decoder. No
Compose audit repetition, no renderer redesign, no Phase 4 work. No
`phase-3-compose` tag was created by this verification.

## 1. HEAD SHA tested

`b7ac3dac33568ecf9e14bd63498577226b3d6086` (`docs: add Phase 3 compose
implementation report`, on `main`). Verified baselines unmoved:
`phase-1-core` -> `2818fa3577b0e31ffdc64a027f38056ab901960c`,
`phase-2-io` -> `49484c1186807f9daa9581c42b308588426beb37`. `git tag` lists
exactly `phase-1-core`, `phase-2-io` — no `phase-3-compose` exists.

The decoder under test is exactly the committed implementation
(`codex-pets-compose/src/androidMain/.../PetDecoder.android.kt`):
`BitmapFactory.decodeByteArray(...)` -> `Bitmap.asImageBitmap()`, wrapped by
the unchanged common `decodeAtlasBytes` (`Ready`/`Failed` mapping). It was
tested as committed before any harness existed, and never modified.

## 2. Emulator / device models

| Leg | AVD | Device profile | ABI | Model string |
|---|---|---|---|---|
| minSdk | `pets_api24` (created for this gate) | Pixel | arm64-v8a | `Android SDK built for arm64` |
| modern | `Resizable_Experimental` (pre-existing) | resizable phone | arm64-v8a | `sdk_gphone16k_arm64` |

Host: macOS arm64. Both emulators ran headless (`-no-window`, SwiftShader).

## 3. Android API levels

- **API 24 (Android 7.0)** — mandatory minimum (`minSdk = 24`). System image
  `system-images;android-24;default;arm64-v8a` installed for this gate.
- **API 36 (Android 16)** — modern leg via the installed
  `android-36.1/google_apis_ps16k/arm64-v8a` image.

No API 28 leg: the mandate is API 24 plus one modern API, both executed.
Codec behavior between them is monotonic for every static format in the
contract; the single divergence found (animated WebP, §12) is a
scope/contract question, not a boundary that changes any classification.

## 4. Test harness changes

New device-test source set only; production graph untouched:

- `codex-pets-compose/build.gradle.kts`
  - `android { withDeviceTest {} }` — enables the AGP KMP device-test
    compilation (`deviceTest`; task `:codex-pets-compose:androidConnectedCheck`).
    The compilation's default source set is **`androidDeviceTest`**
    (verified via Gradle probe; the conventional `androidInstrumentedTest`
    source set exists but is attached to no compilation under AGP 9.3.1, so
    sources live in `src/androidDeviceTest`). Dependencies are attached with
    `named("androidDeviceTest")` because no type-safe accessor is generated.
  - Device-test copy-task workaround: the Compose Gradle plugin registers
    `copyAndroidDeviceTestComposeResourcesToAndroidAssets` with no
    `outputDirectory` (Compose 1.12.0 + AGP 9.3.1); it is disabled because
    this module ships zero Compose resources (no `composeResources` dir).
- `gradle/libs.versions.toml` — test-only additions (never production):
  `junit:junit:4.13.2`, `androidx.test:runner:1.7.0`,
  `androidx.test.ext:junit:1.3.0`,
  `androidx.test.espresso:espresso-core:3.7.0` (forced past the 3.5.0 pulled
  by compose ui-test — 3.5.0 calls hidden `InputManager.getInstance()`,
  removed on API 36, crashing the compose rule before `setContent`),
  `androidx.compose.ui:ui-test-manifest:1.12.0` (provides the
  `ComponentActivity` for `createComposeRule`), plus the already-catalogued
  `compose-ui-test-junit4:1.12.0`.
- New: `src/androidDeviceTest/.../AndroidDecoderFixtures.kt` (9
  host-generated Pillow/libwebp vectors as Base64; containers verified on
  host: VP8 / VP8L / VP8X / VP8X+ANIM, 2-frame GIF red->blue),
  `AndroidDecoderVerificationTest.kt` (13 tests),
  `AndroidComposeSmokeTest.kt` (1 test).

## 5. Production code changes

**NONE.** `git diff --stat` touches only the two build files above; all new
files are under `src/androidDeviceTest`. The two suite failures seen during
the work were transcription errors in hand-typed Base64 fixture literals
(corrupt 192x208 PNG, bad padding on the VP8X vector), fixed by restoring
the host-verified bytes — production code was never a suspect and never
edited. Failure-totality requirement (§15) held throughout: no uncaught
exception, no process crash on any input on either API.

## 6. PNG results

| Case | API 24 | API 36 |
|---|---|---|
| Tiny valid PNG 12x10 | Ready, 12x10, pixel exact (200,40,60) | Ready, 12x10, pixel exact |
| Truncated PNG (half) | Failed, non-blank reason, no bitmap | Failed |

## 7. JPEG results

| Case | API 24 | API 36 |
|---|---|---|
| Valid JPEG 16x12 (Pillow q92, independent encoder) | Ready, 16x12, pixel ~(200,40,60) tol 30 | Ready, 16x12 |
| Truncated JPEG (1/3 bytes) | Failed, no exception escapes | Failed |

File magic was never the gate: dimensions and pixels are asserted, and the
truncation/magic-corrupt corpus (§14) is rejected.

## 8. GIF results

| Case | API 24 | API 36 |
|---|---|---|
| Static GIF 14x11 | Ready, 14x11, red pixel | Ready, 14x11 |
| Animated GIF 14x11 (red->blue) | Ready, 14x11, first pixel exactly (255,0,0); blue second frame absent (b<100) | Ready, 14x11, first pixel (255,0,0) |

No animation playback required or tested: one deterministic first frame is
the contract, and it is red on both APIs.

## 9. VP8 (lossy WebP) results

16x12 `VP8 `-chunk vector: **Ready, 16x12 on API 24 and API 36**; pixel
within lossy tolerance of the encoded color on API 24.

## 10. VP8L (lossless WebP) results

16x12 `VP8L`-chunk vector: **Ready, 16x12 on API 24 and API 36**; pixel
exact (tol 5) on API 24.

## 11. VP8X (alpha WebP) results

16x12 `VP8X`-chunk vector with alpha checker: **Ready, 16x12 on API 24 and
API 36**. Opaque cell stays opaque (alpha 255) with expected green on
API 24.

## 12. Animated WebP result

VP8X+ANIM vector (red->blue, host-verified 2 frames):

- **API 24: `PetAtlasState.Failed`** (`"undecodable spritesheet bytes"`).
  `BitmapFactory.decodeByteArray` returns null for the animated container on
  the API 24 codec. Typed failure, null bitmap, no exception, no crash.
- **API 36: `PetAtlasState.Ready`, 16x12, first pixel (255,1,0)** — the
  modern codec decodes the first frame, satisfying the static-atlas contract.

Per §23 this is a scope/contract decision, not a decoder defect: the
library consumes the encoded image as a STATIC SPRITESHEET ATLAS, and every
static WebP variant (VP8/VP8L/VP8X) passes on API 24. Recommendation: treat
animated WebP as outside the v1 atlas contract (P2 note, §20), not as a
reason to build an animated-image decoder. No production change made.

## 13. Full 1536x1872 atlas result

On-device-generated legal PNG atlas (quadrant pattern, 15,546 encoded
bytes): **Ready, exactly 1536x1872 on API 24 and API 36**, all four quadrant
pixels exact. The ~11.5 MiB decoded-atlas path executes on the minimum
supported API with no rescaling.

## 14. Malformed-input result

Deterministic corpus — empty, 256 B patterned random, truncated PNG / JPEG /
GIF / WebP, PNG/JPEG/GIF/WebP magic-plus-corrupt-structure: **every case
yields `PetAtlasState.Failed` with a non-blank reason and null bitmap on
both APIs**. No uncaught exception, no crash. No fuzz campaign run, per scope.

## 15. Compose smoke result

`rememberPetPlayerState(definition, pngBytes)` -> `CodexPet(state, Idle)` via
`createComposeRule` on device: **PASS on API 24 and API 36**
(`ANDROID-SMOKE atlasState=Ready`, `CodexPet` node exists, no crash). No
screenshot/golden comparison, per scope.

## 16. JVM / iOS regression result

- `:codex-pets-compose:jvmTest`: **34/34 pass** (baseline 34 holds).
- `:codex-pets-compose:iosSimulatorArm64Test`: **23/23 pass** (baseline 23).
- Core/IO suites untouched (no production change anywhere); their baselines
  are unaffected by test-scope-only additions.

## 17. ABI results

`:codex-pets-core:checkKotlinAbi`, `:codex-pets-io:checkKotlinAbi`,
`:codex-pets-compose:checkKotlinAbi`: **all pass**. Test-scope additions do
not alter the public surface.

## 18. P1-A classification — CLOSED

"Android on-device decode never executed" is discharged by execution: 14/14
device tests pass on API 24 (`TEST-pets_api24(AVD) - 7.0-...xml`) and 14/14
on API 36, running the committed `BitmapFactory.decodeByteArray` +
`asImageBitmap` path with dimension and first-frame pixel evidence, plus the
full-size atlas and the malformed corpus. Evidence, not compilation.

## 19. P1-B classification — CLOSED (with P2 scope note)

"Exotic WebP variants on old Android codecs are OS-dependent": executed for
exactly the variants the v1 package contract can carry — VP8, VP8L, VP8X —
**all Ready on API 24** with expected dimensions. The one OS-dependent
divergence found is animated WebP (Failed on API 24, first-frame Ready on
API 36). Since the renderer contract is one static atlas and static
variants pass on the minimum API, P1-B is closed; the animated-container
divergence is **DOWNGRADED TO P2** as a contract-scope note (§12 above), not
a reproducible decoder failure (failure handling itself is verified total).

## 20. Remaining P0 / P1 / P2

- P0: none. P1: none.
- P2 (carried): no OS accessibility-preference detection (explicit
  `pinToIdle`/`resume` only, per contract); animated GIF/WebP render as
  first frame (contract needs one static atlas).
- P2 (new): animated WebP container returns `Failed` (typed, no crash) on
  the API 24 codec while newer codecs return the first frame; outside the v1
  static-atlas contract — do not build an animation decoder for it.
- Observation (not a finding): raw-`ByteArray` decodes are tagged with the
  device density (observed `bitmapDensity=420` on both emulators) rather than
  `DENSITY_NONE`, but decoded pixel dimensions are exact in every case
  (192x208, 1536x1872, all fixtures), so no resource-density rescaling
  occurs and rendering (explicit src/dst pixel rects) is unaffected.

## 21. Final gate

**PASS WITH P2.** Android compile, AAR assembly, ABI, JVM (34/34), and iOS
simulator (23/23) hold; on-device execution passes 14/14 on API 24 and 14/14
on API 36 with zero production-code changes. Both Phase 3 P1s are closed;
the only open items are the pre-existing and one new P2 scope notes above.
Phase 4 remains unstarted, as required.
