# Security Policy — codex-pets-kmp

The library accepts foreign pet packages and encoded images. Each public
untrusted-input path enforces its own boundary; passing data through one layer
does not grant another layer's guarantees.

## A. Directory and ZIP packages (`:codex-pets-io`)

These bounds and checks apply identically to Codex V1 and Pets KMP generic
packages (one shared implementation; only manifest discovery and identity
differ — generic requires `pet.json` with manifest-owned identity, Codex
keeps legacy `avatar.json` discovery with a caller fallback id).

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

`PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)` (Codex
V1) and `PetsKmpPackageParser.parse(manifestBytes, spritesheetBytes)`
(generic v1, manifest-owned identity, no fallback id) each check manifest ≤
64 KiB and encoded sheet ≤ 8 MiB before parsing/probing. They return typed
failures for malformed JSON, image metadata, animated WebP, invalid geometry,
and compatibility errors. The String and `spritesheetPathOf` overloads are
also bounded by UTF-8 manifest size. `parseTrustedMetadata` is an expert
seam: its caller supplies image facts, so it cannot prove that those facts
match any image bytes.

Generic v1 additionally enforces strict integer JSON syntax (unquoted
`-?(0|[1-9][0-9]*)` tokens for every integer field; see
`docs/spec/PETS_KMP_PACKAGE_V1.md` §3.1) before semantic validation.

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

## D. Pet host overlay (`:codex-pets-host`)

`PetHost` reuses the Compose public path, so §C protections still apply (8 MiB
cap, static-format validation, async decode, atlas-dimension check,
Loading/Ready/Failed). The host adds no image copy and no playback math.

Android `SystemOverlay` is privileged user-approved behavior:

- `SYSTEM_ALERT_WINDOW` is declared only in the opt-in `:codex-pets-host`
  library manifest (never in core/io/compose); only host consumers inherit it.
- Missing permission reports `PermissionRequired`, adds no window, and never
  crashes or silently falls back to in-app. The application owns the Settings
  request flow; the library never opens Settings from common code.
- The overlay window is sized near the pet (never fullscreen transparent), so
  only the pet area receives touch for dragging; no invisible fullscreen touch
  surface exists. Flags accept drag touch without stealing keyboard focus.
- No foreground service, no `FOREGROUND_SERVICE`/`BOOT_COMPLETED`/persistent
  notification/auto-start/resurrection in the library. The overlay lives only
  while the application process lives; it does not survive process death,
  force-stop, or reboot, and it never starts automatically.
- Dragging updates `WindowManager.LayoutParams.x/y` and `PetHostState`
  together (dp/px via current density); hiding calls `removeView` exactly once
  (tolerant of an already-detached race).

Desktop `SystemOverlay` is a small transparent undecorated always-on-top
window containing only the pet (never a fullscreen invisible window, never
"windowless rendering"). Hiding closes only the pet window. iOS reports
`Unsupported` and renders nothing rather than abusing PiP/Live
Activities/notifications/accessibility APIs.

## Out of scope

The library has no network transport, URL parser, downloader, image authoring
QA, or filesystem sandbox against concurrent hostile mutation. Hosts handle
transport. `CodexPet` is a renderer, not an input parser.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/yet300/Pets/security).
Include the affected module/version, a minimal input reproducer, the expected
and actual typed outcome, and the host OS/filesystem where applicable.
