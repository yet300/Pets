# Pets KMP Package Format v1

> **2026-10-02 V2 amendment (current implementation).** Explicit V2 support
> now lives in `CodexV2PetPackageParser`/`CodexV2` and
> `PetLoader.loadCodexV2Directory`/`loadCodexV2Zip`. V1 acceptance and the
> generic v1 schema remain unchanged. The first-party Work Pets 0.1.6 artwork
> contract establishes 1536x2288, 8x11, 192x208 cells and sixteen clockwise
> look poses. Standard timings and the optional pure selector follow the
> separately observed Desktop runtime. Fixed geometry, literal integer version
> 2, static PNG/WebP only, 64 KiB/8 MiB caps, ignored unknown members and rejected
> `frame`/`animations` (including null) are explicit library policies.
> Validation checks image metadata, not pixels/alpha or official Desktop
> equivalence. See [design](../spec/CODEX_V2_SUPPORT_DESIGN.md) and
> [implementation evidence](../audit/CODEX_V2_SUPPORT_IMPLEMENTATION.md).

OUR specification for generic cross-platform animated pets. This is NOT a
Codex format. Codex Pets V1 compatibility is a separate adapter; Codex
V2 support is a separate explicit library profile (see amendment above).

## 1. Identity

The manifest document MUST self-identify:

```json
{
  "schema": "pets-kmp",
  "schemaVersion": 1
}
```

- `schema` MUST equal `"pets-kmp"`.
- `schemaVersion` MUST equal integer `1` (unquoted JSON integer token; see
  §3.1 strict integer syntax).
- Never infer generic format merely because Codex parsing failed. Use the
  explicit `PetsKmpPackageParser` / `PetLoader.loadPetsKmpZip` /
  `PetLoader.loadPetsKmpDirectory` entry points.

Generic directory/ZIP package discovery requires `pet.json` only. Pets KMP
is our new format and does not inherit the Codex legacy `avatar.json` name:
a generic package containing only `avatar.json` fails with `MissingManifest`.
(Codex V1 loaders retain `pet.json`/`avatar.json` discovery for
compatibility.) The document content decides the format.

## 2. Shape

```json
{
  "schema": "pets-kmp",
  "schemaVersion": 1,

  "id": "kodee",
  "displayName": "Kodee",
  "description": "Optional free text.",

  "spritesheetPath": "spritesheet.webp",

  "frame": {
    "width": 192,
    "height": 208
  },

  "defaultAnimation": "idle",

  "animations": [
    {
      "key": "idle",
      "loopStart": 0,
      "frames": [
        { "index": 0, "durationMs": 200 }
      ]
    }
  ]
}
```

DTO representation remains internal. The public runtime model
(`PetDefinition` et al.) is independent of JSON.

## 3. Field rules

- `id`: required non-blank. Generic identity is manifest-owned: the public
  generic parser/loader APIs take no caller-supplied fallback identity
  (unlike the Codex adapter, where transport-derived fallback ids exist).
- `displayName`: required non-blank.
- `description`: optional; defaults to `""` after trimming.
- `spritesheetPath`: optional; when absent or blank defaults to
  `spritesheet.webp`. Path-confinement protections still apply (relative
  child only; no `..`, absolute, drive prefix, or backslashes; canonical
  containment; ZIP traversal/duplicate/symlink rules).
- `frame.width` / `frame.height`: required positive integers (cell size;
  strict syntax per §3.1).
- `defaultAnimation`: required non-blank; MUST reference an existing
  animation key (compared after key trimming, case-sensitively).
- `animations`: required; at least one; unique keys (after trimming).

Animation keys (manifest normalization): `animation.key` and
`defaultAnimation` are trimmed during manifest normalization; comparison
occurs after trimming; keys are case-sensitive (`Dance` differs from
`dance`); blank-after-trim is invalid. There is no separate string-length
cap: the whole manifest is bounded to 64 KiB. This is format normalization,
not model validation — the normalized `PetAnimationKey` model itself
preserves its exact value (`PetAnimationKey(" dance ")` differs from
`PetAnimationKey("dance")` by design). Arbitrary keys such as `idle`,
`sleep`, `eat`, `dance`, `happy`, `angry`, `scratch`, `spin`, `celebrate`,
`stand`, `blink` work without library changes.

### 3.1 Strict integer JSON syntax

All integer fields (`schemaVersion`, `frame.width`, `frame.height`,
`animation.loopStart`, `frame.index`, `frame.durationMs`) MUST be unquoted
JSON integer tokens.

Accepted lexical grammar: `-?(0|[1-9][0-9]*)`. Each field's semantic range
validation applies afterwards (positive cell size, `0 <= loopStart <
frames.size`, `0 <= index < frameCapacity`, positive `durationMs` with
overflow-checked ms-to-nanos conversion, `schemaVersion == 1`).

Rejected (deterministic typed failure, never a crash): `"1"`, `"0"`
(quoted), `1.0`, `0.0` (decimal), `1e0`, `1E0`, `1E+0` (exponent),
booleans, nulls where required, objects, arrays, and out-of-range integers.
Codex DTO parsing is unaffected: this rule is generic-format-only.

