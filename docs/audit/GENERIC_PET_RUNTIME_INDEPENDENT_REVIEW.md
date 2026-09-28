# Generic Pet Runtime — Independent Architecture and API Review

## 1. Final gate

**REMEDIATION REQUIRED.** Confidence: **high** for the confirmed source and runtime findings; **moderate** for the Codex long-name compatibility comparison because it was established by comparing parser source at the Phase 4 tag rather than running an old binary. Confirmed P0: 0; confirmed P1: 4; P2 and coverage items are listed below.

The extraction does create a usable generic parser, model, sampler, loader, and renderer. It does **not** yet establish a clean generic public contract. An ordinary generic host exposes a nonexistent Codex idle request, the v1 parser accepts JSON strings as integer fields, a mandatory public fallback encodes a fabricated transition for generic one-shots, and the Codex adapter rejects custom animation names that the Phase 4 parser accepted. The successful test suites do not exercise these contract edges.

## 2. Tested HEAD and lineage

The first git status --short was clean. The requested implementation HEAD is 114c75b5765edbef9b3f45ee4006912bf8f3f718, and phase-4-host resolves to b6685b0b5f8a09e625b57a75cfc317262c41ac0a. Actual starting HEAD was **868ef94e7e822fb7ac055df1640bf0dfd074d00d**, one commit newer; that commit changes only assets/kodee/spritesheet.webp (1,790,848 to 1,833,706 bytes). The history contains, in order, 9821f05, 954e890, 7385d0c, f6c8cac, 045e262, and 114c75b as ancestors of actual HEAD. The implementation commit is origin/main; current HEAD is local main ahead by one commit.

During the audit the two tracked Kodee inputs temporarily disappeared from the shared checkout and then reappeared without an audit edit. Their final SHA-256 is 6688c1dc52bfd0b602a39448ee86545bb1e098aa21f25beb500cfeb4665de75a, unlike the c414c906... hash in docs/research/CODEX_V2_COMPATIBILITY_RESEARCH.md. The final test run used the current files. No production code or asset was modified by this review. A temporary JVM audit test was created, run, and removed; final status should contain only this report.

## 3. Generic versus Codex boundary

Core's generic parser and Codex adapter are explicit entry points: codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/PetsKmpParser.kt:39 and :472. The loader entry points similarly select one parser, with no failure-driven format sniffing (codex-pets-io/src/commonMain/kotlin/com/yet/pets/io/PetLoader.kt:30-105). The CodexV1Profile source is unchanged from phase-4-host. Generic geometry derives from actual image dimensions and declared cell size; the 1536x1872 Codex rule stays in its adapter. The renderer and Android/JVM overlay bodies use Pet and pinToDefault.

The remaining Codex coupling is in *public generic state and model shape*: PetHostState chooses PetAnimations.Idle before binding a definition; PetAnimation requires a fallback key even where the format has no transition. These are observable ABI decisions, not merely internal names.

## 4. Public model audit

PetAnimationKey is open, case-sensitive, and preserves its original string. A temporary JVM probe confirmed blank and whitespace-only keys throw, 64 UTF-16 code units pass, 65 fail, Unicode such as é猫 works, and PetAnimationKey(" dance ") differs from PetAnimationKey("dance"). The constructor and equality are in codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/PetAnimationKey.kt:17-35. The generic parser trims animation keys and defaultAnimation (PetsKmpParser.kt:252, :321, :348), so a manifest " dance " becomes "dance". The specification says keys are nonblank and at most 64 characters but does not explain this normalization split; this is P2 contract ambiguity. Kotlin String.length counts UTF-16 code units, not Unicode code points.

PetDefinition is independent of schema tags and validates its default key's presence, but its internal constructor still defaults defaultAnimationKey to PetAnimations.Idle (PetModel.kt:128-160). Both current parser call sites supply the argument explicitly (PetParser.kt:327-334; PetsKmpParser.kt:440-448); a current non-Codex parser cannot accidentally omit it. No test enforces explicitness for a future adapter. Because the constructor is internal and presence validation catches an idle-free definition, this is P2 architectural debt rather than a current public failure. The frameCount == geometry.frameCapacity invariant makes frameCount redundant for generic sheets with unused cells (PetModel.kt:153-157); it describes cell capacity, not populated frame count. Rename or semantics should be decided before ABI stabilization.

