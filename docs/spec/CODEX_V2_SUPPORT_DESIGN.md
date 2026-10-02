# Explicit Codex V2 library support — proposed design

Date: 2026-10-02. Status: approved by the user on 2026-10-02: "Yes, use your recommendation".

## Recommendation and alternatives

Recommend a separate explicit Codex V2 parser and loader. It preserves the existing Codex V1 ABI and semantics, while making the version boundary reviewable. Expanding the historic V1 parser would change already-locked acceptance rules. Converting V2 to Pets KMP JSON only would not provide the requested library-level Codex support.

## Evidence and scope

The installed first-party Work Pets plugin 0.1.6 `references/sprite-sheet-contract.md` specifies a transparent PNG/WebP at 1536×2288, eight columns and eleven rows with 192×208 cells. Rows 0–8 retain nine standard states; rows 9–10 are sixteen clockwise look poses: zero up, 90 right, 180 down, 270 left. This supersedes the previous absence of first-party V2 authoring-layout evidence. It does not publish the Desktop manifest/runtime API. Existing build-specific runtime research supplies standard timings and look sector selection; document those separately from authoritative artwork geometry.

Implement an explicitly documented library compatibility contract. Require literal JSON integer `spriteVersionNumber: 2`; accept the existing Kodee metadata fields. Use fixed V2 geometry, the existing verified standard built-in sequences, and sixteen explicit held look-animation keys. Accept bounded static PNG/WebP only. Unknown harmless manifest fields are ignored as a stated library policy; reject `frame`/`animations` overrides rather than pretending the TUI V1 override schema is a Desktop V2 contract. Preserve metadata fallback identity. Do not validate artwork pixels or claim an official Desktop-equivalent validator.

## Modules and data flow

- `pets-core`: additive `CodexV2` profile, `CodexV2PetPackageParser`, typed V2 outcome/report/errors. Successful parsing produces ordinary `PetDefinition`, 88 frame capacity, default idle, standard keys plus held look poses. Existing V1 parser/validator remains untouched.
- `pets-io`: explicit V2 directory/ZIP entry points reuse existing bounded transport, path confinement, CRC, duplicate and traversal enforcement. V2 errors remain typed. This does not establish support for an unknown `.codex-pet` container.
- Compose/hosts: use existing normalized definition/player/rendering. Provide an optional pure look-selector helper so applications may choose a pose; do not install platform-global cursor tracking.
- Example: route an explicit V2 manifest to the new V2 parser; same pager, preview, action chips and floating host.

## Optional look selection

Expose a pure function mapping finite dx/dy offsets to one of sixteen look keys, clockwise from up using nearest 22.5-degree sector. A displacement at or below one logical unit yields no override; non-finite input yields no override. This policy follows the observed Desktop calculation and is documented as the library's optional selector. Callers control point collection and when to restore their selected standard animation.

## Validation and acceptance

First lock existing V1 and generic tests. Write V2 tests before implementation: exact Kodee manifest/sheet succeeds; explicit version missing/wrong/string/unknown fails; V1-sized/malformed/animated/wrong-format sheet fails; custom geometry/animation overrides fail with typed diagnostics; metadata/fallback and all 16 indices/angles/neutral boundaries pass. Exercise ordinary player rendering and secure directory/ZIP success and malicious-package regressions. Run JVM/Native suites, Android/iOS builds, and ABI checks/dumps for only intentional additive API changes. Verify V2 import/actions/floating host on desktop and Android where available.

No publication/tagging. Keep the V1 remediation commit separate from V2 implementation.
