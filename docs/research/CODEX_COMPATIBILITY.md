# Codex Pet Compatibility Research

Phase 0 compatibility audit for the unofficial Kotlin Multiplatform Codex-pets library.

- Inspection date (UTC): 2026-09-25
- Status: research only. No implementation. Nothing in this document is authoritative
  over first-party OpenAI source; where sources conflict, the conflict is recorded
  rather than resolved silently.
- Rule applied throughout: GitHub issues and community projects are supporting
  evidence only, never the primary specification.

## 1. Authoritative sources inspected

| # | Repository | Commit SHA (pinned) | Commit date | File(s) inspected | What it defines |
|---|------------|---------------------|-------------|-------------------|-----------------|
| A | `openai/codex` | `55543d87724bb66bdd51bde65254feb9b4c9ed10` | 2026-09-25 | `codex-rs/tui/src/pets/model.rs` (1036 lines), `catalog.rs`, `asset_pack.rs`, `frames.rs`, `ambient.rs`, `mod.rs`, `picker.rs`, `preview.rs`, `codex-rs/config/src/types.rs` (~pet config), `codex-rs/tui/src/app/config_persistence.rs` (pet selection sync) | CLI/TUI runtime: manifest schema, validation rules, default animation table, asset download, frame timing/loop/fallback, cache keys |
| B | `openai/skills` | `49f948faa9258a0c61caceaf225e179651397431` | 2026-06-23 | `skills/.curated/hatch-pet/SKILL.md`, `skills/.curated/hatch-pet/references/codex-pet-contract.md`, `skills/.curated/hatch-pet/references/animation-rows.md`, `skills/.curated/hatch-pet/references/qa-rubric.md`, `skills/.curated/hatch-pet/scripts/validate_atlas.py`, `scripts/compose_atlas.py`, `scripts/prepare_pet_run.py` | hatch-pet authoring contract: atlas geometry, package layout, manifest shape, row/frame counts, per-row durations, validation rules |
| C | `openai/codex` issues (supporting only) | N/A (live tracker) | observed 2026-09-25 | #20730 (WSL path normalization), #33220 (Desktop V2 silent omission; ChatGPT-vs-Desktop V2 mismatch), #34240 (V2 look-direction rows never activate), #20863 (configurable animation sequences proposal) | Desktop behavior clues, V2 (`spriteVersionNumber`, 1536x2288, look rows) reports, community `animation` (singular) proposal — none of this is primary spec |

Negative results (also findings):

- `grep -rn "spriteVersion" openai/codex` at the pinned SHA returns **no matches**.
  The CLI/TUI runtime has no `spriteVersionNumber` field and no V2 concept.
- `find openai/codex -iname "*desktop*"` (depth 3) returns nothing relevant: the
  Desktop/Electron app source is **not** in `openai/codex`. Desktop behavior can
  therefore only be evidenced second-hand (see Section 4).
- `openai/skills` at the pinned SHA contains no `hatch-pet-v2` directory and no
  reference to look-direction rows, `2288`, or `spriteVersionNumber` anywhere under
  `skills/.curated/hatch-pet/`. The skill is V1-only (8x9) as inspected.

## 2. Contract 1 — Codex CLI/TUI runtime (PRIMARY, source A)

Source: `codex-rs/tui/src/pets/model.rs` @ `55543d8`. This is the only
machine-enforced, first-party pet contract available as source.

### 2.1 Manifest schema (`PetFile`)

```rust
struct PetFile {
    id: Option<String>,                          // optional
    displayName: Option<String>,                 // optional, falls back to id, then dir name
    description: Option<String>,                 // optional, default ""
    spritesheetPath: Option<String>,             // optional, default "spritesheet.webp"
    frame: Option<FrameSpec>,                    // optional, defaults to 192x208 8x9
    animations: HashMap<String, AnimationSpec>,  // optional, default = built-in table
}
struct FrameSpec { width: u32, height: u32, columns: u32, rows: u32 }
struct AnimationSpec {
    frames: Vec<usize>,        // required non-empty when the entry exists
    fps: Option<f64>,          // optional, default 8.0
    loop: Option<bool>,        // optional, default true  (serde rename: "loop")
    fallback: String,          // optional via serde default "", then normalized to "idle"
}
```

