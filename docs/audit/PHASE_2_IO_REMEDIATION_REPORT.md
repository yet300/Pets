# Phase 2 IO Remediation Report

- Baseline checkpoint: `c00169191f50bf69ea90ff8ac2b14648c6443ff7`
  (`feat(io): implement Codex Pets package loading` — KNOWN TO FAIL the gate).
- Phase 1 baseline untouched: `2818fa3577b0e31ffdc64a027f38056ab901960c`
  (`tag: phase-1-core`). No history rewritten, nothing amended, no rebase.
- Authority: `docs/audit/PHASE_2_IO_SECURITY_AUDIT.md` (read in full; the
  trailing verdicts §§10–12 were truncated in the filed document, but every
  finding F-01…F-16 and verdict §§1–9 was present and is addressed below).
- Normative upstream unchanged: `openai/codex @ 55543d8`.

## 1. Baseline checkpoint SHA

`c00169191f50bf69ea90ff8ac2b14648c6443ff7` (verified via `git rev-parse HEAD`
before editing).

## 2. Remediation commit SHA(s)

(Recorded after committing; see §34 flow.)

- `fix(io): close package and archive security gaps`
- `test(io): add adversarial package regressions`
- `docs: align IO security and public API contract`

## 3. Exact files changed

Production (`commonMain`): `PetLoadError.kt` (+`DanglingManifest`),
`PetLoader.kt` (String boundary via expect/actual FS), new
`AbortWith.kt`, `internal/fs/{DirectoryPetLoader,PathSecurity,PlatformFileSystem}.kt`,
`internal/zip/{ZipArchive,ZipEntryReader,ZipPaths,ZipDiscovery,ZipLoader,Crc32}.kt`,
`internal/image/{ImageProbe,ByteWindow,PngProbe,GifProbe,JpegProbe,WebpProbe}.kt`;
deleted flat `ImageProbe/ZipArchive/ZipLoader.kt` (superseded by split);
new `androidMain/jvmMain/iosMain` one-line FS actuals.
Core: `CompatibilityReport.kt` (format message order only).
Tests: `TestFixtures.kt` (correct-CRC PNG, GIF87a param, header-override
builder, raw-name-bytes), new `ManifestSecurityTest`, `ZipConsistencyTest`,
`LimitBoundariesTest`, `ZipFuzzTest`, `RealEncoderPngTest` (JVM);
`RealFilesystemSecurityTest` (+manifest matrix); `ImageProbeTest`,
`DirectoryLoadingTest`, `ZipLoadingTest` extended.
Docs: `ARCHITECTURE.md` (io wording + String-boundary contract),
`PUBLIC_API_PROPOSAL.md` (probe/outcome accuracy), new `SECURITY.md`, README
state section.

## 4. F-01 status — FIXED permanently

Manifest discovery routes every followed leaf through canonical confinement
(`locateManifest`: list + stat → canonicalize → target-existence verify →
segment-descendant check). Escaping manifest → `PathEscape`; dangling →
new `DanglingManifest` (never `MissingManifest`); unsafe preferred `pet.json`
fails the package without avatar fallback (documented rule + tests).
11 FakeFS + 5 real-FS permanent tests, all green.

## 5. F-02 status — FIXED permanently

