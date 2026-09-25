# Architecture

Phase 0 architecture for the Kotlin Multiplatform Codex-pets library.
Normative compatibility input: `docs/research/CODEX_COMPATIBILITY.md`.
No production code is written in this phase.

> **Phase 1 Apple Interop Amendment.** Empirical Apple-interop results changed
> the public representation (not the upstream contract, which is unchanged):
> public `kotlin.time.Duration` is forbidden (it exports its packed `rawValue`,
> not nanoseconds) — public time is explicitly named nanos `Long`;
> value-class `PetAnimationKey` is a regular class; the public animation `Map`
> is an interop-safe `animationKeys`/`animation()` lookup; invariant-heavy
> constructors are internal so no natural Swift path can SIGABRT.
> `kotlin.time.Duration` remains usable for internal computation only.

## 1. Guiding invariants (acceptance-gate derived)

1. Core is pure Kotlin: no Compose, no `ImageBitmap`, no Ktor, no desktop APIs,
   no Android `Context`, no AWT. No decoded platform images in core domain models.
   Core dependencies: Kotlin stdlib + `kotlinx-serialization-json` only — no
   coroutines (there are no loaders in core).
2. Frames are metadata over a single atlas (sprite index + nanos duration +
   loop/fallback); geometry has one source of truth
   (`AtlasGeometry.sourceRectForOrNull`), never a stored `sourceRect` beside
   `spriteIndex`, never eagerly sliced `ImageBitmap`s.
3. Animation names are open: `PetAnimationKey(value: String)` with typed constants
   for known Codex states. No speculative V2/look-direction types in v1 public API.
4. Networking is optional and isolated (`core <- io <- network`); consumers that
   do not load from URL never see Ktor.
5. Exactly one filesystem abstraction across all targets (see §4).
6. Timing is per-frame nanoseconds as explicitly named `Long` values (CLI
   `model.rs` model, nanosecond playback math), never integer-millisecond fields
   and never public `kotlin.time.Duration` in normalized playback.
7. Runtime compatibility (CLI machine rules) and authoring QA (hatch-pet
   recommendations) are separate validation layers; authoring quality can never
   fail normal package loading.

## 2. Conceptual pipeline

```text
Raw package (dir | zip | url-zip)
  -> Codex manifest parser          (core: JSON -> raw manifest model, unknown-field tolerant)
  -> compatibility profile          (core, internal: CLI V1 profile; general seam, no invented behavior)
  -> normalized Pet definition      (core: PetDefinition, pure data + geometry, no diagnostics)
  -> asset loader                   (io: confinement incl. symlinks, limits, spritesheet info probe)
  -> platform image decoder         (compose/desktop: ONE atlas ImageBitmap)
  -> renderer                       (compose: drawImage subregions; nanos-based timing in common code)
```

The timing/loop/fallback player (equivalent of CLI `ambient.rs`
`current_animation_frame` + `loop_start` semantics) lives in **core** as the pure
`samplePetAnimation(definition, requested, elapsedNanos: Long): PetPlaybackSample`,
operating only on metadata. Fallback is AT MOST ONE hop evaluated with the same
animation-start clock (CLI parity, see PUBLIC_API_PROPOSAL.md §2.1); scheduling
(`nextFrameInNanos`) is our deterministic improvement (§2.2). Diagnostics live in
`PetCompatibilityReport` / `PetAuthoringReport` / `PetLoadOutcome`, never in
`PetDefinition`. Platform layers map the resulting sprite index to a source
rectangle via `AtlasGeometry.sourceRectForOrNull`.

## 3. Module graph (recommended: accept the proposed layout)

```text
:codex-pets-core ← :codex-pets-io ← :codex-pets-network   (Ktor lives ONLY in network)
:codex-pets-core ← :codex-pets-compose ← :codex-pets-desktop
:sample → all
```

The proposed layout is justified with one correction (network over io):

- `core`: pure Kotlin, zero platform/UI/network deps. Dependencies allowed:
  Kotlin stdlib + `kotlinx-serialization-json` (manifest parsing) and nothing
  else unless implementation proves necessity. No coroutines — no loaders in core.
