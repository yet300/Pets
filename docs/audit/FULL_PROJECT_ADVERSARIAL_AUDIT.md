# FINAL GATE — REMEDIATION REQUIRED

Audited HEAD: b7ac3dac33568ecf9e14bd63498577226b3d6086 on main, plus the pre-existing unstaged Android device-test harness and untracked Phase 3 Android verification files described below. P0: **0**. P1: **6**. P2: **5**. Confidence: **high** for reproduced findings, **moderate** for the shared-state consequence and release impact of Apple framework duplication. This is an audit of the actual dirty checkout, not merely HEAD.

The production source was not edited. No tag was created, Phase 4 was not started, and no fix is included. Scratch Kotlin and Swift source files were removed after execution. The gate is blocked by confirmed public-workflow, rendering-boundary, UI-thread, Apple-interoperability, and Android compatibility defects.

## 1. Git / baseline integrity

Initial status: two unstaged files, codex-pets-compose/build.gradle.kts and gradle/libs.versions.toml; untracked codex-pets-compose/src/androidDeviceTest/ and docs/audit/PHASE_3_ANDROID_VERIFICATION.md. No staged changes. The unstaged diff adds Android device-test configuration and dependencies only (36 added lines); the untracked files contain the existing device tests and report. They were audited as part of the current state and preserved.

HEAD is b7ac3dac33568ecf9e14bd63498577226b3d6086. phase-1-core resolves to 2818fa3577b0e31ffdc64a027f38056ab901960c and phase-2-io to 49484c1186807f9daa9581c42b308588426beb37, matching the stated immutable baselines. phase-3-compose is absent and remains absent. The sole worktree is main at HEAD. The last commits are b7ac3da, 893dfa6, 5c66ff1, e4c412f, 49484c1, 14962c3, 857acdf, c001691, 2818fa3. Comparing current io source to phase-2-io yields no difference. Core changes since phase-1-core are documented Phase 2 additions/adjustments, including GIF/JPEG enum entries and parser acceptance; no silent later change to io was found.

The pinned upstream checkout at /private/tmp/codex-repo resolves exactly to openai/codex 55543d87724bb66bdd51bde65254feb9b4c9ed10. Compatibility statements below use its codex-rs/tui/src/pets/model.rs, ambient.rs, and catalog.rs, not upstream HEAD.

## 2. Module graph

Gradle production edges are core <- io and core <- compose. Core uses kotlinx serialization JSON; io uses Okio; compose uses Compose runtime/foundation/ui. The io edge in compose is jvmTest-only, for the package-to-renderer integration fixture. No production network module, Ktor, OkHttp, Coil, URL loader, or URLSession abstraction was found. The configured targets are Android (minimum API 24), JVM, iOS arm64, and iOS simulator arm64. Phase 4 desktop helpers are absent as intended.

## 3. End-to-end API usability

The ZIP path succeeds on JVM with a complete, Java-encoded 1536x1872 PNG package in PetPackageToRendererTest: PetLoader returns a definition and bytes, Skia decodes the atlas, and the renderer state resolves a 192x208 source region. This exercises a real encoded image and a reference ZIP writer, although it does not draw a pixel golden.

The independent core+compose path is incomplete. A minimal consumer with only JSON and encoded bytes reaches PetPackageParser.parse(json, fallbackId, spritesheetInfo), but no public API supplies spritesheetInfo from the bytes. The only probe is io.internal.image.ImageProbe; the Compose decoder is internal and itself returns no public dimensions. The host must implement image header probing and format recognition, or add io and wrap its bytes as a ZIP/directory. Constructing SpritesheetInfo(1536, 1872, PNG) by assumption compiles but defeats validation. This directly contradicts the optional-io raw-pair path described in docs/architecture/ARCHITECTURE.md §5.1 and §6. See F-01.

## 4. Core compatibility

The internal DTO shape follows pinned upstream PetFile: optional identity, spritesheetPath, frame, and animations; unknown fields are ignored. Core defaults spritesheet.webp, trims identity/path values, lets manifest id outrank the caller fallback, merges sorted custom animations over defaults, and validates exact fallback names without trimming them. The profile is 1536x1872, 8 columns, 9 rows, 192x208 cells, 72 default frames, maximum grid capacity 256, and FPS at most 60. The pinned Rust source uses the same atlas, default table, and custom animation rules. Kotlin negative Int inputs can produce typed semantic errors where Rust unsigned deserialization rejects earlier; this is an error-taxonomy difference, not an acceptance difference. Kotlin and Rust Unicode trim and numeric conversion differences were inspected, but exhaustive differential execution for all Unicode/number strings was not done.