Required vs optional: **every field is optional**. A minimal `{}` manifest loads
(all defaults apply) as long as the spritesheet file exists with valid dimensions.
`id`/`displayName` fall back to the directory/file name; `description` defaults to
`""`; `spritesheetPath` defaults to `"spritesheet.webp"`.

Handling of unknown fields: `PetFile`/`AnimationSpec` do **not** use
`#[serde(deny_unknown_fields)]`, so unknown fields (e.g. a Desktop-style
`"spriteVersionNumber"`) are **silently ignored** by the CLI. This is observed
serde behavior at the pinned source, and it matters: a Desktop V2 manifest parses
fine in the CLI but its version field changes nothing.

### 2.2 Spritesheet and grid validation

From `catalog.rs` + `model.rs::validate_app_spritesheet_dimensions` +
`validate_frame_spec`:

| Item | Value |
|---|---|
| Spritesheet dimensions | **exactly** 1536x1872 px (`SPRITESHEET_WIDTH/HEIGHT`); any other size is rejected |
| Default cell | 192x208 px, grid 8x9, 72 frames |
| Custom `frame` | allowed iff `width*columns == 1536` **and** `height*rows == 1872` (exact cover, checked with overflow guards); all four values must be non-zero |
| Maximum frame count | 256 (`MAX_PET_FRAMES`) |
| Maximum animation FPS | 60.0, must be finite and > 0 (`MAX_ANIMATION_FPS`); default 8.0 |
| Image decoding | Rust `image` crate (`image::image_dimensions`, `image::open`); tests encode WebP. Format is whatever `image` can sniff — PNG/WebP in practice |

### 2.3 Path restrictions

From `resolve_spritesheet_path`: the manifest `spritesheetPath` must be a
**relative child path**. Rejected: absolute paths, any `..` (ParentDir)
component, any `Component::Prefix` (Windows drive prefix). The resolved path must
stay inside the pet directory. There is an explicit test
(`custom_pet_rejects_spritesheet_path_escape`) for `"../spritesheet.webp"`.

### 2.4 Package directory layout (CLI)

Accepted selectors (`Pet::load_with_codex_home`):