- `io`: filesystem + ZIP package loading and runtime package-validation
  enforcement point: it establishes filesystem/archive facts (discovery,
  confinement, limits, image/package facts) and supplies them to core, which
  remains the semantic compatibility authority. Depends on core + the single
  filesystem lib (§4). No Ktor. Authoring pixel QA is deferred tooling and is
  NOT part of io.
- `network`: pet-oriented URL loading with exactly one v1 semantic — HTTPS URL
  → ZIP pet package → bounded download → io ZIP parser (`loadPetZipFromUrl`).
  Depends on **io** (and transitively core), so consumers that do not load from
  URL skip both Ktor and its engines. This is remote custom packages, never
  OpenAI's built-in spritesheet CDN (out of scope, §6).
- `compose`: renderer + state holders. Depends on core only (plus Compose).
  Holds exactly one decoded atlas `ImageBitmap` per pet and draws source regions.
- `desktop`: JVM/Desktop window/overlay helpers (placement, drag, always-on-top
  behavior). Depends on compose (+ core transitively). Starts thin; grows only
  with proven need.
- `sample`: demo apps (android + desktop at minimum). May depend on all.

Dependency direction `core <- io <- network`, `core <- compose`,
`compose <- desktop` is enforced; `sample` is the only module allowed to span
layers. Alternative considered (merging `desktop` into `compose`): rejected —
window management APIs are JVM/Desktop-only and would pollute the shared Compose
API surface and iOS/Android consumers. Alternative considered (network beside io,
`core <- network`): rejected — the v1 URL semantic *is* "download a ZIP and run
it through the io parser", so depending on io removes a duplicated ZIP path and
keeps one enforcement point for limits and validation.

## 4. Filesystem abstraction: exactly one — Okio

Comparison of realistic modern-KMP options:

| Option | Pros | Cons |
|---|---|---|
| **Okio 3.x (`okio.FileSystem`, `okio.Path`) — RECOMMENDED** | Mature multiplatform FS (JVM, Android, all Apple targets, Linux/macOS/Windows native, JS/Wasm); single `Path` type (satisfies "no unspecified Path"); `FakeFileSystem` for hermetic tests; `ZipFileSystem`/streaming zip support; Square stability policy | Extra dependency (justified: it IS the IO layer) |
| kotlinx-io `kotlinx.io.files` | JetBrains, small | Filesystem API experimental/limited per target at cataloged 0.9.1; weaker test fakes; zip story DIY |
| `java.io.File` / `NSURL` expect/actual | zero deps | one abstraction per platform to design, test, and keep consistent — exactly the cost this decision must avoid |

Decision: **Okio** in `:codex-pets-io` only. Core takes only `ByteArray`s,
`String`s, and plain `SpritesheetInfo(width, height, format)` data values so it
never touches files or decoders (this also makes core unit-testable in
`commonTest` without image codecs). Ownership is one-directional: **io owns the
`SpritesheetInfoProbe` abstraction, produces `SpritesheetInfo` facts from bytes,
and passes those pure facts into core validation — core never depends on the
probe itself.** No `java.io`, no `PlatformContext`, no raw `String`-path
plumbing in public APIs — public io APIs accept/return `okio.Path`.

## 5. Package loading and ZIP design (io module)

### 5.1 Accepted inputs

- Directory containing `pet.json` (preferred) or legacy `avatar.json` (CLI parity).
- ZIP archive resolving to exactly one package (see §5.3).
- Raw bytes are supported only as `(manifestBytes, spritesheetBytes)` pairs or a
  ZIP blob — never a bare manifest without its asset, so "asset exists" stays
  invariant like the CLI's.

### 5.2 Path confinement (mirrors CLI `resolve_spritesheet_path`, hardened for symlinks)

Lexical checks alone are insufficient when the filesystem contains symlinks, so
directory-backed packages use canonicalization, not just normalization:

- `spritesheetPath` must be a non-empty relative child path: reject absolute
  paths, any `..` segment, and drive prefixes lexically (CLI parity, fail fast).
- Canonicalize the package root once (`root.canonicalize()`).
- Resolve the requested child against the canonical root, then canonicalize the
  resolved target (which resolves symlinks to their final location).
- Verify the canonical target remains under the canonical package root;
  otherwise `PetLoadError.PathEscape`.