## 5. Core playback/math

Playback.kt samples elapsed <= 0 at zero, uses exact frame boundaries, saturates total nanos, performs one fallback hop using the original elapsed value, and bounds work by frame count rather than elapsed. A single-frame loop yields no next wake-up; a one-shot schedules its fallback transition. Geometry.kt rejects negative/out-of-capacity indices and uses Long arithmetic before Int conversion. The existing JVM and iOS core suites each ran 100 tests. Rust Duration::from_secs_f64(1/fps) rounds to nanoseconds; Kotlin Double.seconds.inWholeNanoseconds matches tested practical values 8, 24, 59.94, and 60. Very small positive FPS has a representational difference: Kotlin can saturate nanoseconds whereas Rust can represent longer whole-second durations; this is an extreme edge outside practical playback, recorded under F-10.

## 6. IO directory security

DirectoryPetLoader canonicalizes the root, gives pet.json precedence, resolves manifest and asset symlinks, checks segment-wise containment, and reads manifest and sheet through actual-stream caps. A preferred unsafe manifest does not fall back to avatar.json. Root symlink identity is the canonical target basename (line 84), matching pinned Rust load_pet_path after canonicalization. The root itself may be a symlink; a concurrently mutated filesystem remains a documented TOCTOU limitation. The read loop checks actual bytes after each 8192-byte read, so a swapped larger target cannot bypass the cap. FakeFS and real-filesystem tests are present; I found no new escape in the reviewed paths.

## 7. IO ZIP security

ZipArchive parses EOCD, central and local headers, declared sizes/CRC/flags, relative offsets, and central-directory bounds; it rejects unsupported methods, data descriptors, encryption at package level, ZIP64 sentinels, and overlapping entry spans. The overlap check compares each complete localHeaderStart..dataEnd span, including headers and payloads, not merely payload against payload. Equal endpoints are allowed; sharing one header is rejected through span overlap or central/local name mismatch. ZIP entries are never extracted. Crc32 uses the standard reflected IEEE polynomial and is checked for empty and nonempty selected data.

The DEFLATE reader does not prove all declared compressed bytes were consumed. A scratch JVM test inserted 0x7f after a valid raw DEFLATE stream inside the selected spritesheet entry, increased both local and central compressed-size fields and the EOCD offset, and observed PetLoadOutcome.Success. Output length and CRC still matched. This is F-07; no traversal, decompression bomb, or data loss was demonstrated.

## 8. IO image probing

PNG validates the signature, first IHDR, positive dimensions, and IHDR CRC. JPEG scans bounded segments to a SOF marker; GIF reads logical-screen dimensions; WebP walks RIFF chunks and handles VP8, VP8L, and VP8X. ByteWindow bounds reads. Header probing deliberately does not prove pixel decodability. The existing external CPython ZIP golden and independently encoded image vectors reduce self-generated-fixture circularity. Animated WebP is accepted as WebP metadata by the VP8X path, which matters for F-06.

## 9. Compose decoding

Android uses BitmapFactory.decodeByteArray; JVM and iOS use Skia Image.makeFromEncoded. The 14 pre-existing Android tests ran on API 24 and API 36 with zero failures, including PNG/JPEG/GIF/VP8/VP8L/VP8X, full atlas, and malformed data. A temporary iOS simulator test fed independent Pillow/libwebp Base64 vectors to the real production decoder: PNG, JPEG, static GIF, VP8, VP8L, VP8X, and animated WebP all returned Ready with asserted dimensions. It was removed afterward; the baseline iOS suite remains PNG-only. API 24 animated WebP returned Failed while API 36 returned Ready. The permanent Android test accepts either result, so its pass does not establish parity; see F-06.

## 10. Compose lifecycle/state/scheduling

rememberPetPlayerState executes ByteArray.copyOf and platform decoding inside remember's calculation during composition, then stores the result. It re-decodes when the ByteArray reference or definition key changes, and not on ordinary recomposition. LaunchedEffect(state, epoch, pinned) owns the timer and cancels it on exit/key change; it samples absolute monotonic elapsed time, then delays. The delay helper floors positive fractional milliseconds despite its comment saying “round up”; this can cause one early recheck near a frame boundary, but no negative or zero-delay tight loop was shown.