### 3.2 Unknown JSON fields

Unknown fields are ignored (forward-compatible) at any object level handled
by the normal DTO decoder — top-level, `frame`, animation entries, and frame
entries. `schemaVersion` still gates incompatible format versions: unknown
fields never excuse a wrong or missing version.

## 4. Geometry

Generic definitions support arbitrary rectangular sprite atlases:

- `atlasWidth > 0`, `atlasHeight > 0` (from probed image dimensions).
- `cellWidth > 0`, `cellHeight > 0` (from manifest `frame`).
- `atlasWidth % cellWidth == 0`.
- `atlasHeight % cellHeight == 0`.

Derived with overflow-safe arithmetic:

- `columns = atlasWidth / cellWidth`.
- `rows = atlasHeight / cellHeight`.
- `frameCapacity = columns * rows`.

No generic requirement of 1536, 1872, 2288, 8, 9, 11, 192, or 208. The Codex
V1 adapter still enforces its exact constants.

## 5. Frames

Each frame:

- `index >= 0` and `index < frameCapacity`.
- `durationMs`: positive integer; converted to internal nanos via checked
  arithmetic (`durationMs * 1_000_000`, overflow rejected).

Reject zero, negative, and overflow. No floating-point durations in v1.

## 6. Loop semantics

- `loopStart = null` / absent: one-shot animation; play frames once, then
  hold the final frame forever (`nextFrameInNanos = null`).
- `loopStart = N`: after the final frame, continue from frame index `N`.

Validate `0 <= N < frames.size`.

Do NOT automatically append the default animation. Do NOT inherit Codex's
three-primary-passes-plus-idle chain. The Codex adapter builds that explicit
`PetAnimation` representation itself.

Runtime note: the normalized model represents a generic one-shot directly as
`loopStart == null` with `fallback == null` (no fallback transition). The
sampler holds the final frame of the SELECTED animation under the same key.
No fabricated self-transition is encoded.

## 7. No generic fallback field

Format v1 has NO generic JSON `fallback` field. Parsed generic animations
expose `PetAnimation.fallback == null` unless some future adapter explicitly
provides one. The Codex V1 adapter keeps its exact fallback behavior
(non-null fallbacks, at most one hop, same elapsed clock). A later schema
version may add transitions/fallbacks after requirements exist.

## 7.1 Unknown requested animation (generic runtime policy)

Requesting an animation key absent from the definition (a typo, or a key
missing after rebinding) samples the definition-owned
`defaultAnimationKey`. This is generic *playback* policy for an unknown
requested key. It is separate from `PetAnimation.fallback` (a declared
animation transition, always null for generic v1) and separate from the
Pets KMP manifest (which has no fallback concept). Public host intent keeps
the explicit requested key; only the sampled pixels resolve to the default.

## 8. Limits

Generic does NOT mean unbounded:

- Manifest ≤ 64 KiB.
- Encoded spritesheet ≤ 8 MiB.
- Frame capacity ≤ 256 (same safe envelope as Codex V1; Kodee's 8x11=88 fits).
- ZIP: ≤ 64 entries, raw archive ≤ 16 MiB, expanded total and per entry ≤
  32 MiB, aggregate compression ratio ≤ 100x. Only stored and raw DEFLATE.

Do not increase limits for theoretical flexibility.

## 9. Image restrictions

Use the verified static-image pipeline; no new codec:

- Static PNG, JPEG, GIF first frame, static WebP VP8/VP8L/VP8X.
- Animated WebP remains rejected per the cross-platform contract.
- Compose validates bytes (8 MiB cap), async decode, dimension equality,
  cancellation, Ready/Loading/Failed — unchanged.

## 10. Error behavior

No exception leakage for malformed untrusted data. Typed failures cover:

- schema missing/wrong, version missing/unsupported.
- missing id/displayName, invalid frame size/grid/capacity.
- duplicate/empty/invalid animation keys, empty animations.
- invalid frame index/duration/loopStart, unknown default animation.
- image format/dimension failures, input limits.

## 11. Complete valid example

See `assets/kodee/pet.pets-kmp.json` for a real 1536x2288, 8x11, 88-frame
example with `idle`, `wave`, `jump`, `happy`, and `rest`.

Note: the Kodee spritesheet changed after the V2 research snapshot, so its
current hash differs from the hash recorded in
`docs/research/CODEX_V2_COMPATIBILITY_RESEARCH.md`. Historical research
evidence is not rewritten; the current asset revision is what the generic
manifest and tests describe.

## 12. What this is not

- Not a Codex format. Codex V1 compatibility lives in `CodexPetPackageParser`
  / `PetPackageParser` with fixed geometry, built-in table, aliases, and
  fallback/loop quirks.
- Not Codex V2. Within the generic parser, `spriteVersionNumber=2` does not
  activate a profile; the example routes explicit V2 to its separate adapter. The
  original `assets/kodee/pet.json` still fails the strict Codex V1 loader by
  design. A SEPARATE generic manifest can describe the same spritesheet as a
  generic pet — that distinction is central.
