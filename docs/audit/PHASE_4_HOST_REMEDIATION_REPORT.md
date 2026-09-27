# Phase 4 Host — Remediation Report

## Gate recommendation

**PASS WITH P2** (high confidence for the tested API 24, API 36, macOS, and iOS simulator paths). All four confirmed P0/P1 findings are fixed. The remaining P2 items are Swift enum spelling (P2-06) and the carried dependency warning (F-11). No `phase-4-host` tag was created; samples and publishing were not started.

This report follows [the independent review](PHASE_4_HOST_INDEPENDENT_REVIEW.md). Its starting HEAD was `69e385f5d98007df3579b8e2dbfbf42245610b12`; `phase-3-compose^{}` remains `9504709bd82da7af9ec16d3e7230af3006abba81`. At the start of remediation, the only untracked file was the supplied independent review. The existing tags were `phase-1-core`, `phase-2-io`, and `phase-3-compose`.

## Finding disposition

| Finding | Status | Remediation and evidence |
| --- | --- | --- |
| P0-01: Android `ComposeView` crashes on attach | **FIXED** | The real overlay installs `ViewTreeLifecycleOwner`, `ViewTreeSavedStateRegistryOwner`, and `ViewTreeViewModelStoreOwner` through the supported AndroidX APIs. A dedicated owner has a `LifecycleRegistry`, `SavedStateRegistryController`, and `ViewModelStore`. Real `WindowManager` device tests reached atlas `Ready` on API 24 and 36 without a process crash. |
| P1-01: platform window ignores intent updates | **FIXED** | Android and JVM resources are keyed by state identity, definition, and atlas bytes; visibility and position are synchronized with the existing resource. Real Android and macOS tests cover external move, hide/show, state replacement, mode switch, and disposal. |
| P1-02: Android permission availability is stale | **FIXED** | The capability composable observes the local lifecycle's `ON_RESUME` and rechecks `Settings.canDrawOverlays`. Activity scenario tests cover denial → grant → denial, and a real overlay test covers removal and reattachment after simulated revocation and grant. |
| P1-03: failed Android add leaves phantom attachment | **FIXED** | `AndroidPetOverlayController.show` records the view and params only after `addView` succeeds. Public `PetHostState.platformState` separates actual `Showing`, `PermissionRequired`, or `PlatformRejected` from desired `isVisible`. Injected `SecurityException` tests verify no phantom view and successful retry. |
| P2-01: nonfinite/huge coordinates | **FIXED** | `PetHostState` converts nonfinite input to zero in construction and `moveTo`; negative coordinates remain valid. Platform dp-to-int conversion saturates rather than overflows. Common tests cover nonfinite input. |
| P2-02: missing UI-thread contract | **FIXED** | State control methods document Compose/UI-thread use; desktop window and `ComposePanel` operations are marshalled to the Swing EDT. |
| P2-03: headless JVM reports available | **FIXED** | A headless JVM reports `Unsupported`; pure availability tests cover that branch. |
| P2-04: no iOS InApp UI test | **FIXED** | An iOS simulator `runComposeUiTest` composes the real InApp host with a valid 1536 × 1872 PNG, observes atlas `Ready`, and exercises hide/show/move. `iosSimulatorArm64Test` passed. |
| P2-05: duplicate SystemOverlay ownership | **FIXED** | A state permits one active SystemOverlay owner and rejects a second. Replacing the state disposes the old resource and binds the new one. Tests cover the ownership guard, state replacement, and two independent states. |
| P2-06: Swift enum spelling | **DEFERRED** | Kotlin/Native currently exports `PetHostMode.inapp` and `.systemoverlay`; Swift consumer typechecking passes with these spellings. Changing the public enum model merely for Swift spelling would expand this remediation's ABI scope. |
| F-11: duplicate Kotlin/Native `unique_name` warnings | **DEFERRED** | The same ten warning families remain; no new family, suppression, or iOS link regression was observed. Dependency surgery was explicitly outside this remediation. |

## Android ownership and lifecycle

`OverlayViewTreeOwners.java` is a small Android-only bridge to AndroidX's Java-facing ViewTree APIs. No reflection or resource-tag fallback remains. `OverlayLifecycleOwner` attaches and restores its saved-state controller, enters `CREATED` before view addition, advances through `STARTED` to `RESUMED` after a successful add, returns to `CREATED` on hide, and reaches `DESTROYED` on disposal. Its `ViewModelStore` is cleared on disposal. The `ComposeView` uses `DisposeOnViewTreeLifecycleDestroyed`. The controller removes only successfully added views. No owner type appears in the public host ABI.