## 5. Default animation semantics

For a definition with default stand and no idle, samplePetAnimation and PetPlayerState initialize and pin to stand (Playback.kt:68-87; codex-pets-compose/src/commonMain/kotlin/com/yet/pets/compose/PetPlayerState.kt:84-89, :146-151). The ordinary rememberPetPlayerState API passes no explicit initial key and therefore follows the definition default (PetPlayer.kt:35-52). An explicit known initial key is sampled; an explicit unknown key silently resolves to the default. The current public remember overload does not expose initialAnimation; the optional argument is on an internal constructor.

The core fallback-to-default behavior is implementation/documentation behavior, not specified by Pets KMP v1. See §7.

## 6. Host-state default semantics

**Confirmed P1-01.** PetHostState's constructor and rememberPetHostState default animation to PetAnimations.Idle (codex-pets-host/src/commonMain/kotlin/com/yet/pets/host/PetHostState.kt:30-35, :71-73, :133-145). Ordinary rememberPetHostState() thus exposes requestedAnimation == idle for a definition whose only tracks are stand/dance. PetInAppHost sends that idle request to PetPlayerState (PetHost.kt:91-105); the core unknown-key fallback renders stand, masking the wrong host intent. Pin shows stand, play(dance) changes intent to dance, and resume uses the latest explicit request. Recreating the host state without an explicit key reintroduces idle. The existing PetHostGenericTest verifies visual composition and later play/pin/resume but never asserts initial requestedAnimation or initial sample (codex-pets-host/src/jvmTest/kotlin/com/yet/pets/host/PetHostGenericTest.kt:60-124).

This violates the claimed format-agnostic host API: its public requested intent is a Codex key absent from the bound definition. The appropriate model should represent no explicit request until binding or derive the initial request from the definition, while preserving explicitly supplied intent. See finding detail in §23.

## 7. Unknown-animation semantics

For default stand, samplePetAnimation(definition, PetAnimationKey("typo"), 0) returns a stand sample (Playback.kt:73-77). PetPlayerState.play records the unknown request privately, samples stand, and PetHostState.play publicly retains the typo while its player displays stand (PetPlayerState.kt:118-127; PetHostState.kt:95-98). Thus the three layers agree on pixels but differ on exposed intent. This may be a deliberate permissive generic policy, but docs/spec/PETS_KMP_PACKAGE_V1.md does not define it. It can hide application typos. **P2 API contract decision:** either document unknown-to-default for the generic runtime or expose an explicit failure/validation path. Do not infer a generic rule solely from Codex unknown-to-idle behavior.

## 8. Fallback and self-fallback design

**Confirmed P1-02.** PetAnimation publicly requires fallback: PetAnimationKey and includes it in equality/hashCode/toString (PetModel.kt:61-100); the generated Swift header exports PetAnimation.fallback. Pets KMP v1 explicitly has no fallback field (docs/spec/PETS_KMP_PACKAGE_V1.md §7), but the parser assigns fallback = key for every generic animation, including one-shots (PetsKmpParser.kt:414-422). A consumer inspecting a generic one-shot sees a transition to itself that the package never declared. This is a compatibility mechanism made part of the generic normalized ABI. Any future adapter wanting a one-shot without a transition must fabricate the same key or reinterpret this mandatory field. This is a pre-0.1.0 model issue; it does not make the current sampling calculation wrong.

## 9. One-shot and loop correctness

A temporary JVM test exercised generic frames [0,1,2] at 0, 1, 2, 2,999,999, 3,000,000, 3,000,001 ns and Long.MAX_VALUE, with 1 ms durations. At completion and later it returned frame 2, the same animation key, and nextFrameInNanos = null. A one-frame one-shot held its only frame with null next wake-up. No recursion or replay occurred. Source uses one fallback hop with the original elapsed time and frameAt's terminal hold (Playback.kt:80-87, :129-145). Confidence: high for normal bounded durations.

