# Phase 4 Host — Independent Review

## FINAL GATE

**REMEDIATION REQUIRED** (high confidence). Tested HEAD: `69e385f5d98007df3579b8e2dbfbf42245610b12`. `phase-3-compose`: `9504709bd82da7af9ec16d3e7230af3006abba81`. **P0: 1; P1: 3; P2: 7 (six Phase 4 items plus carried F-11).** The implementation report was used only as a map. The actual Android overlay crashes on both tested APIs, and a real macOS overlay ignores `hide()`.

## 1. Repository and tag integrity

`git status --short` was clean at the start; `git log --oneline --decorate --graph -25` shows seven Phase 4 commits after the Phase 3 tag. `git tag --list` contains only `phase-1-core`, `phase-2-io`, `phase-3-compose`; no `phase-4-host` tag exists. Resolved SHAs: Phase 1 `2818fa3577b0e31ffdc64a027f38056ab901960c`, Phase 2 `49484c1186807f9daa9581c42b308588426beb37`, Phase 3 `9504709bd82da7af9ec16d3e7230af3006abba81`. The annotated Phase 3 tag object is `850d1d2e249c681446f98c7c4285a75135142f2d`; both object and commit match the baseline report. Temporary test edits were removed; the final source diff was checked clean before writing this report.

## 2. Module graph and scope

The build files establish `core <- io`, `core <- compose <- host`, and `apple -> core + io + compose + host`. A resolved `:codex-pets-host:dependencies --configuration jvmRuntimeClasspath` graph independently shows project core and compose, with no project IO dependency. Host `commonMain` directly depends on core, compose, and Compose runtime/foundation/UI; it has no IO or transport dependency. Production host source contains no package parser, filesystem/ZIP/network implementation, image decoder/probe, frame sampler, frame-duration math, fallback logic, or sprite rectangle calculation. The host delegates decoding and playback to the Compose player.

## 3. Public API

The JVM, Android, and KLIB ABI dumps expose `PetHostMode`, `PetSystemOverlayAvailability`, `PetHostState`, `rememberPetHostState`, `rememberPetSystemOverlayAvailability`, and `PetHost`. No `Context`, `WindowManager`, `View`, `JWindow`, Swing/AWT, `UIWindow`, coroutine scope/dispatcher, bitmap, platform point, or overlay adapter appears in the public host ABI. `PetHost` deliberately exposes the existing Compose `Modifier`. Kotlin's `internal` state constructor is not a public JVM ABI constructor. See `codex-pets-host/api/{jvm,android}/codex-pets-host.api` and `api/codex-pets-host.klib.api`.

## 4. PetHostState mutability

The implementation report's shorthand `public var` is misleading: **all five setters are `private set`** in `PetHostState.kt:33-51`; the JVM/Android ABI has getters and no setters, and the Swift header marks them `readonly`. External Kotlin/Java/Swift callers cannot assign `state.xDp = NaN`, change `requestedAnimation`, or bypass `show/hide/play/pin` with property setters. There is no public-setter API-freeze finding. However, `moveTo()` itself accepts NaN, infinities, and huge floats unchanged (`PetHostState.kt:63-67`); InApp clamps only its rendered position. This is P2-01. The state uses Compose `mutableStateOf`, but its six control methods do not document a UI-thread/snapshot threading contract. No thread-safety claim is made here (P2-02).

## 5. Common InApp path

`PetHost.kt:54-66` dispatches InApp to the single `PetInAppHost` in `commonMain`, shared by Android, JVM, and iOS. `PetInAppHost` creates one `rememberPetPlayerState`, forwards animation/pin intent to player methods, and draws `CodexPet` (`PetHost.kt:82-98,130-154`). Its drag pointer input is attached to the pet-sized child, not the full transparent root. The caller's modifier applies to the InApp container; it is documented as ignored for SystemOverlay. Size, padding, offset, clip, and pointer modifiers therefore affect the outer InApp container according to normal Compose modifier ordering. A consumer-supplied full-container `pointerInput` can intercept touches by the consumer's own request; the host's built-in pointer input does not do that. `fillMaxSize` on the host's transparent container can occupy the allotted layout area, but it has no built-in whole-area touch handler.

