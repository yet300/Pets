# Generic Module Rename Migration Report

## 1. Starting HEAD
`8ba51403bc341d8b7a76ef31b1584f1f5bb90d15` (`docs(audit): close generic pet runtime gate`)

## 2. Final HEAD
`65d583652dad4dfeed9b2f802708d440a33578c2` (`ci: add Android API 36 real-overlay runtime verification`)

## 3. Commits
- `e2b19b1`: `refactor(build): rename core and io modules to pets-*` — initial rename of core and io modules
- `59e9dee`: `refactor(build): rename all modules to pets-* and update CI` — complete rename including compose, host, apple, CI, docs, Swift consumer, and ABI reference files
- `65d5836`: `ci: add Android API 36 real-overlay runtime verification` — add `android-api36` CI job mirroring `android-api24`

## 4. Old -> New Module Mapping

| Old Module | New Module | Physical Directory |
|---|---|---|
| `:codex-pets-core` | `:pets-core` | `pets-core/` |
| `:codex-pets-io` | `:pets-io` | `pets-io/` |
| `:codex-pets-compose` | `:pets-compose` | `pets-compose/` |
| `:codex-pets-host` | `:pets-host` | `pets-host/` |
| `:codex-pets-apple` | `:pets-apple` | `pets-apple/` |

No duplicate old module directories remain.

## 5. Old -> New Maven Coordinate Mapping

| Old Artifact ID | New Artifact ID |
|---|---|
| `codex-pets-core` | `pets-core` |
| `codex-pets-io` | `pets-io` |
| `codex-pets-compose` | `pets-compose` |
| `codex-pets-host` | `pets-host` |
| (apple framework, not published as Maven) | (N/A) |

Group remains `com.yet.pets`, version remains `0.1.0`.

Verified via `generatePomFileForJvmPublication`:
- `com.yet.pets:pets-core-jvm:0.1.0`
- `com.yet.pets:pets-io-jvm:0.1.0`
- `com.yet.pets:pets-compose-jvm:0.1.0`
- `com.yet.pets:pets-host-jvm:0.1.0`

## 6. Root Project Rename
`settings.gradle.kts`: `rootProject.name = "pets-kmp"` (was `"codex-pets-kmp"`)

## 7. Apple Module/Framework Rename

| Old | New |
|---|---|
| Gradle module | `:codex-pets-apple` → `:pets-apple` |
| XCFramework name | `CodexPets` → `Pets` |
| Framework base name | `CodexPets` → `Pets` |
| Swift import | `import CodexPets` → `import Pets` |
| Release task | `assembleCodexPetsReleaseXCFramework` → `assemblePetsReleaseXCFramework` |
| Swift consumer verification | Passes with `import Pets` |

Exactly one Apple umbrella framework remains (`Pets.xcframework`). No parallel `CodexPets` distribution.

## 8. Gradle Dependency Graph Before/After

**Before:**
```
:codex-pets-core ← :codex-pets-io
:codex-pets-core ← :codex-pets-compose ← :codex-pets-host
:codex-pets-apple → core + io + compose + host
```

**After:**
```
:pets-core ← :pets-io
:pets-core ← :pets-compose ← :pets-host
:pets-apple → core + io + compose + host
```

**Verified:**
- `pets-host` still does NOT depend on `pets-io`, Okio, filesystem, Ktor, or network APIs.
- Dependency direction unchanged: `core <- io`, `core <- compose`, `core <- compose <- host`.
- `./gradlew projects` shows only new module names.

## 9. Production Source Semantic Diff

Compared production source files (`src/commonMain`, `src/androidMain`, `src/iosMain`, `src/jvmMain`) at starting HEAD vs final HEAD:

**Zero semantic changes** in any Kotlin production file. Only differences are:
- KDoc comment updates (module references): `PetLoader.kt`, `PlatformPetOverlayHost.android.kt`
- Test temp directory name updates: `GenericDirectoryTest.kt`, `RealFilesystemSecurityTest.kt`
- File path changes due to git mv (no content changes)

All production logic identical: parsers, validators, playback, geometry, renderers, host state, loaders.