The temporary test also checked loopStart = 1 around prefix and total boundaries: frames 0,1,2,1,2,1 at 0,1,2,3,4,5 ms. Existing tests cover loopStart = 0. Generic parser validates 0 <= loopStart < frame count and never appends Codex idle (PetsKmpParser.kt:406-422). The sampler uses duration modulo, independent of elapsed magnitude (Playback.kt:106-123). Extremely large multi-frame total durations saturate at Long.MAX_VALUE, so completion cannot be represented earlier than that bound; no arithmetic overflow was observed.

## 10. Generic schema

The schema requires pets-kmp/1, id, displayName, positive cell dimensions, defaultAnimation, and nonempty unique animation keys. Missing schema and version are mapped to InvalidSchema and UnsupportedSchemaVersion; missing/blank id to MissingId; missing/partial frame to InvalidFrameSize; absent animations to EmptyAnimations; missing default to UnknownDefaultAnimation (PetsKmpManifestDto.kt:13-42; PetsKmpParser.kt:194-205, :227-249, :320-342). Null animations has a different path: the DTO is a nonnullable List, so deserialization yields MalformedManifest rather than EmptyAnimations. Wrong JSON primitive types similarly return MalformedManifest containing a serialization exception message. This is typed at the outer API but less semantic and potentially library-version-dependent; P2 taxonomy inconsistency.

Arbitrary geometry is real: the 96x80, 32x40 synthetic fixture yields 3x2, capacity 6. Capacity 256 works by code; 257 is rejected before model creation (PetsKmpParser.kt:298-318). Zero/negative cell size, nondivisible atlas, and invalid frame indices fail typed validation. Geometry products use Long and sourceRectForOrNull rejects capacity and negative indices before multiplication (Geometry.kt:36-74). Long.MAX_VALUE durationMs is rejected before ms-to-nanos multiplication (PetsKmpParser.kt:368-385); Int.MAX_VALUE width/height/index and loopStart cannot overflow the shown Long products or range checks.

The 64 KiB manifest cap prevents 100,000 minimal animations. A compact JSON estimate using unique base-62 keys and one frame each fits about **1,283** entries (65,506 bytes), excluding longer metadata. Duplicate detection uses HashSet.add in a linear pass (PetsKmpParser.kt:250-276); no quadratic duplicate search was found. This is an illustrative upper count for one encoding, not a universal maximum.

## 11. JSON strictness and versioning

**Confirmed P1-03.** The specification says schemaVersion is integer 1, frame dimensions/indices are integers, and durationMs is a positive integer (docs/spec/PETS_KMP_PACKAGE_V1.md:14-18, :69-74, :108-113). The parser uses default kotlinx.serialization numeric coercion (PetsKmpParser.kt:52, :156-163; PetsKmpManifestDto.kt:15, :26-42). A temporary JVM probe showed schemaVersion: "1", frame.width: "32", frame.index: "0", and durationMs: "1" all produce Success, as does integer exponent spelling 1e0. Decimal 1.0 and a very large numeric literal fail. The quoted cases violate the format's JSON type contract; the exponent case needs an explicit format decision because it denotes an integral number but is not integer lexical syntax. Version 2 is rejected. This mismatch is a wire-contract blocker before declaring v1 stable.

Json is configured with ignoreUnknownKeys = true (PetsKmpParser.kt:52); the v1 spec does not promise unknown-field tolerance and says no speculative fields. This is P2 spec/implementation ambiguity. If tolerance is intended, document it as a forward-compatibility promise, including whether nested unknown fields are tolerated.

## 12. Generic parser and error API

