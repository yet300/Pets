# Phase 2 IO Security Audit — `:codex-pets-io` (READ-ONLY, adversarial)

- Role: independent senior Kotlin Multiplatform / archive-security reviewer. Phase 2 was NOT
  implemented by this reviewer. No production code modified. Phase 3 NOT begun.
- Date (UTC): 2026-09-25 (evidence runs ~22:30–23:15 UTC).
- Method: full source read of every `codex-pets-io/src/commonMain|commonTest|jvmTest` file
  (never inferred from the implementation report), fresh Gradle gates from the actual checkout
  (no shadow copy, no `|| true`, no disabled tests, no stale XML as sole evidence), ephemeral
  adversarial scratch tests (created, run, then DELETED — repo contains none of them),
  live re-verification of normative upstream at the pinned SHA, real-image cross-checks with an
  independent decoder (Pillow), and a fresh-framework Swift smoke run on a booted simulator.
- Normative upstream (pinned, NOT current HEAD): `openai/codex`
  commit `55543d87724bb66bdd51bde65254feb9b4c9ed10`. Both `codex-rs/tui/Cargo.toml` and
  `codex-rs/tui/src/pets/model.rs` were re-fetched live at that SHA for this audit and now
  independently confirm: TUI `image` features `["jpeg", "png", "gif", "webp"]`; path-like
  loader prefers `pet.json` over `avatar.json` (`missing pet.json or avatar.json in <dir>`);
  manifest `spritesheetPath` must be a relative child (rejects absolute, `ParentDir`,
  drive `Prefix`) with NO canonicalization and NO symlink handling; dimensions come from
  `image::image_dimensions`; `custom:<id>` loader uses cache identity `custom-<id>` and
  ignores the manifest id on that path. The compatibility research doc's claims on these
  points are accurate.
- Upstream ` pet_dir` itself IS canonicalized once (`load_pet_path`), but the resolved
  spritesheet is only `pet_dir.join(path)` + existence check. Our canonical containment is a
  deliberate hardening over upstream — which makes Finding F-01 worse, not better: the
  hardening was applied to the asset while the manifest was left at (below) upstream behavior.

## 0. Final gate (read first)

**REMEDIATION REQUIRED** — one P0 (manifest-symlink confinement bypass, proven on the real
filesystem), three P1s (stale core ABI dumps with a false "green" report claim; missing
central/local header consistency with a proven arbitrary-range-read primitive; silent
data-descriptor acceptance; PNG IHDR CRC ignored with proven strict-decoder divergence),
plus P2 hardening/documentation items. Details: Findings F-01…F-16, verdicts §§1–12 below.

The Phase 2 implementation report (`PHASE_2_IO_IMPLEMENTATION_REPORT.md`) is NOT accepted as
evidence. It is additionally factually wrong in at least two load-bearing places: it claims
`:codex-pets-core:checkKotlinAbi` is green (it fails fresh — Finding F-05), and it claims a
"deterministic symlink policy (in-root allowed, escapes/dangling typed)" for the directory
loader (manifest symlinks escape unconfined and dangling manifest symlinks mistype — Finding F-01).

---

## Fresh build / test evidence (actual checkout, 2026-09-25 ~23:00 UTC)

All gates run from the real checkout with `--rerun-tasks` where counts matter:

| Gate | Result | Counts (fresh XML, 0 skipped everywhere) |
|---|---|---|
| `:codex-pets-io:jvmTest` | SUCCESS | **88 tests, 0 failures, 0 errors** (DirectoryLoading 24, ZipLoading 34, ImageProbe 12, LoaderEquivalence 3, OutcomeAndLimits 5, RealFilesystemSecurity 10) |
| `:codex-pets-io:iosSimulatorArm64Test` | SUCCESS | **78 tests, 0 failures, 0 errors** (same minus the 10 JVM real-FS tests) |
| `:codex-pets-io:assemble` | SUCCESS | includes release frameworks iosArm64 + iosSimulatorArm64 |
| `:codex-pets-io:checkKotlinAbi` | SUCCESS | jvm + android + klib dumps current |
| `:codex-pets-core:jvmTest` | SUCCESS | **100 tests, 0 failures, 0 errors** |
| `:codex-pets-core:iosSimulatorArm64Test` | SUCCESS | **100 tests, 0 failures, 0 errors** |
| `:codex-pets-core:checkKotlinAbi` | **FAILED** | 3 changed dumps (`AtlasGeometry.copy` stale) — Finding F-05 |
| Swift smoke (fresh release frameworks, booted iPhone 17 / iOS 27.0) | **13/13 PASS** | §11 |