- Deterministic symlink policy: a symlinked spritesheet whose canonical target
  stays inside the package root loads normally; any symlink (file or parent dir)
  escaping the root is rejected as `PathEscape`; dangling symlinks are rejected
  as missing-asset errors. Rationale: permit benign in-package links, block
  escape-by-link. This is stricter than the CLI (which has no canonicalization)
  and is recorded as a deliberate hardening, not CLI parity.
- Missing `pet.json`/`avatar.json`, missing spritesheet file → deterministic
  typed errors (not nulls, not silent omission — unlike Desktop's reported
  silent-skip, our loader always reports).

### 5.3 ZIP layout rule (deterministic, ambiguous → reject)

Accept exactly these two shapes, else `PetLoadError.AmbiguousPackage`:

1. `pet.json` (or `avatar.json`) at archive root (+ asset entries).
2. Exactly one top-level directory `<name>/` containing `pet.json` (or
   `avatar.json`) — with no competing root manifest.

Reject: zero manifests, root + nested manifests, two nested candidate dirs,
duplicate/conflicting entries for the same normalized path, symlink entries
(ZIP is never extracted to disk, so links have no safe interpretation —
reject unconditionally, unlike the in-package-tolerant directory policy),
malformed archives (bad CRC/structure), encryption (unsupported).

Bound the raw archive size BEFORE opening/indexing it (reject over-limit blobs
up front), then enforce entry-count and expanded-size limits during streaming
reads. Never extract the archive to disk; read only the manifest + spritesheet
entries into bounded buffers.

### 5.4 Resource limits (defined, not unlimited)

| Limit | Value | Rationale |
|---|---|---|
| Manifest size | 64 KiB | CLI has no cap; manifests are small JSON — fail fast on garbage |
| Spritesheet file size | 8 MiB | 2x CLI download cap (4 MiB) to allow local lossless WebP/PNG headroom |
| Decoded atlas dimensions | must equal the CLI V1 profile: exactly 1536x1872 | exact-match like CLI; future profiles extend this table only with first-party sources |
| ZIP entry count | ≤ 64 | packages hold ~2 files; 64 leaves margin, stops zip-of-death enumeration |
| ZIP total compressed | ≤ 16 MiB | 2x spritesheet cap |
| ZIP total uncompressed | ≤ 32 MiB | 4x spritesheet cap; bomb ratio guard |
| Per-entry uncompressed | ≤ 32 MiB | single-entry bomb guard |
| Compression ratio tripwire | fail if uncompressed > 100x compressed for the archive | classic zip-bomb signal |
| Frame count | ≤ 256 | CLI `MAX_PET_FRAMES` parity |
| Animation fps | finite, 0 < fps ≤ 60 | CLI `MAX_ANIMATION_FPS` parity |
| Manifest `frame` grid | exact cover of decoded dims, all values non-zero | CLI parity |

Streaming discipline: enforce caps while reading (running totals, abort early);
bound raw archive size before indexing; never extract the archive to disk; read
only the manifest + spritesheet entries into bounded buffers. Unknown manifest
fields are ignored (CLI serde parity) with no unknown-key tracking. These
runtime rules are enforced by `CodexCompatibilityValidator` and gate loading.

### 5.5 Two validation layers (mandatory separation)

Runtime compatibility and authoring QA are different contracts enforced at
different points:

- `CodexCompatibilityValidator` (pure core): validates only pure compatibility
  facts — manifest semantic normalization, frame/grid geometry, supplied
  spritesheet dimension/format facts, frame count, animation frame indices, FPS
  range, loop model, fallback references, normalized definition invariants.
  Produces `PetCompatibilityReport(errors, warnings)`. It MUST NOT check file
  existence, directory containment, symlinks, ZIP entries, or archive ambiguity:
  a pure-core validator cannot see filesystem facts, and no fake-filesystem
  abstraction is invented in core to pretend otherwise.
- `:codex-pets-io` package loader owns all filesystem/archive facts:
  pet.json/avatar.json discovery, spritesheet existence, lexical path rejection,
  canonical path confinement, symlink policy, ZIP structure/traversal/limits/
  duplicates/ambiguity, bounded extraction of manifest + spritesheet bytes,
  probing encoded image metadata and passing the resulting facts into core
  validation, and identity normalization per PUBLIC_API_PROPOSAL.md §4.1.
  The complete load operation is Codex-compatible because these layers compose:
  io establishes the facts, core judges them.