The raw parse path enforces 64 KiB manifest and 8 MiB sheet caps, probes image metadata, and returns a PetsKmpParseOutcome rather than leaking ordinary malformed-input exceptions (PetsKmpParser.kt:65-87). parseTrustedMetadata is explicitly caller-trusted for image facts and still caps the manifest. Invalid spritesheet bytes and animated WebP are rejected by the shared probe; successful metadata does not prove full pixel decode. Path defaulting trims absent/blank spritesheetPath to spritesheet.webp (PetsKmpParser.kt:189-192). Manifest key trimming versus direct key preservation needs documentation (§4).

PetsKmpError has 20 public variants in the Swift header, plus report, parse outcome, and path outcome types. Separate error families from Codex are sensible because schema rules differ, but NormalizationFailed and some serialization-message-bearing MalformedManifest values reveal implementation details. The hierarchy is usable by Swift through protocol/type casts; the external Swift consumer currently does not exercise any generic parser or error branch. This is P2 ABI ergonomics/coverage, beyond the concrete P1 fallback field.

## 13. Generic IO and security

Codex and generic directory loaders call the same readSharedDirectoryPackage/readSharedDirectoryAsset implementation (DirectoryPetLoader.kt:64-94, :107-152). Codex and generic ZIP loaders call readSharedZipPackage/readSharedZipAsset and the same structural parser/readZipEntryData (ZipLoader.kt:57-99, :112-177). Shared ZIP code performs raw cap, indexed structure, collision, symlink, encryption, overlap, CRC, and bounded entry checks; both branches differ primarily in path extraction and final parser. Path validation and canonical containment are shared. The default limits are 64 entries, 16 MiB raw, 32 MiB expanded/per-entry, 100x ratio (PetPackageLimits.kt:15-34); core independently caps manifest and sheet. No generic bypass was found.

Generic directory and ZIP discovery also accept avatar.json through the shared resolver, as docs/spec/PETS_KMP_PACKAGE_V1.md currently permits. This appears inherited from Codex legacy naming and is P2 API/format cleanup, not a security bypass. The generic fallbackId argument is likewise redundant for valid manifests: rawId is required before normalizePetIdentity is called, and manifest id wins (PetsKmpParser.kt:227-235, :433-449; Identity.kt:23-36). Blank/missing id fails, so a valid generic package never uses fallbackId for identity. P2 pre-release API cleanup.

The path helper trims whitespace and uses the default for blank strings. IO's checkManifestRelativePath rejects backslash, absolute paths, drive prefixes, and parent traversal; Unicode lookalikes are ordinary filename characters, not decoded separators. Source and inherited security tests support this. The generic ZIP has explicit traversal and duplicate tests. A supposed genericDirectoryLoadsSyntheticPet test actually calls loadPetsKmpZip (PetsKmpLoadingTest.kt:49-68); there is no generic public directory test. P2 coverage gap.

## 14. Codex V1 regression

CodexV1Profile was unchanged between phase-4-host and implementation HEAD. Existing and new tests cover fixed 1536x1872/8x9 geometry, standard and alias keys, idle durations, 3 primary passes plus idle, loopStart, custom fallback one-hop, unknown-to-idle, and same original elapsed; fresh JVM and iOS suites passed. PetParser explicitly sets defaultAnimationKey = idle (PetParser.kt:327-334). Whitespace fallback errors are still mapped to UnknownFallback rather than leaking the new key constructor exception (PetParser.kt:369-389).

**Confirmed P1-04 by source comparison and a current-HEAD probe:** phase-4-host PetAnimationKey accepted arbitrary strings without a length guard, and its Codex parser constructed custom keys directly. The current PetAnimationKey rejects length 65 (PetAnimationKey.kt:23-27), while the Codex parser catches it and fails with MalformedManifest (PetParser.kt:262-277). A temporary JVM test with a valid custom manifest and a 65-character ASCII key confirmed the current failure. The old model/parser path accepted that key by source inspection; an old binary was not run. The generic format may impose 64; the Codex adapter must preserve its own prior contract or explicitly document a compatibility break. No other Codex semantic change was confirmed.

## 15. Kodee generic package

