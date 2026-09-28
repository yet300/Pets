# Tamagotchi example implementation report

## Decision

**REMEDIATION REQUIRED. Confidence: high.** The three applications build and launch, and the shared gallery displays Kodee on Android, iOS, and macOS. The remaining acceptance gap is direct visual and interaction verification of the separate macOS floating window during press and drag. The computer-use capture available in this run captured the main application window, not its separate `JWindow`. The iOS import picker and full Android example import/overlay interaction also remain untested by direct UI operation. Source inspection and automated tests support the implementation, but they cannot establish those visual behaviors.

## Baseline and topology

- Starting HEAD: `71e85f0ef5cadd5cf762c977dd539b6668fb59a8`.
- The manually created Wizard project already contained `example/androidApp`, `example/iosApp`, `example/desktopApp`, and `example/shared`. `./gradlew projects` confirmed only Android and desktop Gradle app modules plus the shared module; iOS is the existing Xcode project.
- The working tree was clean at the start. The `wip/tamagotchi-example` branch was not merged.
- Library dependencies added to shared: `:pets-core`, `:pets-compose`, and `:pets-host`. `:pets-io` was not needed because the user supplies the two raw files and `PetsKmpPackageParser.parse(manifestBytes, spritesheetBytes)` already validates that bounded pair.

## Changed files and architecture

- `example/shared/src/commonMain/kotlin/com/pets/example/App.kt`: shared Material 3 screen, horizontal pager, action chips, import dialog, preview, overlay button.
- `example/shared/src/commonMain/kotlin/com/pets/example/PetGalleryState.kt`: session-local pet list, selected real pet, selected action, parser-backed import.
- `example/shared/src/{androidMain,iosMain,jvmMain}/kotlin/com/pets/example/PlatformActions.*.kt`: narrow native file-picking actions; Android settings permission action.
- `example/shared/src/commonMain/composeResources/files/kodee/{pet.pets-kmp.json,spritesheet.webp}`: exact generic Kodee package copied from `assets/kodee`.
- `example/shared/build.gradle.kts`: only the required library and Android picker dependencies.
- `example/desktopApp/src/main/kotlin/com/pets/example/main.kt`: window title.
- `example/desktopApp/build.gradle.kts`: optional `PETS_EXAMPLE_JAVA_HOME` for local packaging with a full JDK.
- `example/iosApp/iosApp.xcodeproj/project.pbxproj`: fixed the Wizard build phase to invoke `:example:shared:embedAndSignAppleFrameworkForXcode` from the repository root.
- `pets-host/src/jvmMain/kotlin/com/yet/pets/host/PlatformPetOverlayHost.jvm.kt`: internal transparency and position handling.
- `example/shared/src/commonTest` and `pets-host/src/jvmTest`: gallery and JVM host regression tests.

The shared gallery owns `ExamplePet(definition, spritesheetBytes)`, the list, and the currently playable real pet. The final pager page is a `+` tile. Selecting it leaves the last real pet and floating overlay intent intact. Importing a valid generic package appends a page and selects it. Invalid packages never enter the list. Imported pets remain in memory for the session.

Animation chips are generated from `selectedPet.definition.animationKeys`; arbitrary keys get a readable text label. `Pet` and `rememberPetPlayerState` draw the pager pet. The shared overlay state receives the same selected action, and `PetHost(..., PetHostMode.SystemOverlay)` owns the floating surface. The overlay is not created on iOS because `rememberPetSystemOverlayAvailability()` returns `Unsupported`. Android shows the concise permission action when required. Closing the floating pet hides/disposes its host while the main application stays open. Changing the selected real pet rebinds the existing host composition to the new definition and bytes. No separate example animation engine or platform overlay window was introduced.

## Desktop host investigation and fix

**Transparency. Confidence: moderate.** The reported old failure was an opaque pet-sized rectangle during click or drag. In the current host source, only `JWindow.background` had explicit alpha zero. The root pane, content pane, and `ComposePanel` had no explicit non-opaque configuration. This was a plausible source of a child background showing through even when the outer window was transparent. The JVM-only fix sets transparent backgrounds and `isOpaque = false` on the root, content, layered/glass panes where applicable, and `ComposePanel`. A real-window test checks the outer alpha and the root/content/panel opaque flags. I launched the macOS app and opened and closed the host window through the UI, but the capture bridge did not include that separate window; therefore visual alpha before/during/after drag is **not confirmed**. No claim of a reproduced and visually fixed macOS flash is made.