The pure clamp handles negative, huge, nonfinite, zero/tiny container, and pet-larger-than-container inputs deterministically (`PetHostState.kt:125-142`). Desktop overlay deliberately does not use that clamp, so negative virtual-screen coordinates remain representable.

## 6. Play/pin/resume lifecycle

Common InApp and both overlay compositions call `rememberPetPlayerState -> PetPlayerState.play/pinToIdle/resume -> CodexPet`. The player implementation keeps a changed requested key recorded while pinned and `resume()` restarts the requested key from zero (`PetPlayerState.kt:114-124,192-210`). The host uses two separate `LaunchedEffect`s keyed by requested animation and pinned state. Existing tests prove state-method transitions and some position invariance, but **do not adversarially exercise all same-frame play/pin/resume sequences through `PetHost`**. Effect ordering under rapid combined changes, host recreation while pinned, and two compositions sharing one host state remain unverified; this is an unconfirmed risk, not a claimed defect.

InApp movement changes position state and does not key `rememberPetPlayerState` or either playback effect. The decoder copies bytes once inside the Compose player; the host adds no copy. The stock JVM tests for move/no re-decode mimic the player pipeline rather than call `PetHost` for their key assertions; they provide weaker evidence than their names suggest. InApp hide leaves the player remembered above the visibility branch, so ordinary hide/show does not request a new decode. Overlay hide/show behavior is defective (P1-01), so its intended decode behavior is not validated as a working lifecycle.

## 7. Android capability

`Settings.canDrawOverlays` is used when the composable is evaluated; missing permission returns `PermissionRequired` and the Android overlay composable returns without an InApp fallback. The public capability query has no lifecycle observer, polling, refresh parameter, or other snapshot trigger (`RememberOverlayAvailability.kt:10-12`; Android actual `PlatformPetOverlayHost.android.kt:155-162`). A temporary instrumented test changed the permission seam from false to true and waited for idle: the observed public value remained `PermissionRequired` on both API 24 and API 36. Returning from Settings does not itself create a snapshot read that would reevaluate this function. See P1-02. The test varied the production permission seam rather than opening Settings; actual Settings navigation/resume was not exercised.

## 8. Real Android overlay execution

This review **did exercise the production `PetHost(..., SystemOverlay)` path with real permission and real `WindowManager`**. On API 36 (`Resizable_Experimental`, emulator-5554) and API 24 (`pets_api24`, emulator-5556), a temporary device test set the permission override to null, asserted `Settings.canDrawOverlays(context)`, composed a visible SystemOverlay host, and waited for attachment. An initial run used placeholder bytes; a second run generated a valid 1536 x 1872 PNG atlas matching the parsed definition. Test setup installed the instrumentation APK, then ran `adb -s SERIAL shell appops set com.yet.pets.host.test SYSTEM_ALERT_WINDOW allow` and `adb -s SERIAL shell am instrument -w -e class 'com.yet.pets.host.AndroidHostOverlayTest#auditRealOverlayWithValidAtlas' com.yet.pets.host.test/androidx.test.runner.AndroidJUnitRunner` for each serial. Both valid-atlas processes crashed during `ComposeView` attachment with `IllegalStateException: Composed into the View which doesn't propagate ViewTreeSavedStateRegistryOwner!`, at `AbstractComposeView.resolveComposeViewContext(ComposeView.android.kt:361)` through `ViewRootImpl.performTraversals`. The temporary test was then removed. This proves the real add/attach path is reached, and proves it cannot render as shipped. See P0-01.