## 10. ABI Comparison

- Ran `checkKotlinAbi` on all four modules: **PASS**
- Regenerated ABI reference files via `updateKotlinAbi` to reflect new library unique names (`com.yet.pets:pets-*` vs `com.yet.pets:codex-pets-*`)
- Public Kotlin ABI surface unchanged — no added/removed/modified declarations
- Only metadata change: library unique name in ABI dumps

## 11. Generic Runtime Regression Results

All tests pass:
- `:pets-core:jvmTest` — **BUILD SUCCESSFUL** (105 tests)
- `:pets-core:iosSimulatorArm64Test` — **BUILD SUCCESSFUL** (37 tests)
- `:pets-io:jvmTest` — **BUILD SUCCESSFUL** (149 tests)
- `:pets-io:iosSimulatorArm64Test` — **BUILD SUCCESSFUL** (131 tests)
- `:pets-compose:jvmTest` — **BUILD SUCCESSFUL** (47 tests)
- `:pets-compose:iosSimulatorArm64Test` — **BUILD SUCCESSFUL** (32 tests)
- `:pets-host:jvmTest` — **BUILD SUCCESSFUL** (25 tests)
- `:pets-host:iosSimulatorArm64Test` — **BUILD SUCCESSFUL** (17 tests)

Includes: generic default/no-idle host, nullable intent, optional fallback, strict integer JSON, arbitrary animation keys, one-shot hold, generic ZIP/directory, synthetic non-Codex, Kodee generic, iOS no-idle host, Swift generic consumer.

## 12. Codex V1 Regression Results

All Codex compatibility locks pass:
- `CodexLockTest` — fixed V1 geometry, rows/timing, aliases, arbitrary long custom names, custom fallback, empty-vs-whitespace fallback, loopStart, one-hop, original elapsed clock, original Kodee V2 rejection
- `CustomAnimationsTest`, `DefaultAnimationsTest`, `PlaybackTest`, `ManifestParsingTest` — all green

## 13. IO Security Regression Results

All security/loader suites pass:
- Traversal rejection
- Symlink confinement (in-package allowed, escape rejected, dangling rejected)
- ZIP duplicate/overlap/CRC/DEFLATE consumption
- Archive limits (entry count, compressed/uncompressed size, compression ratio)
- Manifest discovery (generic requires `pet.json` only; `avatar.json`-only fails)

## 14. JVM Results

All JVM test suites green across ubuntu-latest, windows-latest, and macOS runners (CI matrix).

## 15. iOS Results

All iOS simulator tests green on macOS-latest (arm64 simulator). XCFramework assembly and Swift consumer verification pass.

## 16. Android API 24 / API 36 Results

Local Android test assembly successful:
- `:pets-compose:assembleAndroidTest` — **BUILD SUCCESSFUL**
- `:pets-host:assembleAndroidTest` — **BUILD SUCCESSFUL**

Hosted CI run **36452924860** at HEAD `65d5836` — **all 23 jobs SUCCESS**:

| Job | Result | Duration |
|---|---|---|
| `android-api24` | ✅ **GREEN** | 5m46s |
| `android-api36` | ✅ **GREEN** | 7m12s |
| `build` (21 matrix jobs) | ✅ **GREEN** | — |

API 24 real-overlay runtime verified: **Compose 14/14, Host 14/14** (0 failures, 0 errors, 0 skips).
API 36 real-overlay runtime verified: **Compose 14/14, Host 14/14** (0 failures, 0 errors, 0 skips).

Both API levels execute identical test suites via `reactivecircus/android-emulator-runner`:
- Grants `SYSTEM_ALERT_WINDOW` before host test
- Runs `:pets-compose:androidConnectedCheck` and `:pets-host:androidConnectedCheck`
- Host uses `TYPE_APPLICATION_OVERLAY` path on API 36 (API ≥ 26)

## 17. XCFramework Result

- Task: `:pets-apple:assemblePetsReleaseXCFramework` — **BUILD SUCCESSFUL**
- Output: `pets-apple/build/XCFrameworks/release/Pets.xcframework`
- Contains `ios-arm64` and `ios-arm64-simulator` slices
- Framework base name: `Pets`

