# Phase 2 IO Implementation Report — `:codex-pets-io`

- Scope: Phase 2 only. No networking/Compose/Desktop/authoring-QA code started.
- Phase 1 milestone: uncommitted verified tree (repo has single commit `2a6dc58`;
  Phase 1 was verified from the working tree per its audit trail). Only Phase 2
  core deltas: additive `SpritesheetFormat.GIF`/`JPEG` + format-gate/message
  update + 1 regression test. All 100 Phase 1 core tests remain green.
- Normative upstream: `openai/codex @ 55543d8` (`pets/model.rs` loader semantics;
  TUI `Cargo.toml` pins `image = [..., "jpeg", "png", "gif", "webp"]`).

## 1. Phase 1 milestone SHA used

No commit SHA exists (Phases 0–2 are uncommitted work). Behavioral dependency =
Phase 1 tree as verified by `PHASE_1_CORE_REMEDIATION_VERIFICATION.md` +
`PHASE_1_APPLE_REMEDIATION_VERIFICATION.md` (99 tests then; 100 now with the
format test).

## 2. Exact files changed

- `settings.gradle.kts` (+`:codex-pets-io`), `gradle/libs.versions.toml`
  (+okio 3.18.2, `okio`, `okio-fakefilesystem`; removed 2 dangling entries),
  `.github/workflows/gradle.yml` (+4 io CI legs).
- New `codex-pets-io/` module: `build.gradle.kts`, 8 `commonMain` sources,
  5 `commonTest` files, 1 `jvmTest` file, `api/` dumps (jvm/android/klib).
- Core: `PetModel.kt` (+GIF/JPEG), `PetParser.kt` (format gate), +1 core test.
- Docs: `ARCHITECTURE.md` (io wording fix + String-boundary note),
  `PUBLIC_API_PROPOSAL.md` (§4 String boundary amendment).

## 3. Module/dependency structure

`core <- io` only. io production deps: `:codex-pets-core` + `okio` (3.18.2);
tests add `okio-fakefilesystem` + `kotlin-test`. No coroutines/Ktor/Compose/Skia.
Targets JVM/Android/iosArm64/iosSimulatorArm64. `explicitApi()` + built-in
`abiValidation()`. Core gained no Okio (verified by import audit + dumps).

## 4. Public IO API

`PetLoader.loadPetDirectory(path: String, limits = Default)`,
`PetLoader.loadPetZip(bytes: ByteArray, fallbackId = "pet", limits = Default)`,
`PetLoadOutcome` (`Success(definition, spritesheetBytes)` content-equal /
`Failure(errors)` snapshotted), 15-case `PetLoadError`, total-ctor
`PetPackageLimits` + `Default` with load-time `InvalidLimits`. No `Result`,
no throws for package data, no decoded images, no Okio types publicly.

## 5. Okio version + why

3.18.2 (latest stable at implementation; verified on Maven Central). Used for:
`FileSystem`/`Path` (single filesystem model), `Buffer`/`Inflater(nowrap=true)`
(raw-deflate entry reads on all targets), `DeflaterSink` (test fixtures).
`java.util.zip` appears nowhere in `commonMain`; no temp files; no extraction.

## 6–7. Directory confinement + symlinks

Lexical (relative, no `..`, no absolute, no drive prefix, no backslash —
cross-platform hardening) then canonical: `canonicalize(root)` →
resolve → `canonicalize(target)` → strict segment-descendant check (rejects
`/pet/foobar`-under-`/pet/foo`). In-root links load; escaping links →
`PathEscape`; dangling → `DanglingSymlink` (leaf-in-parent-listing probe) else
`MissingSpritesheet`. TOCTOU documented as non-sandbox (validate → open/read
immediately, caps while streaming). `pet.json` wins over `avatar.json`
(upstream order); no descendant search; identity = canonical basename.

## 8. ZIP implementation design

Option B (minimal central-directory reader, §12-spike verified against Okio
3.18.2 sources: `Inflater(nowrap: Boolean)` exists in common; CRC32 has no Okio
common helper → 20-line local table). Stored + deflated only; single-disk;
ZIP64/multi-disk/unknown-method → `InvalidArchive`. Shapes: root XOR one nested
dir (pet.json preferred); else `AmbiguousPackage`/`MissingManifest`. Entries:
absolute/`..`/drive/empty → `InvalidArchive`; symlinks → `SymlinkEntry`;
encrypted → `EncryptedArchive`; exact + case-fold duplicates → `DuplicateEntry`.
Reads bounded by declared sizes AND actual stream caps with CRC + length checks.

## 9–10. Discovery + limits

