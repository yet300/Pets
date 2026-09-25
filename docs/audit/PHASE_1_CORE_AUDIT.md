# Phase 1 Core Audit — `codex-pets-core` vs pinned upstream

- Role: independent senior KMP reviewer. Did NOT implement the code.
- Scope: read-only adversarial audit of Phase 1 pure `:codex-pets-core` layer. No production code modified. No fixes applied. Phase 2 not started.
- Date (UTC): 2026-09-25
- Workspace: `/Users/yet/development/Multiplatform/Pets` (git: single commit `2a6dc58 Initial commit` + uncommitted Phase 0/1 work; see §8)

## Normative reference (authoritative)

- Repository: `openai/codex`
- Pinned commit: `55543d87724bb66bdd51bde65254feb9b4c9ed10`
- Primary files fetched and inspected at that SHA (via `raw.githubusercontent.com`):
  - `codex-rs/tui/src/pets/model.rs` (full file incl. `PetFile`, `FrameSpec`, `AnimationSpec`, `load_animations`, `validate_animation_indices`, `default_animations`, `idle_animation`, `app_state_animation`, `validate_frame_spec`, `resolve_spritesheet_path`, `load_pet_manifest`, tests)
  - `codex-rs/tui/src/pets/ambient.rs` (full file incl. `current_animation`, `current_animation_frame`, `frame_at_elapsed`, `nanos_to_duration`)
  - `codex-rs/tui/src/pets/catalog.rs` (constants `DEFAULT_FRAME_WIDTH/HEIGHT/COLUMNS/ROWS`, `SPRITESHEET_WIDTH/HEIGHT`, builtin list)
- Current HEAD explicitly NOT used as reference.
- Phase 0 docs treated as requirements, but contradictions with pinned upstream are reported (see F-04, §7).

## Implementation inspected (do not trust any Phase 1 report)

All `codex-pets-core/src/commonMain` files read in full:

- `ManifestDto.kt` (39 lines): `CodexPetManifestDto`, `FrameSpecDto`, `AnimationSpecDto`
- `PetParser.kt` (335 lines): `PetSpritesheetPathOutcome`, `PetParseOutcome`, `PetPackageParser`, `CodexCompatibilityValidator`
- `PetModel.kt` (141 lines): `SpritesheetFormat`, `SpritesheetInfo`, `PetFrame`, `PetAnimation`, `PetDefinition`
- `Playback.kt` (127 lines): `PetPlaybackSample`, `staticIdleSpriteIndex`, `samplePetAnimation`
- `CodexV1Profile.kt` (103 lines): `CodexV1` constants + `defaultAnimations`
- `PetAnimationKey.kt` (35 lines): `PetAnimationKey`, `PetAnimations`
- `Identity.kt` (35 lines): `PetIdentity`, `normalizePetIdentity`
- `Geometry.kt` (67 lines): `IntRect`, `AtlasGeometry`
- `CompatibilityReport.kt` (104 lines): `PetCompatibilityError`, `PetCompatibilityReport`

All `commonTest` files read in full (82 `@Test` total; see §8):

- `ManifestParsingTest.kt` (15), `DefaultAnimationsTest.kt` (5), `PlaybackTest.kt` (20), `CustomAnimationsTest.kt` (17), `ModelInvariantsTest.kt` (7), `GeometryTest.kt` (10), `IdentityTest.kt` (8)

Also read: `codex-pets-core/build.gradle.kts`, `gradle/libs.versions.toml`, `codex-pets-core/api/codex-pets-core.klib.api`, `api/jvm/codex-pets-core.api`, `api/android/codex-pets-core.api`, `.github/workflows/gradle.yml`, `.github/workflows/publish.yml`, `docs/research/CODEX_COMPATIBILITY.md`, `docs/architecture/ARCHITECTURE.md`, `PUBLIC_API_PROPOSAL.md`, `TEST_STRATEGY.md`.

No Phase 1 implementation report was found in-repo (no `docs/audit/`, no Phase 1 markdown); therefore every claim is verified from source, not from a report.

---

## Findings (ID / severity / file / lines / expected / actual / reproduction / remediation)

### F-01 — P0 — version catalog dangling refs block EVERY Gradle gate

- File: `gradle/libs.versions.toml:19,24`
- Lines:
  - `19: androidx-lifecycle-runtimeCompose = { ... version.ref = "androidx-lifecycle" }` — no `[versions] androidx-lifecycle` exists.
  - `24: coil-compose = { ... version.ref = "coil" }` — no `[versions] coil` exists.
- Expected: any Phase 1 gate (`jvmTest`, `iosSimulatorArm64Test`, `assemble`, `checkKotlinAbi`) runs. CI matrix in `.github/workflows/gradle.yml:25-32` assumes these run.
- Actual: every `./gradlew` invocation fails in configuration phase before any task executes:
  ```
  org.gradle.api.InvalidUserDataException > Undefined version reference
  In version catalog libs, version reference 'coil' doesn't exist
  Dependency 'io.coil-kt.coil3:coil-compose' references version 'coil' which doesn't exist
  ```
  Reproduced 2026-09-25 for both `:codex-pets-core:jvmTest --rerun-tasks` and `:codex-pets-core:checkKotlinAbi` (exit BUILD FAILED, 392–595 ms, no tests executed). Full log captured in §8.
