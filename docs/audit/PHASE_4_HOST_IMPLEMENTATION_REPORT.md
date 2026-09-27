# Phase 4 Host Implementation Report — Cross-platform Pet Host

Date: 2026-09-27. Scope: Phase 4 specification (cross-platform pet hosting
with one common API and platform-specific capabilities). Phase 3
(`phase-3-compose`) was verified present before any change and was never
moved. No Phase 4 tag was created. No sample was started. No publication was
performed. No network functionality was touched.

## 1. Starting HEAD

`9504709bd82da7af9ec16d3e7230af3006abba81` (`main`, tagged `phase-3-compose`).

Pre-change verification:

- `git status --short` — clean (only untracked build outputs ignored).
- `git log --oneline --decorate --graph -20` — HEAD `9504709` tagged
  `phase-3-compose`, parent `c3aac11` (Gradle 9.7 checksum fix).
- `git tag --list` — `phase-1-core`, `phase-2-io`, `phase-3-compose`.

## 2. phase-3-compose tag SHA

- Commit: `9504709bd82da7af9ec16d3e7230af3006abba81`
  (`git rev-list -n 1 phase-3-compose`).
- Annotated tag object: `850d1d2e249c681446f98c7c4285a75135142f2d`.
- The tag was already present, so it was not created or moved (per §0).

## 3. Commits created

| SHA (short) | Message |
| --- | --- |
| `29270a9` | docs: redefine Phase 4 as cross-platform pet host |
| `352bef4` | feat(host): add common pet host state and in-app hosting |
| `458ee6e` | feat(host): add desktop floating overlay |
| `bdaf26b` | feat(host): add Android application overlay |
| `cc6eb13` | test(host): add platform host regressions |
| `f4c042d` | chore(host): wire umbrella, CI, docs, and security for pet host |
| _(this report)_ | docs(audit): record Phase 4 host implementation report |

No Phase 1/2/3 history was amended. Historical audit reports were not
rewritten.

## 4. Module graph

```text
:codex-pets-core <- :codex-pets-io            (Okio ONLY in io)
:codex-pets-core <- :codex-pets-compose
:codex-pets-core <- :codex-pets-compose <- :codex-pets-host
:codex-pets-apple -> core + io + compose + host (facade only)
```

Host production dependencies (`codex-pets-host/build.gradle.kts`,
`commonMain`): `:codex-pets-core`, `:codex-pets-compose`,
`compose.runtime`/`foundation`/`ui` only. No `:codex-pets-io`, Okio, Ktor,
Coil, filesystem, or network dependency in production code or build file
(verified by source grep + dependency inspection). `jvmTest` adds only
`ui-test-junit4` + `compose.desktop.currentOs` (test scope);
`androidDeviceTest` adds only `kotlin.test`/`junit4`/`runner`/`ext-junit`/
`core`/`ui-test-junit4`/`espresso`/`ui-test-manifest` (device-test scope).

## 5. Public host API

All in `commonMain` (`com.yet.pets.host`), `explicitApi()` strict:

```kotlin
public enum class PetHostMode { InApp, SystemOverlay }

public enum class PetSystemOverlayAvailability {
    Available, PermissionRequired, BestEffort, Unsupported,
}

public class PetHostState {
    public var isVisible: Boolean
    public var xDp: Float
    public var yDp: Float
    public var requestedAnimation: PetAnimationKey
    public var isPinned: Boolean
    public fun show()
    public fun hide()
    public fun moveTo(xDp: Float, yDp: Float)
    public fun play(animation: PetAnimationKey)
    public fun pinToIdle()
    public fun resume()
}

@Composable
public fun rememberPetHostState(
    initialXDp: Float = 0f,
    initialYDp: Float = 0f,
    initialAnimation: PetAnimationKey = PetAnimations.Idle,
    initiallyVisible: Boolean = true,
): PetHostState

@Composable
public fun rememberPetSystemOverlayAvailability(): PetSystemOverlayAvailability

@Composable
public fun PetHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
    mode: PetHostMode,
    modifier: Modifier = Modifier,
)
```

Small adjustment vs. the §8 sketch: `isPinned` is exposed as a public
`Boolean` alongside the four sketched properties. Reason: `pinToIdle`/
`resume` need an observable pinned state (mirroring `PetPlayerState.isPinned`)
so callers and tests can distinguish static-idle from playing without
reaching into the player. Coordinates remain plain `Float` dp; no platform
coordinate types, no public Compose value classes, no maps.