The production type branch is `TYPE_PHONE` on API 24 and `TYPE_APPLICATION_OVERLAY` on API 26+ (`PlatformPetOverlayHost.android.kt:40-47`). The real tests reach view attachment under those branches and granted app-op, so neither is blocked by a synchronous `BadTokenException` in this setup. Content Ready, dragging, hide/removal, permission revocation while visible, outside-window touch delivery, and repeated lifecycle transitions could not be verified because attachment kills the test process. The production window params are pet-sized, translucent, `TOP|START`, and `FLAG_NOT_FOCUSABLE`, with no fullscreen or focus-stealing flag (`PlatformPetOverlayHost.android.kt:125-149`). These are source/param assertions, not a real post-layout touch test.

## 9. Android ComposeView lifecycle

The overlay builds a `ComposeView(applicationContext)` and attaches a lifecycle owner by reflection or a resource-tag fallback (`PlatformPetOverlayHost.android.kt:181-204,241-251`). It attaches **no `ViewTreeSavedStateRegistryOwner`**, which Compose requires in this real path; the process crash is P0-01. There is no ViewModelStoreOwner attachment either, but no separate ViewModelStore exception was reached. The application-context density and WindowManager path compile and enter attach; orientation/density changes after successful attachment remain untested.

## 10. Android permission behavior

`AndroidPetOverlayController.show()` stores `overlayView` and params **before** `windowOps.addView` (`PlatformPetOverlayHost.android.kt:95-103`). The caller catches `SecurityException` and `BadTokenException` without publishing a failure (`:283-302`), while `PetHostState.isVisible` remains true. Subsequent `hide()` tries to remove the view and tolerates `IllegalArgumentException`. This deterministic control-flow divergence is P1-03. No public typed host-failure state or callback exists. `IllegalArgumentException` from `addView` is not caught and could crash; no claim is made that it occurs during correctly permitted use.

## 11. Android touch and cleanup

Static params and fake-controller tests show a pet-sized window and one `removeView` per successful controller hide. The real attached composition crashes before real drag, outside-window touch, hide/show, double-hide, two windows, disposal, or weak-reference leak checks can run. The host manifest declares only `SYSTEM_ALERT_WINDOW`; production source contains no service, boot receiver, accessibility overlay, MediaProjection, PiP, or foreground persistence. The overlay remains intended to be process-owned. The current inability to complete real cleanup/touch tests is a consequence of P0-01 and is explicitly not counted as passing evidence.

## 12. JVM real window

On this macOS ARM64 workstation, a temporary `runComposeUiTest` composed the **production** SystemOverlay and inspected its real `JWindow`. It was displayable, visible, always-on-top, and pet-sized. After `host.hide(); waitForIdle()`, the same window was still visible; the assertion failed with `hide should hide real JWindow`. The scratch test was removed. This confirms P1-01 on a supported desktop runtime. Window property `isAlwaysOnTop == true` was observed; relative stacking against another process was not tested, and no OS-global stacking guarantee is inferred.

## 13. JVM EDT and lifecycle

`RealJvmOverlayWindow.dispose()` calls `JWindow.dispose()` and does not exit the application, but repeated real dispose/weak-reference tests were not completed. The code does not explicitly marshal JWindow/ComposePanel construction, mutations, or disposal to the Swing EDT (`PlatformPetOverlayHost.jvm.kt:61-103,177-224`). The audit did not establish whether the enclosing Compose effect always runs on EDT: **UNCONFIRMED RISK**, not P1.

## 14. DPI and multi-monitor

Desktop controller tests prove negative coordinates are passed through. A real mixed-DPI or second-monitor environment was unavailable, so scale changes after moving monitors are unverified. The production code fixes the AWT window to 96 x derived-height user units and uses density scale 1 for state-to-location; it also treats Compose drag deltas directly as dp (`PlatformPetOverlayHost.jvm.kt:169-207`). This deserves a mixed-DPI runtime check before claiming conversion correctness; **UNCONFIRMED RISK**. In a headless JVM, `PlatformPetOverlayHost` returns before `JWindow` creation (`:157-161`), but capability still reports `Available` on macOS/Windows based only on `os.name` (`:24-44`), P2-03. Linux reports `BestEffort`; source/docs correctly avoid a Wayland absolute-position or stacking guarantee.

