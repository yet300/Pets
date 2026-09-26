# Security Policy — codex-pets-kmp

This library loads **untrusted pet packages** (directories and ZIP archives)
containing a JSON manifest and an image spritesheet. The boundaries below are
enforced by `:codex-pets-io` and pinned by permanent regression tests.

## Threat model

- **Path traversal / Zip Slip**: manifest `spritesheetPath` values and ZIP entry
  names are lexically confined (relative-only; no `..`, absolute paths, drive
  prefixes, backslashes). Directory targets are additionally canonicalized and
  required to stay segment-wise inside the canonical package root
  (`/pkg/foo` never contains `/pkg/foobar`).
- **Symlink confinement**: symlinks (files and parent directories) resolving
  inside the package load normally; escapes fail as `PathEscape`; dangling
  links fail as `DanglingSymlink`/`DanglingManifest`. ZIP symlink entries are
  rejected unconditionally (`SymlinkEntry`); archives are never extracted, so
  even an undetected non-Unix symlink representation stays inert payload bytes.
- **ZIP bombs / resource exhaustion**: enforced caps — manifest 64 KiB,
  spritesheet 8 MiB, entries ≤ 64, raw archive ≤ 16 MiB (checked before
  indexing), uncompressed total ≤ 32 MiB, per-entry ≤ 32 MiB, 100× compression
  tripwire (exact integer arithmetic). Caps apply to central-directory metadata
  AND to actual bytes read; declared sizes are advisory only.
- **Archive scope**: stored + deflated entries, single-disk, no ZIP64.
  Central/local headers are cross-checked (names, method, flags, CRC, sizes);
  entry ranges must precede the central directory and must not overlap;
  data-descriptor entries and encrypted entries are rejected; central-directory
  size is reconciled exactly.
- **Image handling**: header-only metadata probing (PNG/GIF/JPEG/WebP); no pixel
  decoding in io. A successful probe is NOT a renderability guarantee — corrupt
  pixel data fails later at the platform decoder, which is outside this
  library's load boundary.
- **TOCTOU**: canonicalization and opening are not atomic. The loader validates,
  then opens/reads immediately with streaming caps. This stops normal
  traversal/symlink escapes but is NOT a sandbox against a hostile
  concurrently-mutated filesystem.

## What is NOT covered

Transport/network fetching is host-owned and out of scope: this library never
fetches bytes from the network, never parses URLs, and ships no HTTP/download/
redirect/caching code. Image rendering and authoring pixel QA do not exist in
this library yet (explicit non-goals of Phases 1–2). Do not feed loader outputs
to decoders without handling decoder errors.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use the repository's
private vulnerability reporting at `https://github.com/yet300/Pets/security`
(GitHub private advisories). Include: affected module/version, a minimal
reproducer (manifest or archive bytes), expected vs actual typed outcome, and
the host OS/filesystem where observed.
