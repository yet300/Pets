# Targeted full-project remediation re-verification

Date: 2026-09-27. **Final gate: REMEDIATION REQUIRED.** Confidence: **high** for the reproduced local behavior, ABI, Apple type identity, and hosted CI failure; **moderate** for the absence of any other replacement P0/P1 defect; **unknown** for the runtime safety of the duplicate iOS KLIB metadata and the unexecuted hosted Windows/Swift legs.

This review re-verified F-01 through F-11 at `6411fabede1b98e2186ff920529338b86513ae0b` on `main`. It did not repeat the prior full adversarial audit, change production code, create a tag, or start Phase 4. The checkout was clean at the start. The only retained source change is this report. Temporary cross-layer and cancellation tests were removed after execution.

## Repository and lineage

`git status --short` was empty initially. The last 25-commit graph (18 commits exist) places the remediation commits in this order between `b7ac3da` and the current documentation commits:

| Change | Resolved full SHA |
| --- | --- |
| Core raw parsing and probe | `bc6eae0621cbf9fc741dea0979c2a2b005618e68` |
| Exact DEFLATE consumption | `47e5f40af1f6f4bbdc6104e73fbc656cbbe555ab` |
| Compose limits, async decode, playback | `7de980377cf781b7a14b824c63c41918767ad9c2` |
| Apple umbrella framework | `b1971d8f5f0fb48774a5a9833ad5b7581d4b6925` |
| CI additions | `af137e25e3b8cbb3d14b3bee0e33f0f8e55d85a7` |

`git tag --list` contains exactly `phase-1-core` and `phase-2-io`. The former resolves to `2818fa3577b0e31ffdc64a027f38056ab901960c`; the latter to `49484c1186807f9daa9581c42b308588426beb37`. `phase-3-compose` does not exist. All five remediation SHAs are ancestors of the tested HEAD.

## Finding disposition