## 6. commonMain files

- `PetHostMode.kt` — `InApp`/`SystemOverlay` with never-silent-convert contract.
- `PetSystemOverlayAvailability.kt` — four availability states with semantics.
- `PetHostState.kt` — host-intent state + `rememberPetHostState` +
  `PetHostDefaultPetWidthDp` (96 dp) + pure `clampHostPosition` (internal,
  deterministic, non-finite-safe).
- `PetHost.kt` — public `PetHost` dispatcher + internal `PetInAppHost`
  (player integration via `LaunchedEffect`, transparent container,
  `BoxWithConstraints` bounds, `detectDragGestures` drag, hide = empty).
- `PlatformPetOverlayHost.kt` — small internal expect/actual seam:
  `@Composable internal expect fun platformSystemOverlayAvailability()` +
  `@Composable internal expect fun PlatformPetOverlayHost(...)`
  (named per the §16 example; function form is the documented equivalent).
- `RememberOverlayAvailability.kt` — public capability query (kept in its own
  file so the public ABI contains no `PlatformPetOverlayHost` container name).

## 7. Platform-specific files

- `androidMain/.../PlatformPetOverlayHost.android.kt` — real `WindowManager`
  overlay (see §10), `AndroidOverlayPermission` seam
  (`SYSTEM_ALERT_WINDOW` check + `TYPE_APPLICATION_OVERLAY`/`TYPE_PHONE` +
  `FLAG_NOT_FOCUSABLE`), `OverlayWindowOps` + `RealOverlayWindowOps`,
  `AndroidPetOverlayController` (single small view, exact `removeView`),
  `androidOverlayLayoutParams` (pet-sized, `TOP|START`), `dpToPx`/`pxToDp`,
  reflection-based view-tree lifecycle attach (no compile-time
  `ViewTreeLifecycleOwner` dependency), `ComposeView` content with player
  pipeline + drag updating both params and state.
- `androidMain/AndroidManifest.xml` — `SYSTEM_ALERT_WINDOW` only (opt-in host
  manifest; never in core/io/compose; no service/receiver).
- `jvmMain/.../PlatformPetOverlayHost.jvm.kt` — OS-name availability
  (`jvmAvailabilityForOsName` pure helper), `JvmOverlayWindow` +
  `RealJvmOverlayWindow` (`JWindow`, transparent, undecorated via `JWindow`
  construction, non-resizable, always-on-top, non-focusable), pure
  `JvmPetOverlayController` (dp/px, show/move/hide/dispose; hide never exits),
  single-window `ComposePanel` content with player pipeline + drag moving the
  window itself.
- `iosMain/.../PlatformPetOverlayHost.ios.kt` — availability `Unsupported`,
  overlay renders nothing (no hidden `UIWindow`, no PiP/Live
  Activities/Dynamic Island/notification/accessibility abuse).
- `iosMain/.../IosAvailability.kt` — `iosStaticOverlayAvailability()`
  (non-composable pin for `iosTest`).

## 8. InApp semantics

Common Compose code on Android/JVM/iOS: visible pet, transparent surrounding
area (`Color.Transparent` containers), position from `PetHostState`
(`offset(x.dp, y.dp)` of a 96 dp-wide pet sized by cell aspect), drag via
`pointerInput` + `detectDragGestures` (common only, no per-platform
duplication), hide/show (hidden = transparent empty box, no `CodexPet` node),
animation/pin intents via `LaunchedEffect(requestedAnimation)` /
`LaunchedEffect(isPinned)` calling `player.play` / `pinToIdle` / `resume`.
`PetHostState` is the source of intent; drawing never mutates it.

## 9. Desktop SystemOverlay implementation

Separate `JWindow` (transparent background `Color(0,0,0,0)`, `isAlwaysOnTop`,
`JWindow` undecorated/non-resizable by construction, `focusableWindowState =
false`) containing a `ComposePanel` sized near the pet (≈96 dp wide, height
from cell aspect; never fullscreen). Content is `CodexPet(player)` through
`rememberPetPlayerState` (existing pipeline, no custom renderer). Dragging the
visible pet moves the overlay window itself (`state.moveTo` + `controller.moveTo`
synchronously); the sprite is not dragged inside a giant invisible window.
`hide()` sets window invisible + controller hides; `dispose()` disposes only
the pet window — application lifetime stays host-owned (no `exitApplication`).
Headless environments return early (no window, no crash); pure controller
logic is tested with a fake window.