1. Path-like value (`.`, `..`, `~/`, `../`, `./`, absolute, or containing `/` or
   `\`) → directory containing `pet.json` (preferred) or legacy `avatar.json`;
   missing both → error `missing pet.json or avatar.json in <dir>`.
2. `custom:<id>` or bare `<id>` → `$CODEX_HOME/pets/<id>/pet.json`, else legacy
   `$CODEX_HOME/avatars/<id>/avatar.json`, else `unknown pet <id>`.
3. Built-in id (`codex`, `dewey`, `fireball`, `rocky`, `seedy`, `stacky`, `bsod`,
   `null-signal`) → versioned cache
   `$CODEX_HOME/cache/tui-pets/v1/assets/<id>-spritesheet-v4.webp`, always with
   the default 8x9 geometry and default animation table (manifest is ignored).

### 2.5 Animation model (CLI)

Normalized form: `Animation { frames: Vec<AnimationFrame { sprite_index, duration }>, loop_start: Option<usize>, fallback: String }`.

- **Arbitrary animation names**: `animations` is a `HashMap<String, …>` — custom
  names are first-class. Custom entries **merge over** (override / extend) the
  built-in table; `idle` is inserted if absent.
- **Per-animation timing**: custom entries use one uniform `duration = 1/fps` per
  frame. Built-in rows instead use per-frame durations with a longer final frame
  (see table below) — so the runtime model is per-frame durations, NOT global fps.
- **Looping**: `loop != false` → `loop_start = Some(0)` (loop whole track from
  first frame). `loop == false` → `loop_start = None`: when elapsed exceeds total
  duration the player holds the last frame (`ambient.rs`), and `current_animation`
  hands off to `fallback` (default `"idle"`).
- **Fallback**: every animation carries a `fallback` name; validation requires the
  fallback target to exist in the final table, and every animation must have ≥ 1
  frame with all indices `< frame_count`.
- **Playback subtlety** (`app_state_animation`): each non-idle built-in row plays
  its primary frames **3x**, then the idle frames are appended and `loop_start`
  points at the appended idle section — i.e. built-ins play the row 3 times then
  settle into the idle loop. A reimplementation must reproduce `loop_start`
  semantics, not just frame lists.

### 2.6 Built-in animation table (CLI defaults)

`default_animations()` in `model.rs`. Durations are per-frame ms; the last value
is the final-frame duration. `loop_start` for non-idle rows = 3x primary length
(see 2.5). Aliases included.

| Key(s) | Row | Frames (sprite indices) | Per-frame ms |
|---|---|---|---|
| `idle` | 0 | 0–5 | 1680, 660, 660, 840, 840, 1920 (loop_start=0) |
| `running-right`, `move_right` | 1 | 8–15 | 120 x7, final 220 |
| `running-left`, `move_left` | 2 | 16–23 | 120 x7, final 220 |
| `waving`, `wave` | 3 | 24–27 | 140 x3, final 280 |
| `jumping`, `bounce` | 4 | 32–36 | 140 x4, final 280 |
| `failed`, `sad` | 5 | 40–47 | 140 x7, final 240 |
| `waiting` | 6 | 48–53 | 150 x5, final 260 |
| `running` | 7 | 56–61 | 120 x5, final 220 |
| `review` | 8 | 64–69 | 150 x5, final 280 |

Runtime state mapping (`ambient.rs::PetNotificationKind`): the TUI ambient player
only ever selects `running`, `waiting`, `review`, `failed`, and `idle` (with
fallback to `idle` if a name is missing). `running-right`/`running-left`/
`waving`/`jumping` (and aliases) are defined in the table but **never selected by
the ambient state machine** at the pinned SHA — they exist for external triggers
(drag/preview paths) or future use. Lifetimes: running 3 min, failed 1 h, waiting
24 h, review 7 d. Reduced-motion (`animations_enabled == false`) pins the first
idle frame and schedules no follow-up frames.

### 2.7 Built-in asset download (CLI)

`asset_pack.rs`: CDN base `https://persistent.oaistatic.com/codex/pets/v1/`,
per-pet file `<id>-spritesheet-v4.webp`; **https only** (validated before
request and re-validated after redirects); 60 s timeout; **4 MiB** streaming cap
(`PET_MAX_DOWNLOAD_BYTES`, pre-checked against `content-length` and enforced per
chunk); staging write to a UUID-suffixed temp file, dimension validation before
atomic rename install, re-validation of the destination on rename race.

## 3. Contract 2 — hatch-pet authoring skill (PRIMARY, source B)

Sources: `codex-pet-contract.md`, `animation-rows.md`, `qa-rubric.md`,
`SKILL.md`, `validate_atlas.py`, `compose_atlas.py`, `prepare_pet_run.py`.

### 3.1 Atlas

- Format PNG or WebP; **1536x1872**; 8 cols x 9 rows; cell **192x208**;
  transparent background; unused cells fully transparent; fully-transparent pixels
  must not carry non-zero RGB residue (validation error); atlas must have an alpha
  channel; near-opaque used cells (>95% opaque) are errors (usually a
  non-transparent background); used cells need ≥ 50 non-transparent pixels.
- Row/frame counts: idle 6, running-right 8, running-left 8, waving 4, jumping 5,
  failed 8, waiting 6, running 6, review 6 → 57 used cells, 15 transparent.
- Per-row durations (`animation-rows.md`): idle `280, 110, 110, 140, 140, 320`;
  running-right/left `120` + final `220`; waving `140` + final `280`; jumping
  `140` + final `280`; failed `140` + final `240`; waiting `150` + final `260`;
  running `120` + final `220`; review `150` + final `280`.

### 3.2 Package layout and manifest (skill)

```text
${CODEX_HOME:-$HOME/.codex}/pets/<pet-name>/
├── pet.json
└── spritesheet.webp
```

```json
{ "id": "pet-name", "displayName": "Pet Name",
  "description": "One short sentence.", "spritesheetPath": "spritesheet.webp" }
```

The skill documents only these 4 manifest fields — it does **not** document the
CLI's `frame` or `animations` fields (contradiction C1 below). The app loads
custom pets by folder name under `pets/`.

## 4. Contract 3 — Codex Desktop V2 + adjacent reports (SUPPORTING ONLY)

Desktop source is not public; everything here is second-hand (issue reporters
quoting `app.asar` inspection, validator behavior, and UI observations) and must
be treated as unconfirmed until a first-party source appears.

Reported V2 shape (issues #33220, #34240, plus #20730 for loader path handling):

- Manifest carries `"spriteVersionNumber": 2` (V1 presumably `1` or absent).
- V2 atlas reported as **1536x2288** = 8 cols x **11 rows** (two extra rows).
- Rows 9–10 reportedly hold **16 look-direction frames** (clockwise, 000° = up);
  neutral/front is a pointer deadzone falling back to idle.
- A valid V1 package is 1536x1872; reporters observe the Desktop loader accepting
  exactly one geometry per version value and **silently omitting** mismatched
  folders from Settings (no per-folder error).
- ChatGPT's pet-creation flow reportedly produces/activates a "v2" pet using the
  **9-row 1536x1872** contract, which Desktop then rejects — i.e. two different
  geometries travel under the same `spriteVersionNumber: 2` label.
- Issue #34240 (+ Aug 2026 follow-up) reports look-direction rows never activate
  at runtime (possibly feature-gated cursor tracking), so even the V2 runtime
  behavior is uncertain.
- Issue #20863 proposes a **singular** `"animation"` manifest field
  (`states`/`chains`/`events`, `durationMs`, `lastFrameDurationMs`, sequence
  chaining, hover/drag events) — a different shape from the CLI's **plural**
  `"animations"` (`frames`/`fps`/`loop`/`fallback`). The two must not be
  conflated; the proposal is not implemented upstream.

## 5. Compatibility matrix

Legend: ✅ confirmed in source · ⚠️ reported second-hand · ❌ absent/prohibited.

| Aspect | CLI/TUI (A) | hatch-pet skill (B) | Desktop incl. V2 (C, supporting) |
|---|---|---|---|
| Manifest fields | `id?, displayName?, description?, spritesheetPath?, frame?, animations?` — all optional | `id, displayName, description, spritesheetPath` only | + `spriteVersionNumber` ⚠️ |
| Required fields | none (defaults cover everything) | `id/displayName/description/spritesheetPath` per docs | unknown |
| `spritesheetPath` default | `"spritesheet.webp"` ✅ | `"spritesheet.webp"` ✅ | same ⚠️ |
| Path restrictions | relative child only; no `..`, no absolute, no drive prefix ✅ | unspecified | POSIX/Windows mismatch bugs reported ⚠️ (#20730) |
| Unknown-field handling | silently ignored (no `deny_unknown_fields`) ✅ | N/A (authoring side) | unknown; mismatched dirs silently omitted ⚠️ |
| Atlas (V1) | exactly 1536x1872 ✅ | exactly 1536x1872 ✅ | 1536x1872 accepted for V1 ⚠️ |
| Atlas (V2) | ❌ (rejected by exact-size check) | ❌ (validator expects 1872) | 1536x2288, 8x11 ⚠️ |
| Cell | 192x208 default; custom `frame` iff exact cover ✅ | fixed 192x208 ✅ | 192x208 assumed ⚠️ |
| Frame-count cap | 256 ✅ | 72 implicit (fixed grid) | unknown |
| Max animation FPS | 60.0 ✅ | unspecified | unknown |
| Animation names | open map; 9 states + 5 aliases built in ✅ | 9 fixed states | + look-direction (16 dirs, rows 9–10) ⚠️ |
| Timing model | per-frame durations; custom = uniform `1/fps`; built-ins = per-row table w/ long final frame ✅ | per-row duration table ✅ | unknown |
| Looping | `loop_start: Option<usize>`; non-idle built-ins play 3x then settle into idle ✅ | "first and last frames can loop" (QA rubric) | unknown |
| Fallback | per-animation `fallback`, must name an existing animation, default `idle` ✅ | unspecified | unknown |
| Validation errors | bad dims, non-covering grid, count > 256, empty frames, index ≥ count, bad fps, dangling fallback, path escape, missing files ✅ | sparse/empty used cell, non-transparent unused cell, RGB residue, opaque atlas, geometry mismatch ✅ | silent omission instead of errors ⚠️ |
| Package layout | `pets/<id>/pet.json` (+ legacy `avatars/<id>/avatar.json`, explicit paths) ✅ | `pets/<pet-name>/{pet.json, spritesheet.webp}` ✅ | same roots; WSL/Windows path bugs ⚠️ |
| CDN / built-ins | `https://persistent.oaistatic.com/codex/pets/v1/*-spritesheet-v4.webp`, 8 pets ✅ | N/A | N/A |
| Network limits | https-only, 60 s, 4 MiB streaming cap, atomic install ✅ | N/A | unknown |

## 6. Contradictions and uncertainties (do not resolve silently)

- **C1 — skill under-documents the runtime.** The skill (B) documents 4 manifest
  fields; the CLI (A) implements 6 (`frame`, `animations`). A pet using custom
  `frame`/`animations` is CLI-valid but skill-undocumented; skill-generated pets
  always use defaults. Our v1 scope implements the CLI schema (A) as normative
  and treats the skill text as an authoring guide, not the schema.
- **C2 — idle durations differ by exactly 6x.** Skill: `280, 110, 110, 140, 140,
  320`. CLI: `1680, 660, 660, 840, 840, 1920`. Ratios (6.0x each) suggest a unit
  or loop-count discrepancy in one of the documents. Normative for playback is
  the CLI table (it is executed code); the skill table is noted as suspect.
- **C3 — V2 exists only second-hand.** No `spriteVersionNumber`, no 11-row
  geometry, no look-direction logic exists anywhere in `openai/codex` @ pinned
  SHA. V2 support therefore cannot satisfy acceptance gate #1 from primary
  sources; it is scoped as an **adapter-ready but unconfirmed profile** (see §7).
- **C4 — two geometries share the "v2" label.** ChatGPT-flow v2 = 9-row
  1536x1872 vs Desktop v2 = 11-row 1536x2288 (#33220). A `spriteVersionNumber`
  field alone does not disambiguate geometry; any V2 adapter must key off
  decoded dimensions, not the version number.
- **C5 — singular vs plural animation fields.** Community `animation` proposal
  (#20863) ≠ CLI `animations` schema. We implement the CLI shape only; the
  proposal shape is explicitly out of scope until first-party adoption.
- **C6 — ambient row coverage.** The CLI defines 9 rows but its ambient player
  only selects 5 states. Consumers must not assume every defined row is
  reachable in every host; our normalized model keeps all rows addressable and
  leaves state-mapping to the host layer.
- **U1 — look-direction mapping unconfirmed.** Direction order (clockwise from
  up), deadzone semantics, gating — all single-sourced from #34240. Recorded
  here as research only; no profile sketch or type carries it into v1.
- **U2 — Desktop validation/limits unknown.** No first-party data on Desktop
  size caps, format acceptance beyond PNG/WebP, or error surfacing (observed:
  silent omission).

## 7. Recommended v1 compatibility scope

- **Normative: Contract 1 (CLI/TUI @ `55543d8`) in full** — manifest schema with
  `frame` + `animations`, exact-cover grid validation, 256-frame / 60-fps caps,
  relative-path confinement, per-frame durations with `loop_start`/fallback
  semantics, built-in 9-row table incl. aliases, 1536x1872 geometry.
- **Two validation layers.** Contract 1 rules are machine-enforced runtime
  compatibility (`CodexCompatibilityValidator`) and gate loading. Contract 2
  (skill) rules are asset-authoring QA (`CodexAuthoringValidator`, advisory
  `PetAuthoringReport`) that can never fail loading of a CLI-valid package.
- **V2/look-direction: research record only, zero v1 API surface.** No
  `LookDirection` types, no look metadata slots, no V2 profile sketch: without a
  first-party source there is nothing to implement against. Unknown-field
  tolerance (mirroring serde's ignore-unknown behavior) is kept; the internal
  profile selector keys on decoded dimensions, never on a version number alone.
  Future V2 animation names fit the existing open-key model without breaking
  changes, and activate only with a first-party contract.
- Explicitly out of v1: singular-`animation` proposal shape, ChatGPT-flow quirks,
  Desktop-specific loader bugs (WSL paths), OpenAI's built-in spritesheet CDN.
  Remote loading covers user-supplied ZIP packages over https only.
