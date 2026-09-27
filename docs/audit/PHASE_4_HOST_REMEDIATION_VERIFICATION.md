# Phase 4 Host — Independent Remediation Verification

## Final gate

**PASS WITH P2** — high confidence for the tested paths. Tested HEAD: `cdea295b61283c1b0598aaecae9490fafaae90dc` (`main`, `origin/main`). The four prior blocking findings are **CLOSED**; this targeted re-verification found no replacement P0/P1. Remaining findings: **P0 0, P1 0, P2 2** (P2-06 Swift enum spelling and carried F-11 Kotlin/Native duplicate-library warnings). No `phase-4-host` tag was created, and this review made no production-code change.

## Target and lineage

The initial `git status --short` was clean. `git rev-parse HEAD` returned the tested SHA above. `git log --oneline --decorate -15` placed the remediation and documentation commits immediately before HEAD. `git tag --list` contained only `phase-1-core`, `phase-2-io`, and `phase-3-compose`; `git branch --list phase-4-host` returned no branch. `phase-3-compose^{}` resolved to the expected `9504709bd82da7af9ec16d3e7230af3006abba81`.

`git merge-base --is-ancestor` passed for all four expected commits:

| Commit | Full SHA | Role |
| --- | --- | --- |
| `701e759` | `701e75928a9b0091bc1afe5d3074458e083c0dd9` | Platform resource synchronization |
| `9ac8d48` | `9ac8d48c13ad8f6d3eb12e495c6aa5dd23d2b1bd` | Android owners and attachment state |
| `6646a3e` | `6646a3e34d4923aaf768d820db5ba671bf2aa0dc` | Permanent real overlay tests and CI grant |
| `cdea295` | `cdea295b61283c1b0598aaecae9490fafaae90dc` | Review and remediation documentation |

## Blocking finding disposition

| Finding | Result | Independent evidence |
| --- | --- | --- |
| P0-01, Android overlay attachment crash | **CLOSED** | Fresh connected tests on real API 24 and API 36 emulators, after granting `SYSTEM_ALERT_WINDOW` app-op to `com.yet.pets.host.test`, each ran `realOverlayLifecycleWithGrantedAppOp` successfully. The test uses production `PetHost(..., SystemOverlay)`, actual `WindowManager`, platform permission check, a generated valid 1536 × 1872 PNG atlas, and actual `ComposeView`; it waits for `PetAtlasState.Ready`, attached view, and `RESUMED` lifecycle. Both XML suites report 14 tests, zero failures/errors/skips. Production `OverlayViewTreeOwners.attach` uses supported `ViewTreeLifecycleOwner.set`, `ViewTreeSavedStateRegistryOwner.set`, and `ViewTreeViewModelStoreOwner.set`. No reflection fallback is present. |
| P1-01, platform window ignores host intent | **CLOSED** | The same real Android test checks external `moveTo` against `LayoutParams`, hide/detach, show/reattach of the same view, touch-driven drag changing state and window coordinates, state replacement destroying/detaching the old view, and SystemOverlay → InApp cleanup. Its bounds assertion limits the attached view to pet scale. The fresh macOS `jvmTest` XML confirms `realWindowFollowsVisibilityPositionAndModeSwitch` ran without skip: actual `JWindow` became visible, moved, hid, showed, and was disposed on mode and state changes. |
| P1-02, stale permission availability | **CLOSED** | `permissionAvailabilityRefreshesOnActualActivityResume` ran on both Android APIs. The public `rememberPetSystemOverlayAvailability()` changed PermissionRequired → Available → PermissionRequired after permission-seam changes and actual Activity `CREATED` → `RESUMED` transitions, without an unrelated recomposition trigger. `realOverlayRespondsToRevocationAndLaterGrantOnResume` covers live overlay removal and reattachment. Production observes `LocalLifecycleOwner` `ON_RESUME` and rechecks `Settings.canDrawOverlays`. |
| P1-03, phantom attachment after `addView` rejection | **CLOSED** | `hostReportsRejectedAddAndRecoversWithoutPhantomView` ran on both Android APIs. Injected `SecurityException` left desired `isVisible` true but set `platformState` to PermissionRequired, with no controller attachment or saved params. After restoring the add seam, a state update retried and reached Showing. Production `AndroidPetOverlayController.show` records the view and params only after `addView` succeeds. |

### Android owner and resource lifecycle