| ID | Result | Confidence | Evidence and limit |
| --- | --- | --- | --- |
| F-01 | **CLOSED** | High | Public `PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)` checks both caps, invokes core's encoded-image probe, validates compatibility, and returns typed `PetParseOutcome`. No caller-created `SpritesheetInfo`, internal probe, or IO module is required. Raw-pair test and module graph pass. |
| F-02 | **CLOSED** | High | Public Compose path checks `>8,388,608` bytes before `copyOf` and decoder; boundary tests at cap−1/cap/cap+1 count decoder calls. A real 1×1 PNG against a 1536×1872 definition and separate wrong-width/wrong-height cases end `Failed` with no draw parameters; full-size atlas reaches `Ready`. Core probe rejects unsupported/animated formats before platform decode. |
| F-03 | **CLOSED** | High | Initial state is `Loading`; `LaunchedEffect(state)` performs copy and decode in `withContext(Dispatchers.Default)` and publishes on return to the composition context. API 24 full-atlas smoke asserts the decoder is off main. Deterministic A/B latch test keeps B after A completes late. A separate temporary leave-before-completion JVM UI test passed with the abandoned state still `Loading`. No `GlobalScope` or unmanaged decode scope was found. |
| F-04 | **CLOSED** | High | `play(key)` owns intent; `CodexPet(state)` has no animation parameter and does not call `play`. Two renderers see one state/timeline, two states keep independent intent, and successive intent changes sample the new key from time zero. The scheduler reads current state after each wake; a cancelled effect does not carry a captured old key into `refresh`. Two renderers do each run a scheduler effect, but they do not compete over intent. |
| F-05 | **CLOSED** | High for type identity | Fresh `CodexPets.xcframework` contains `ios-arm64` and `ios-arm64-simulator`. `verifySwiftConsumer` runs external `swiftc -typecheck`, importing only `CodexPets` and passing an IO-loaded `PetDefinition` directly to the core sampler while calling `PetPlayerState.play`. Header has exactly one `CodexPetsPetDefinition` declaration and no old prefixed model identity. This is typechecking, not a Swift app runtime test. |
| F-06 | **CLOSED** | High | Core WebP probe rejects VP8X animation flag and `ANIM`/`ANMF` chunks; public raw parser and direct Compose decoder use it. A temporary ZIP/directory test on JVM and iOS returned typed `UnsupportedImageFormat` in both paths. Permanent API 24 and API 36 device tests require `Failed` for animated WebP; neither accepts `Ready`. Static PNG/JPEG/GIF/VP8/VP8L/VP8X passed independent platform vectors. |
| F-07 | **CLOSED** | High | JVM/Android `Inflater.bytesRead` and iOS zlib `total_in` must equal declared compressed size at stream end. The permanent common test accepts exact raw DEFLATE and rejects the prior one-byte tail reproducer, several trailing bytes, concatenation, truncation, and padding. Fresh JVM and iOS suites passed. |
| F-08 | **CLOSED** | High | Normal byte-pair route has 64 KiB/8 MiB caps; String and path extraction routes check UTF-8 manifest length. Permanent tests pass at limit−1 and exact limit, return `InputLimitExceeded` at limit+1. `parseTrustedMetadata` is explicitly documented as an expert, caller-trusted metadata seam that does not authenticate image bytes. Its public presence is defensible because IO needs the seam; the name and KDoc do not promise untrusted raw-pair validation. |
| F-09 | **OPEN — P1** | High | YAML parses and names API 24, Windows JVM, and Swift jobs, but the hosted run at this exact HEAD **failed before running them**. The checked-in Gradle 9.7.0 distribution SHA-256 is wrong, so a fresh runner rejects the wrapper download. This is an executable CI gate failure, beyond the allowed “configured but unproven” P2 case. See detailed evidence below. |
| F-10 | **CLOSED** | High | Current README, SECURITY, architecture, public API proposal, test strategy, and affected KDoc agree on raw pair limits, optional IO, direct Compose cap/geometry, `Loading`→`Ready`/`Failed`, state-owned intent, static formats, animated WebP rejection, host-owned transport, and single Apple umbrella. Historical reports remain historical. |
| F-11 | **DEFERRED P2 (UNRESOLVED P2)** | Moderate | Fresh iOS metadata compilation still reports duplicate KLIB `unique_name` values for `androidx` and `org.jetbrains.androidx` lifecycle, savedstate, collection and related families. Dependency resolution confirms both families. iOS tests, framework links, and Swift typecheck pass, but those results do not prove duplicate runtime classes/symbols are absent from every packaged target. No warning was suppressed. |

F-01 through F-06 are all closed on the requested tested paths. No replacement production P0/P1 defect was found. The newly confirmed **F-09 CI failure is P1 and blocks the final gate**. Counts: P0 **0**, P1 **1** (F-09), P2 **1** (F-11). The hosted gate failure does not invalidate local source and runtime results; it prevents claiming continuous verification.

## Boundary and runtime details

### Raw package, image probe, and Compose

Core owns the one authoritative PNG/JPEG/GIF/WebP encoded metadata probe. IO's previous independent probe files were removed; `PetLoader.finishLoad` calls `EncodedSpritesheetProbe.probe`, as do the public raw parser and Compose decoder. The probe remains metadata inspection, not a promise that all pixels decode. Its PNG IHDR CRC, bounded JPEG scan, GIF dimensions, and VP8/VP8L/VP8X static paths are exercised by the fresh core/IO suites. Representative directory symlink/path, ZIP cap/CRC/overlap, and external ZIP golden tests also passed; this was a touched-path regression check, not a full new security audit.

Compose does not depend on IO in `commonMain`, Android, JVM, or iOS production configurations. Its `jvmTest` IO dependency is solely an integration fixture. The public byte entry independently enforces the cap and static-format probe. `PetPlayerState.completeDecode` compares decoded width **and** height with definition geometry before `Ready`; a mismatch clears the atlas, returns `Failed`, and makes `drawParamsFor` return null. The caller must keep the mutable input array stable until the background snapshot; passing a new array instance requests replacement. This is stated in current docs.