The separate assets/kodee/pet.pets-kmp.json declares pets-kmp/1, 192x208 cells, default idle, and idle/wave/jump/happy/rest. The exact current WebP is a static 1536x2288 image (8x11, capacity 88) and hashes to 6688c1dc52bfd0b602a39448ee86545bb1e098aa21f25beb500cfeb4665de75a. There is no spriteVersionNumber or V2 branch in the generic manifest or production runtime. KodeeGenericTest packages the manifest and exact sheet bytes at test time and passes them through public loadPetsKmpZip; the Compose UI test parses the pair, reaches Ready, and changes source region from idle index 0 to wave index 24. Fresh JVM suites passed both tests. There is no persistent ZIP fixture to hash separately; the test ZIP is built from the source bytes.

Visual inspection of a contact sheet made from the current WebP supports row 6 as alert/waiting-like arm and head movement, rather than rest. Row 8 shows tilts, blinking, and hands near the head; "happy" is not clearly established. The research document labels row 6 waiting and row 8 review for the observed Desktop animation table. This is **P2 example-data quality**, not a generic runtime defect; arbitrary generic names remain legal. The current image also makes the old research SHA stale.

## 16. Original Kodee rejection

assets/kodee/pet.json still declares spriteVersionNumber = 2. Fresh KodeeGenericTest used that exact manifest and current exact WebP through public PetLoader.loadPetZip; it returned CompatibilityFailure containing UnsupportedAtlasDimensions(1536, 2288), as required. CodexLockTest also verifies that version 2 cannot activate a V2 profile. Production source search found no kodee, 2288, row-11, or spriteVersionNumber branch beyond comments/documentation.

## 17. Synthetic non-Codex proof

The 96x80 WebP, 32x40 cell, blink/dance, default blink fixture passes the public generic ZIP loader (PetsKmpLoadingTest.kt:32-47) and core parser (PetsKmpParserTest.kt:165-184). Core sampling uses those keys and generic loop rules. Host's stand/dance no-idle fixture composes on JVM, and Android/JVM overlay source consumes Pet. The synthetic sheet is a metadata fixture rather than a full decoded renderer image, so it does not by itself prove Ready in Compose. Kodee supplies the full decoded renderer proof but has an idle track. This division is a P2 end-to-end coverage gap.

## 18. Compose and player

Pet is the generic renderer and CodexPet a thin compatibility wrapper (codex-pets-compose/src/commonMain/kotlin/com/yet/pets/compose/CodexPet.kt). Player state uses only the normalized definition, initializes from its default, pins to staticDefaultSpriteIndex, retains latest request while pinned, and resumes from elapsed zero. Existing generic player tests passed fresh. The renderer's internal test tag remains CodexPet, and the public CodexPet wrapper has no explicit removal/deprecation schedule. These are P2 naming/compatibility cleanup, not playback failures.

## 19. Host and platform overlays

Common host and Android/JVM overlay content use Pet and pinToDefault; only the host-state default imports PetAnimations.Idle. Fresh local API 36 connected suites passed Compose 14/14 and Host 14/14 with a granted overlay app-op. Hosted API 24 CI passed. The local emulator inventory contained only API 36, so API 24 was not rerun locally. A temporary iOS simulator InApp UI test with a 96x80 generic PNG, default stand, and no idle reached Ready, sampled stand, played dance, pinned to stand, and resumed dance. It also confirmed that the public host requestedAnimation remained idle initially. The temporary test was removed; the absence of a permanent no-idle iOS host test remains a P2 coverage gap.

## 20. Apple ABI

Fresh verifySwiftConsumer passed; release XCFramework assembly passed (its release link outputs were up to date from earlier builds). The generated simulator Swift header exposes PetAnimationKey, PetDefinition.defaultAnimationKey, PetsKmpPackageParser, PetsKmp error protocol and 20 concrete error classes, pinToDefault, and the public PetAnimation.fallback. The generic API is present in the one CodexPets umbrella and no duplicate PetDefinition identity was found. The external Consumer.swift typechecks only Codex ZIP loading, sampling, and host control; it does not compile a generic parse, generic error switch, or no-idle host call. This is P2 external-consumer coverage, while the fallback field is P1-02.