**Position and drag. Confidence: high for the pure policy; moderate for physical behavior.** Source inspection confirmed that `JWindow.setLocation` previously accepted effectively unbounded converted coordinates. A pure `recoverableOverlayLocation` policy now uses each `GraphicsDevice`'s usable bounds, including negative virtual coordinates. It chooses the nearest display position leaving at least 48 logical pixels (or the smaller window dimension) reachable on a display. This allows secondary monitors left or above the primary; it does not clamp all coordinates to zero or the primary screen. Controller movement writes the actual constrained position back to host state, preventing a later recomposition from restoring an unreachable request. Tests cover one display, left/above secondaries, different sizes, partial edges, and extreme positive/negative requests.

The previous drag path truncated each `dragAmount` component to an integer before converting it from Compose pixels to dp. It now retains fractional deltas and converts once with `composeDensity`, then the controller converts dp to the AWT logical coordinates used by `setLocation`. No graphics-device transform multiplier was added. The existing JVM conversion tests pass. A physical 100-point Retina drag was not measured in this run.

No Android host implementation, parser, playback semantics, public host API, Codex V2 format, or generic package format was changed.

## Platform results

### Android

- `:example:androidApp:assembleDebug` succeeded. The APK was installed and `com.pets.example/.MainActivity` launched on `emulator-5554`.
- The captured screen shows Kodee, the pager indicators, animation chips, and `Allow floating pet`. This is the expected state before overlay permission.
- The app's overlay app-op was granted on the emulator. Direct interaction with the example's `Open pet`, drag, hide, and import picker was not captured.
- `:pets-host:connectedAndroidDeviceTest` passed **14/14** on the emulator after installing the host test APK and granting `SYSTEM_ALERT_WINDOW` to `com.yet.pets.host.test`. The first run failed only at that prerequisite; the correctly configured rerun passed.

### iOS

- Xcode Debug simulator build succeeded for the booted iPhone 17 (iOS 27.0). The app installed and launched as `com.pets.example.KotlinProject`.
- The captured screen shows animated Kodee and action chips, with no system-overlay action. The import picker code compiled for `iosSimulatorArm64`; opening the dialog and picking files on the simulator were not directly exercised.

### Desktop / macOS

- `:example:desktopApp:run` launched on macOS. `:example:desktopApp:createDistributable` also succeeded with a full JDK 25.
- The packaged app was opened via the macOS UI bridge. The main window showed Kodee, pager indicators, action chips, and `Open pet`. Clicking it changed the action to `Close pet`; clicking close restored `Open pet` while the main window remained open.
- The floating `JWindow` itself was outside the window-scoped capture, so transparency during press/drag, exact drag distance, and on-screen recovery on multiple physical monitors were not visually confirmed.
- The current macOS packaged run was made before the final one-line glass-pane opacity setting; the JVM host tests ran after that setting.

## Verification and artifacts

- `git status --short`, `git rev-parse HEAD`, `./gradlew projects`: baseline inspection.
- `./gradlew :example:shared:jvmTest :pets-host:jvmTest :pets-host:iosSimulatorArm64Test :pets-host:checkKotlinAbi :example:androidApp:assembleDebug`: final successful combined run.
- `./gradlew :pets-host:connectedAndroidDeviceTest`: final successful run, 14/14 with the host test app-op granted.
- `xcodebuild -project example/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=79BF70F8-7FD9-4B15-95C5-3D83BC8E8920' -derivedDataPath /tmp/pets-example-ios-derived CODE_SIGNING_ALLOWED=NO build`: succeeded after the final picker/preview changes.
- `xcrun simctl install booted /tmp/pets-example-ios-derived/Build/Products/Debug-iphonesimulator/KotlinProject.app` and `xcrun simctl launch booted com.pets.example.KotlinProject`: succeeded on the prior successful build; final Xcode rebuild succeeded afterward.
- `./gradlew :example:desktopApp:run`: ran on macOS.
- `PETS_EXAMPLE_JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home ./gradlew --no-configuration-cache -Pcompose.desktop.packaging.checkJdkVendor=false :example:desktopApp:createDistributable`: succeeded. The local Homebrew vendor override was command-line-only.
- `git diff --check`: clean.

Screenshots: [Android](screenshots/tamagotchi-android.png) and [iOS](screenshots/tamagotchi-ios.png). The macOS main-window observation was made through the UI bridge; no persistent screenshot of the separate overlay was available.

## Remaining P2 and recommendation

1. Visually capture the separate macOS `JWindow` before click, while pressed, during drag, after drag, and after changing action; verify no opaque rectangle or flash.
2. Measure a roughly 100-point macOS Retina drag and test recovery with a physical secondary display at negative coordinates.
3. Directly exercise the example Android permission return, overlay action, drag, close, and JSON/image import flows.
4. Directly exercise the iOS JSON/image picker and Add action.

The implementation is runnable and the automated regression gates pass. **Recommend REMEDIATION REQUIRED** until the above direct interaction checks establish the final visual and import acceptance criteria. No app or library was published; no release or phase tag was created.