Copy/decode is inside a composition-owned `LaunchedEffect` and `Dispatchers.Default`; completion returns to the effect context before snapshot publication. The old A decoder in the permanent latch test finished after B had published and did not overwrite B. The temporary JVM test removed the composition while decode was blocked, released it afterward, and observed no late publication to the abandoned state. The Android API 24 full 1536×1872 atlas smoke observed a non-main decoder thread and eventual `Ready`. Cancellation of the `withContext` result prevents its publication even when an injected decoder itself blocks non-cooperatively; the platform decode is not forcibly interrupted. A decoder-thrown `CancellationException` is within the decoder's broad `Exception` catch, but the cancelled `withContext` still prevents `completeDecode` when the job is cancelled. No stale `Ready`/`Failed` was observed.

Playback uses explicit `state.play(key)`. Rapid Idle→Waving→Running→Failed calls are synchronous state changes on the UI thread; `play` resets sample and epoch, while a waking scheduler calls `refresh` against the **current** requested key/start timestamp. The permanent two-renderer and separate-state tests pass. `delayMillisForNextFrame` uses quotient plus remainder rather than addition before division: 1 ns, 999,999 ns, and 1,000,000 ns yield 1 ms; 1,000,001 ns yields 2 ms. `Long.MAX_VALUE` cannot overflow that conversion, and nonpositive defensive inputs yield 1 ms, preventing a zero-delay loop.

The pinned Codex checkout's `image` 0.25.9 accepted the tested animated WebP metadata and opened its first frame, according to the reproducer recorded in the remediation report. This library intentionally rejects that container for the cross-platform v1 static atlas contract. The present review independently verified this repository's rejection path and static format matrix; it did not rerun the upstream Rust crate.

### Apple, ABI, and module graph

The release umbrella's `Info.plist` lists precisely `ios-arm64` and `ios-arm64-simulator`. In the generated simulator header, IO success returns `CodexPetsPetDefinition *`, core sampling accepts `CodexPetsPetDefinition *`, and the success/parser-facing APIs use that same declaration. The header has one declaration of that type and zero `Codex_pets_corePetDefinition` references. After removing comments, there are no `Okio`, `Skia`, `Coroutine`, `Decoder`, `ImageBitmap`, `Bitmap`, `Inflater`, or `ZipArchive` names in the public header. `transitiveExport = false` is configured. Legacy individual layer frameworks remain build outputs; the documented Swift consumer topology is the single umbrella.

Fresh `checkKotlinAbi` passed for core, IO, and Compose. IO JVM/Android/KLIB public dumps have no diff against `phase-2-io`; implementation changed internally. Core JVM/KLIB dumps contain the intended `EncodedSpritesheetProbe`, `PetInputLimits`, raw parser outcomes/errors, bounded byte-pair overload, and named trusted-metadata overloads. Compose JVM/Android/KLIB dumps contain `Loading`, `play`, and `CodexPet(state)`; the old renderer animation parameter is absent. No public platform decoder, Okio, inflater, ZIP implementation, bitmap/ImageBitmap, Skia, coroutine scope/dispatcher, or IO type was found in core/Compose ABI. The production graph remains `core <- io` and `core <- compose`; no core back-edge, Compose→IO production edge, Ktor, Coil, or network dependency appears in the module build files.

### F-09: hosted CI is actually broken

`.github/workflows/gradle.yml` parses as YAML. Its matrix uses valid `ubuntu-latest`, `macos-latest`, and `windows-latest` runner names and existing Gradle tasks. The Android job sets up KVM, installs API 24 `default` x86_64 through the emulator action, and invokes `androidConnectedCheck`. There is no Windows-only Unix command, `continue-on-error`, `|| true`, or test secret requirement. The failure is outside YAML syntax:

- `gradle/wrapper/gradle-wrapper.properties` specifies `gradle-9.7.0-bin.zip` but pins `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`.
- The [official Gradle checksum table](https://gradle.org/release-checksums/) identifies the pinned value as the **9.6.1** binary checksum. The correct **9.7.0** binary checksum is `84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`, exactly the downloaded checksum in the hosted log.
- [Hosted run 36301985695](https://github.com/yet300/Pets/actions/runs/36301985695) at `6411fab...` completed **failure**. Its [API 24 job](https://github.com/yet300/Pets/actions/runs/36301985695/job/108571194517) booted the emulator, then the wrapper rejected the download before Gradle tasks began. A standard [Ubuntu ABI job](https://github.com/yet300/Pets/actions/runs/36301985695/job/108571194891) failed on the same checksum. Windows JVM and Swift jobs were cancelled before completing, so they provide no runtime/typecheck evidence in CI.

The local runs succeeded because this workstation already had the distribution installed. This is a deterministic clean-runner blocker. It should be fixed in a separate remediation change; this targeted review was explicitly barred from modifying production/configuration code.

### F-11: dependency duplicate classification

The iOS metadata compiler again warned on identical `unique_name` values, including `lifecycle-runtime_commonMain`, from `androidx.lifecycle:lifecycle-runtime:2.11.0` and `org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.9.6`; analogous warnings cover lifecycle compose, savedstate and collection. The resolved `iosSimulatorArm64CompileKlibraries` graph shows the AndroidX 2.11.0 family through Compose runtime/UI and the JetBrains 2.9.6 UIKit/Compose bridge in the same graph. Their transformed common metadata KLIB manifests both declare `unique_name=lifecycle-runtime_commonMain`; the JetBrains common metadata file observed here contains only a tiny root-package metadata stub, while the AndroidX common metadata file contains `androidx.lifecycle` declarations. That observation alone does **not** prove the final native link has no overlapping runtime implementation. Successful iOS test/framework links and Swift typecheck show no current build or consumer-type failure. Classification: **C — UNRESOLVED P2**, neither “fixed” nor “proven safe.”

## Fresh execution gates

The required combined `--rerun-tasks --max-workers=1 -Dorg.gradle.jvmargs=-Xmx6g` invocation ran all 12 module tasks and finished **BUILD SUCCESSFUL** (195 tasks executed, 3m27s). Fresh XML counts, excluding the later temporary tests:

| Module | JVM | iOS simulator arm64 | Assembly | ABI |
| --- | ---: | ---: | --- | --- |
| Core | 105/105 | 105/105 | Pass | Pass |
| IO | 149/149 | 131/131 | Pass | Pass |
| Compose | 47/47 | 32/32 | Pass | Pass |

All listed test suites had zero failures/errors. The 6 GiB heap was supplied because the prior audit documented a memory-starved iOS release linker failure at the default 2 GiB heap. The scratch IO animated-WebP ZIP/directory test passed 1/1 on JVM and 1/1 on iOS simulator, separately from the baseline counts. The temporary Compose leave-before-decode test passed 1/1 on JVM, then was removed. These were run against production source without production edits.

The mandatory API 24 connected suite freshly executed **14/14** on `pets_api24` (arm64 Android 7.0), including full atlas, off-main decode, static format matrix, malformed input, and animated WebP rejection. The available API 36 suite freshly executed **14/14** on `Resizable_Experimental` (arm64 Android 16). Its first attempt ran **zero** tests because the emulator was still booting and ADB went offline during installation; after boot completion, a fresh rerun executed and passed all 14. Neither result relies on old XML. The API 36 rerun overwrote the connected-test XML, so the API 24 count is evidenced by that run's live Gradle output rather than a retained API 24 XML file. No physical Android or iOS device was used.

The fresh `:codex-pets-apple:assembleCodexPetsReleaseXCFramework :codex-pets-apple:verifySwiftConsumer` invocation completed **BUILD SUCCESSFUL** (40 tasks executed, 2m34s). The Swift verification task invokes `xcrun --sdk iphonesimulator swiftc -typecheck` against the external `codex-pets-apple/swift-tests/Consumer.swift` source and the generated framework. This verifies compile-time type identity, not runtime behavior in an iOS application.

## Final gate and working tree

**REMEDIATION REQUIRED.** F-01–F-08 and F-10 are closed; F-11 remains unresolved P2. F-09 is an actual P1 hosted CI failure: a clean runner cannot execute the tests because the pinned Gradle distribution checksum is incorrect. The new Windows and hosted Swift legs therefore have not supplied successful continuous verification. No `phase-3-compose` tag, Phase 4 work, or production/configuration changes were made.

Final `git status --short` contains only `?? docs/audit/FULL_PROJECT_REMEDIATION_VERIFICATION.md`. Compared with the clean initial checkout, that is exactly the required report; no scratch source remains.