## 18. Swift Consumer Result

- Task: `:pets-apple:verifySwiftConsumer` — **BUILD SUCCESSFUL**
- Consumer imports `import Pets`
- Compiles: generic parse, generic ZIP load, default animation key, nullable fallback/intent, `pinToDefault`, generic error inspection
- Single umbrella framework, no duplicate `PetDefinition` identity

## 19. Publication/POM Inspection

Verified via `generatePomFileForJvmPublication`:
- No live publication advertises `codex-pets-*` artifact IDs
- All POMs use `pets-core`, `pets-io`, `pets-compose`, `pets-host`
- Group: `com.yet.pets`, Version: `0.1.0`
- POM names/descriptions updated to generic "Pets *" naming

## 20. Search for Stale Live `codex-pets-*` Names

**Tracked source files (excluding historical docs in `docs/audit/`, `docs/research/`):**

- Zero occurrences of `codex-pets-core`, `codex-pets-io`, `codex-pets-compose`, `codex-pets-host`, `codex-pets-apple` in:
  - `settings.gradle.kts`
  - Live `build.gradle.kts`
  - Current CI (`.github/workflows/gradle.yml`)
  - Publication config (all `build.gradle.kts` mavenPublishing blocks)
  - Current README dependency instructions
  - Current architecture module graph
  - `SECURITY.md`
  - `PUBLIC_API_PROPOSAL.md`
  - `TEST_STRATEGY.md`
  - Source KDoc comments
  - `Consumer.swift`

- Zero occurrences of `CodexPets` as framework/umbrella name in live config

## 21. Historical Occurrences Intentionally Retained

Per instruction, historical documents in `docs/audit/` and `docs/research/` retain original `codex-pets-*` and `CodexPets` references for historical accuracy. Examples:
- `docs/audit/GENERIC_PET_RUNTIME_REMEDIATION_REPORT.md`
- `docs/audit/GENERIC_PET_RUNTIME_REMEDIATION_VERIFICATION.md`
- `docs/audit/PHASE_*_*.md`
- `docs/audit/FULL_PROJECT_*.md`
- `docs/research/CODEX_V2_COMPATIBILITY_RESEARCH.md`

No mass-edit of history performed.

## 22. Remaining Risks / P2 Items

- `linuxX64` Kotlin/Native target remains deferred (no CI leg)
- Version catalog third-party aliases unchanged (no rename needed)
- `wip/tamagotchi-example` branch not merged or adapted (per instructions)
- GitHub repository description still says "Codex like pets library for Kotlin Multiplatform" (repository metadata, not a rename blocker)

## 23. Final Recommendation

**PASS** — All acceptance gate criteria met at exact HEAD `65d5836`:

✅ Root project is generic (`pets-kmp`)  
✅ Live modules are exactly `pets-*`  
✅ Live Maven artifacts are generic `pets-*`  
✅ Kotlin packages remain `com.yet.pets.*`  
✅ Codex-specific APIs remain appropriately Codex-named (`CodexPetPackageParser`, `CodexV1`, `CodexPet`, `pinToIdle`, etc.)  
✅ Live Apple umbrella renamed to generic `Pets`  
✅ Exactly one Apple umbrella remains  
✅ Swift consumer imports `Pets` successfully  
✅ Gradle dependency direction unchanged  
✅ No runtime semantic changes  
✅ Generic runtime tests green  
✅ Codex V1 tests green  
✅ Original Kodee V2 still rejects through Codex adapter  
✅ Generic Kodee still succeeds  
✅ IO security regressions green  
✅ Android API 24 real overlay green (hosted CI run 36452924860)  
✅ Android API 36 real overlay green (hosted CI run 36452924860)  
✅ ABI validation green  
✅ Publication metadata contains no stale live Codex artifact IDs  
✅ Current docs use new naming  
✅ Old names remain only in historical evidence  
✅ Hosted CI at exact final HEAD is green (23/23 jobs)  
✅ Example work NOT resumed  
✅ No tag created