The owner initializes the `SavedStateRegistryController`, restores it, and reaches `CREATED` before attachment; successful show advances through `STARTED` to `RESUMED`; hide returns to `CREATED`; permanent disposal reaches `DESTROYED` and calls `ViewModelStore.clear()`. The real device test checks the active and destroyed states and detached old view. Source confirms the saved-state and ViewModelStore setup and clear; the test does not directly inspect the store's cleared contents. The controller records only successfully added views and clears its record before `removeView`, preventing a second removal. Repeated hide/show and state/mode disposal passed on both devices. The drag test dispatches touch events to the real attached `ComposeView`; it does not inject a physical gesture through the window manager.

The one-state/one-active-SystemOverlay contract is enforced by `PetHostState.claimOverlay`, which rejects a second owner. The common ownership test passed. `twoDifferentStatesOwnIndependentRealWindows` passed on both Android APIs, demonstrating two separate states can host independent real overlays.

## Other targeted gates

| Gate | Result |
| --- | --- |
| Android API 24 | **PASS**, local emulator `pets_api24`, 14/14 host and 14/14 Compose device tests, zero skipped/failures. Real overlay test explicitly asserts platform permission, so a missing app-op cannot silently skip it. |
| Android API 36 | **PASS**, local emulator `Resizable_Experimental`, 14/14 host and 14/14 Compose device tests, zero skipped/failures. |
| Real macOS JWindow and EDT | **PASS**, fresh `jvmTest` XML reports `realWindowFollowsVisibilityPositionAndModeSwitch` and `realWindowMutationsRunOnEdt` executed, zero skips. Production `RealJvmOverlayWindow` marshals construction, size, location, visibility, and disposal through `onSwingEdt`; `ComposePanel` creation, content, and attachment are marshalled likewise. No deadlock occurred. |
| Play/pin/resume | **PASS**, fresh host JVM test `hostPlayPinResumeUsesLatestIntentAcrossSameFrameChanges` executed. It checks pinned idle, multiple same-frame requested changes, and resume to the latest requested animation. |
| Coordinates/headless | **PASS**, `moveTo` normalizes NaN and both infinities to zero; negative finite coordinates remain valid. Android and JVM dp-to-pixel conversion saturates at integer bounds. A headless JVM returns Unsupported; the corresponding JVM availability test passed. |
| iOS InApp | **PASS**, fresh `iosSimulatorArm64Test` XML confirms `HostIosInAppUiTest.inAppCompositionVisibilityAndPosition` ran with a valid atlas, reached Ready, and exercised move/hide/show. iOS SystemOverlay remains Unsupported. The move/hide/show assertions inspect host state; the test does not separately inspect rendered pixels. |
| ABI | **PASS**, fresh `checkKotlinAbi` for core, IO, Compose, and host. Host ABI contains `PetHostPlatformState` and the `platformState` getter. No public `WindowManager`, Android exception, AndroidX owner, `JWindow`, or `CoroutineScope` appeared in the host ABI dumps. |
| Apple | **PASS**, fresh release `assembleCodexPetsReleaseXCFramework` and `verifySwiftConsumer` both succeeded. Each iOS device/simulator header contains exactly one `CodexPetsPetDefinition` interface. |
| Lower-module regression | **PASS**, fresh JVM and iOS simulator suites for core, IO, Compose, and host; fresh Compose and host connected tests on both Android APIs. |

## Hosted CI at the tested HEAD

[GitHub Actions run 36318782295](https://github.com/yet300/Pets/actions/runs/36318782295) completed successfully for exact SHA `cdea295b61283c1b0598aaecae9490fafaae90dc`. Its 22 jobs include Android API 24, host JVM on Ubuntu and Windows, host iOS simulator, host and lower-module ABI, lower-module JVM/iOS gates, and Swift consumer verification. The API 24 job log shows `adb shell appops set com.yet.pets.host.test SYSTEM_ALERT_WINDOW allow`, then 14 Compose and 14 host tests on the Android 7.0 emulator and a successful build. The permanent real overlay test is in that 14-test host suite and asserts real permission before attachment; local XML independently confirms it is executed rather than skipped.

## Remaining P2 and limits

- **P2-06:** Swift export spells `PetHostMode.inapp` and `.systemoverlay`; Swift consumer typechecking succeeds.
- **F-11:** Fresh Compose and host iOS metadata compilation succeeded and emitted the same ten duplicate `unique_name` families recorded in the prior review: lifecycle-viewmodel-savedstate, lifecycle-viewmodel, navigationevent-compose, annotation, collection, savedstate-compose, lifecycle-runtime-compose, savedstate, lifecycle-common, and lifecycle-runtime. No new family, warning suppression, or Apple link/runtime failure was observed. No dependency surgery was attempted.

The tests do not cover a mixed-DPI multi-monitor desktop setup or a physical Android touch injection. These are coverage limits, not observed P0/P1 defects. The targeted evidence above is sufficient to close the four prior blockers on the tested platforms.

**Final gate: PASS WITH P2.**