## 21. Documentation consistency

README and the spec describe the generic format separately from Codex. ARCHITECTURE.md opens with the generic extraction but later retains Phase 0 text such as "decoded atlas dimensions must equal 1536x1872" in a table that reads as global package limits (ARCHITECTURE.md:220) and "v1 ships CliV1 only" (ARCHITECTURE.md:304). SECURITY.md §B describes only PetPackageParser even though generic raw parsing exists; §A limits do apply to both loaders. The old research SHA for Kodee is stale after the asset commit. Current normative docs should distinguish global limits, Pets KMP rules, and Codex-only rules. Historical audit reports should remain historical. P2 documentation cleanup.

## 22. CI and verification

Hosted GitHub Actions run [36399897131](https://github.com/yet300/Pets/actions/runs/36399897131) succeeded at exact implementation SHA 114c75b with 22 green jobs: core/io/compose/host JVM and iOS simulator tests, four ABI jobs, Android API 24, and external Swift consumer. No hosted run exists for local asset-only HEAD 868ef94 in the inspected recent runs.

Fresh local --rerun-tasks run at actual HEAD passed: core JVM 137 and iOS 137 tests; IO JVM 157 and iOS 137; Compose JVM 51 and iOS 35; Host JVM 28 and iOS 17, all zero failures/errors/skips. The earlier four checkKotlinAbi tasks passed, as did fresh local Swift consumer. API 36 connected tests passed 14 Compose and 14 Host, zero failures. The release XCFramework task succeeded with release outputs up to date. A temporary independent core probe passed key, one-shot, and loop cases; its deliberate initial numeric strictness assertion failed, documenting the quoted-number acceptance, then the probe was corrected to record actual behavior, passed, and was removed. Separate temporary probes confirmed current Codex rejection of a 65-character custom key and the generic no-idle InApp host behavior on iOS; both were removed.

## 23. Findings

### P1-01 — Codex idle leaks into generic host intent

- **Severity:** P1.
- **File/lines:** codex-pets-host/src/commonMain/kotlin/com/yet/pets/host/PetHostState.kt:30-35, :71-73, :133-145; PetHost.kt:91-105.
- **Violated contract:** A generic host must not require or claim a Codex animation key before binding its definition.
- **Expected:** With default stand/no idle and rememberPetHostState(), the state should expose no explicit request or resolve to stand on binding.
- **Actual:** requestedAnimation is idle; the player silently samples stand through unknown-key fallback.
- **Reproduction:** Parse a stand/dance-only definition, call rememberPetHostState(), bind PetHost InApp, then read state.requestedAnimation and player.currentSample.animation. They are idle and stand respectively; after state recreation idle returns. A temporary iOS InApp UI test confirmed the initial pair and Ready state.
- **Impact:** Public state lies about requested intent and can mislead controls, persistence, analytics, and future policy changes. The current generic test misses it.
- **Remediation direction:** Model unspecified initial intent or derive it from the definition at binding; preserve explicitly supplied requests and test bind/rebind/pin/resume/recreation.

### P1-02 — Fabricated public fallback in generic model

- **Severity:** P1.
- **File/lines:** codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/PetModel.kt:61-100; PetsKmpParser.kt:414-422; generated Swift header PetAnimation.fallback.
- **Violated contract:** Generic normalized public data should not require a Codex fallback transition absent from Pets KMP v1.
- **Expected:** A generic one-shot has a direct hold semantic with no fabricated fallback visible to consumers.
- **Actual:** Every generic animation reports fallback equal to its own key.
- **Reproduction:** Parse a generic one-shot dance and inspect definition.animation("dance").fallback; it is dance even though no fallback appears in the manifest.
- **Impact:** The generic ABI encodes implementation mechanics as domain data and constrains future adapters/schemas.
- **Remediation direction:** Separate one-shot hold from optional transition/fallback in the normalized model while keeping Codex adapter behavior exact.

### P1-03 — JSON strings accepted for required integer fields

- **Severity:** P1.
- **File/lines:** codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/PetsKmpManifestDto.kt:15, :26-42; PetsKmpParser.kt:52, :156-163; docs/spec/PETS_KMP_PACKAGE_V1.md:14-18, :69-74, :108-113.
- **Violated contract:** Pets KMP v1 requires JSON integer fields.
- **Expected:** "schemaVersion":"1", "width":"32", "index":"0", and "durationMs":"1" fail as wrong JSON types.
- **Actual:** Each was accepted and normalized by a temporary JVM probe; 1e0 was accepted too.
- **Reproduction:** Replace one integer token at a time in a valid 96x80, 32x40 manifest, then call PetsKmpPackageParser.parseTrustedMetadata; each quoted case returns Success.
- **Impact:** The published wire format and implementation disagree, which makes cross-language generators/validators inconsistent.
- **Remediation direction:** Choose and enforce precise JSON numeric syntax, including quoted and exponent forms; document the chosen rule and add permanent boundary tests.

### P1-04 — Codex custom animation name regression

- **Severity:** P1; confidence moderate.
- **File/lines:** codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/PetAnimationKey.kt:23-27; PetParser.kt:262-277.
- **Violated contract:** Codex V1 adapter behavior should remain unchanged by generic key limits.
- **Expected:** A valid Codex custom animation with a 65-character key follows the prior parser path; generic format limits may differ.
- **Actual:** The new key constructor throws and Codex parsing returns MalformedManifest. The phase-4-host key class had no validity/length guard and its parser directly inserted the key.
- **Reproduction:** In a valid Codex V1 manifest add an animation whose name is 65 ASCII a characters and whose frame/fps/fallback values are valid, then compare PetPackageParser at current HEAD with phase-4-host source behavior. A temporary current-HEAD JVM test returned MalformedManifest.
- **Impact:** Previously accepted Codex custom packages can fail after extraction.
- **Remediation direction:** Preserve Codex name acceptance separately from the generic 64-character policy or document and explicitly approve a compatibility break after validating upstream bounds.

### P2 and unconfirmed items

- **P2:** Generic parser trims keys while direct PetAnimationKey preserves spaces; document normalization and count length consistently.
- **P2:** Internal PetDefinition and public PetHost defaults still refer to idle; only the latter is separately P1.
- **P2:** Unknown generic play silently resolves to default, absent an explicit v1 contract.
- **P2:** ignoreUnknownKeys is unspecified for the project-owned format; version 2 rejection is correct.
- **P2:** Null animations yields generic MalformedManifest; missing animations yields EmptyAnimations.
- **P2:** fallbackId and avatar.json discovery are legacy concepts unnecessary for required-id generic packages.
- **P2:** Generic directory test is mislabeled and never invokes the public directory loader.
- **P2:** Kodee row 6 rest label is visually weak; row 8 happy is unproven. Research image hash is stale.
- **P2:** The temporary iOS generic no-idle InApp test passed, but no permanent version exists; external Swift generic-error paths lack direct tests.
- **P2:** Architecture/Security docs retain Codex-only statements in contexts that can read as global.
- **P2:** No hosted CI run exists for local asset-only HEAD 868ef94; exact implementation HEAD has a green run.
- **UNCONFIRMED RISK:** The 65-character Codex case was not executed against an old binary. Source comparison strongly supports the regression, but a dual-version executable comparison would remove the residual uncertainty.
- **UNCONFIRMED RISK:** No new malicious ZIP corpus or directory symlink race test was added during this review; shared code and existing tests support preservation of security guards.

## 24. Final recommendation

Do not rename modules, resume the example, or publish 0.1.0 yet. Resolve P1-01 through P1-04, lock the chosen behavior with cross-platform and Swift tests, then repeat the focused gate. The extraction is structurally close: generic geometry, explicit parser selection, shared secure IO, one-shot math, rendering, and Kodee paths work. The remaining failures are at the public semantics and wire-contract boundary, exactly where pre-release correction is cheapest.