CodexPet calls state.adoptAnimation during composition. On a key change, adoptAnimation writes animationEpoch and sampleState snapshot state during that composition. For one renderer this is idempotent once the key settles, and the existing UI test passes. For two renderers using the same state with different keys, each call necessarily changes the one shared requestedAnimation back to its own key; neither renderer owns stable intent. This is a static proof of F-04, with possible repeated invalidations; an actual infinite recomposition was not claimed without a bounded runtime reproducer. pinToIdle/resume are public snapshot mutations with no documented thread confinement; no cross-thread race was reproduced.

## 11. Cross-module invariants

IO probes a bounded encoded sheet, then supplies facts to core, so its ordinary successful package flow agrees at load time. But PetLoadOutcome.Success exposes the mutable spritesheetBytes array without a defensive copy; a caller can mutate it before Compose consumes it. More importantly, the optional io-free path supplies definition and bytes independently. Compose never compares decoded bitmap.width/height to definition.geometry, and PetDefinition stores no format fact. A scratch JVM test constructed a valid 1536x1872 definition, decoded a valid 1x1 PNG, and observed PetAtlasState.Ready plus a 192-pixel source region against a 1-pixel bitmap. F-02 covers this single broken boundary.

## 12. Resource exhaustion / memory

IO defaults are manifest 64 KiB, encoded sheet 8 MiB, 64 ZIP entries, raw archive 16 MiB, uncompressed total/per-entry 32 MiB, and 100x aggregate ratio. Direct Compose accepts any ByteArray size and asks the platform decoder to allocate before checking dimensions; no equivalent 8 MiB or 1536x1872 limit exists. The expected legal RGBA atlas alone is 1536 × 1872 × 4 = 11,501,568 bytes (about 10.97 MiB), plus encoded copy and decoder buffers. Safe source-level proof establishes absence of a bound; I did not attempt an OOM. See F-02.

Direct core parse likewise has no input-byte, nesting, animation-count, or frame-list-length cap. A host using untrusted JSON without io must set its own limit; SECURITY.md only explicitly scopes package limits to io. This is F-08, a policy/documentation gap. No claim of a demonstrated core OOM is made.

## 13. Android

The installed AVDs pets_api24 and Resizable_Experimental were executed, not inferred from build success. The production decoder's permanent suite passed 14/14 on API 24 and 14/14 on API 36. A temporary main-thread test copied and decoded a legal solid-color 1536x1872 PNG five times: API 24 measured [29, 22, 15, 14, 15] ms; API 36 measured [40, 28, 43, 37, 27] ms. Encoded sizes were 15,441 and 15,454 bytes. These are emulator samples, not calibrated device benchmarks, but prove normal legal input can exceed a 16 ms frame budget and API 36 exceeded it in every run. See F-03. Android device tests are not in gradle.yml (F-09).

## 14. JVM Desktop

The macOS host executed real Skia decode tests; CI executes JVM tests on Ubuntu. Windows JVM Desktop is configured but not executed in CI or this audit. Do not infer Windows renderer/runtime correctness from KMP compilation. The JVM integration test uses a genuine 1536x1872 image and Java ZIP writer. Windows is an unverified supported runtime leg under F-09.

## 15. iOS

iOS simulator arm64 production code and tests executed. Core 100, io 130, compose 23 baseline tests passed. The temporary independent full-format decoder test also passed, answering the prior iOS codec uncertainty; it was removed. iOS device arm64 frameworks compile/link, but no physical-device runtime test ran. The Compose build emitted duplicate KLIB unique-name warnings for mixed androidx and org.jetbrains.androidx lifecycle/savedstate/collection artifacts; no runtime failure was demonstrated. F-11 records this as dependency hygiene.

## 16. ABI / Apple / Java interop

All three checkKotlinAbi tasks passed and JVM/Android/KLIB dumps were inspected for public leaks. Core invariant-heavy constructors and internal DTOs are absent from the intended public ABI; PetAnimationKey is a regular equality-by-value class. Compose public ABI does not expose its internal decoder or ImageBitmap. Generated Apple headers reveal an actual cross-framework type mismatch: CodexPetsCore exports CPCPetDefinition as Swift PetDefinition, while CodexPetsIo embeds CPICodex_pets_corePetDefinition as Swift Codex_pets_corePetDefinition. A scratch Swift file importing both frameworks and returning an IO success definition as CodexPetsCore.PetDefinition failed swiftc -typecheck with “cannot convert return expression of type Codex_pets_corePetDefinition to return type PetDefinition.” See F-05. The scratch file/cache were removed.