Ephemeral adversarial scratch (run, recorded below, then deleted — NOT in the repo): 20 JVM
tests + 3 common tests on JVM and iosSimulatorArm64, plus the 13-check Swift consumer. Every
dynamic claim in this audit comes from those runs, quoted inline.

Repo state: single commit `2a6dc58 "Initial commit"`; **everything else is uncommitted**
(16 changed/tracked paths + untracked `codex-pets-core/src|api`, `codex-pets-io/`,
`docs/audit`, `docs/research`). There is no Phase 1 SHA and no Phase 2 SHA (§12).

---

## Findings

Severity: P0 = exploitable escape / broken security guarantee, must fix before any release.
P1 = real defect (compat deviation, parser trust issue, false report claim) that must be fixed
before 0.1.0 freeze. P2 = hardening, test-coverage, or documentation debt, acceptable to
schedule but recorded with direction.

### F-01 — P0 — Manifest symlinks escape package confinement; dangling manifest mistyped

- File + lines: `codex-pets-io/src/commonMain/kotlin/com/yet/pets/io/PetLoader.kt:86-100`
  (discovery via `existsQuietly` + unbounded `readBoundedFile`; manifest path never passes
  through `resolveContainedChild` in `PathSecurity.kt:99-114`).
- Expected: the package-confinement guarantee covers EVERY file followed through the
  filesystem. Per the report's own §6–7 policy, an escaping link must yield `PathEscape` and a
  dangling link `DanglingSymlink` — for the manifest exactly as for the spritesheet. Upstream
  follows manifest symlinks too, but upstream claims no confinement; this library does.
- Actual: `pet.json`/`avatar.json` are resolved as `canonicalRoot / "pet.json"` and read with
  NO canonicalization and NO descendant check. A `pet.json` symlink to a file OUTSIDE the
  package root is silently followed and its content becomes the package manifest
  (`Success`, attacker/host-file-controlled id, displayName, description, spritesheetPath).
  A dangling `pet.json` symlink yields `MissingManifest`, never `DanglingSymlink`.
- Reproduction (ephemeral scratch, FakeFileSystem AND real FS — both proven):
  - FakeFS (`allowSymlinks = true`): `/pkg/pet.json -> /outside/secret.json` (secret holds
    `{"id":"escaped"}`), valid `/pkg/spritesheet.webp` → `Success(id="escaped")`. Expected
    `Failure(PathEscape)`.
  - Real FS (macOS temp dirs, `java.nio` symlinks): `pet.json -> /tmp/.../outside.json`
    → `Success(id="real-escaped")`. Expected `Failure(PathEscape)`.
  - Avatar-only variant: `/pkg/avatar.json -> /outside/secret.json`, no `pet.json` →
    `Success(id="avatar-escaped")`. Same hole via the legacy name.
  - `pet.json` regular + `avatar.json -> outside`: loads via pet (correct, avatar never read).
  - Dangling `pet.json -> nowhere.json` (FakeFS and real FS): `Failure(MissingManifest)`,
    expected `DanglingSymlink`.
  - Root-itself-as-symlink works correctly (`Success` via canonicalized root) — the hole is
    specifically the manifest leaf, not the root.
- Remediation direction: run the discovered manifest path through the same
  canonicalize + `isStrictDescendant` enforcement as the spritesheet (reuse
  `resolveContainedChild` or a manifest-typed equivalent), mapping unresolvable leaves to
  `DanglingSymlink`/`MissingSpritesheet`-analogous manifest errors (today only
  `MissingManifest` exists — add the leaf check before falling back to `avatar.json`, and
  decide + document whether a dangling `pet.json` should mask a healthy `avatar.json`).
  Add permanent tests A–E for BOTH manifest names on FakeFS and real FS.

### F-02 — P1 — Central-directory entry can point at arbitrary archive ranges; local header not cross-checked

- File + lines: `codex-pets-io/src/commonMain/kotlin/com/yet/pets/io/ZipArchive.kt:212-230`
  (`readLocalDataOffset` checks ONLY local signature + method equality).
