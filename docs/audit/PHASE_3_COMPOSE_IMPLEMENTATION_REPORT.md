# Phase 3 Compose Implementation Report

## 1. Verified Phase 1 + Phase 2 SHAs/tags

| Phase | SHA | Tag | Status |
|---|---|---|---|
| Phase 1 core | `2818fa3577b0e31ffdc64a027f38056ab901960c` | `phase-1-core` | verified baseline, unmoved |
| Phase 2 IO implementation | `49484c1186807f9daa9581c42b308588426beb37` | `phase-2-io` (created by this agent: `git tag -a phase-2-io 49484c1… -m "Phase 2 IO verified"`) | independently verified Phase 2 commit, tag created, unmoved |

Pre-work verification run: `git status --short` (clean), `git log --oneline --decorate --graph -10`,
`git rev-parse HEAD` == Phase 2 SHA, `git rev-parse phase-1-core^{}` == Phase 1 SHA.
Neither verified tag was moved afterward (`git tag --list` still shows exactly
`phase-1-core`, `phase-2-io` on the same commits).

## 2. Scope-reset commit SHA

`e4c412fee4762aedd36d248d77b56fcc5d6075fd` — `docs: remove network layer from v1 scope`.
Docs-only (6 files, no production code, no Compose implementation).

## 3. Exact files changed

Scope reset (`e4c412f`): `README.md`, `SECURITY.md`,
`docs/architecture/ARCHITECTURE.md`, `docs/architecture/PUBLIC_API_PROPOSAL.md`,
`docs/architecture/TEST_STRATEGY.md`, `gradle/libs.versions.toml` (Ktor version entry removed).

Implementation (`5c66ff1047615157a9f93e1bc169e35f5cbc5291` — `feat(compose): implement Codex pet renderer`,
17 files, +744/−2):
- `settings.gradle.kts` (+1: `include(":codex-pets-compose")`)
- `build.gradle.kts` (+2: compose plugin aliases `apply false`)
- `gradle/libs.versions.toml` (+3: `compose-ui-test-junit4` catalog entry)
- `.github/workflows/gradle.yml` (+8: compose CI legs)
- `codex-pets-compose/build.gradle.kts` (new, 98 lines)
- `codex-pets-compose/src/commonMain/.../compose/CodexPet.kt` (new)
- `codex-pets-compose/src/commonMain/.../compose/PetAtlasState.kt` (new)
- `codex-pets-compose/src/commonMain/.../compose/PetDecoder.kt` (new)
- `codex-pets-compose/src/commonMain/.../compose/PetPlayer.kt` (new)
- `codex-pets-compose/src/commonMain/.../compose/PetPlayerState.kt` (new)
- `codex-pets-compose/src/androidMain/.../PetDecoder.android.kt` (new)
- `codex-pets-compose/src/jvmMain/.../PetDecoder.jvm.kt` (new)
- `codex-pets-compose/src/iosMain/.../PetDecoder.ios.kt` (new)
- `codex-pets-compose/api/{jvm,android}/codex-pets-compose.api`, `api/codex-pets-compose.klib.api` (new dumps)
- `codex-pets-core/.../core/Identity.kt` (KDoc-only: stale "io/network layers … or URLs" wording fixed; no API change)

Tests (`893dfa6a9a1452bcb1d498675071f80fc68da61b` — `test(compose): add renderer regressions`,
7 files, +705):
- `src/commonTest/.../PetPlayerStateTest.kt`, `DrawParamsTest.kt`, `AtlasDecoderSmokeTest.kt`, `ComposeFixtures.kt`
- `src/jvmTest/.../AtlasDecoderFormatTest.kt`, `CodexPetUiTest.kt`, `PetPackageToRendererTest.kt`

No `phase-3-compose` tag was created (awaits independent verification).

## 4. Revised module graph

```text
:codex-pets-core        (stdlib + kotlinx-serialization-json; no coroutines, no I/O)
  ^                    (optional)                ^
  :codex-pets-io       (core + Okio)             :codex-pets-compose (core + Compose only)
```