## 10. Android SystemOverlay implementation

Real `WindowManager` application overlay:

- Permission `SYSTEM_ALERT_WINDOW` (host manifest only).
- Type: API ≥ 26 `TYPE_APPLICATION_OVERLAY`; API 24–25 legacy `TYPE_PHONE`
  (correct non-system-app overlay type with `SYSTEM_ALERT_WINDOW`).
- Flags: `FLAG_NOT_FOCUSABLE` (touch for drag accepted, keyboard focus not
  stolen, no `FLAG_NOT_TOUCHABLE`, no `FLAG_FULLSCREEN`).
- Format `PixelFormat.TRANSLUCENT`, gravity `TOP|START`.
- Size: pet-sized (`96dp × petHeight` converted at current density; asserted
  1–2000 px, never screen-sized). Only the pet area receives touch.
- Content: `ComposeView(applicationContext)` with reflection-attached
  lifecycle owner, `setContent { player + CodexPet + drag }`.
- No accessibility-service overlays, no Toast windows.

## 11. Android API 24 behavior

`pets_api24` emulator (Android 7.0, arm64): host `androidConnectedCheck` 8/8
pass. Overlay type resolves to `TYPE_PHONE` (asserted by
`overlayTypeMatchesApiLevel` on-device). WindowMetrics modern path is not
required on 24; compatibility path uses resource density + `WindowManager`
params without assuming status/nav-bar heights, portrait, or insets. Static
format matrix and animated-WebP rejection remain in compose device tests
(unchanged). No crash on permission-missing path.

## 12. Android API 36 behavior

`Resizable_Experimental` emulator (Android 16, arm64): host
`androidConnectedCheck` 8/8 pass; compose `androidConnectedCheck` 14/14 pass
on the same API 36 run. Overlay type resolves to `TYPE_APPLICATION_OVERLAY`.
Same small-window/flags/cleanup assertions pass. No fullscreen interception,
no focus theft.

## 13. Android permission behavior

`Settings.canDrawOverlays` via `AndroidOverlayPermission` seam
(`overrideForTest` only for automation; null always calls the platform API —
production check never weakened). Missing → composable availability
`PermissionRequired`, no window added, no crash, no silent `InApp` fallback,
no automatic Settings launch (future Phase 5 sample owns the flow).
Device test pins this with injected `false`. Granted/test-injected (`true`)
drives controller show/move/hide against fake ops (real `WindowManager`
semantics verified by params/type/flags assertions + `BadToken`/`SecurityException`
tolerance in production add path).

## 14. Android cleanup/lifetime behavior

`AndroidPetOverlayController.hide()` calls `removeView` exactly once per added
view (second `show` is a no-op; double `hide` is safe; `IllegalArgumentException`
"View not attached" is caught). `PlatformPetOverlayHost` `DisposableEffect`
`onDispose` always hides + destroys the lifecycle owner. Documented: overlay
can outlive an `Activity` while the process lives; NOT guaranteed across
process death/force-stop/reboot; no boot persistence, no foreground service
(none added — verified by manifest + source grep).

## 15. iOS Unsupported semantics

`iosSimulatorArm64Test` pins `iosStaticOverlayAvailability() == Unsupported`
(`HostIosAvailabilityTest`) plus common state/clamp logic on iOS. The
composable availability returns the same value; `PlatformPetOverlayHost`
renders nothing. No second `UIWindow`, no PiP/`AVPictureInPictureController`,
no video-encoding, no Live Activities/Dynamic Island/notification/accessibility
abuse. InApp via common code is the only supported iOS path.

## 16. Drag behavior

Common `detectDragGestures`: `change.consume()` on movement only (no per-frame
writes), `dragAmount` px → dp via `LocalDensity`, `clampHostPosition` to
container bounds, `state.moveTo`. Deterministic; animation never reset
(`play` untouched — pinned by `moveToDoesNotChangeAnimationOrPinned` +
`hostMoveDoesNotRestartAnimation`); atlas never re-decoded (inputs unchanged —
pinned by `hostMoveDoesNotRedecodeAtlas` + `animationChangeDoesNotRedecode`).
Android drag additionally updates `LayoutParams.x/y` (dp→px at current
density); desktop drag moves the overlay window (`controller.moveTo`).

