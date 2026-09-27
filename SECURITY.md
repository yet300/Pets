# Security Policy — codex-pets-kmp

The library accepts foreign pet packages and encoded images. Each public
untrusted-input path enforces its own boundary; passing data through one layer
does not grant another layer's guarantees.

## A. Directory and ZIP packages (`:codex-pets-io`)

- Manifest ≤ 64 KiB; encoded sheet ≤ 8 MiB. Actual reads are capped, not just
  declarations.
- ZIP: ≤ 64 entries, raw archive ≤ 16 MiB, expanded total and per entry ≤
  32 MiB, aggregate compression ratio ≤ 100×. Only stored and raw DEFLATE
  entries are supported. DEFLATE must finish and consume exactly the declared
  compressed bytes; sizes and CRC must match. Encrypted, descriptor, ZIP64,
  overlapping, and structurally inconsistent archives fail. Entries are never
  extracted to disk.
- Virtual ZIP paths are relative and confined. Directory symlinks resolving
  within the canonical package root are allowed; escapes and dangling targets
  fail. Root and asset checks are not atomic against a concurrently mutated
  filesystem.
- IO calls core's pure image metadata probe. A successful probe does not prove
  that all pixels decode.

## B. Raw manifest and image bytes (`:codex-pets-core`)

`PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)` checks
manifest ≤ 64 KiB and encoded sheet ≤ 8 MiB before parsing/probing. It returns
typed failures for malformed JSON, image metadata, animated WebP, invalid
geometry, and compatibility errors. The String and `spritesheetPathOf`
overloads are also bounded by UTF-8 manifest size. `parseTrustedMetadata` is
an expert seam: its caller supplies image facts, so it cannot prove that those
facts match any image bytes.

Raw parsing performs no filesystem/path-confinement or ZIP checks. Applications
that receive bytes directly supply their own transport and storage policy.

## C. Direct Compose atlas bytes (`:codex-pets-compose`)

`rememberPetPlayerState` checks encoded bytes ≤ 8 MiB before platform decode,
rejects malformed or animated image metadata, then decodes on a
composition-owned background coroutine. It publishes `Ready` only if decoded
pixel dimensions equal `PetDefinition.geometry`; otherwise it publishes
`Failed` and draws no atlas. Cancellation or input replacement cannot publish
an old result. The input is a mutable `ByteArray`: the caller keeps it stable
until the background snapshot is taken. Pass a new array instance to replace
content. One transient encoded copy is released after decode; one decoded
atlas is retained by the player state.

Static PNG, JPEG, GIF first frame, and static WebP VP8/VP8L/VP8X are the v1
image contract. Animated WebP is rejected consistently, including on newer
decoders that could display its first frame.

## Out of scope

The library has no network transport, URL parser, downloader, image authoring
QA, or filesystem sandbox against concurrent hostile mutation. Hosts handle
transport. `CodexPet` is a renderer, not an input parser.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/yet300/Pets/security).
Include the affected module/version, a minimal input reproducer, the expected
and actual typed outcome, and the host OS/filesystem where applicable.
