# Pets KMP Package Format v1

OUR specification for generic cross-platform animated pets. This is NOT a
Codex format. Codex Pets V1 compatibility is a separate adapter; Codex
Desktop V2 is research only and unsupported.

## 1. Identity

The manifest document MUST self-identify:

```json
{
  "schema": "pets-kmp",
  "schemaVersion": 1
}
```

- `schema` MUST equal `"pets-kmp"`.
- `schemaVersion` MUST equal integer `1`.
- Never infer generic format merely because Codex parsing failed. Use the
  explicit `PetsKmpPackageParser` / `PetLoader.loadPetsKmpZip` /
  `PetLoader.loadPetsKmpDirectory` entry points.

Manifest filename may remain `pet.json` (with legacy `avatar.json` discovery
in directory/ZIP packages), but the document content decides the format.

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

- `id`: required non-blank.
- `displayName`: required non-blank.
- `description`: optional; defaults to `""` after trimming.
- `spritesheetPath`: optional; when absent or blank defaults to
  `spritesheet.webp`. Path-confinement protections still apply (relative
  child only; no `..`, absolute, drive prefix, or backslashes; canonical
  containment; ZIP traversal/duplicate/symlink rules).
- `frame.width` / `frame.height`: required positive integers (cell size).
- `defaultAnimation`: required non-blank; MUST reference an existing
  animation key.
- `animations`: required; at least one; unique keys. No speculative fields.

Animation keys: non-blank, at most 64 characters, deterministic
equality/hashCode, no platform-dependent behavior. Arbitrary keys such as
`idle`, `sleep`, `eat`, `dance`, `happy`, `angry`, `scratch`, `spin`,
`celebrate`, `stand`, `blink` work without library changes.

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

- `loopStart = null` / absent: one-shot animation; hold final frame when
  complete.
- `loopStart = N`: after the final frame, continue from frame index `N`.

Validate `0 <= N < frames.size`.

Do NOT automatically append the default animation. Do NOT inherit Codex's
three-primary-passes-plus-idle chain. The Codex adapter builds that explicit
`PetAnimation` representation itself; generic one-shot hold is modeled with a
self-fallback so the shared one-hop player holds without appending anything.

## 7. No generic fallback field

Format v1 has NO generic JSON `fallback` field. One small unambiguous runtime
contract. Existing internal fallback mechanics needed for Codex remain, but
generic packages do not expose them. A later schema version may add
transitions/fallbacks after requirements exist.

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

## 12. What this is not

- Not a Codex format. Codex V1 compatibility lives in `CodexPetPackageParser`
  / `PetPackageParser` with fixed geometry, built-in table, aliases, and
  fallback/loop quirks.
- Not Codex V2. `spriteVersionNumber=2` does not activate any profile. The
  original `assets/kodee/pet.json` still fails the strict Codex V1 loader by
  design. A SEPARATE generic manifest can describe the same spritesheet as a
  generic pet — that distinction is central.