## 15. iOS

The common InApp implementation is compiled into iOS and iOS simulator tests pass. The iOS actual reports `Unsupported` and renders nothing for SystemOverlay (`PlatformPetOverlayHost.ios.kt:15-32`); there is no production iOS `UIWindow`, `NSWindow`, PiP, Live Activity, or accessibility workaround. Existing iOS host tests check availability and common state/clamp only; **they never compose `PetHost(..., InApp)` on a simulator or assert atlas Ready/rendering/hide/show**. The simulator harness's ability to perform that UI test was not established. This coverage gap is P2-04, not proof that iOS InApp fails.

## 16. Multi-host ownership

Two distinct `PetHostState` instances have independent snapshot fields and each SystemOverlay call constructs its own controller/window; stock tests prove independent intent, not two real windows. Using **the same** state in two SystemOverlay calls would construct two windows and two player/decode pipelines, with no single-owner contract documented or enforced (P2-05).

## 17. Mode and visibility transitions

Mode switch changes the `when` branch, so Compose should dispose the old platform effect, but an adversarial runtime mode-switch/visibility-race test was not completed. On JVM, the effect is keyed only by definition and bytes; `LaunchedEffect(state.isVisible)` has an empty body (`PlatformPetOverlayHost.jvm.kt:177-236`). Android likewise has no visibility/position observer outside its fixed effect (`PlatformPetOverlayHost.android.kt:241-303`). Therefore `show/hide/moveTo` after creation and replacing the `state` object without replacing definition/bytes do not synchronize the platform resource (P1-01). This is independently confirmed for desktop `hide()` and source-proven for the other transitions; Android runtime follow-through is blocked by P0-01.

## 18. Apple umbrella and Swift

`codex-pets-apple` exports core, IO, compose, and host into one `CodexPets` framework. A fresh `--rerun-tasks` release XCFramework build and external simulator Swift consumer typecheck both passed (49 executed tasks); the artifact is `codex-pets-apple/build/XCFrameworks/release/CodexPets.xcframework`. The release simulator header has exactly one `@interface CodexPetsPetDefinition` declaration and no `CodexPetsHost`/`Codex_pets_host` duplicate identity. Host state fields are readonly in Swift. Kotlin enum entries lower to Swift `PetHostMode.inapp` and `.systemoverlay`, not idiomatic `.inApp`/`.systemOverlay`; this is P2-06 API ergonomics before 0.1.0. The Swift consumer uses and successfully typechecks those actual spellings.

## 19. ABI

Fresh `--rerun-tasks` `checkKotlinAbi` succeeded for core, IO, compose, and host. The host ABI has no accidental public platform adapter or setter. ABI validity says nothing about the broken runtime overlay lifecycle. No Phase 4 ABI freeze/tag should occur while P0/P1 findings remain.

## 20. CI

The workflow declares Ubuntu host `jvmTest`, `assemble`, `checkKotlinAbi`; macOS host `iosSimulatorArm64Test`; Windows host `jvmTest`; and Android API 24 host `androidConnectedCheck`. `gh run list` returned no hosted run for Phase 4 HEAD: the newest visible run was successful for `phase-3-compose` SHA `9504709`, while this checkout is seven commits ahead of `origin/main`. Hosted Phase 4 execution is therefore unverified. Existing CI's Android host tests use fake ops and never grant overlay permission, so even a green hosted run would not detect P0-01.

## 21. Lower-phase regression

Fresh `--rerun-tasks` JVM and iOS simulator tests, ABI checks, and Swift typecheck completed successfully for core, IO, compose, and host (145 executed tasks). XML test counts were core 105 JVM/105 iOS, IO 149 JVM/131 iOS, compose 47 JVM/32 iOS, host 20 JVM/14 iOS; zero failures. The clean host JVM suite was rerun after scratch-test removal to restore its XML report. Clean stock Android device suites then passed on both local emulators: compose 14/14 and host 8/8 per API. The temporary failing tests were removed before these clean stock runs. These green suites do not contradict the real-overlay crashes: no stock test calls real `WindowManager.addView` with granted permission.