- Expected: a hostile central directory must not be able to redirect an entry's payload to an
  arbitrary archive range. At minimum the local header's filename must equal the central
  filename and size/flag fields must be consistent (or the archive rejected); overlapping data
  ranges between entries must be rejected unless there is an explicit safe reason.
- Actual: central `filename`, `CRC`, `compressedSize`, `uncompressedSize`, `extraLength`,
  `flags` are never compared with the local header (local sizes/flags/name are not even read).
  Proven by scratch: patching the local name `pet.json -> petXjson` (central untouched) still
  loads `Success` — the local header is located but its identity is ignored. Pointing the
  spritesheet entry's `localOffset` at the manifest's local header was attempted by the parser
  (no overlap guard fired) and failed closed ONLY via CRC mismatch (`InvalidArchive: CRC
  mismatch`) — i.e. the arbitrary-range read primitive exists and today is saved by downstream
  checks, not by the parser.
- Reproduction: build a valid 2-entry ZIP with the repo's test builder; flip one local-name
  byte; `PetLoader.loadPetZip` → `Success` (must be `InvalidArchive`).
- Remediation direction: compare local vs central filename (bytes), method (already done),
  and compressed/uncompressed sizes; reject on any divergence with `InvalidArchive`. Add an
  overlap check over `[dataOffset, dataOffset + compressedSize)` ranges for all entries
  (O(n²) at n ≤ 64 is trivial). Record the policy in `ZipArchive` KDoc.

### F-03 — P1 — Data-descriptor general-purpose bit silently accepted; flag policy incomplete

- File + lines: `ZipArchive.kt:29-30` (only `ZIP_FLAG_ENCRYPTED = 0x1` defined),
  `ZipArchive.kt:150,181` (flags read, only bit 0x1 tested), `ZipLoader.kt:54-60`
  (symlink/encrypted sweep, no descriptor sweep).
- Expected: unsupported shapes fail deterministically. Bit 3 (0x08, data descriptor) changes
  the meaning of local size/CRC fields to zero/placeholder; an implementation that never reads
  local sizes must reject the bit explicitly rather than accidentally tolerate it. Reserved
  bits and the UTF-8 bit deserve a documented accept/ignore/reject decision.
- Actual: entries with flag `0x08` on both manifest and sheet load `Success` (proven by
  scratch-built descriptor-flagged ZIP). Tolerance is accidental (local fields are never
  read), not designed.
- Reproduction: hand-built ZIP with local+central flags `0x08`, correct central sizes →
  `Success` (must be a typed rejection, e.g. `InvalidArchive("data descriptors …")`).
- Remediation direction: reject bit `0x08` in `readCentralDirectory`; document that bit
  `0x0800` (UTF-8) is accepted-and-ignored because decoding is unconditionally UTF-8 (§14),
  and that reserved bits are ignored (lenient, recorded). Add tests for `0x08`, UTF-8-bit,
  and reserved-bit entries.

### F-04 — P1 — PNG probe ignores IHDR CRC: accepts files strict decoders (and upstream) reject

- File + lines: `codex-pets-io/src/commonMain/kotlin/com/yet/pets/io/ImageProbe.kt:27-40`;
  test fixture bakes the leniency in: `TestFixtures.kt:101` (`be32(0) // CRC placeholder
  (probe does not verify it)`).
- Expected: the probe must agree with pinned upstream `image::image_dimensions` on
  accept/reject, or the deviation must be documented with its renderer consequence. CRC is
  the PNG integrity mechanism; the `image` crate validates chunk CRCs while dimensioning.
- Actual: any 4-byte IHDR CRC is accepted, including zeroed and corrupted values. Proven with
  a REAL Pillow-generated 1536×1872 PNG: flipping one IHDR CRC byte → probe still returns
  `SpritesheetInfo(1536, 1872, PNG)`, while Pillow (`Image.open().load()`) raises
  `UnidentifiedImageError`. Same class of gap for truncated-after-header and corrupt-tail
  inputs (probe: dims; Pillow: `OSError: image file is truncated`) — those two are
  header-probe-by-design and likely match `image_dimensions` (which is itself header-level),
  but the CRC case is a strictness deviation, not a design consequence.