## 17. Test quality

After scratch removal, tests passed: core JVM/iOS 100/100; io JVM/iOS 148/130; compose JVM/iOS 34/23. Two scratch JVM tests independently exposed F-02 and F-07. The permanent Compose UI smoke uses a 1x1 PNG with a 1536x1872 definition and only asserts the node exists, so it misses atlas consistency. Android's animated-WebP test accepts both Ready and Failed, masking a supported-target divergence. The JVM WebP “lossless” vector contains a VP8 lossy chunk; the independent Android vectors cover VP8L correctly.

Ten existing tests were inspected for whether their assertions reach the named invariant: core GeometryTest boundary tests, PlaybackTest exact boundaries, CustomAnimationsTest FPS, ManifestParsingTest default/format, io ZipConsistencyTest overlap, local mismatch, CRC, and external golden, plus Compose PetPackageToRendererTest and AndroidDecoderVerificationTest. Their principal assertions are substantive; the two weaknesses above remain. Mutation-testing ten tests in isolated source copies was not completed, so this review does not claim mutation adequacy.

## 18. CI

gradle.yml runs each module's JVM test/assemble/ABI on Ubuntu and iOS simulator test on macOS. It has no Windows JVM matrix and no connected Android device job. There is no continue-on-error for the listed gates. publish.yml triggers on GitHub releases and has Central credentials/signing wiring; publishing was not attempted. The absence of an Android device CI leg is material because the API 24 animated-WebP difference escaped the baseline JVM/iOS suite.

## 19. Documentation/security policy

README.md lines 21–22 still say Compose does not exist; SECURITY.md lines 42–44 say rendering does not exist and imply decoder handling is external. Both are current-doc drift (F-10). SECURITY.md opens by correctly scoping listed limits to io, but does not clearly state that direct core JSON and Compose encoded bytes have no caps; the Compose KDoc at PetPlayer.kt lines 22–25 incorrectly describes the 8 MiB io limit as though it bounds every input. Architecture §5.1 promises raw manifest/sheet pairs, but the public metadata step is unavailable (F-01). Security wording around ZIP backslashes is broader than ZipPaths.kt's actual virtual-path policy; entries are never extracted, so no escape was shown.

## 20. Dependencies/build/publication readiness

The fresh combined --rerun-tasks suite executed tests and ABI checks but failed linking the Compose iOS simulator release framework with Kotlin/Native “Java heap space” under the checked-in 2 GiB Gradle setting. Retrying linkReleaseFrameworkIosSimulatorArm64 with one worker and a 6 GiB Gradle heap passed, and a subsequent compose assemble passed. This is an environmental build-capacity observation, not a source correctness defect. Gradle also warned about missing Android host-test source sets, a redundant cast in one test, and duplicate KLIB unique names; the latter is F-11. Module coordinates are com.yet.pets at 0.1.0, with POM license/SCM/signing fields present; Phase 5 publishing readiness was not claimed.

## 21. Code quality / SOLID / DRY / KISS

The module boundary is coherent: pure core, bounded package io, platform decoder and renderer in compose. ZIP parsing is concentrated but cohesive. Two policy seams now need attention: the image metadata probe lives only in optional io, and the public Compose boundary does not repeat relevant validation. Additional abstraction or file splitting alone would not solve either. No unsupported Phase 4 work was treated as a defect.

## 22. AI-assisted code risk review

Concrete optimism defects were found: a KDoc claims the io cap bounds direct Compose bytes; the Android animated-WebP test permits either outcome; a Compose smoke test constructs an intentionally inconsistent definition/bitmap pair and treats a node tag as success; README/SECURITY describe a pre-Compose state. Tests and comments were not accepted as proof. No finding is based on the code having been AI-assisted.

## 23. Findings table