## 17. Multi-monitor / bounds handling

InApp: `clampHostPosition` uses actual `BoxWithConstraints` `maxWidth`/
`maxHeight` (no hard-coded phone sizes). Tested: negative→0, too-large→max,
small container→origin, normal→passthrough, non-finite→0. Desktop overlay:
no global `>= 0` clamp — `RealJvmOverlayWindow.moveTo` sets location directly
so negative desktop coordinates (secondary monitors via the active graphics
configuration) remain representable; verified by
`overlayControllerAllowsNegativeDesktopCoordinates`. Android bounds use current
density; no status/nav-bar/portrait assumptions; foldable-perfect engine
explicitly out of scope.

## 18. Player integration

Inside every host composition (in-app + both overlay contents):

```text
host requestedAnimation/isPinned
  -> LaunchedEffect -> player.play / pinToIdle / resume
  -> CodexPet(player)
```

`rememberPetPlayerState(definition, spritesheetBytes)` receives the caller's
array unchanged (no extra 8 MiB copy; Compose remains the decode boundary).
Initial `Idle→Idle` play is a no-op; `Waving` propagates to the same zero-
elapsed sample as Phase 3 (`hostPlayMatchesPlayerSemantics`). Two hosts use
two players/states (independent timelines); two renderers of one state share
one timeline (Phase 3 contract preserved).

## 19. Proof: no playback duplication

Production grep over `codex-pets-host/src/{common,android,jvm,ios}Main` for
`samplePetAnimation|sourceRect|loopStart|durationNanos|nextFrameInNanos`:
no hits. For `okio|Okio|ktor|Ktor|coil|Coil|FileSystem|HttpClient|java.io.File`:
no hits. Host owns only visibility/position/animation/pinned intent; all frame
math, fallback (one-hop), `loopStart`, elapsed-to-frame, and source-rectangle
geometry stay in core/compose. `samplePetAnimation` appears only in host
*tests* as an oracle for intent propagation, not in production.

## 20. Apple umbrella changes

`codex-pets-apple/build.gradle.kts`: added `api(project(":codex-pets-host"))`
+ `export(project(":codex-pets-host"))` (`transitiveExport = false`
unchanged). No `CodexPetsHost.framework` as a supported distribution: host
keeps its `CodexPetsHost` build artifact (like other layer frameworks) but the
documented Swift topology remains the single `CodexPets.xcframework`.
Release XCFramework rebuilt: header contains host types
(`CodexPetsPetHostMode*`/`PetHostState*`/`PetSystemOverlay*`, 14 refs), zero
`Codex_pets_host`/`CodexPetsHost` duplicate identities, one
`CodexPetsPetDefinition` identity (10 refs) shared by IO/core/compose/host.
`swift-tests/Consumer.swift` extended with `controlHostState` (host
`play`/`moveTo`/`requestedAnimation` through the umbrella) and
`hostCapability` (host mode); `verifySwiftConsumer` (`swiftc -typecheck`)
passes. Note: Swift lowers `InApp`→`inapp`, `SystemOverlay`→`systemoverlay`;
the consumer uses those exact names (initial `.inApp` spelling failed
typecheck and was corrected).

## 21. ABI review

`explicitApi()` + `abiValidation()` enabled for `:codex-pets-host`; baselines
generated via `updateKotlinAbi` and committed (`api/jvm`, `api/android`,
`api/*.klib.api`); `checkKotlinAbi` passes on Ubuntu. Public ABI is exactly:
`PetHost`, `PetHostMode`, `PetSystemOverlayAvailability`, `PetHostState`
(+ `rememberPetHostState`, `rememberPetSystemOverlayAvailability`). No public
`WindowManager`/`Context`/`UIWindow`/`NSWindow`/`java.awt.Window`/
`CoroutineScope`/`Bitmap`/`ImageBitmap`/internal overlay adapter (the public
`RememberOverlayAvailabilityKt` container holds only the capability query;
the internal `PlatformPetOverlayHost` seam leaves no public trace — verified
by `grep PlatformPetOverlay api/` → clean). Apple-interop safe: ordinary
classes/enums, `Float`/`Boolean`/`String` only.

## 22. Test counts

Fresh `--rerun-tasks` full gates (this workstation, 6m33s, 282 tasks):