- Compatibility impact: a bad-CRC PNG package LOADS here (`Success`) where upstream errors;
  the later renderer decode (Compose `ImageBitmap`) would then fail AFTER package loading —
  the failure moves from load time (typed) to render time (platform decoder error).
- Reproduction: Pillow PNG → corrupt bytes 29..33 (IHDR CRC) → `ImageProbe.probe` returns
  dims (must be `null` → `UnsupportedImageFormat`).
- Remediation direction: verify the IHDR CRC32 (12 bytes: type + data) with the existing
  `Crc32` — ~5 lines, zero new deps — or, if deliberately lenient, document that corrupt-CRC
  PNGs load but may fail at render, and add a renderer-side typed path for that case. Either
  way add a real-encoder PNG bad-CRC regression test (do not keep zero-CRC synthetics as the
  only PNG coverage).

### F-05 — P1 — `:codex-pets-core:checkKotlinAbi` fails fresh; report claims green

- Files: `codex-pets-core/api/codex-pets-core.klib.api:271`, `codex-pets-core/api/jvm/…:copy`,
  `codex-pets-core/api/android/…:copy` vs
  `codex-pets-core/src/commonMain/kotlin/com/yet/pets/core/Geometry.kt:20-21`
  (`@ConsistentCopyVisibility public data class AtlasGeometry internal constructor`).
- Expected: ABI dumps match the build; `checkKotlinAbi` green; report claims about gates true.
- Actual: fresh `:codex-pets-core:checkKotlinAbi` FAILS with 3 `<<<ABI has changed>>>` hunks —
  dumps still declare `AtlasGeometry.copy(...)` while the build (correctly, via
  `@ConsistentCopyVisibility` + internal constructor) omits it. Phase 2's "intentional
  GIF/JPEG dump update" (dumps do contain the new `GIF`/`JPEG` entries — verified) left this
  drift in place, and §15's "checkKotlinAbi green for both modules" is false on this checkout.
  (`:codex-pets-io:checkKotlinAbi` IS green — verified fresh.)
- Reproduction: `./gradlew :codex-pets-core:checkKotlinAbi` → BUILD FAILED.
- Remediation direction: run `:codex-pets-core:updateKotlinAbi`, review the diff (must be
  exactly the `copy`/`copy$default` removals plus the GIF/JPEG additions), re-run the gate.
  Do NOT hand-edit dumps. The audit deliberately did not regenerate them.

### F-06 — P2 — Stored entries ignore declared compressed size (`comp != uncomp` accepted)

- File + lines: `ZipArchive.kt:330-336` (`readStored` uses only `uncompressedSize`).
- Expected: method 0 requires `compressedSize == uncompressedSize` per spec; violations are
  malformed shapes and should fail deterministically.
- Actual: proven — patching central `compSize` to 1 while keeping `uncompSize` real still
  loads `Success`. Impact is low (reads stay bounded by `uncompressedSize` + caps), but the
  parser is needlessly lenient about a cheaply-checked invariant.
- Remediation: reject `method == 0 && compSize != uncompSize` during central parsing.

### F-07 — P2 — CRC verification skipped for zero-length entries

- File + lines: `ZipArchive.kt:322` (`if (entry.uncompressedSize > 0 && Crc32.of(output) …)`).
- Expected: declared CRC is always verified, or the exception is documented.
- Actual: proven — an `empty.bin` entry with central CRC patched to `0xDEADBEEF` still loads
  `Success`. Zero impact on manifest/sheet integrity (their CRCs are checked whenever
  non-empty), but a spec deviation that also weakens the "every actual read is CRC-verified"
  KDoc claim (`ZipArchive.kt:18-19`).
- Remediation: always verify (CRC of empty input is 0 — the check is free), or narrow the
  KDoc wording.

### F-08 — P2 — File-vs-directory normalized collisions not detected

- File + lines: `ZipLoader.kt:52,61-72` (directories filtered BEFORE duplicate detection).
- Expected: deterministic collision model over ALL entries; no two entries normalize to one
  path regardless of kind.
- Actual: `pet.json/` (directory) and `pet.json` (file) coexist silently — the directory is
  dropped by `filter { !it.isDirectory }` before `byPath`/`seenFolded` run. Downstream this
  fails closed (`MissingManifest` when only the dir exists; dir ignored when both exist), so
  no traversal/bypass, but the "duplicate/conflicting entries" rule (§5.3 ARCHITECTURE) is
  only enforced for files.