The permanent test `realOverlayLifecycleWithGrantedAppOp` uses production `PetHost(..., SystemOverlay)`, the real permission API, a real `WindowManager`, and a valid PNG atlas matching a parsed 1536 × 1872 `PetDefinition`. On both local API 24 (`TYPE_PHONE`) and API 36 (`TYPE_APPLICATION_OVERLAY`) emulators, it observed `PetAtlasState.Ready`, attached `ComposeView`, and `RESUMED` owner state. The attached view measured between 1 and 2,000 pixels in each dimension, with touch enabled and a pet-sized window rather than a fullscreen surface. It verified external `moveTo` in actual `LayoutParams`, hide/removal, show/reuse of the same `ComposeView`, dispatched real drag events changing state and window position, old-view detachment and owner destruction after state replacement, and cleanup on mode switch. The separate dual-state test observed two real attached windows and verified hiding one did not detach the other.

The permission tests use an injected permission seam to exercise changes deterministically while keeping `Settings.canDrawOverlays` as the production check. A real Activity lifecycle transition from `CREATED` to `RESUMED` invalidates the public availability result. A host test verifies permission loss removes the attached view, and a later grant reattaches that same view. A separate host-level injected `SecurityException` between permission check and `addView` leaves `isVisible` true as intent but reports `PermissionRequired`, has no recorded attachment, and successfully retries. A controller-level rejection test independently verifies transactional state.

CI's API 24 emulator job installs the host instrumentation APK, grants `SYSTEM_ALERT_WINDOW` to the deterministic package `com.yet.pets.host.test` with `adb shell appops set`, then runs the Compose and host connected suites. The real overlay test asserts actual permission before proceeding, so a missing grant fails the job rather than silently skipping the regression. Local verification performed the same install/app-op sequence on both API 24 and 36.

## JVM, common, and iOS behavior

On this graphical macOS runtime, the real `JWindow` test observed a displayable, visible, always-on-top, pet-sized window. External `moveTo(120, 140)` changed the real window location; `hide()` made it invisible; `show()` reused that window. InApp mode disposed it, returning to SystemOverlay created one replacement, state replacement disposed the prior window and positioned the new one, and the final mode switch disposed that window. `JWindow` construction, `ComposePanel` creation/content/attachment, visibility, position, and disposal run through `onSwingEdt`; the real adapter is exercised from a non-EDT test. Pure controller tests still cover density conversion and negative positions. Compose desktop density and AWT logical coordinates agreed on the tested Retina display. Mixed-DPI, multi-monitor transitions remain best effort because no second display configuration was available to exercise.

The common host now applies play, pin, and resume in one ordered effect. A host-level test covers same-frame play/pin and rapid requested-key changes before resume, asserting the latest animation intent. State methods normalize invalid coordinates while preserving negative desktop positions. A single active state owner avoids ambiguous simultaneous overlay control; separate state instances remain independent. iOS SystemOverlay continues to report `Unsupported` and renders no cross-app window; the real simulator InApp UI test passed.

## Verification and ABI

Fresh `--rerun-tasks` lower-phase gates passed: `jvmTest`, `iosSimulatorArm64Test`, and `checkKotlinAbi` for core, IO, compose, and host, plus host `assemble` (205 executed tasks). JVM/iOS XML counts were core **105/105**, IO **149/131**, compose **47/32**, and host **25/17**, all with zero failures or errors. Both local Android emulators passed **14 compose + 14 host** device tests, zero failures each. The host suite includes the real overlay and permission regressions. A fresh release `CodexPets.xcframework` assembly and `verifySwiftConsumer` passed (49 executed tasks). The simulator framework header contains exactly one `CodexPetsPetDefinition` interface, no duplicated host-prefixed definition, and the new host platform-state type.

The host ABI diff adds only `PetHostPlatformState` and the readonly `PetHostState.platformState` getter. It exposes no `WindowManager`, `View`, Android exception, `JWindow`, AndroidX lifecycle/saved-state owner, or coroutine scope. Existing lower-phase ABIs passed unchanged. F-11 still reports the same ten `unique_name` families identified in the independent review: lifecycle-viewmodel-savedstate, lifecycle-viewmodel, navigationevent-compose, annotation, collection, savedstate-compose, lifecycle-runtime-compose, savedstate, lifecycle-common, and lifecycle-runtime. The Apple umbrella linked and the Swift consumer typechecked despite those warnings.

Remediation commits:

1. `701e759` — `fix(host): synchronize platform resources with host intent`
2. `9ac8d48` — `fix(host-android): install overlay owners and report attachment state`
3. `6646a3e` — `test(host-android): exercise real overlays with granted app-op`
4. The documentation commit containing this report and the supplied independent review.

**Remaining:** P0: 0; P1: 0; P2: 2 (P2-06 and F-11). Gate recommendation: **PASS WITH P2**. This recommendation covers the tested devices and graphical runtime; hosted GitHub Actions execution and mixed-DPI multi-monitor behavior have not been observed in this local remediation.