There is no `:codex-pets-network`. Compose depends on core, NOT on io
(proven by import scan: zero `pets.io`/`okio`/`ktor`/`coil` imports in all compose
production source sets — `commonMain`, `androidMain`, `jvmMain`, `iosMain` —
and by the `jvmRuntimeClasspath` graph below). A consumer uses `core + compose`
without Okio, filesystem, or ZIP code. IO stays optional; the io→compose flow
is exercised only from `jvmTest` scope (see §20's fixture note and §6).

## 5. Proof Ktor/network removed from scope

- `grep -r ktor gradle/libs.versions.toml` → no matches (entry deleted in `e4c412f`).
- `settings.gradle.kts` includes exactly `core`, `io`, `compose` — no network module exists.
- Current docs (`ARCHITECTURE.md` §4/§6/§11, `PUBLIC_API_PROPOSAL.md` §5, `TEST_STRATEGY.md`,
  `README.md`, `SECURITY.md`) state the removal normatively, including the canonical
  "codex-pets-kmp does not own transport" decision and the explicit
  no-`loadPetZipFromUrl`/no-`DownloadPolicy`/no-HTTP-status/no-redirect-policy/no-URL-identity list.
- Remaining `ktor`/`network` mentions live only in `docs/audit/*` and
  `docs/research/*` (historical evidence, deliberately preserved) plus one KDoc line
  in `PetDecoder.kt` stating there is *no* Coil/network loader.

## 6. Compose dependency graph

Production (`jvmRuntimeClasspath`, top level):

```text
+--- org.jetbrains.kotlin:kotlin-stdlib:2.4.20
+--- project ':codex-pets-core'
+--- org.jetbrains.compose.runtime:runtime:1.12.0
+--- org.jetbrains.compose.foundation:foundation:1.12.0
(plus compose-ui 1.12.0 and skiko 0.150.1 transitively)
```

Conceptually: `core` (stdlib + serialization-json) ← `compose` (core + Compose
runtime/foundation/ui + transitive Skiko). NO Ktor, NO Coil, NO Okio, NO network
library, NO io project dependency in any production configuration. Test scope
additionally uses `ui-test-junit4`, `compose.desktop.currentOs` (Skiko natives
for the test host only), and `project(":codex-pets-io")` (integration fixture
only — see §20).

## 7. Public Compose API

Reviewed from the committed ABI dumps (`api/jvm`, `api/android`, `api/*.klib.api`):

```kotlin
@Composable
fun rememberPetPlayerState(definition: PetDefinition, spritesheetBytes: ByteArray): PetPlayerState

@Composable
fun CodexPet(state: PetPlayerState, animation: PetAnimationKey, modifier: Modifier = Modifier)

class PetPlayerState {
    val atlasState: PetAtlasState
    val currentSample: PetPlaybackSample
    val isPinned: Boolean
    fun pinToIdle()
    fun resume()
}

sealed interface PetAtlasState {
    data object Ready
    data class Failed(val reason: String)
}
```

Deliberate additions vs the sketch: `atlasState` (§11 — corrupt bytes must be
handleable), `isPinned` + `resume()` (§15 — a one-way pin with no resume path
would be a trap). Everything else from the sketch is preserved. No public
`ImageBitmap`/Skia/Bitmap/`Duration`/decoder/coroutine-scope/clock/scheduler/IO/Okio
surface (see §25).

## 8. Player-state ownership model

One coherent owner: `PetPlayerState` holds the definition, the single decoded
atlas (internal), the requested animation intent, the current sample, the
monotonic animation-start timestamp, the pinned flag, and a scheduler epoch.
Consumers never mutate samples, frame indices, or timers. Animation intent
enters through `CodexPet(state, animation)` and is adopted synchronously and
idempotently inside composition (`adoptAnimation`, internal): same key = no-op,
changed key = record + epoch bump + clock restart from zero (or record-only
while pinned). The frame loop (`LaunchedEffect(state, epoch, pinned)`) restarts
exactly when the clock does, because both are driven by the same epoch.

## 9. Image-decoder design

Spike result (verified against the pinned Compose Multiplatform 1.12.0 / Skiko
0.150.1 artifacts in the Gradle cache before freezing the API):

- (A) A stable Compose/common decoding API exists (`createImageBitmap`) but is
  **internal** — unusable. Rejected.
- (B) Existing Skia dependency already present via `compose-ui`: CHOSEN for
  JVM Desktop + iOS arm64 + iOS simulator arm64 —
  `org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()`,
  both callable from `commonMain` on those targets, zero new dependencies.
- The spike assumption "one decoding API works everywhere" proved FALSE for
  Android: `org.jetbrains.skia` is not exposed on the Android target, so a
  minimal internal `expect`/`actual` (`decodePlatformImageBytes`, one function)
  was required after all: Android actual = `BitmapFactory.decodeByteArray` +
  `asImageBitmap` (two canonical platform calls); JVM/iOS actuals share the
  Skia one-liner. No Coil, no image framework, no network loader, no public
  decoder abstraction.

## 10. Supported image formats by platform

| Format | JVM Desktop (Skia) | iOS arm64 + sim (Skia) | Android (BitmapFactory, minSdk 24) |
|---|---|---|---|
| JPEG | ✅ executed (16×12 round-trip) | ✅ same Skiko codec family; PNG path executed on sim, JPEG shares it | ✅ platform codec (compiled; not executed here — no device leg) |
| PNG | ✅ executed (incl. 1536×1872 profile shape) | ✅ executed on simulator (1×1 smoke) | ✅ platform codec (compiled) |
| GIF | ✅ executed (first frame) | ✅ same Skiko codec family | ✅ platform codec, first frame (compiled) |
| WebP | ✅ executed (1×1 lossless vector) | ✅ same Skiko codec family | ✅ platform codec; exotic WebP variants are OS-codec-dependent (documented) |

Animated GIF/WebP inputs decode to their first frame on every target — sufficient
by contract (one static atlas; frames are subregions). No format was removed
from the Phase 1/2 compatibility contract. Android on-device decode execution
is the one unverified cell (environment limitation; CI has no device leg either
— recorded in §28).

## 11. Decode failure behavior

Synchronous decode at state creation → outcome is final → `PetAtlasState` has
exactly `Ready`/`Failed`, deliberately NO `Loading` (no state added merely
because the example existed). `Failed.reason` is always non-blank
(`"empty spritesheet bytes"` / `"undecodable spritesheet bytes: …"`).
Skiko throws `IllegalArgumentException` on foreign bytes; `BitmapFactory`
returns `null`; both map to `Failed`. No platform decoder exception escapes;
renderer draws an empty aspect-preserving placeholder. Tested: garbage (256 B),
empty, half-truncated PNG, corrupt PNG header, truncated JPEG — all `Failed`,
zero crashes.

## 12. Monotonic timing model

`TimeSource.Monotonic` origin captured at state creation; `nowNanos()` returns
elapsed nanoseconds since that origin (default clock; injectable `() -> Long`
seam for tests). No wall-clock, no `currentTimeMillis`. Animation start =
monotonic timestamp; every `refresh()` recomputes `elapsed = now - start`
absolutely (backward clocks clamp to zero). Restoration story (explicit):
`PetPlayerState` is not `rememberSaveable` (holds a decoded image); process
death recreates it and the animation restarts from frame zero.

## 13. Frame scheduling design

`delayMillisForNextFrame(sample)`: `nextFrameInNanos == null` → `null` (park);
`<= 0` (defensive; core never emits negative) → 1 ms re-check; else
`nanos / 1_000_000` rounded up to ≥ 1 ms (truncation harmless — elapsed is
re-measured absolutely). The `LaunchedEffect` loop samples → waits exactly the
reported delay → resamples; static samples `break` to `awaitCancellation()`
(park until intent change). No 60 FPS polling loop; CPU/battery cost is one
wake-up per frame change.

## 14. Animation-change semantics

- Recomposition with the SAME key: `adoptAnimation` no-ops; effect keys
  unchanged → clock untouched (tested).
- Changed key: old effect instance (and its timer) cancelled by key change;
  new start timestamp recorded; sample restarts from elapsed zero (tested,
  including cross-check that waving restarts at sprite 24 after 2 s of idle).
- Fallback/loop/one-shot behavior comes verbatim from core sampling at the new
  elapsed (tested: pre-completion hold, post-completion single hop at the same
  clock, full-cycle loop restart).

## 15. Reduced-motion/static behavior

`pinToIdle()`: sets sticky pinned flag (epoch bump restarts the effect into the
park branch), cancels the timer, shows `staticIdleSpriteIndex` with
`nextFrameInNanos = null`. While pinned, intent changes are recorded but do not
resume motion (deliberate choice over auto-unpin: a user who stopped motion
should not be re-animated by a programmatic intent change). `resume()` unpins
and restarts the currently requested animation from zero (also deterministic
when called unpinned). No OS accessibility-preference detection in v1
(explicitly deferred by contract); `pinToIdle`/`resume`/`isPinned` is the
cross-platform control. All paths tested.

## 16. Atlas memory model

One decoded atlas per state, never sliced, never re-decoded per frame:
`drawParamsFor()` returns views (offsets/sizes) into the single `ImageBitmap`.
For a CLI V1 pet: one transient encoded copy (≤ 8 MiB Phase 2 cap) + one
1536×1872 RGBA atlas (≈ 11.5 MiB). The copy is dropped after decoding.

## 17. ByteArray ownership/copy policy

`remember(definition, spritesheetBytes)`: `ByteArray` equality is referential,
so a NEW array instance = new image content → exactly one defensive `copyOf()`
+ one decode; the same instance never re-decodes (no multi-megabyte hashing on
any frame). The copy is taken once, decoded once, then dropped — caller-side
mutation after construction can neither corrupt nor silently update rendering;
to display new content the host passes a new array. A new `PetDefinition`
instance likewise triggers one re-decode (both are remember keys). Proven by
the `replacingImageBytesCausesExactlyOneRedeode` UI test (1 → 1 → 2 decodes).

## 18. Geometry/drawing design

`drawParamsFor(spriteIndex)` = `definition.geometry.sourceRectForOrNull(...)`
(the only geometry entry point; no duplicate formulas) mapped to
`DrawScope.drawImage(atlas, srcOffset, srcSize, dstOffset = Zero, dstSize =
full canvas)`. No pixel copies per frame. `null` (failed atlas or invalid
index — defensive only for core-validated samples) → empty placeholder, never
a crash.

## 19. Scaling/aspect behavior

`Modifier.aspectRatio(rectAspect).then(modifier)`: default layout preserves the
sprite aspect (non-square 192×208 → 192/208 ≈ 0.923, asserted); the destination
is the full laid-out size. A consumer-forced non-proportional fixed size fills
that size (documented). Filtering = Compose default (`DefaultFilterQuality`,
bilinear): Codex sprites are smooth illustrations, not pixel art — no filtering
config exposed in v1.

## 20. Tests added + counts

| Suite | Scope | Tests |
|---|---|---|
| `PetPlayerStateTest` (frame-0 start, same-key no-restart, key-change restart, delay scheduling, fallback via core, loop, one-shot hold+hop, pin, resume, pinned-intent, backward clock, delay edges) | common | 12 |
| `DrawParamsTest` (rect 0, row boundary, final sprite, non-square aspect, invalid indices, missing atlas, no-slicing) | common | 7 |
| `AtlasDecoderSmokeTest` (1×1 PNG ready, garbage/empty/truncated → Failed) | common | 4 |
| `AtlasDecoderFormatTest` (PNG/JPEG/GIF round-trips with dims, WebP vector, truncated JPEG, corrupt header) | JVM | 6 |
| `CodexPetUiTest` (enter, virtual-clock animation change, clean leave, one-redecode) via `runComposeUiTest` | JVM | 4 |
| `PetPackageToRendererTest` (hand-built legal 1536×1872 ZIP → `PetLoader.loadPetZip` → decode → frame 0 → rect; STORED entries because io rejects data descriptors) | JVM | 1 |

Fixtures: public core parsing + Base64 1×1 vectors + `javax.imageio`-generated
images + a generated 1536×1872 blank PNG. No vendored binaries, no
third-party assets. No real durations slept (fake/virtual clocks throughout).

## 21. Android result

Compiles (`compileAndroidMain`) and packages (`codex-pets-compose.aar` via
`assemble`, which also emits the identical Android ABI dump). No Android-host
or device test execution — the repo's CI has no Android execution leg for any
module (same standing as core/io); the Android decode actual is two canonical
platform calls compiled in.

## 22. JVM result

`jvmTest`: **34/34 pass** (12 state + 7 geometry + 4 smoke + 6 format + 4 UI + 1 integration).

## 23. iOS simulator result

`iosSimulatorArm64Test`: **23/23 pass** (12 state + 7 geometry + 4 decoder
smoke — real Skia decode and all three failure paths executed on the
simulator).

## 24. Lower-layer regression results

`core:jvmTest` 100/100, `core:iosSimulatorArm64Test` 100/100,
`core:checkKotlinAbi` ✅; `io:jvmTest` 148/148, `io:iosSimulatorArm64Test`
130/130, `io:checkKotlinAbi` ✅. Baselines (`100/100` core, `148 JVM / 130 iOS`
io) hold exactly; no regressions.

## 25. Compose ABI review

Dumps (`api/jvm`, `api/android`, `api/*.klib.api`) reviewed before accepting:
public surface is exactly `PetPlayerState` (3 vals + 2 funs), `PetAtlasState`
(`Ready`/`Failed(reason)`), `rememberPetPlayerState`, `CodexPet`. No accidental
public decoder, `ImageBitmap`/Skia internals, coroutine scope, platform bitmap,
clock, or scheduler member. `checkKotlinAbi` ✅ with `explicitApi()` strict.

## 26. Apple header review

Fresh `CodexPetsCompose` framework built; header inspected:
`PetPlayerState` → `pinToIdle()`, `resume()`, `atlasState`, `currentSample`
(core type, already audited), `isPinned` BOOL; `PetAtlasStateFailed(reason:)`
NSString + `Ready.shared`. No `Duration`/`rawValue`, no Skia/Ktor/Okio/decoder
types, no value-class surprises, no platform image types (the single
"ImageBitmap" header match is prose inside a doc comment, not a type).
`@Composable` entry points remain Kotlin-consumed, as specified.

## 27. Intentional deviations/hardenings

1. `resume()`/`isPinned`/`atlasState` added beyond the sketch (decode-failure
   handling and a defined resume path demanded it).
2. Intent adopted synchronously in composition (convergent idempotent write),
   not only in the effect — otherwise the first frame could show the wrong
   animation.
3. One small internal `expect`/`actual` decoder (spike disproved the
   one-API-everywhere assumption for Android).
4. Intent change while pinned records without resuming (reduced-motion safety).
5. Filtering left at the Compose bilinear default; no v1 knob.
6. `compose.desktop.currentOs` + `project(":codex-pets-io")` in `jvmTest` ONLY
   (Skiko natives for tests; io as integration fixture, never production).
7. `Identity.kt` KDoc de-networked (comment-only, ABI re-verified).
8. Compose CI legs added to `gradle.yml` (mirroring core/io legs).

## 28. Remaining P0/P1/P2

- P1: Android on-device decode execution never run (no device leg anywhere in
  CI; same standing as prior phases). Mitigation: 2-call canonical platform
  path, compiled + ABI-verified.
- P1: exotic WebP variants on old Android OS codecs are OS-dependent
  (documented; Skia targets unaffected).
- P2: no OS accessibility-preference detection (explicit control only, per
  contract).
- P2: animated GIF/WebP render as first frame (contract needs one static
  atlas; documented).
- No P0 items. Phase 4 (Desktop Helpers) has NOT started.

## 29. Final gate recommendation

**PASS WITH P1s** (recommended tag `phase-3-compose` only after independent
verification): no network module exists; Ktor absent from production scope;
current docs no longer promise first-party URL loading; compose → core only
(import scan + dependency graph); one atlas decoded per state; frames drawn as
atlas subregions with no per-frame slicing; core is the only playback engine;
monotonic elapsed time; `nextFrameInNanos` drives scheduling with no polling
loop; same-key recomposition stable; key changes deterministic; `pinToIdle`
parks; malformed bytes fail typed without crashing; PNG/JPEG/GIF/WebP verified
(JVM matrix + iOS-sim smoke) with the Android-execution caveat recorded;
aspect preserved; no platform-image/Skia/`Duration` leaks (ABI + header
reviewed); core/io tests + ABIs green at baseline counts; compose JVM 34/34,
iOS-sim 23/23, assemble + ABI green; Phase 4 not started.