Discovery per §8/§14 above; nested dirs deeper than one level are not
candidates. All 7 approved limits enforced with overflow-safe Long math; raw
archive cap precedes indexing; metadata totals AND actual reads both bounded;
100× tripwire via exact Double division on capped totals.

## 11. Pinned image-format evidence

TUI `Cargo.toml @ 55543d8`: `image = { workspace = true, features = ["jpeg",
"png", "gif", "webp"] }`. Format set = JPEG/PNG/GIF/WebP (deliberately not
current HEAD). Core correction: +`GIF`/`JPEG` enum entries (additive, message
updated, core regression test added). Nothing broader.

## 12. Metadata probe (no pixel decoding)

Common header-only parsers: PNG (sig + first-chunk IHDR), GIF (87a/89a + LSD),
JPEG (SOI → segment scan to SOF C0–C3/C5–C7/C9–CB/CD–CF, defensive lengths),
WebP (RIFF/WEBP + VP8 start-code/14-bit dims + VP8L 0x2F/bitfields + VP8X
flags/reserved/LE24 canvas — layout verified against a real animated sample at
300×225 ground truth). Total on foreign bytes; `null` → `UnsupportedImageFormat`.

## 13–14. Tests + real-FS results

commonTest 78 (FakeFS dirs, synthetic ZIPs incl. deflate/ratio/CRC/encryption/
symlink/duplicate/slip cases, probe incl. VP8/VP8L/VP8X + corruptions,
equivalence, limits, outcomes) + jvmTest 10 real-FS (in-root file/dir symlinks
allowed; file + parent-dir escapes rejected; dangling typed; foobar confusion
rejected; dotdot lexical; limits; SYSTEM handle). **JVM 88/88, iOS-sim 78/78,
all green.** Real-FS run on macOS/actual FS: 10/10 pass.

## 15–16. Build + core regression

`jvmTest`, `iosSimulatorArm64Test`, `assemble`, `checkKotlinAbi` green for both
modules in one invocation. Core: 100/100 JVM + iOS-sim, ABI check green after
intentional GIF/JPEG dump update.

## 17. IO ABI review

297-line klib dump: errors/outcome/limits/loader only. Zero internals
(ZipArchive/ZipEntry/ImageProbe/Crc32/AbortWith), zero Okio/FileSystem/Path,
zero Ktor/Compose/networking/authoring types.

## 18. Apple header + Swift smoke (fresh release frameworks, clean worktree)

No `id`/boxes/`NSDictionary` in io surface; `loadPetZip(bytes:fallbackId:limits:)`
+ `loadPetDirectory(path:limits:)` typed. External consumer
(`/tmp/pets-io-swift/consumer.swift`): ZIP load → Success with manifest id,
custom `dance` key enumerated/looked up/sampled with exact nanos,
bytes accessible; garbage → `InvalidArchive`; directory via `String` path;
`Default` limits readable; `/tmp` → `MissingManifest`. **13/13 PASS on booted
iPhone 17 simulator.** Notes: `KotlinByteArray` must be module-qualified when
linking both frameworks (standard multi-KMP behavior); companion Default via
`.Companion.shared.Default`. Preflight verdict recorded: String boundary kept,
no Path overload (would leak Okio decls into our header).

## 19. Intentional deviations/hardenings

Backslash rejection (cross-platform determinism); pet.json-over-avatar.json in
same dir (upstream order); deeper-than-one nesting ignored; case-fold
duplicates rejected (case-insensitive-host safety); ZIP64/multi-disk/odd
methods rejected (Phase 2 scope); unknown manifest fields ignored per core;
unit-bit JPEG SOF set; VP8X reserved bytes skipped not validated.

## 20. Remaining P0/P1/P2

P0/P1: none. P2: (a) `PetPackageLimits` data-class `copy/componentN` exported
(harmless config value); (b) FakeFS `allowSymlinks` must be enabled explicitly
in tests (flag default); (c) `description_` NSObject rename applies to io
models too (cosmetic); (d) ZIP bombs relying on lying central sizes are caught
at actual-read caps (by design, tested via ratio path).

## 21. Gate recommendation

**PASS.** Every acceptance condition holds by fresh evidence: core free of Okio,
directory + ByteArray-ZIP loading, no disk extraction, deterministic discovery,
lexical + canonical escape rejection, deterministic symlink policy
(in-root allowed, escapes/dangling typed; ZIP symlinks rejected), all 7 limits
enforced on metadata AND actual reads, malformed archives → typed failures,
pinned JPEG/PNG/GIF/WebP probing without pixel decoding, core semantic
authority preserved (equivalence-tested), natural Swift consumption proven,
clean ABI, 88 + 78 green, 100 core tests green, no Phase 3 code.