| ID | Severity | Module | Summary | Proven? | Blocking 0.1.0? |
|---|---|---|---|---|---|
| F-01 | P1 | core/compose/io | Public raw JSON+bytes flow requires an inaccessible image probe | Static ABI/API proof | Yes |
| F-02 | P1 | compose/core/io | Direct atlas bytes are uncapped and unchecked against definition | Scratch JVM reproduction + static bounds proof | Yes |
| F-03 | P1 | compose | Legal atlas copy/decode blocks composition/UI thread | API 24/36 timing reproduction | Yes |
| F-04 | P1 | compose | Two CodexPet instances sharing a state overwrite each other's animation intent | Deterministic static state-transition proof | Yes |
| F-05 | P1 | Apple ABI | Core and io frameworks expose incompatible copies of PetDefinition | swiftc diagnostic | Yes |
| F-06 | P1 | Android/io/compose | Animated WebP accepted upstream/io but fails decoding on API 24 | Device log + code path | Yes |
| F-07 | P2 | io ZIP | Deflate trailing compressed bytes accepted | Scratch JVM reproduction | No |
| F-08 | P2 | core/docs | Direct JSON lacks a stated caller limit policy | Static proof | No |
| F-09 | P2 | CI/Desktop | Android device and Windows JVM runtime absent from CI | Workflow inspection | No |
| F-10 | P2 | docs | Current README/SECURITY and Compose KDoc overstate or predate behavior | Direct text/source comparison | No |
| F-11 | P2 | dependencies | Duplicate iOS KLIB unique-name warnings | Fresh build warning | No |

### F-01 — Public raw-pair orchestration is missing (P1, high confidence)

**Location/contract:** PetParser.kt lines 72–75 and 87–92 require SpritesheetInfo; ImageProbe.kt lines 23–30 is internal to io; PetDecoder.kt lines 49–67 is internal to compose. Architecture §5.1 and §6 promise host-supplied manifest/sheet bytes with optional io. **Expected:** a core+compose consumer can transform those two inputs into a validated PetDefinition and rendered state using public APIs. **Actual:** no public image metadata step exists, so the caller must reimplement format/dimension probing or fabricate facts. **Reproduction:** try to fill the third parameter of PetPackageParser.parse(manifestJson, "pet", ...) using only public core+compose APIs; neither exposes a ByteArray -> SpritesheetInfo function. **Runtime/build evidence:** the ABI dump contains only parse overloads requiring SpritesheetInfo; the only probe is internal and compose's decoder is internal. This is a complete static API proof, so a runtime call cannot supply the missing argument. **Targets/impact:** all Kotlin targets on the optional-io path; advertised architecture cannot be used safely. **Direction:** expose a bounded raw-pair orchestration path or a public metadata probe with a clear ownership/bounds contract.

### F-02 — Compose trusts arbitrary, potentially mismatched atlas bytes (P1, high confidence)

**Location/contract:** PetPlayer.kt lines 22–25 and 47–49, PetDecoder.kt lines 49–60, PetPlayerState.kt lines 56–59 and 150–160. Public direct bytes are described as foreign and valid definitions are supposed to drive exact atlas source regions. **Expected:** reject an atlas whose decoded dimensions disagree with definition.geometry and apply an encoded/decode limit before allocation. **Actual:** a valid 1x1 PNG is Ready with a 1536x1872 definition; drawParamsFor(0) hands out a 192x208 source rectangle. There is no input-length gate before copyOf or decoder call, and no decoded-dimension gate. IO's 8 MiB cap is bypassed. PetLoadOutcome.Success also exposes mutable bytes, allowing post-load disagreement before render. **Reproduction:** scratch JVM test used PetPackageParser.parse("{}", "test", SpritesheetInfo(1536,1872,PNG)), decodeAtlasBytes(tinyPngBytes), then PetPlayerState; Ready, bitmap.width=1, geometry.atlasWidth=1536, srcWidth=192 all asserted. **Runtime/build evidence:** scratch test passed; source order proves allocation precedes any validation. OOM risk is a consequence of unbounded input, not a claimed reproduced OOM. **Targets/impact:** Android, JVM, iOS; wrong/clipped output and avoidable memory exhaustion on host-supplied bytes. **Direction:** validate encoded size and decoded dimensions at the public Compose entry, with typed failure; document format treatment and avoid trusting IO-only invariants.

### F-03 — Decode executes on the composition thread (P1, high confidence)