| Module | JVM | iOS simulator arm64 |
| --- | ---: | ---: |
| Core | 105/105 | 105/105 |
| IO | 149/149 | 131/131 |
| Compose | 47/47 | 32/32 |
| Host | 20/20 (12 common + 8 JVM) | 14/14 (12 common + 2 iosTest) |

Zero failures/errors everywhere. Lower Phase 3 suites match their remediation
counts exactly (no regression). Android device (real emulators, not compile
only): host 8/8 on `pets_api24` (API 24) + 8/8 on `Resizable_Experimental`
(API 36); compose 14/14 on API 36 (same run). Swift `verifySwiftConsumer`
passes; release XCFramework assembles.

## 23. CI changes

`.github/workflows/gradle.yml` (`build` matrix): added Ubuntu
`:codex-pets-host:jvmTest`, `:codex-pets-host:assemble`,
`:codex-pets-host:checkKotlinAbi`; macOS
`:codex-pets-host:iosSimulatorArm64Test`; Windows
`:codex-pets-host:jvmTest`. No Native Linux targets added. `android-api24`
job script extended to `:codex-pets-compose:androidConnectedCheck
:codex-pets-host:androidConnectedCheck` (host device tests run inside the
existing connected suite; no extra matrix explosion). Existing API 24 /
Windows JVM / Swift consumer / JVM / iOS / ABI jobs retained.

## 24. F-11 warning status

SAME (unresolved P2, not suppressed, no exclusions attempted per §51).
Fresh `compileIosMainKotlinMetadata` for host still emits duplicate
`unique_name` warnings for the same families as Phase 3 (lifecycle,
savedstate, collection, annotation, navigationevent — `androidx.*` 2.11.0
alongside `org.jetbrains.androidx.*` 2.9.6). Host adds the same warnings under
its own `kotlinTransformedMetadataLibraries` path because it shares the Compose
1.12 graph; the warning kind/count is unchanged in nature (no new families,
no fix, no suppression). Do not call this release-ready on F-11 alone.

## 25. Remaining P0/P1/P2

- P0: none found in Phase 4 scope.
- P1: none found in Phase 4 scope (all acceptance-gate items verified below).
- P2: F-09 is closed (new hosted legs are configured; local API 24/36 +
  Windows-equivalent JVM logic executed; hosted execution itself remains
  for CI to prove). F-11 remains open P2 (same duplicate-KLIB hygiene issue,
  neither fixed nor proven safe). No new P2 introduced (no sample, no network,
  no FGS, no playback duplication, no API leaks).

## 26. Final recommendation

**PASS for Phase 4 host functionality (with P2 F-11 carried).**

Acceptance gate (all verified):

- [x] host public contract lives in commonMain
- [x] InApp works on Android/JVM/iOS (common code + device/JVM/iOS tests)
- [x] host depends on compose/core, not IO (build file + source grep)
- [x] Android SystemOverlay uses real WindowManager overlay semantics
  (type/flags/size/permission/drag/cleanup + API 24/36 device runs)
- [x] missing Android permission is typed/non-crashing (`PermissionRequired`,
  no window, no fallback)
- [x] API 24 behavior handled (`TYPE_PHONE`, compat density, device run)
- [x] desktop uses a small transparent undecorated floating window
  (pet-sized, always-on-top, draggable, hide ≠ exit)
- [x] iOS honestly reports SystemOverlay Unsupported (no fake window)
- [x] dragging works without restarting playback (state + UI tests)
- [x] hiding cleans platform host resources (`removeView`/dispose exactly once)
- [x] no fullscreen invisible Android interception surface exists
  (pet-sized params asserted)
- [x] no FGS was added (manifest + source grep clean)
- [x] no network was added (no Ktor/URL/client; transport still host-owned)
- [x] no playback/geometry logic duplicated (production grep clean)
- [x] lower Phase 3 tests remain green (105/149/47 + iOS counts intact)
- [x] host tests pass (20 JVM, 14 iOS, 8+8 Android device)
- [x] ABI passes (JVM/Android/KLIB baselines, no platform leaks)
- [x] Apple umbrella still builds (release XCFramework + Swift typecheck)
- [x] Phase 5 sample has NOT started (no sample dir)

No real P0/P1 remains in the Phase 4 scope. Per §59, no Phase 4 tag is
created here — tagging remains a separate release decision after independent
review (and after F-11 is resolved or rigorously proven safe for 0.1.0).