Local headers fully parsed and cross-checked (filename bytes, method, flags,
CRC, both sizes); entry spans (`Long`) must precede the central directory and
pass O(n²) pairwise non-overlap; malicious-offset and true-overlap regressions
pinned (the latter asserts the overlap guard's message).

## 6. F-03 status — FIXED permanently

Explicit policy: reject `0x0001`/`0x0040` (encrypted) and `0x0008` (data
descriptor); accept-ignore `0x0800` (decoding is unconditionally UTF-8);
ignore all other bits (documented leniency). Tests for descriptor rejection,
UTF-8 acceptance, reserved-bit tolerance.

## 7. F-04 status — FIXED permanently

IHDR CRC32 verified over type + 13 payload bytes via the existing `Crc32`.
Fixtures carry correct CRCs (zero-CRC valid fixture removed); bad/truncated
CRC, bad size, zero dims rejected; real-encoder JVM PNG accepted and its
corrupted twin rejected.

## 8. F-05 status — FIXED

`updateKotlinAbi` run (never hand-edited); diff reviewed = exactly the
`AtlasGeometry.copy`/`copy$default` removals (GIF/JPEG already present);
`checkKotlinAbi` green.

## 9. P2 findings fixed

F-06 stored invariant; F-07 unconditional CRC (empty+CRC0 passes, empty+bad
fails); F-08 collisions over all kinds incl. file/dir; F-09 exact integer
ratio with boundary unit tests; F-10 limit-1/limit/limit+1 matrix for all 7
limits; F-11 lying-metadata bomb + true-overlap regressions; F-12 UTF-8-only
documented + substitution tests + CPython-zipfile golden committed as base64;
F-13 symlink-completeness note in KDoc/report; F-14 strict central-size
reconciliation + Long offsets; F-15 recorded keep (copy/componentN suit config
semantics); F-16 consumer-docs note (qualify shared runtime types).
Doc P1s: ARCHITECTURE Path contract corrected; proposal probe/outcome wording
corrected; core message lists JPEG/PNG/GIF/WEBP.

## 10. P2 findings deferred

None outstanding. (F-15/F-16 verdicts are "keep + document", done above.)

## 11. Restructuring performed

Per §§23–26 guidance (concepts, not line counts):
`internal.fs/{DirectoryPetLoader,PathSecurity}` (+expect/actual FS accessor —
required because `FileSystem.SYSTEM` is invisible to common metadata
compilation, found while running `assemble`);
`internal.zip/{ZipArchive (structure), ZipEntryReader, ZipPaths, ZipDiscovery,
Crc32}`; `internal.image/{ImageProbe dispatcher, ByteWindow, Png/Gif/Jpeg/Webp
probes}`. No Utils/Manager types; ABI verified free of all internals.

## 12. Documentation corrections

`ARCHITECTURE.md`: io wording (§1), String-boundary canonical contract (§19),
`custom:` decision item retained. `PUBLIC_API_PROPOSAL.md`: outcome class
semantics, internal-probe reality (§20). New `SECURITY.md` (traversal, symlinks,
bombs, scope, limits, private-advisory reporting via GitHub, no invented
email). README current-state section. Historical audits untouched.

## 13. Permanent regressions added

Manifest in-root/escape/dangling × {pet,avatar} × {FakeFS,realFS} (16),
precedence-under-attack (4), root-symlinked (1); local/central filename, size,
CRC, flags mismatches (4); malicious offset + true overlap (2); descriptor/
UTF-8/reserved bits (3); stored invariant (1); CRC empty ± (2); central-size
reconcile (1); file/dir + dir/dir collisions (2); integer-ratio units (1 test,
9 asserts); limit matrix 8 tests × 3 points; lying bomb (1); malformed names
(2); external golden (1); PNG CRC/size/zero/real-encoder (7); fuzz truncation
sweep + corruption sweep + probe sweep (3). ABI gate via existing CI legs.

## 14–16. Fresh counts

- Core JVM 100/100, core iOS-sim 100/100 (0 skipped, fresh XML).
- IO JVM **148**/148 (130 common incl. FakeFS + 18 JVM: 15 real-FS + 3 PNG).
- IO iOS-sim **130**/130.
- Real-FS suite: **15/15** (spritesheet link/escape/dangling ×2 suites worth +
  manifest link/escape/dangling/precedence/root-link + limits + handle).

## 17–18. ABI results

Core `checkKotlinAbi` green (copy-removal diff reviewed). IO
`checkKotlinAbi` green; klib dump reviewed (299 lines → now +DanglingManifest,
intentional additive): errors/outcome/limits/loader only; no ZIP/parser/CRC/
probe/PathSecurity/Okio/platform-exception leakage.

## 19. Swift result

Fresh release frameworks from the clean worktree; consumer re-run on booted
iPhone 17: **13/13 PASS** (String directory path, ZIP bytes, typed
Success/Failure incl. new errors, PetDefinition, custom keys, nanos, limits,
malformed-ZIP failure). Header re-audited: zero Okio declarations, no `id`
keys, no `NSDictionary`, no raw-Duration `int64_t`. Dual-framework notes:
qualify `KotlinByteArray`, companion Default via `.Companion.shared.Default`
(standard multi-KMP behavior, documented in §16 verdict lineage).

## 20. Remaining P0/P1/P2

P0/P1: none. P2: data-class `copy/componentN` on errors/limits (kept, F-15);
`description_` rename + boxed `loopStart` ergonomics (pre-existing);
FakeFS `allowSymlinks` defaults off (tests set it explicitly);
`NormalizationFailed` still defensive-unreachable (declared);
strict central-size equality could reject exotic-but-valid producers
(accepted risk, documented preference).

## 21. Final gate recommendation

**PASS (pending independent re-verification — do NOT tag `phase-2-io` yet).**
All P0/P1 fixed with permanent tests, P2 closed or recorded, full gate green,
Swift green, ABIs green and reviewed. No Phase 3 code exists.