**Location/contract:** PetPlayer.kt lines 47–49 invokes decoder(copyOf()) inside remember, then Android PetDecoder.android.kt lines 12–13 calls BitmapFactory synchronously. A UI renderer must avoid large blocking work on normal legal input. **Expected:** full atlas decode off the UI-critical composition path. **Actual:** 1536x1872 PNG copy+decode on the Android main thread took [29,22,15,14,15] ms on API 24 and [40,28,43,37,27] ms on API 36; the former first two and all latter samples exceed 16 ms. **Reproduction/evidence:** temporary AndroidJUnit4 test generated a legal 15.4 KiB solid PNG, called decodeAtlasBytes(bytes.copyOf()) inside InstrumentationRegistry.runOnMainSync five times, asserted Ready, and logged elapsedRealtimeNanos. The test passed on both emulators and was removed. **Targets/impact:** Android measured, JVM/iOS synchronous by source; normal state creation can visibly jank. No >50 ms sample was observed, and these emulator numbers are not physical-device benchmarks. **Direction:** arrange asynchronous/background decode and state handoff, or expose a predecode path with explicit threading ownership.

### F-04 — Shared state has conflicting renderer owners (P1, high confidence for wrong intent)

**Location/contract:** CodexPet.kt lines 64–75 calls state.adoptAnimation(animation) in each composition; PetPlayerState.kt lines 61–65 and 96–105 stores exactly one requestedAnimation/sample/epoch. Public API does not prohibit sharing one PetPlayerState. **Expected:** two simultaneous renderers with different animation parameters render their own requested tracks, or sharing is rejected/clearly unsupported. **Actual:** composing CodexPet(state, Idle) then CodexPet(state, Waving) sets the single state to Waving; when the Idle caller next composes it resets that state to Idle. This is an algebraic proof from sequential writes to one field; executing it cannot make both intents simultaneously true. Repeated snapshot invalidation/recomposition is a risk, not claimed as a demonstrated loop. **Targets/impact:** all Compose targets; incorrect animation, shared timing resets, and potential unstable recomposition. **Direction:** give animation intent per renderer or enforce/document single-owner state and move mutation to a lifecycle effect rather than composition.

### F-05 — Swift cross-framework model types do not compose (P1, high confidence)

**Location/contract:** io/build.gradle.kts lines 35–39 depends on core and builds its own framework; generated CodexPetsIo.h lines 528–534 returns CPICodex_pets_corePetDefinition, while CodexPetsCore.h lines 605–606 exports CPCPetDefinition. Apple core/io consumers should be able to use one public PetDefinition type. **Expected:** an IO-loaded definition can be passed where standalone core PetDefinition is required. **Actual:** Swift compiler rejects the assignment with “cannot convert return expression of type Codex_pets_corePetDefinition to return type PetDefinition.” **Reproduction/evidence:** a scratch Swift file imported both simulator frameworks and defined a function returning CodexPetsCore.PetDefinition from CodexPetsIo.PetLoadOutcomeSuccess.definition; swiftc -typecheck for arm64 iOS simulator failed with that diagnostic. The scratch file/cache were removed. **Targets/impact:** Swift consumers linking separate Apple frameworks; public models are split into incompatible module copies before 0.1.0. **Direction:** publish an Apple framework topology that exports a single core type identity, and compile a Swift multi-module consumer in CI.

### F-06 — Animated WebP fails on minimum Android despite accepted format (P1, high confidence)

**Location/contract:** WebpProbe.kt lines 55–60 accepts VP8X metadata; PetDecoder.kt lines 34–37 promises first-frame decoding for animated GIF/WebP; Android PetDecoder.android.kt lines 12–13 uses BitmapFactory. Pinned upstream enables WebP and calls image::open for the atlas; the pinned Rust image crate's behavior on this specific animated vector was not separately executed. The current loader has no animation flag rejection. **Expected:** a package accepted as WebP renders its first static atlas frame on every supported Android API, or is explicitly rejected at load with a consistent contract. **Actual:** the independent VP8X+ANIM vector returns PetAtlasState.Failed on API 24 and Ready on API 36. **Reproduction/evidence:** the permanent AndroidDecoderVerificationTest.animatedWebpFirstFrameIsRecorded ran on both emulators; the API 24 connected log says “webp-animated api=24 state=Failed reason=undecodable spritesheet bytes.” Its test deliberately accepts either outcome. A full-size animated package was not generated, so the IO-to-renderer package failure is inferred from the dimension-only VP8X probe and identical decoder path, not falsely described as end-to-end executed. **Targets/impact:** Android API 24; a documented supported format variant can fail official rendering after metadata acceptance. **Direction:** either support first-frame animated WebP on minSdk 24 or reject animated containers consistently during loading and narrow the public compatibility claim.