- Remediation: run duplicate/case-fold detection over all entries (including directories)
  before filtering, or document that directory entries are invisible to the collision model.

### F-09 — P2 — Compression-ratio boundary uses Double division; prefer integer comparison

- File + lines: `ZipArchive.kt:266-285` (`uncompressedTotal.toDouble() / compressedTotal.toDouble() > max`).
- Expected: boundary `uncompressed ≤ 100 × compressed` decided exactly; no NaN/Infinity/
  division-by-zero path.
- Actual: zero-cases ARE handled (`uncomp == 0` → pass; `comp == 0, uncomp > 0` → reject — no
  div-by-zero, verified by reading + ratio tests). Totals are capped ≤ 32 MiB so Double
  division is *nearly* exact, but values within ~1 ulp of exactly 100× can round either way.
  Scratch-measured threshold on a 64 KiB-zeros fixture: last-fail `358.6939890111739` vs
  first-pass `358.69398987853566` — boundary behaves monotonically, but exactness at 100×
  rests on floating-point rounding, not arithmetic.
- Remediation: compare in Long: `uncompressedTotal > maxRatioNumerator × compressedTotal`
  with the ratio expressed exactly (100/1 today; keep Double in the public config but convert
  once, or store the ratio as exact rational). Hard caps dominate anyway, so this is hygiene.

### F-10 — P2 — Off-by-one boundary tests missing for most limits (implementation verified correct)

- Files: `DirectoryLoadingTest.kt:173-194`, `ZipLoadingTest.kt:272-370`,
  `EquivalenceAndLimitsTest.kt:88-98`.
- Expected: every approved limit pinned at `limit-1 / limit / limit+1` with inclusive-maxima
  semantics (`≤ limit` accepted, `> limit` rejected).
- Actual: implementation IS inclusive-correct — proven dynamically by this audit:
  manifest 65535/65536 → `Success`, 65537 → `LimitExceeded(manifestBytes)`; entry counts
  63/64 → `Success`, 65 → `LimitExceeded(zipEntries)`; spritesheet exactly-at-cap →
  `Success`, cap-1 → `LimitExceeded`; ratio threshold monotonic with exact-boundary pass.
  But the repo's own tests only pin the reject side (70 KiB manifest, 8 MiB+1 sheet, 65
  entries) and never the accept-at-limit side. A future refactor could shift a boundary by
  one with no test turning red.
- Remediation: add `limit-1/limit/limit+1` cases for manifest, spritesheet, raw archive,
  entry count, per-entry, uncompressed total, and an exact-100× ratio case (via computed
  totals + custom `maxCompressionRatio`, as demonstrated in scratch).

### F-11 — P2 — No lying-metadata zip-bomb regression test (design holds, proof missing)

- Files: `ZipArchive.kt:338-367` (actual-read caps), `ZipLoadingTest.kt:294-309,327-341`.
- Expected: an entry declaring small `uncompressedSize` but deflating large must abort at the
  actual-read cap; archive bounds must not be bypassable via overlapping ranges/offsets.
- Actual: design holds by reading — `readDeflated` aborts mid-stream (`total > cap`), output
  length is re-checked against the declaration, CRC re-checked, and unselected entries are
  never inflated at all (so lying metadata on ignored payloads cannot detonate). Existing
  tests cover honest-bomb metadata (40 MiB declared, ~1000× ratio) and corrupt payloads, but
  NOT declared-small/actual-large for a SELECTED entry, nor overlapping-range selection.
- Remediation: add a test that patches central `uncompressedSize` small on a deflated sheet
  (actual output large) and asserts `LimitExceeded`/`InvalidArchive` — never `Success` with
  partial bytes. Add an overlapping-range test asserting rejection once F-02's guard lands.

### F-12 — P2 — Filename encoding scope undocumented; no external-encoder ZIP fixture