## 22. F-11 status

Fresh `--rerun-tasks` `compileIosMainKotlinMetadata` for both host and lower-phase compose emitted the same ten `unique_name` families: lifecycle-viewmodel-savedstate, lifecycle-viewmodel, navigationevent-compose, annotation, collection, savedstate-compose, lifecycle-runtime-compose, savedstate, lifecycle-common, lifecycle-runtime. No new family, suppression, or iOS link/runtime failure was observed. F-11 remains a carried P2 dependency-hygiene concern, not a Phase 4 blocker by itself.

## 23. Findings

### P0-01 — Real Android overlay crashes during normal permitted use

- **Severity/platform:** P0; Android API 24 and 36. High confidence, reproduced twice.
- **File/lines:** `codex-pets-host/src/androidMain/kotlin/com/yet/pets/host/PlatformPetOverlayHost.android.kt:181-204,241-251,283-302`.
- **Violated contract / expected:** A permitted SystemOverlay must attach a real pet-sized `ComposeView`, render, then support move/hide/disposal without process crash.
- **Actual:** Only a lifecycle owner is installed. On real view attachment, Compose requires a `ViewTreeSavedStateRegistryOwner` and throws `IllegalStateException`; the instrumentation process crashes. The exception is asynchronous and outside the add-path catch.
- **Reproduction:** Install the host instrumentation APK; `adb -s emulator-5554 shell appops set com.yet.pets.host.test SYSTEM_ALERT_WINDOW allow` (API 36), or equivalent with `emulator-5556` (API 24); compose `PetHost` with the parsed 1536 x 1872 definition, a matching generated PNG atlas, `PetHostState()`, and `SystemOverlay` using the real permission seam. Both runs fail at `AbstractComposeView.resolveComposeViewContext(ComposeView.android.kt:361)` through `ViewRootImpl.performTraversals`.
- **Impact/direction:** The primary Android overlay mode cannot render and can terminate the app in normal supported use. Attach all Compose-required view-tree owners with a valid lifecycle and verify real Ready/render/drag/hide/disposal on both APIs. Do not paper over the asynchronous exception with the existing `addView` catch.

### P1-01 — Overlay windows do not follow host visibility/position/state changes

- **Severity/platform:** P1; confirmed on real macOS JWindow, also deterministic Android source path (Android runtime is blocked by P0-01).
- **File/lines:** `codex-pets-host/src/jvmMain/kotlin/com/yet/pets/host/PlatformPetOverlayHost.jvm.kt:177-236`; Android actual `:241-303`.
- **Violated contract / expected:** `PetHostState.show/hide/moveTo` and a new state argument should control the live platform window; hiding must remove or hide its touch surface.
- **Actual:** Window creation/show is inside `DisposableEffect(definition, spritesheetBytes)` only. Desktop visibility effect is empty. No effect observes external position changes; changing state identity is not a key. The real macOS window remained visible after `host.hide(); waitForIdle()`.
- **Reproduction:** Temporarily compose production `PetHost(..., SystemOverlay)` in `runComposeUiTest` on macOS; locate the new always-on-top `JWindow` via `Window.getWindows()`; verify visible, call `host.hide()`, wait, and observe `window.isVisible == true`. The temporary test was removed.
- **Impact/direction:** Hide/show and external move are broken; a visible Android pet window would continue intercepting its pet-sized area after hide once P0-01 is fixed. Bind resource visibility/position to snapshot state and key ownership by the actual state, while preserving one resource per host.

### P1-02 — Android permission availability does not refresh