- `CodexAuthoringValidator` — **DEFERRED tooling work (not Phase 1/2).** It
  requires pixel-level access, which must NOT introduce image-decoder
  dependencies into `:codex-pets-io` or any runtime artifact. When built, it
  lives in separate tooling (sample/CI-side) and produces an advisory
  `PetAuthoringReport`. Phase 1 and Phase 2 runtime artifacts implement no
  authoring QA.
- Authoring validation MUST NOT fail normal loading: a CLI-valid package with
  ugly-but-legal pixels loads successfully; QA findings are reported alongside,
  never as load errors. Default loader semantics follow runtime compatibility,
  not authoring quality. Callers opt into strict QA explicitly (e.g. a
  `--strict-qa` sample/CI mode), which still reports through `PetAuthoringReport`
  rather than corrupting load outcomes.

## 6. Network design (network module over io, optional)

Pet-oriented URL loading with one precise v1 semantic: **HTTPS URL → ZIP pet
package → bounded download → io ZIP parser**. Not implemented in Phase 0.

- Public entry: `loadPetZipFromUrl(url, limits, policy): PetLoadOutcome`;
  internally downloads bounded bytes, then delegates to the io ZIP loader — one
  enforcement point for limits and runtime validation.
- Schemes: **https only**, validated before request and re-validated after every
  redirect (CLI `validate_download_url` parity); http/file/custom schemes rejected.
- Redirects: follow preserves https-only invariant; any downgrade or
  non-https landing URL aborts.
- Size: remote ZIP cap = `PetPackageLimits` max compressed archive bytes
  (**16 MiB** default) — NOT the 4 MiB OpenAI built-in spritesheet cap, which
  applies to a different object (single CDN image) and stays in compatibility
  research only. Pre-checked against `content-length` when available, enforced
  incrementally per chunk; overshoot aborts with a typed error. Downloaded bytes
  still pass through the io ZIP limits afterward. `DownloadPolicy` may configure
  a LOWER bound only; values above the archive hard limit are rejected, never
  silently clamped.
- Timeout: 60 s total (CLI parity), configurable downward by caller.
- HTTP errors: non-2xx → typed `HttpStatusError(status)`; no silent fallback.
- Content handling: the download is treated as a ZIP package blob; it is never
  sniffed as an image and never confused with OpenAI's built-in spritesheet CDN
  (different host, different shape, out of scope — see research doc §2.7).
- Cancellation: fully cooperative via coroutine cancellation; partial buffers are
  discarded, nothing is cached on cancel or failure.
- No caching, no CDN pinning, no built-in-pet catalog in v1.

## 7. Compatibility profiles (internal, general, no invented behavior)

```text
CodexProfile (internal sealed): CliV1 (+ future first-party profiles only)
profileFor(spritesheetInfo): CodexProfile   // keyed by decoded DIMENSIONS
```

- v1.0 ships `CliV1` only: 1536x1872, 9-row table, CLI validation/timing.
- The selector keys on **decoded dimensions** — never on a version number alone
  (lesson C4: two geometries share the "v2" label). Unknown dimensions are a
  validation error, not a silent fallback.
- The abstraction is general (new geometry + table + rules can slot in), but it
  contains NO invented V2 behavior: no look-direction types, no 11-row table, no
  version-number semantics. Future V2 support activates only with a first-party
  contract. The open `PetAnimationKey` model already covers future names without
  breaking changes.
- Per-animation `fallback`, `loopStart`, per-frame nanos durations, and the 3x-play-
  then-settle rule are modeled in the normalized form so any future profile maps
  onto the same player.

### 7.1 Phase 2 decision item: `custom:` cache identity (recorded, not resolved)

Upstream `Pet::load_with_codex_home("custom:<id>")` uses cache identity
`"custom-<id>"` and ignores the manifest `id` on that loader path, while the
pure core normalizer is intentionally `manifestId ?: fallbackId`. Phase 2 must
decide explicitly whether `custom:` selector support reproduces the upstream
cache-id behavior or deliberately separates package identity from cache
identity — with rationale either way. No `CODEX_HOME` or `custom:` behavior
exists in Phase 1, and this contradiction must not be silently resolved later.

## 8. Platform support matrix (v1 = explicit, not "all KMP targets")

Terminology is load-bearing: **JVM Desktop running on Linux is not
Kotlin/Native `linuxX64`**. They are different compilations with different
decoders, CI legs, and test pipelines.