- Files: `ZipArchive.kt:170-175` + comment `172-174`, `TestFixtures.kt:234-300` (repo's own
  writer feeds the repo's own reader).
- Expected: UTF-8-only support stated; malformed bytes fail deterministically (no
  replacement-collision surprises); CRC proven against an external implementation, not just
  self-agreement.
- Actual: behavior is SAFE and now cross-platform verified — JVM AND iosSimulatorArm64 both
  decode `FF FE` to `[U+FFFD, U+FFFD]` (substitution, no throw), so the code comment is
  accurate on both tested targets; traversal smuggling via malformed bytes is impossible
  (U+FFFD never equals `.`, `/`, or any ASCII byte of `pet.json`); substitution collisions
  can only produce `DuplicateEntry` (fail closed). Gaps: (a) CP437 legacy names are implicitly
  rejected/unsupported but nowhere documented; (b) no committed test pins substitution-vs-throw
  on each target; (c) CRC correctness rests on the `"123456789"` vector (good) but every ZIP
  test uses repo-computed CRCs — no external-encoder archive exists in the suite. (Mitigating
  evidence from THIS audit: a Python-`zipfile`-built archive loaded `Success` on the iOS
  simulator Swift run — external interop holds at least for the happy path — but that
  fixture is not committed.)
- Remediation: document "names are UTF-8; anything else fails closed (MissingManifest /
  DuplicateEntry / InvalidArchive), CP437 unsupported"; pin the substitution behavior per
  target; commit one golden ZIP produced by an external implementation (e.g. Python `zipfile`
  or `java.util.zip`) with independently computed CRCs.

### F-13 — P2 — ZIP symlink detection blind for non-Unix creators; impact statement needed

- File + lines: `ZipArchive.kt:179-180` (`isUnix = (versionMadeBy ushr 8) == 3`; symlink iff
  Unix + `0xA000` mode bits); `ZipLoader.kt:54-56` (reject `SymlinkEntry`).
- Expected: every representable symlink rejected, or the limitation accurately documented
  with its true security impact.
- Actual: Windows-created (`madeBy` 0) or forged entries never flag `isSymlink`, even with
  symlink-like attribute bits; conversely any Unix entry with forged `0xA000` bits is rejected
  (fail-closed DoS only). Because archives are NEVER extracted or materialized, even an
  undetected symlink entry is inert — its target-path bytes would be fed to the manifest
  parser/image probe and fail closed. The report's "ZIP is never extracted" framing is
  correct; the residual gap is detection completeness, not a traversal hole.
- Remediation: document "symlink rejection is hygiene/defense-in-depth; confinement for ZIPs
  comes from never touching a real filesystem; non-Unix symlink representations are treated
  as regular files and fail closed at parse/probe". Consider also flagging the bare
  `0xA000`-without-Unix case.

### F-14 — P2 — EOCD `centralSize` never reconciled with walked entries; minor arithmetic hygiene

- File + lines: `ZipArchive.kt:132-135` (offset/size sanity vs `size`), `138-210` (walk from
  `centralOffset` without ever comparing consumed bytes to `centralSize`).
- Expected: `centralSize`/`centralOffset` fully constrain the directory: entries must lie
  within `[centralOffset, centralOffset + centralSize)` and consume it consistently.
- Actual: `cdOffset/cdSize` are range-checked against the archive size, each entry's
  signature is checked, and `ZipArchive.parse` caps `entryCount` — but a `centralSize` of 0
  with 2 valid entries parses fine, and central bytes past the last entry (before EOCD) are
  ignored. All outcomes are fail-closed via per-entry signature checks; the gap is strictness,
  plus one theoretical nit: `at + 30 + nameLength + extraLength` (`:225`) and the central
  stride (`:206`) use Int arithmetic that could wrap only for archives near 2 GiB — unreachable
  under the 16 MiB default raw cap, still fail-closed via `bounds()` even with insane custom
  limits (Kotlin arrays are bounds-checked; worst case is `InvalidArchive`, never memory
  unsafety).
- Remediation: assert the walk stays within `centralOffset + centralSize` and ends exactly
  there (or document deliberate tolerance); use Long for the two offset computations.

### F-15 — P2 — `PetPackageLimits` data-class `copy/componentN` in ABI (acceptable, record)

- Files: `PetPackageLimits.kt:15-30`; ABI dumps (`codex-pets-io.klib.api:276-283`,
  jvm/android dumps: `component1-7`, `copy`, `copy$default`).
- Expected (§31): decide before 0.1.0 freeze whether destructuring/copy surface is wanted.
- Actual: `copy`/`componentN` ARE exported. Verdict: P2, keep. Rationale: value semantics
  (equals/hashCode/toString) suit an immutable config; `copy` is genuinely used (repo tests
  derive tight budgets via `Default.copy(...)`); Swift/Java impact is additive surface noise,
  not instability (no `Any`, no opaque types — §11 confirms clean consumption). Revisit only
  if the 0.1.0 API review wants a `PetAnimationKey`-style plain class for uniformity.