- **Severity/platform:** P1; Android API 24 and 36. High confidence for lack of automatic invalidation.
- **File/lines:** `codex-pets-host/src/commonMain/kotlin/com/yet/pets/host/RememberOverlayAvailability.kt:10-12`; Android actual `PlatformPetOverlayHost.android.kt:155-162,225-232`.
- **Violated contract / expected:** After a caller grants overlay permission in Settings and returns, availability should become `Available` or have a documented explicit refresh trigger so a previously blocked host can start.
- **Actual:** The query directly checks `Settings.canDrawOverlays` during composition but observes no lifecycle or snapshot state. A temporary device test changed the permission seam from false to true, waited for Compose idle, and still read `PermissionRequired` on both APIs. No public refresh mechanism exists.
- **Reproduction:** Compose `rememberPetSystemOverlayAvailability()` with `AndroidOverlayPermission.overrideForTest=false`; record `PermissionRequired`; set override true without unrelated recomposition; wait for idle; observed value remains `PermissionRequired`. The scratch test was removed. Actual Settings navigation was not performed, so the resume-specific runtime sequence remains a corroboration gap, but the missing invalidation path is explicit in source.
- **Impact/direction:** The app can remain stuck in permission-required UI after a grant until unrelated recomposition/recreation. Observe app resume/permission changes or provide a documented refresh API and bind the overlay host to it.

### P1-03 — Android add failure is swallowed while visibility remains true

- **Severity/platform:** P1; Android. High confidence from deterministic exception/control-flow inspection; actual denied race was not induced.
- **File/lines:** `codex-pets-host/src/androidMain/kotlin/com/yet/pets/host/PlatformPetOverlayHost.android.kt:95-103,113-122,283-302`; `PetHostState.kt:33-35,53-61`.
- **Violated contract / expected:** A failed real `addView` must be observable and must not be represented as a successfully visible pet.
- **Actual:** `controller.show()` stores its view before calling `addView`; the caller swallows `SecurityException`/`BadTokenException`; public `state.isVisible` stays true and there is no failure outcome. The controller may temporarily report showing for an unattached view until disposal.
- **Reproduction:** With an `OverlayWindowOps` whose `addView` throws `SecurityException`, call `controller.show(view, params)` and catch as the platform host does; `state.isVisible` remains true and `controller.isShowing()` is true despite no `addView` success. This follows directly from the cited assignments; the review did not run a separate injected test for this branch.
- **Impact/direction:** Permission races or window-manager rejection produce a silent missing pet and misleading public state. Make add success transactional and expose an actual host failure/status distinct from desired visibility.

### P2 and unconfirmed items

| ID | Item | Evidence / limit |
| --- | --- | --- |
| P2-01 | Nonfinite/huge coordinates accepted by `moveTo` | State retains invalid values; InApp renders clamped; overlay conversion is not a coherent public coordinate contract. |
| P2-02 | No documented snapshot/UI-thread contract | Methods write Compose snapshot state; caller threading expectations absent. |
| P2-03 | Headless JVM capability is optimistic | macOS/Windows return `Available` while headless host returns without a window. |
| P2-04 | iOS InApp actual UI test absent | iOS simulator tests cover state/availability only. |
| P2-05 | Same state in multiple host compositions undefined | Two independent windows/players can be created for one public state; no explicit ownership contract. |
| P2-06 | Swift enum spelling | `inapp` and `systemoverlay` are typechecked but awkward before 0.1.0. |

Unconfirmed risks needing targeted follow-up after P0-01: same-frame play/pin/resume ordering through the real host; Android real touch/drag/cleanup/permission-revocation and repeated show/hide; desktop EDT and mixed-DPI/multi-monitor behavior; real iOS InApp Ready/render path. These are not included in P0/P1 counts.

## 24. Final recommendation

**REMEDIATION REQUIRED.** Fix and rerun real Android overlay tests on API 24 and 36, including attachment/Ready, visibility, drag, touch bounds, cleanup, and failure reporting. Fix state-to-window synchronization on desktop and Android, and permission availability refresh. Keep `phase-4-host` untagged; do not publish or begin the sample phase. After remediation, rerun the lower-phase and Apple gates and review the remaining unconfirmed lifecycle/ownership cases before freezing the host ABI.