- Reproduction: `./gradlew :codex-pets-core:jvmTest` on clean checkout at current working tree.
- Remediation direction: declare the missing versions or delete the unused `androidx-lifecycle-runtimeCompose` / `coil-compose` library entries (they are unused by `:codex-pets-core`; the ARCHITECTURE §9 already flags “repair the catalog's dangling references”). Do NOT work around by editing files during audit. This alone forces REMEDIATION REQUIRED — no current gate evidence can be produced until fixed.
- Note: `build/test-results/{jvmTest,iosSimulatorArm64Test}/*.xml` show a prior passing run on 2026-09-25T16:58–16:59Z (82/82 each, 0 failures). That is stale evidence; current tree cannot reproduce it.

### F-02 — P1 — `fallback: "   "` (blank) incorrectly normalizes to `idle`; upstream only defaults exact `""`

- File: `codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/PetParser.kt:268`
  ```kotlin
  val fallbackName = spec.fallback.trim().ifEmpty { PetAnimations.Idle.value }
  ```
- Upstream (`model.rs::load_animations`):
  ```rust
  let fallback = if spec.fallback.is_empty() { "idle".to_string() } else { spec.fallback };
  ```
  No `trim()` anywhere on fallback. `is_empty()` is exact-empty, not blank.
- Expected: `"fallback": "   "` stays literal `"   "` and then fails with `UnknownFallback` (no animation named `"   "` exists), exactly like `"fallback": "missing"`.
- Actual: Kotlin trims → `""` → `ifEmpty` → `"idle"` → parse SUCCEEDS with fallback idle. False-accept vs upstream false-reject.
- Reproduction (manifest, with valid 1536×1872 WEBP facts):
  ```json
  {"animations": {"custom": {"frames": [1], "fallback": "   "}}}
  ```
  Upstream: `bail!("animation custom fallback     does not exist")`. Kotlin: `Success` with `fallback == Idle`. The existing test `CustomAnimationsTest.blankFallbackNormalizesToIdle` (line 57–60) asserts the WRONG behavior (`"  "` → Idle) and would fail if parity were restored.
- Also affected: `"fallback": "  idle  "` — upstream literal (→ UnknownFallback), Kotlin trimmed (→ Idle success).
- Remediation direction: mirror upstream exactly: `if (spec.fallback.isEmpty()) "idle" else spec.fallback` with NO trim. If blank-trim is desired as hardening, it must be labeled as deliberate hardening (see §5) and the test renamed, not claimed as parity. Task explicitly calls out `isEmpty()` vs `isBlank()` — this is the instance.
- Contrast (correct): `spritesheetPath` trimming IS parity — upstream does `map(str::trim).filter(!is_empty).unwrap_or("spritesheet.webp")` and Kotlin `effectiveSpritesheetPath` (`ManifestDto` trim + `isNullOrEmpty`) matches, including blank→default. Do not “fix” spritesheetPath.

### F-03 — P2 — numeric schema types diverge: `Int` vs `u32`/`usize` changes error taxonomy (still reject, different typed error)

- Files: `codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/ManifestDto.kt:24-38`
  ```kotlin
  internal data class FrameSpecDto(val width: Int, val height: Int, val columns: Int, val rows: Int)
  internal data class AnimationSpecDto(val frames: List<Int> = ..., ...)
  ```
- Upstream: `FrameSpec { width: u32, ... }`, `AnimationSpec { frames: Vec<usize>, ... }`.
- Expected (upstream): negative numbers are schema errors:
  - `{"frame": {"width": -192, ...}}` → `serde` fails on `u32` → `parse pet.json` context → loader error (Kotlin analogue: `MalformedManifest`).
  - `{"animations": {"x": {"frames": [-1]}}}` → `serde` fails on `usize` → parse error.
- Actual (Kotlin): negatives decode fine as `Int`, then map to semantic errors:
  - negative frame dims → `InvalidFrameGrid` (see `GeometryTest.zeroGridValuesRejected`, `PetParser.kt:135-140`).
  - negative sprite index → `SpriteIndexOutOfRange` (see `CustomAnimationsTest.negativeIndexRejected`, `PetParser.kt:252-256`).
  - Both still FAIL (good), but with a different `PetCompatibilityError` variant than upstream’s parse-failure analogue. Any consumer branching on `MalformedManifest` vs `InvalidFrameGrid`/`SpriteIndexOutOfRange` will diverge.
- Additional edge: values in `(Int.MAX_VALUE, UInt.MAX_VALUE]` (2147483648–4294967295): upstream `u32` accepts then validates grid; Kotlin `Int` rejects at JSON decode → `MalformedManifest`. Unreachable for covering grids (covering values are ≤1872) but reachable for non-covering-grid error taxonomy.
- Remediation direction: keep `Int` (KMP-idiomatic) but document the taxonomy mapping explicitly, or switch DTO fields to `UInt`/`ULong` with custom serializers if exact error parity is required. At minimum, add tests asserting the CURRENT mapping so the deviation is pinned, not accidental.

### F-04 — P2 — Phase 0 identity spec contradicts upstream `custom:` cache-id behavior; Kotlin follows Phase 0 (correct for Phase 1 scope, but contradiction must be recorded)

- Files:
  - Upstream `model.rs::load_pet_manifest`: `pet_id = if cache_id == fallback_id { manifest_id.unwrap_or(fallback_id) } else { cache_id }`. For `custom:<id>` flow, `cache_id = "custom-<id>"`, `fallback_id = "<id>"` → id is ALWAYS `"custom-<id>"`, even if manifest contains `"id": "chefito"`.
  - Kotlin `Identity.kt:21-34` + `PetParser.kt:220-225`: `id = safeManifestId ?: safeFallback` — manifest always wins; no `custom-` prefix concept.
  - Phase 0 `PUBLIC_API_PROPOSAL.md §4.1`: `pet.id = manifestId ?: fallbackId` — matches Kotlin, contradicts upstream custom flow.
- Expected per pinned upstream (custom selector): `Pet::load_with_codex_home("custom:chefito")` with manifest `{"id":"chefito"}` yields id `"custom-chefito"` (see upstream test `custom_pet_selector_loads_codex_home_pet_manifest`).
- Actual (Kotlin Phase 1): same inputs via `normalizePetIdentity("chefito", ..., "chefito")` yield `"chefito"`.
- Impact for Phase 1: NONE for the implemented API — Phase 1 has no `CODEX_HOME`/`custom:` loader; `fallbackId` is caller-derived (dir/ZIP/URL per proposal). Within that scope, manifest-wins is correct and tested (`IdentityTest.explicitManifestIdWinsOverFallback`). The divergence only matters when Phase 2 implements `custom:` loading.
- Remediation direction: record the contradiction (done here); Phase 2 must decide: (a) reproduce `custom-` prefix for CLI parity, or (b) deliberately diverge with rationale (content-addressed cache ids are loader detail, not identity). Do not silently assume manifest-wins covers `custom:`.
- Related minor hardening (P2, no action): Kotlin trims `fallbackId` (`fallbackId.trim().ifEmpty{"pet"}`); upstream `fallback_id` from `file_name().to_str().unwrap_or("pet")` is untrimmed. Trimming is safe hardening; keep.

### F-05 — P2 — dangling-fallback player resolves to `idle`; upstream holds original last frame (only reachable for invalid manual models)

- File: `codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/Playback.kt:57-62`
  ```kotlin
  if (selected.loopStart == null && elapsedNanos >= totalNanos(selected)) {
      val fallback = animations[fallbackKey] ?: idle
      val evaluatedKey = if (animations.containsKey(fallbackKey)) fallbackKey else Idle
      return evaluate(fallback, evaluatedKey, elapsedNanos)
  }
  ```
- Upstream (`ambient.rs::current_animation`):
  ```rust
  if animation.loop_start.is_none() {
      if elapsed >= total && let Some(fallback) = get(&fallback) { return Some(fallback); }
  }
  Some(animation)
  ```
  If fallback key is absent, upstream returns the ORIGINAL completed one-shot (then `current_animation_frame` holds its last frame with `delay: None`). Kotlin substitutes `idle` evaluated at the same clock.
- Expected (exact parity): dangling fallback → hold original last frame.
- Actual: dangling fallback → jump to idle track.
- Reachability: ZERO for parser output — `PetParser` validates `UnknownFallback` against the final table (`PetParser.kt:193-199`) and `CodexCompatibilityValidator` re-checks (`PetParser.kt:329-331`), so foreign data can never produce a dangling definition via `parse`. Only manually constructed `PetDefinition` (bypassing the validator, as in `PlaybackTest.missingFallbackFallsBackToIdle:248-258`) can trigger it. The test pins the hardening intentionally (“the player stays total by resolving to idle”).
- Remediation direction: keep the totality hardening (never crash on programmer error), but label it as hardening in KDoc (currently `samplePetAnimation` KDoc does not mention the dangling-fallback substitution) and never claim it as upstream parity.

### F-06 — P2 — subnormal/tiny-fps hardening vs upstream panic (correct hardening, must not be called parity)

- File: `PetParser.kt:258-267`
  ```kotlin
  val fps = spec.fps ?: CodexV1.DEFAULT_FPS
  val duration = if (fps.isFinite() && fps > 0.0 && fps <= CodexV1.MAX_FPS) (1.0 / fps).seconds else null
  if (duration == null || !duration.isFinite()) { errors += InvalidFps(...); return null }
  ```
- Upstream guard is identical (`is_finite && >0 && <=60`, default 8.0), BUT then calls `Duration::from_secs_f64(1.0 / fps)` unconditionally. For `fps = 4.9e-324` (Double.MIN_VALUE, covered by `CustomAnimationsTest.subnormalFpsYieldsNonFiniteDurationAndIsRejected:99-103`), `1.0/fps = +inf` → `from_secs_f64(inf)` panics (overflow). Same for smallest normal `2.225e-308` (`1/fps ≈ 4.49e307` s overflows Rust `Duration`). Kotlin instead yields `(inf).seconds == INFINITE` → `!isFinite` → typed `InvalidFps`. This is DELIBERATE LIBRARY HARDENING (category B in the task), not exact upstream behavior (category A).
- Hardening window: Kotlin additionally rejects `0 < fps ≲ 1.08e-10` (durations > ~292 years saturate to `INFINITE` in `kotlin.time`) that upstream `Duration` (max ~1.8e19 s) would accept. No practical pet is affected (a frame every 300 years); all practical fps (8, 24, 59.94, 60) are unaffected. See FPS table §3.
- Remediation direction: keep the hardening; explicitly document it as hardening (“upstream would panic; we return InvalidFps”) in `PetParser` KDoc and in the FPS row of the parity table. Do not claim “exact fps parity” without the qualifier.

### F-07 — P2 — deterministic multi-error + sorted iteration is an improvement, not parity (correct, keep, label)

- File: `PetParser.kt:177-185` (`keys.sorted()`), `193-199`/`202-214` (`sortedBy`), vs upstream `for (name, spec) in specs` over `HashMap` (random order) with first-`bail!` wins.
- For VALID inputs the result is order-independent in both (inserts commute; fallback validation runs after all inserts, so forward refs like `a→b` work regardless of iteration — verified by `CustomAnimationsTest.customFallbackToCustomAnimationAccepted`). Good.
- For INVALID inputs with ≥2 bad animations, upstream reports exactly ONE error (nondeterministic which); Kotlin reports ALL errors in sorted name order (pinned by `CustomAnimationsTest.multipleAnimationErrorsAreAllReported`). This is a deterministic improvement.
- Remediation direction: keep; ensure docs/tests use the wording “deterministic improvement,” never “upstream reports N errors.”

### F-08 — P2 (info) — alias entries share instances vs upstream fresh instances (no behavioral difference)

- File: `CodexV1Profile.kt:96-100` (`MoveRight to runningRight`, etc. — same object) vs upstream `default_animations()` calling `app_state_animation(...)` afresh per alias (equal but distinct objects).
- `PetAnimation` is effectively immutable (`frames.toList()` snapshot, no mutators), so sharing is observationally equivalent under `equals`. Test `aliasesShareReferenceTracks` uses `assertEquals` (not `assertSame`), so it passes under either. No remediation; note for reviewers that `assertSame` must never be added.

### F-09 — P2 — defensive copies are shallow-castable (`toList`/`toMap` return mutable impls at runtime)

- Files: `PetModel.kt:59` (`frames.toList()`), `PetModel.kt:106` (`animations.toMap()`), `CompatibilityReport.kt:90-91` (`errors.toList()`, `warnings.toList()`).
- Snapshotting against caller-owned mutables is verified (`ModelInvariantsTest.definitionSnapshotsCallerCollections`, `reportSnapshotsCallerCollections`) and `PetFrame`/`PetAnimation` values are immutable, so depth is sufficient for the exposed model.
- Residual: on JVM/Android, `toList()` returns `ArrayList` and `toMap()` returns `LinkedHashMap`; a hostile caller can `(definition.animations as MutableMap)[...] = ...` or `(animation.frames as MutableList).clear()` via unchecked cast and mutate the “immutable” view. True sealing requires `Collections.unmodifiableList/Map` or Kotlin `toImmutable*`. Standard KMP practice accepts this; the threat is out-of-scope for a data model (same exposure exists in stdlib `listOf` on JVM? No — `listOf` returns unmodifiable in recent Kotlin, but `toList` does not guarantee it).
- Remediation direction: document that the guarantee is snapshot-isolation from caller-owned collections at construction time, not runtime cast-proofing. If cast-proofing is required, wrap with unmodifiable views on JVM (expect/actual) — explicitly out of Phase 1 scope; do not change now.

### F-10 — P2 — `parseManifest` construction path is exception-unchecked (unreachable today, but foreign-data-totality relies on reasoning, not enforcement)

- Files: `PetParser.kt:161-169` (`AtlasGeometry(...)`), `226-233` (`PetDefinition(...)`), `269-273` (`PetAnimation(...)`) — none wrapped in try/catch; only JSON decode is caught (`73-86`).
- Today unreachable: covering-grid values imply small factors (so `AtlasGeometry` overflow guards cannot fire); fps-capped durations imply valid `PetFrame`/`PetAnimation`; `frameCount == capacity` holds by construction. Verified by audit.
- Risk: future edits (new grid rules, new duration sources) can open a crash path where foreign bytes throw `IllegalArgumentException` instead of yielding `Failure`. The KDoc promise (“Foreign invalid data always yields Failure, never a crash,” `PetParser.kt:18-19`) is therefore one refactor away from false.
- Remediation direction: wrap the normalization/construction tail in `try { ... } catch (e: IllegalArgumentException) { Failure(...) }` OR add a test that fuzzes the boundary (huge `frame` values already covered by `hugeValuesRejectedWithoutOverflow`). P2 because not currently triggerable.

### F-11 — P2 — test-quality gaps (82 tests exist and previously passed, but several branches/edges are unpinned or weak)

Counts verified: `grep -c @Test` → Identity 8, Custom 17, Geometry 10, Default 5, Manifest 15, Playback 20, Model 7 = 82. Prior XML (stale, see F-01): 82/82 pass on jvm + 82/82 on iosSimulatorArm64. Current tree cannot re-run (F-01).

- F-11a — implementation-against-itself (weak goldens):
  - `DefaultAnimationsTest.parsedDefaultsEqualCodeTable:82-85` (`parseSuccess("{}").animations == CodexV1.defaultAnimations()`) passes even if the whole table is wrong. Independent literals exist for idle + rows via `assertRow` call args, but the test name overclaims.
  - `ManifestParsingTest.emptyManifestUsesAllDefaults:25-33` compares geometry to `CodexV1.defaultGeometry()` instead of literal `AtlasGeometry(1536,1872,8,9,192,208)` (the literal IS pinned in `GeometryTest.defaultGeometryMatchesV1Profile`, so coverage exists once — the manifest test should use the literal too).
- F-11b — missing fps edges (task explicitly lists): `NaN`, `+Infinity`, `-Infinity` never appear as `fps` inputs (only `0`, `-1`, `60.0001`, `1e308` in `invalidFpsValuesRejected`, plus `1e999`→Malformed and `4.9e-324` subnormal). Smallest normal `2.225e-308` also untested. All currently reject correctly by code reading, but unpinned.
- F-11c — missing merge edges: alias override (`{"animations": {"move_right": ...}}` replacing an alias), `idle` deletion attempt (cannot be deleted — `or_insert` dead code — but no test pins that overriding `idle` then adding alias still keeps `idle`), reverse-order forward ref (`b→a` where `a` sorts later). Only `a→b` is tested.
- F-11d — weak scheduling assertions: `veryLargeElapsedHasNoIterationBlowup` (`PlaybackTest:196-199`) and `infiniteElapsedIsDeterministic` (`207-211`) assert only `spriteIndex in range`, not exact deterministic values. They prove no crash/hang but cannot detect off-by-one in the modulo path. `farFutureElapsedStaysInLoop` is the only exact far-future golden.
- F-11e — missing facade edge: `spritesheetPathOf(ByteArray)` with invalid UTF-8 is untested (only `parse(ByteArray)` invalid is tested). `spritesheetPathOf` malformed-JSON is tested (String only).
- F-11f — duplicate/overlap (minor): `idleProgressionUsesPerFrameDurations` and `exactFrameBoundaryAdvances` both assert the 1680 ms boundary; keep both (boundary deserves duplication) but do not count them as two independent branches.
- Remediation direction: add the NaN/Inf/smallest-normal fps cases, alias-override + reverse-ref merge cases, literal-geometry manifest assertion, exact-value large-elapsed goldens (compute via `u128` math in Python, not via implementation), and ByteArray `spritesheetPathOf` invalid case. Do not delete the 82.

### F-12 — P2 — Unicode `trim()` differs between Rust and Kotlin (negligible, record only)

- Files: `Identity.kt:27-29`, `PetParser.kt:104-105,268`.
- Rust `str::trim` strips Unicode `White_Space`; Kotlin `String.trim()` strips chars `<= ' '` (ASCII whitespace + some controls). Exotic whitespace (e.g., U+00A0 NBSP, U+2003 EM SPACE) is trimmed by Rust but NOT by Kotlin. Consequence: manifest `{"id": "\u00A0"}` → upstream treats as absent (→ fallback), Kotlin treats as present (→ id `"\u00A0"`). No test covers non-ASCII whitespace. No practical pet uses NBSP ids; record only.
- Remediation direction: if exact parity is required, use `trim { it.isWhitespace() }` (Unicode-aware) instead of `trim()`. P2 low.

### F-13 — P2 (info) — Apple-interop surface risk (no redesign in this audit; flag only)

- Public ABI exposes `kotlin.time.Duration` (`PetFrame.duration`, `PetPlaybackSample.nextFrameIn`) and `value class PetAnimationKey` with `@JvmInline` (`PetAnimationKey.kt:3,13`). Both are in the klib + JVM dumps (expected).
- Risk: Swift/KMP consumers see `Duration` as an unboxed `Int64` + companion helpers (awkward but usable), and inline value classes have historically had fragile ObjC headers. `kotlin.jvm.JvmInline` in `commonMain` is stdlib-legal but JVM-flavored. No DTO/internal/CodexV1 leakage found (see §6) — the surface is clean. Flagged per task instructions; another reviewer owns Apple interop — no remediation here.

---

## §2 Manifest parity (field-by-field vs `PetFile`/`FrameSpec`/`AnimationSpec`)

| Upstream field | Upstream shape | Kotlin DTO | Verdict |
|---|---|---|---|
| `id` | `Option<String>`, `#[serde(default)]`, trim+filter-empty, fallback dir name | `CodexPetManifestDto.id: String? = null`, `normalizePetIdentity` trim+ifEmpty-null, fallback param | ✅ parity (modulo F-12 NBSP) |
| `displayName` | `Option<String>` rename `displayName`, trim+filter-empty, `or(manifest_id).unwrap_or(fallback)` | `displayName: String? = null`, same chain | ✅ parity |
| `description` | `Option<String>`, `map(trim).unwrap_or_default()` (no empty-filter) | `description: String? = null`, `?.trim() ?: ""` | ✅ parity |
| `spritesheetPath` | `Option<String>` rename, trim+filter-empty, default `"spritesheet.webp"` | `spritesheetPath: String? = null`, `effectiveSpritesheetPath` trim+isNullOrEmpty→default | ✅ parity |
| `frame` | `Option<FrameSpec>`, default 192×208 8×9 | `frame: FrameSpecDto? = null`, `CodexV1.defaultGeometry()` | ✅ parity |
| `FrameSpec` | `u32 ×4`, non-zero + exact-cover + overflow guards + count ≤256 | `FrameSpecDto: Int ×4`, same checks with `Long` arithmetic (`PetParser.kt:135-170`) | ⚠️ P2 taxonomy per F-03; accept/reject parity holds for all covering grids |
| `animations` | `HashMap<String,AnimationSpec>`, `#[serde(default)]`, no `deny_unknown_fields` | `Map<String,AnimationSpecDto> = emptyMap()`, `Json{ignoreUnknownKeys=true}` | ✅ parity (incl. `spriteVersionNumber` passthrough, tested) |
| `AnimationSpec.frames` | `Vec<usize>`, default `[]`, non-empty + `< frame_count` | `List<Int> = emptyList()`, same checks | ⚠️ P2 per F-03 |
| `AnimationSpec.fps` | `Option<f64>`, default 8.0, finite + `0<fps≤60` | `Double? = null`, same guard + `DEFAULT_FPS` | ✅ except F-06 hardening window + F-11b untested NaN/Inf |
| `AnimationSpec.loop` | `Option<bool>` rename `loop`, default true → `Some(0)` / `None` | `Boolean? = null` rename `loop`, `if (loop != false) 0 else null` | ✅ parity |
| `AnimationSpec.fallback` | `String` default `""`, `if is_empty → idle` (NO trim) | `String = ""`, `trim().ifEmpty → idle` | ❌ P1 per F-02 |
| Unknown fields | silently ignored | `ignoreUnknownKeys=true` | ✅ parity |

`isEmpty()` vs `isBlank()` audit: only `fallback` is wrong. `id`/`displayName`/`spritesheetPath` correctly implement trim-then-empty (matching upstream trim-then-`is_empty`). `description` correctly does not filter. `"fallback": "   "` is the reproducer.

## §3 FPS / duration edge table (`fps → 1/fps → Duration → PetFrame → playback`)

Upstream validation: any finite `0 < fps ≤ 60`, then `Duration::from_secs_f64(1/fps)` (panics on overflow/NaN path).

| fps | Upstream | Kotlin (`PetParser.kt:258-267`) | Playback note |
|---|---|---|---|
| 8 | ✅ 125 ms | ✅ 125 ms | exact |
| 24 | ✅ ≈41.666 ms | ✅ same `(1/24).seconds` | no ms truncation (tested `fractionalFpsPreservesPrecision`) |
| 59.94 | ✅ ≈16.683 ms | ✅ same | tested, loops correctly |
| 60 | ✅ ≈16.666 ms | ✅ same | tested `sixtyFpsIsAcceptedWithExactDuration` |
| 60.0001 | ❌ bail | ❌ `InvalidFps` | parity |
| 0, negative | ❌ bail | ❌ `InvalidFps` | parity |
| NaN | ❌ bail | ❌ `InvalidFps` (`isFinite` false) | parity by reading, UNTESTED (F-11b) |
| ±Infinity | ❌ bail | ❌ `InvalidFps` | parity by reading, UNTESTED |
| `Double.MIN_VALUE` 4.9e-324 | guard passes → `from_secs_f64(inf)` PANICS | ❌ `InvalidFps` (inf duration) | hardening (F-06) |
| smallest normal 2.225e-308 | guard passes → `from_secs_f64(4.49e307)` PANICS | ❌ `InvalidFps` (saturates inf) | hardening, UNTESTED |
| 1e-10 (tiny but finite duration 1e10 s) | ✅ (fits Rust Duration) | ❌ `InvalidFps` (1e10 s > Kotlin max ~9.2e9 s → INF) | hardening window, no practical pet |
| 1e308 | ❌ bail (>60) | ❌ `InvalidFps` | parity (tested) |
| 1e999 (JSON) | ❌ serde overflow → parse error | ❌ `MalformedManifest` | parity (tested `overflowingJsonNumberIsMalformed`) |

Hardening never rejects a practical pet (all real fps are 2–60). Do not call it exact parity.

## §4 Default animation table (independently reconstructed from pinned Rust)

Rust: `idle_animation()` = `[(0,1680),(1,660),(2,660),(3,840),(4,840),(5,1920)]`, `loop_start=Some(0)`, `fallback="idle"`. `app_state_animation(row,n,ms,final)` = primary `row*8+col` (col 0..n-1, last uses `final`), frames = primary×3 + idle, `loop_start=Some(n*3)`, fallback idle. Aliases call the constructor afresh with same args.

| Key(s) | Row | Primary indices | Per-frame ms | Total frames | loopStart | Fallback | Kotlin (`CodexV1Profile.kt`) |
|---|---|---|---|---|---|---|---|
| `idle` | 0 | 0,1,2,3,4,5 | 1680,660,660,840,840,1920 | 6 | 0 | idle | ✅ exact (`idleFrames`, `idleAnimation`) |
| `running-right`,`move_right` | 1 | 8–15 | 120×7,220 | 30 | 24 | idle | ✅ (`appStateAnimation(1,8,120,220)`) |
| `running-left`,`move_left` | 2 | 16–23 | 120×7,220 | 30 | 24 | idle | ✅ (2,8,120,220) |
| `waving`,`wave` | 3 | 24–27 | 140×3,280 | 18 | 12 | idle | ✅ (3,4,140,280) |
| `jumping`,`bounce` | 4 | 32–36 | 140×4,280 | 21 | 15 | idle | ✅ (4,5,140,280) |
| `failed`,`sad` | 5 | 40–47 | 140×7,240 | 30 | 24 | idle | ✅ (5,8,140,240) |
| `waiting` | 6 | 48–53 | 150×5,260 | 24 | 18 | idle | ✅ (6,6,150,260) |
| `running` | 7 | 56–61 | 120×5,220 | 24 | 18 | idle | ✅ (7,6,120,220) |
| `review` | 8 | 64–69 | 150×5,280 | 24 | 18 | idle | ✅ (8,6,150,280) |

Every built-in + alias key (14 keys), primary indices, repetitions (3×), appended idle, per-frame durations, fallback, loopStart verified. `DefaultAnimationsTest` goldens match this table (except the self-assertion caveat F-11a). No reliance on Kotlin tests for this table — recomputed from Rust above.

## §5 Custom animation merge

| Case | Upstream | Kotlin | Verdict |
|---|---|---|---|
| specs empty | return defaults after `validate_animation_indices` | defaults, then same two validation loops | ✅ |
| new key | `insert` | `animations[key]=normalized` | ✅ (tested `customEntryAddsNewKey`) |
| override builtin | `insert` overwrites | same | ✅ (tested `customEntryOverridesDefault` for `idle`) |
| override `idle` | overwritten; `entry("idle").or_insert` no-op | overwritten; `if (!contains) idle` no-op | ✅ |
| `idle` absent after | `or_insert(idle)` (dead in practice — defaults always have idle) | same guard (dead) | ✅ |
| alias override | `insert("move_right",…)` overwrites alias | same code path | ✅ by reading, UNTESTED (F-11c) |
| fallback to later-added key | validated AFTER all inserts → order-independent | same (`193-199` after `177-185`) | ✅ (tested one direction) |
| invalid sprite | per-spec bail + final validate | per-spec `SpriteIndexOutOfRange` + final table check | ✅ accept/reject parity; multi-error count differs (F-07) |
| iteration order effect | `HashMap` random → which single error reported is nondeterministic | `sorted()` → deterministic | ✅ improvement (F-07) |

Result does not depend on map iteration for success/failure — verified.

## §6 Playback audit (`samplePetAnimation` vs `ambient.rs`)

| Behavior | Upstream | Kotlin (`Playback.kt`) | Test |
|---|---|---|---|
| unknown key → idle | `get(name).or_else(get idle)` | `if contains else Idle` | ✅ `unknownKeyResolvesToIdle` |
| loop from 0 | modulo total | `prefix + (elapsed-prefix)%loop` | ✅ `regularLoopRestartsCleanly` |
| prefix + nonzero loopStart | prefix once, suffix loops | same (`95-105`) | ✅ `prefixLoopSectionWithNonZeroLoopStart` |
| at frame boundary (`<` vs `<=`) | `<` advances | `<` (`119`) | ✅ `exactFrameBoundaryAdvances` |
| at total duration (looping) | `>=total` enters loop section | `>=total` (`100`) | ✅ `builtInThreeXPrefixSettlesIntoIdleLoop`, `regularLoopRestartsCleanly` |
| at total duration (one-shot) | `current_animation` hops (`>=`) | hops (`57`) | ✅ `oneShotExactlyAtCompletionHops` |
| one-hop fallback | single `if let Some` | single `if`, no recursion | ✅ |
| same-clock fallback | same `elapsed` passed through | same `elapsedNanos` (`61`) | ✅ `oneHopAtoBEvaluatedAtSameClock` (B at 250 ms, not frame 0) |
| A→B→C stays B | no recursion | no recursion (`evaluate` never hops) | ✅ `twoHopsRemainOnBDoesNotAdvanceToC` |
| very large elapsed | O(frames) sums + `%` (u128) | O(frames) saturating sums + `%` | ✅ weak (`veryLargeElapsed...`, F-11d) |
| zero elapsed | first frame | first frame | ✅ |
| negative elapsed | N/A (monotonic `Instant`) | coerces to 0 (`67`) | hardening, ✅ `negativeElapsedCoercesToZero` |
| infinite elapsed | N/A (`std::time::Duration` finite) | saturates `Long.MAX` (`68`) | hardening, ✅ weak (F-11d) |
| single-frame looping | `(sprite, None)` | `(sprite, null)` (`90-93`) | ✅ `singleFrameLoopingIsStatic` |
| single-frame one-shot | `(sprite, None)` pre-hop (TUI quirk: no wake-up) | `(sprite, remaining)` pre-hop, hop at total | deliberate improvement, ✅ `singleFrameOneShotReportsRemainingThenHops` |
| dangling fallback | hold original | jump to idle | hardening, F-05 |

Complexity: `totalNanos` O(F), prefix O(loopStart), `frameAt` O(F) — never O(elapsed/cycles). No loop proportional to elapsed. Confirmed by reading; `veryLargeElapsed` test would hang if violated.

Off-by-one: both use `remaining < frameNanos` and `elapsed >= total`. No `<=` bug found.

## §7 Duration accumulation / overflow

- All sums in `Long` nanos with `saturatingAdd` (`80-81`); `elapsed` saturated (`66-70`); `total-prefix` guarded by `loopDuration > 0` (`100`). Modulo-by-zero impossible (loop section has ≥1 positive frame; saturation-collapse to 0 is guarded).
- `frameAt` subtracts `frameNanos` (`122`) — no underflow (guarded by `<` branch).
- Upstream `max(1)` for sub-ns frames has no Kotlin analogue, but is unreachable: `PetFrame` requires positive finite `Duration`, whose minimum is 1 ns, so `inWholeNanoseconds ≥ 1` always. No finding.
- Saturation cannot mis-select for realistic animations (totals are ms–seconds; `Long.MAX` ns ≈ 292 years). Pathological max-duration models (256 × max `Duration`) saturate in Kotlin but fit in upstream `u128` — divergence only for absurd manual models, never manifest-derived (fps-capped) ones. See F-06 window.

## §8 Model invariants, immutability, facade, boundary, ABI, tests, builds

- Invariants: `AtlasGeometry` positivity + overflow guards (`Geometry.kt:27-39`), `frameCapacity: Long` (`43-44`), `sourceRectForOrNull` bounds + overflow-safe math (`51-58`), `PetFrame` index/duration guards (`PetModel.kt:38-43`), `PetAnimation` non-empty + loopStart-in-indices (`61-67`), `PetDefinition` frameCount>0 + `frameCount == capacity` + non-empty + contains-idle (`108-117`). Fallback-existence correctly left to validators. Foreign-parse paths cannot violate these for reachable inputs (F-10 documents the one-refactor-away risk).
- Immutability: snapshots verified (F-09 for cast caveat).
- Facade: `PetPackageParser.spritesheetPathOf` (A) + `parse(manifest, fallbackId, SpritesheetInfo)` (B) satisfy both Phase 2 needs without DTO access. DTOs (`CodexPetManifestDto`, `FrameSpecDto`, `AnimationSpecDto`) are `internal` in source and ABSENT from klib (`codex-pets-core.klib.api` 466 lines), JVM (`api/jvm`, 369 lines), and Android dumps. `CodexV1`, `effectiveSpritesheetPath`, `parseManifest`, `sourceRectFor` also absent. ✅
- Boundary: `commonMain` imports are ONLY `kotlinx.serialization{,.json}`, `kotlin.time.*`, `kotlin.jvm.JvmInline` (stdlib). `grep` for okio/ktor/coroutines/compose/android/Foundation/java.io/image-decoder finds only the word “decoder” in a KDoc comment (`PetModel.kt:13`). `commonMain.dependencies` is ONLY `libs.kotlinx.serialization.json`; `commonTest` adds ONLY `libs.kotlin.test`. ✅ (Transitives of serialization-json are unavoidable.)
- ABI: public surface is exactly the intended contract (`AtlasGeometry`, `IntRect`, `SpritesheetFormat/Info`, `PetFrame/Animation/Definition/Key/Animations`, errors/report, outcomes, `PetPackageParser`, `CodexCompatibilityValidator`, `normalizePetIdentity`, `samplePetAnimation`, `staticIdleSpriteIndex`). No DTO/internal/profile/helper/platform/mutable-impl leakage. JVM dumps expose `List/Map` interfaces, not impls. Apple risk flagged only (F-13).
- Tests: 82 exist (grep counts sum to 82; prior XML sums to 82). Prior run 82/82 pass on jvm AND iosSimulatorArm64 (stale). Current gates BLOCKED (F-01). Quality gaps in F-11.

### Build/test evidence

Prior (stale, pre-catalog-break) — `codex-pets-core/build/test-results/`:

- `jvmTest`: Custom 17, Default 5, Geometry 10, Identity 8, Manifest 15, Model 7, Playback 20 = 82, 0 failures/errors/skips (timestamp 2026-09-25T16:58:16Z).
- `iosSimulatorArm64Test`: same 82/82 (timestamp 2026-09-25T16:59:37Z).

Current (audit run, no suppression, no edits):

- `./gradlew :codex-pets-core:jvmTest --rerun-tasks` → BUILD FAILED in 595 ms, `InvalidUserDataException: Undefined version reference 'coil'` (configuration phase, 0 tests executed).
- `./gradlew :codex-pets-core:checkKotlinAbi` → same BUILD FAILED in 392 ms.
- `:codex-pets-core:iosSimulatorArm64Test`, `:codex-pets-core:assemble` not attempted separately — same configuration-phase failure is certain (catalog is evaluated for all tasks). Host DOES support iOS simulator (prior results prove it); Android assemble requires SDK (CI uses ubuntu + `assemble`, not run here beyond the shared config failure).

## Unverified claims (no Phase 1 report found; these would need a report to verify)

- Any claimed “82 tests prove parity” — rejected: 82 exist and previously passed, but F-02 is a live counterexample (a test pins the wrong fallback behavior), F-11 lists unpinned edges, and current tree cannot run them (F-01).
- Any claimed “exact fps parity” — rejected without F-06 qualifier (panic vs typed error).
- Any claimed “error parity” — rejected: multi-error (F-07) and taxonomy (F-03) differ.
- Any claimed “Phase 0 docs fully match upstream” — rejected: F-04 contradiction.
- No Apple-interop claim is made in-repo; none verified here beyond F-13 flag.

## Gate recommendation: REMEDIATION REQUIRED

- P0: F-01 blocks every gate; CI is red on the current tree.
- P1: F-02 is a genuine accept/reject divergence from the pinned upstream (`"fallback": "   "`).
- P2 load: F-03–F-13 must be addressed or explicitly accepted with doc/test updates (especially F-06/F-07 labeling, F-11 missing edges, F-10 totality guard).
- PASS or PASS WITH P2 is not available while a P0 + P1 are open.

### Minimal remediation checklist (no code changed in this audit)

1. Fix `gradle/libs.versions.toml` dangling `coil` / `androidx-lifecycle` refs (F-01); re-run all four CI legs green.
2. Restore exact fallback parity: `isEmpty()` without `trim()` (F-02); update `blankFallbackNormalizesToIdle` to assert `UnknownFallback` for `"   "`.
3. Document F-06 hardening + F-07 determinism as hardening in KDoc; add F-11b/c/d/e tests (NaN/Inf/smallest-normal fps, alias override, reverse forward-ref, exact large-elapsed goldens, ByteArray `spritesheetPathOf` invalid).
4. Record F-04 `custom:` contradiction as a Phase 2 decision item; optionally add `try/catch` totality guard (F-10) and Unicode-trim note (F-12).

---
*Audit method: full source read of all commonMain/commonTest, Gradle/ABI/CI/docs read, pinned upstream fetched at `55543d8` (model/ambient/catalog) and compared function-by-function, imports searched (not trusted from comments), prior test XML inspected, current gates executed without `|| true`, without disabled tests, without file edits.*
