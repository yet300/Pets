# Test Strategy (designed before implementation)

## 1. Principles

- No proprietary assets: never vendor OpenAI CDN spritesheets or community pets.
  All fixtures are synthetic (generated at test time) or hand-written JSON.
- Core logic (`commonTest`) runs hermetic with hand-constructed
  `SpritesheetInfo` data values: no Okio, no filesystem, no fake filesystem,
  no codecs, no network, no real files. Core has no
  coroutines, so player/validator tests are plain synchronous tests.
  (Filesystem fakes such as Okio `FakeFileSystem` belong to the future
  `:codex-pets-io` phase, never to core.)
- Real decoding is verified per platform family (JVM/Android-host with real PNG/
  WebP files generated at test time via platform codecs; iOS-sim smoke).
- Deterministic ZIP tests; no network in tests (Ktor `MockEngine` for the
  downloader's cap/redirect/timeout logic).
- Runtime compatibility and authoring QA are tested as separate suites with
  opposite load-gate assertions (§3a vs §3b).

## 2. Fixture generation (no repo binaries)

- `TestPets.minimalManifestJson(...)` builders for every schema variant.
- Synthetic spritesheet facts as plain `SpritesheetInfo` values at the exact
  profile geometry (1536x1872); pixel-level occupancy fixtures (empty / sparse /
  opaque / RGB-residue) are reserved for the **deferred** authoring tooling,
  never core.
- Platform tests generate real PNG/WebP via `javax.imageio` (JVM) / equivalent
  and feed them through the io-owned probe + decoder path (future io phase).

## 3. Required cases (traceable to the contract)

### 3a. Runtime compatibility — gates loading (Phase 1 core rows vs Phase 2/3 io rows)

Core rows (pure, Phase 1): manifest parsing/defaults, geometry, frame count,
indices, fps, loop model, fallback existence, identity, effective
spritesheet-path STRING default. IO/network rows below (lexical path rejection,
file discovery, symlinks, ZIP, limits, downloader) are Phase 2/3 and run
against the io/network modules, never core.

Manifest parsing: all-optional-fields defaulting; `frame` exact-cover accept;
non-covering grid reject; zero dims reject; count > 256 reject; unknown fields
ignored **silently by default** (incl. `spriteVersionNumber` passthrough — no
unknown-key tracking, assert absence of diagnostics plumbing, not presence).
Atlas/grid: wrong dims reject; non-covering `frame` reject.
Compatibility profile: CLI default table exact (indices + `Duration`s +
`loopStart` 3x-settle rule + aliases); custom `animations` merge/override;
`idle` auto-insert.
Custom animations: arbitrary names; `fps` default 8.0; `loop=false` → hold +
single-hop fallback; fallback references validated for existence only (no chain
traversal — see §3c).
Invalid inputs: empty `frames`; index ≥ frame_count; dangling fallback;
fps ∈ {NaN, ≤0, >60, infinite}; `spritesheetPath` ∈ {absolute, `..`, drive
prefix, empty→default}; missing pet.json/avatar.json; missing spritesheet;
unsupported image format (io-supplied `SpritesheetInfo` reports unknown → typed error).
Path/ZIP security (all Phase 2 io): `../` traversal; absolute-path entry; Zip Slip
(`../../evil`); duplicate/conflicting entries; symlink ZIP entries rejected;
**directory symlink policy**: in-package symlink loads, escaping symlink →
`PathEscape`, dangling symlink → missing-asset error (each with a dedicated
io-module test using real symlinks on JVM plus Okio `FakeFileSystem` symlink
support where available); corrupt ZIP (bad CRC/truncation); raw-blob pre-cap reject before
indexing; entry-count > 64; compressed > 16 MiB; uncompressed > 32 MiB; ratio
tripwire; manifest > 64 KiB; spritesheet > 8 MiB; root-vs-nested ambiguity (all
three shapes: none / both / two nests → `AmbiguousPackage`).
Network (MockEngine, `loadPetZipFromUrl`): https-only (http rejected pre-flight);
redirect downgrade aborts; remote cap = archive limit (16 MiB default):
`content-length` preflight over cap aborts, per-chunk enforcement aborts,
`DownloadPolicy` lower bound honored, above-hard-limit policy value rejected
(not clamped); downloaded bytes flow through the io ZIP parser (assert a valid
ZIP URL yields a loaded definition; assert an oversized/invalid ZIP URL yields
the io error, not a network-typed error); non-2xx typed error; cancellation
discards bytes; 60 s timeout honored (virtual time).

### 3b. Authoring QA — DEFERRED tooling (not Phase 1/2)

`CodexAuthoringValidator` → `PetAuthoringReport` requires pixel access and must
not introduce decoder dependencies into runtime artifacts, so it is deferred to
separate tooling work. Reserved cases (each asserting both the advisory flag AND
that the same CLI-valid package still loads): sparse used cell; non-transparent
unused cell; RGB residue; opaque atlas; near-opaque cell; canonical row
occupancy mismatch. No authoring types appear in any Phase 1/2 load-path
signature.

### 3c. Playback (`samplePetAnimation`, `Duration`-based) — Codex animation-model parity with deterministic host scheduling

Selection/frame semantics match the reference; `nextFrameIn` scheduling is our
deterministic improvement (§2.2 of the API proposal), never claimed as TUI
scheduler parity. All durations asserted as `Duration` (built-in ms constants
via `.milliseconds`; custom fps via `1.0/fps` seconds — never integer-ms fields):

- Normal frame progression across row boundaries with per-frame durations incl.
  the long final frame.
- Looping from index 0 (`loopStart = 0`): elapsed multiples of total duration
  restart cleanly.
- Built-in 3x primary prefix → idle-loop suffix: prefix plays exactly 3x, then
  `loopStart` lands in the appended idle section; far-future elapsed stays in
  the idle loop.
- One-hop fallback, no recursion: A(non-looping) → B asserted post-completion
  at the same elapsed clock (mid-track B, not B frame 0). A → B → C with A and
  B non-looping and elapsed far beyond BOTH durations still selects **B at the
  same elapsed time** — assert NOT C. Fallback cycles need no traversal tests
  beyond existence validation.
- Non-looping multi-frame: holds last frame pre-hop; post-hop `nextFrameIn`
  follows the evaluated animation.
- Single-frame one-shot (deliberate improvement): pre-completion `nextFrameIn`
  equals remaining time to the fallback transition (TUI would schedule nothing);
  post-completion the normal single hop applies.
- Elapsed substantially beyond the transition (e.g. 10x total duration).
- Single-frame looping: static sample, `nextFrameIn == null`.
- Unknown requested key → idle evaluation (assert `sample.animation == Idle`).
- High-precision accumulation: 60 FPS custom animation (16.666…ms frames) and
  59.94 FPS (≈16.683ms frames) over many cycles — assert no drift from
  integer-millisecond truncation (e.g. frame index at t=10s matches
  nanosecond-math expectation, not floor-per-frame accumulation).
- Reduced-motion/static: `staticIdleSpriteIndex` returns the first idle frame;
  `PetPlayerState.pinToIdle()` stops the timer (compose/host test).
- Geometry single-source: `sourceRectForOrNull` corner cells (0, 7, 64, 71)
  exact rects; out-of-range indices (−1, frameCount, Int.MAX_VALUE) return null;
  no throwing/skipping path exists in public API.

### 3d. Identity normalization (deterministic, no random IDs)

Explicit manifest id wins; missing id + present displayName (id falls back to
directory name per CLI); missing id/displayName + directory basename; nested ZIP
top-level dir name; root-layout ZIP bytes default `"pet"`; explicit
`fallbackId`/`sourceName` parameter overrides the default; URL ending in
`bella.zip` → `"bella"`; blank/whitespace-only id/displayName treated as absent;
post-redirect URL wins for the fallback (manifest id still wins overall).

### 3e. Renderer (compose, host/instrumented)

Single atlas decode (assert one `ImageBitmap`, N draws reference subregions —
fail on per-frame slicing); subregion math for corner cells; animation key
switch without re-decode; `PetPlayerState` (assert the old `PetState` name is
gone from the API dump). Module boundary: loader success carries
`spritesheetBytes: ByteArray` and compose decodes them with no io dependency
(assert no `PetAssetRef` type and no io-module reference in the compose API).

## 4. CI mapping (matches the support matrix — no Native-linux leg)

`commonTest` on supported legs: JVM, iosSimulatorArm64, Android host.
Decoder tests on JVM + Android host; downloader tests on JVM (MockEngine);
`checkKotlinAbi` (Kotlin 2.4 built-in) + Dokka on the JVM leg; iOS-sim leg runs
the core suite as consumer canary. No `linuxX64` Kotlin/Native leg (deferred
target — a leg would misrepresent support). Coverage target for core
validation/player paths: 100% branch on the cases above; enforced by review
checklist, not a vanity percentage gate.