### F-16 — P2 — Swift dual-framework type duplication (standard multi-KMP, document)

- Observed in §11 smoke run: linking `CodexPetsCore` + `CodexPetsIo` exposes core types twice
  (`PetAnimations.shared.Idle: PetAnimationKey` vs io's `Codex_pets_corePetAnimationKey`) and
  `KotlinByteArray` must be module-qualified (`CodexPetsIo.KotlinByteArray`) — the same note
  the Phase 2 report makes. Compilation failed until the consumer qualified both; runtime
  then 13/13 green.
- This is standard multi-KMP framework behavior, not a defect. Remediation: one paragraph in
  consumer docs ("link both frameworks; qualify shared runtime types; prefer the io
  framework's view of core types at load boundaries") so app developers do not rediscover it.

---

## Verdicts required by the brief

### 1. Fresh build/test evidence — recorded at top

`jvmTest`/`iosSimulatorArm64Test`/`assemble`/`checkKotlinAbi` green for io (88+78, 0 failures);
core tests green (100+100) with `checkKotlinAbi` RED (F-05). Swift 13/13 on fresh frameworks.
No shadow copies, no `|| true`, no disabled tests, counts from fresh XML.

### 2. Directory confinement verdict — PASS for spritesheet, P0-FAIL for manifest

Lexical policy (`PathSecurity.kt:15-39`: relative-only, no `..`, no absolute, no drive prefix,
no backslash) + canonical `resolveContainedChild` (`:99-114`) + segment-aware
`isStrictDescendant` (`:77-82`, proven: `/root/pet2/…` NOT descendant of `/root/pet`, self not
descendant, child is) + TOCTOU honestly documented as non-sandbox (`:94-97`) all hold for the
SPRITESHEET: root-symlink, nested child, `.`, `//` (Okio-normalized, loads), `..`, absolute,
drive, backslash/UNC inputs, dangling targets, symlinked parents, in-root links (load),
escaping links (`PathEscape`) are all correctly handled and covered by FakeFS + real-FS tests.
BUT the manifest path bypasses all of it (F-01 P0): spritesheet confinement is real, package
confinement is not.

### 3. Manifest-symlink verdict — FAIL (P0, F-01)

A: regular file → `Success` (correct). B: symlink inside root → `Success` (acceptable outcome,
wrong reason — no check ran). C: symlink outside root → **`Success` reading outside content**
(FakeFS and real FS). D: dangling → `MissingManifest` (must be `DanglingSymlink`). E: parent
dir in resolution — root symlink handled; manifest-leaf parent escapes unchecked (same hole).
`avatar.json` affected identically. The report's "deterministic symlink policy" describes the
spritesheet only.

### 4. ZIP structural-parser verdict — SOLID CORE, P1/P2 GAPS (F-02, F-03, F-14)

EOCD handling is genuinely good: 22-byte minimum, comment-window search with exact
`at + 22 + commentLength == size` match (fake signatures in comments/data correctly skipped by
continuing the backward scan), 65535 max comment, truncation → `InvalidArchive`, impossible
offset/size (`cdOffset > size || cdSize > size || cdOffset > size - cdSize`, overflow-safe
Long comparisons), multi-disk rejected (all three disk fields + per-entry `diskStart`),
ZIP64 sentinels rejected (`0xFFFF`/`0xFFFFFFFF`), raw cap before indexing, entry-count cap
before walking, per-entry signature re-check. Gaps: central/local consistency unchecked (P1,
F-02), descriptor bit accepted (P1, F-03), `centralSize` unreconciled (P2, F-14). Unsupported
shapes otherwise fail deterministically; no Int/Long wrap is reachable under default caps
(and is fail-closed regardless).

### 5. ZIP path/collision verdict — PASS with P2 edge (F-08, F-12)

`normalizeZipEntryPath` fails closed on `../pet.json`, `a/../../pet.json`, `/pet.json`,
`C:/pet.json`, `C:\pet.json` (literal backslash segment — deterministic, never a separator),
`\\server\share`, `./pet.json`, `a/./pet.json`, `a//pet.json`, empty names, drive prefixes;
`pet.json/` normalizes (directory, then excluded from file discovery → `MissingManifest`,
fail-closed). Case-fold (`A/pet.json` vs `a/PET.JSON`) AND exact duplicates rejected BEFORE
discovery — no first-wins/last-wins (verified in code order `ZipLoader.kt:61-72` vs `:74+`).
`lowercase()` is locale-independent (deterministic across hosts). NUL/control bytes are
preserved but can only mismatch lookups (fail closed). Edge: file-vs-directory collisions
invisible to the model (P2, F-08).

### 6. Decompression/CRC verdict — PASS with P2 notes (F-06, F-07)

Stored + deflate only; unknown/encrypted methods → typed failures; no platform fallback (Okio
common `Inflater(true)` everywhere). Deflate: legitimate end-of-stream required, output length
re-checked against declaration, CRC re-checked, truncation → `InvalidArchive`, streaming cap
aborts mid-inflation (`total > cap` per 8 KiB chunk), no partial manifest/image can escape
(errors throw before `finishLoad`). CRC32 independently correct (init `0xFFFFFFFF`, poly
`0xEDB88320`, final XOR, unsigned masking; vectors `"" → 0`, `"123456789" → 0xCBF43926`
pinned in-repo). Notes: stored `compSize` ignored (P2, F-06); empty-entry CRC skipped (P2, F-07);
external-encoder fixture absent from repo tests (P2, F-12 — mitigated by this audit's
Python-built ZIP loading on-device).

### 7. Resource-limit verdict — PASS (implementation inclusive-correct; tests need boundaries)

All seven limits enforced on metadata AND actual reads with overflow-safe Long math
(`addCapped`); raw cap precedes indexing; manifest/sheet reads double-bounded
(`min(limit, maxEntryUncompressedBytes)`); inclusive maxima verified dynamically
(manifest 65535/65536 pass, 65537 fails; entries 64 pass, 65 fail; sheet at-cap passes).
`InvalidLimits` total-constructor + load-time validation (NaN/negative/zero-ratio rejected).
Test gap: boundaries pinned only on the reject side (P2, F-10).

### 8. Zip-bomb verdict — PASS BY DESIGN (add regression test, P2 F-11)

Totals cover the WHOLE archive (irrelevant/dir/duplicate/unselected entries all counted
during indexing — a tiny valid pet + massive ignored payload still trips
`uncompressedArchiveBytes`, proven by existing test); unselected entries are never inflated
(lying metadata there cannot detonate); selected entries are inflated under independent
stream caps with length + CRC re-verification (lying metadata there aborts). Overlapping
ranges cannot bypass archive-level bounds (raw cap + totals + per-read caps compose), though
explicit overlap rejection is still recommended (F-02). Ratio tripwire has no div-by-zero
and behaves monotonically at the boundary (scratch-measured); prefer integer comparison
(P2, F-09).

### 9. Image probe / upstream parity verdict — PARITY EXCEPT PNG CRC (P1, F-04)

Pinned SHA confirmed live: `image = { …, features = ["jpeg", "png", "gif", "webp"] }`; core
delta is minimal and exact (enum `GIF`/`JPEG` + gate + message + 1 test — `PetModel.kt:10-16`,
`PetParser.kt:118-121,319-322`). Real-encoder agreement 6/6: Pillow PNG/JPEG/GIF87a/VP8/VP8L/
animated-VP8X all probe to exact dims and `WEBP` for all three WebP subtypes (VP8X canvas
found first — correct for animated files). JPEG SOF set correctly includes C0–C3/C5–C7/C9–CB/
CD–CF and excludes DHT/JPG/DAC; both scanners guarantee progress (JPEG `+1`/`+length≥2` with
512-iteration cap; WebP `+8+size` with 64-iteration cap; odd-chunk padding handled; RIFF-size
truncation rejected). GIF87a covered (Pillow emits 87a — the repo's synthetics only use 89a;
add an 87a case). Deviations: PNG IHDR CRC ignored (P1 — loads where upstream/strict decoders
fail; renderer would fail post-load); header-only acceptance of truncated/corrupt-tail inputs
(design-conformant, `image_dimensions` is header-level too — acceptable, but document that a
successful probe does not imply renderability); PNG 100 000px declar
...[truncated 3081 chars]