### Meaningful P2 detail

- **F-07:** ZipEntryReader.kt lines 66–85 copies the declared compressed slice and reads InflaterSource to output EOF, then checks output size/CRC at lines 42–50. It never inspects remaining compressed input or inflater bytesRead. The scratch mutation described in §7 returned Success. Require exact compressed consumption if strict archive validation is the intended contract.
- **F-08:** PetParser.kt lines 72–95 directly decodes caller strings/bytes with no 64 KiB gate; normalizeCustomAnimation maps every supplied frame, and animation count has no cap. State explicitly whether direct parsing is caller-trusted and what host caps are required, or add a bounded public path. No OOM was produced.
- **F-09:** .github/workflows/gradle.yml has only Ubuntu JVM and macOS iOS simulator tasks. Add Android device tests and a Windows JVM runtime leg before claiming those legs continuously verified.
- **F-10:** README.md lines 21–22 and SECURITY.md lines 42–44 predate Compose; PetPlayer.kt lines 22–25 borrow an io-only 8 MiB premise. Update current docs after F-01/F-02 contract decisions. Historical phase reports should remain historical.
- **F-11:** fresh compileIosMainKotlinMetadata logged duplicate unique_name values from androidx and org.jetbrains.androidx lifecycle, savedstate, navigationevent, annotation, and collection KLIBs. Resolve the dependency graph before release; no runtime crash was observed, so this remains P2.

## 24. Recommended remediation ordering

**A. MUST FIX BEFORE ANY PHASE 4 WORK:** F-01, F-02, F-03, F-04, F-06. These alter the fundamental core+compose consumer path or supported renderer behavior; adding helpers above them would compound the broken contract.

**B. MUST FIX BEFORE 0.1.0:** F-05, then resolve F-11 and extend CI per F-09. Apple framework type identity is a release API decision. F-07 should be resolved before describing ZIP parsing as strict; it need not block unrelated Phase 4 implementation. F-08/F-10 require policy and documentation decisions.

**C. CAN DEFER AFTER 0.1.0:** only genuinely low-risk remaining cleanup after the public/security wording is made accurate; do not defer a known overclaim into the release.

**D. INFORMATIONAL / FUTURE PHASE:** no network module and no Phase 4 desktop helpers are expected. Physical iOS device runtime and Windows JVM runtime were not executed in this local audit. Publication itself was not exercised.

## 25. Final gate

**REMEDIATION REQUIRED.** Six confirmed P1s remain. The current state must not receive a phase-3-compose tag or 0.1.0 release based on passing Gradle tests. The only intended lasting audit change is this report; pre-existing uncommitted Android verification work remains user-owned and unchanged.

Explicit answers to the mandatory cross-cutting questions: (1) core+compose raw JSON+bytes is not realistically usable without implementing metadata probing (F-01); (2) mismatched atlas is Ready (F-02); (3) Compose bypasses io limits (F-02); (4) legal atlas decode can exceed 16 ms on UI thread (F-03); (5) state is mutated during composition (F-04); (6) shared-state renderers fight over one intent (F-04); (7) stale cancelled effects have no demonstrated overwrite, and LaunchedEffect cancellation/epoch provides a reasonable guard; (8) iOS simulator decoded all four claimed format families in a temporary independent vector test; (9) Windows JVM runtime is unexecuted, Ubuntu CI and macOS local JVM are executed; (10) ZIP accepts trailing declared compressed garbage (F-07); (11) full local-header-to-data spans are checked for cross-entry overlap; (12) direct core JSON is unbounded; (13) SECURITY scopes io limits but fails to explain direct-entry obligations clearly; (14) Apple framework type identity and optional-io API require pre-0.1.0 change; (15) no silent post-tag io source regression was found, while Phase 2 core format additions are explicit.

Final status verification: git status --short reproduced the initial four user-owned entries plus only docs/audit/FULL_PROJECT_ADVERSARIAL_AUDIT.md. git diff --check passed. No scratch source, Swift file, Swift module cache, or generated test build tree remains; Gradle clean completed after recording the test results.