| Target | v1 | Rationale / cost |
|---|---|---|
| Android (minSdk 24) | ✅ supported | Compose + decoder story mature; CI host-testable |
| iOS device (arm64) | ✅ supported | Core/io/network are pure KMP; Compose iOS renderer via shared compose module |
| iOS simulator (arm64) | ✅ supported | Same code as device; CI-runnable on macOS runners |
| JVM Desktop (published once, runs on macOS / Windows / Linux) | ✅ supported | Reference renderer host; full test/debug tooling |
| Kotlin/Native linuxX64 | ❌ deferred | Own decoder + window impl + CI leg; JVM Desktop covers Linux in v1 |
| Kotlin/Native macOS | ❌ deferred | Same per-target cost; no v1 consumer |
| Kotlin/Native Windows (mingw) | ❌ deferred | Same per-target cost; weakest CI/codec story |
| JS / Wasm | ❌ deferred | Canvas region rendering + async decode + browser test harness; revisit on demand |

Cost of each additional target: new decoder path, new CI leg, new test fixture
pipeline, and API-surface review for platform leaks. v1 keeps 4 supported
targets sharing one decoder-per-platform-family design (Android/JVM share Bitmap
paths via Compose; iOS via Compose-iOS). `linuxX64` may remain as a
non-published, best-effort local compilation aid only if it costs nothing — it
is NOT a supported/published target and gets no CI leg until promoted by an
explicit decision.

## 9. Production library requirements (gap-closure plan, phased)

Explicit policy over popularity — each addition justified:

- Public API policy: `explicitApi()` (strict) from day one of Phase 1; api/
  dumps reviewed in PRs.
- ABI validation: prefer the Kotlin 2.4 built-in `kotlin { abiValidation() }`
  with CI running `checkKotlinAbi` (covers "don't break Swift/KMP consumers").
  The standalone `binary-compatibility-validator` plugin is NOT added: it would
  duplicate the built-in system with no demonstrated capability gap. Revisit only
  if a concrete gap (e.g. a needed check the KGP integration lacks) is recorded.
- Publishing: keep Vanniktech `maven-publish` (already wired) → Maven Central;
  fix placeholder POM (license/developer/SCM), real `group:artifact:version`
  per module (`com.yet.pets:codex-pets-core`, …), sources + javadoc/Dokka jars
  (Central requirement), GPG signing (already `signAllPublications`).
- Dokka: multimodule Dokka for the documentation artifact only.
- CI: matrix matches the support matrix exactly — `jvmTest` (ubuntu), iOS
  simulator test (macOS), `testAndroidHostTest` (ubuntu) — plus `checkKotlinAbi`,
  Dokka, and per-module test tasks; keep the macOS leg for iOS + publish. No
  `linuxX64` (Native) leg: it is deferred, and a CI leg would misrepresent it as
  supported. The current repo's `linuxX64Test` leg is removed or retargeted in
  Phase 1 for this reason.
- Tests per target: `commonTest` (core logic, hand-built facts, no codecs) + JVM/
  Android-host decoder tests + iOS-sim smoke (see TEST_STRATEGY.md).
- Versioning/changelog: SemVer + `CHANGELOG.md` (keep-a-changelog style); release
  flow stays tag → `publish.yml`.
- License: keep Apache-2.0 `LICENSE`; add missing `SECURITY.md` (zip-bomb/path
  traversal disclosure channel, limits table) and `CONTRIBUTING.md`.
- Dependency discipline: version catalog only (already in use); repair the
  catalog's dangling references (missing `androidx-lifecycle`, `coil`,
  `kotlinx-serialization`, `kotlinxDatetime` versions); add Ktor/Okio/serialization
  with explicit reasons; no new build plugin without a recorded justification.
- Template cleanup: delete `CustomFibi.kt`/`FibiTest.kt` (expect without actuals
  cannot compile once real targets build), fill empty README, rename root
  project (`multiplatform-library-template` → product name), fix coordinates.

## 10. Risks and phasing pointer

Risks classified P0/P1/P2 and the phased implementation plan live in the Phase 0
report (chat delivery) and are tracked from Phase 1 in `TEST_STRATEGY.md`.
Next gate: explicit approval of this architecture + compatibility contract before
any Phase 1 code.
