# Codex Pets V2 compatibility research

Research date: 2026-09-28. Scope: first-party evidence for a **normative** V2 runtime contract for `codex-pets-kmp`. Confidence in the final verdict: **moderate**. This is research only; no runtime, example, or asset was changed.

## 1. Executive verdict

**B. V2 CONTRACT PARTIALLY VERIFIED — DO NOT IMPLEMENT**

The public `openai/codex` TUI and current official `openai/skills` Hatch Pet skill still specify the 9-row V1 surface. A locally installed Codex/ChatGPT desktop package contains remarkably specific V2 loader and renderer code: `spriteVersionNumber` 1 or 2, exact 1536×2288 V2 geometry, the standard animation table, and a 16-sector look calculation. That is much stronger than inferring a contract from Kodee's pixels. It is still insufficient to define a portable normative contract: the installed app's signature does **not** verify, the public Desktop source is unavailable, black-box UI access is blocked, and several validation and animated-image behaviors are not established. The code describes one observed desktop build, not a published cross-surface guarantee.

The supplied Kodee example remains **BLOCKED** unless the user explicitly accepts a non-normative compatibility mode. Do not add V2 APIs, a profile, parsing, geometry, or look-direction behavior from this report.

## 2. Official sources inspected and evidence classification

| Source | Tier / admissibility | Inspection |
|---|---|---|
| [`openai/codex`](https://github.com/openai/codex) current `main` | **A**, authoritative for its public CLI/TUI only | Shallow checkout plus full default-branch history. All ten `codex-rs/tui/src/pets/*` files; particularly [`model.rs`](https://github.com/openai/codex/blob/1cc7e2361237ce7244430ee1d581c77f95c57ac8/codex-rs/tui/src/pets/model.rs), [`catalog.rs`](https://github.com/openai/codex/blob/1cc7e2361237ce7244430ee1d581c77f95c57ac8/codex-rs/tui/src/pets/catalog.rs), and [`ambient.rs`](https://github.com/openai/codex/blob/1cc7e2361237ce7244430ee1d581c77f95c57ac8/codex-rs/tui/src/pets/ambient.rs). |
| [`openai/skills`](https://github.com/openai/skills) current `main` | **A**, official authoring guidance, not necessarily current Desktop implementation | Every file presently under [`skills/.curated/hatch-pet/`](https://github.com/openai/skills/tree/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/hatch-pet): `SKILL.md`, three references, eight Python scripts, `agents/openai.yaml`, and license. No templates, fixtures, or tests exist in that directory at this SHA. |
| `/Applications/ChatGPT.app/Contents/Resources/app.asar` | **Candidate A, not admitted as fully authenticated**; treated as supporting build-specific evidence | Readable package metadata and four extracted, minified JS modules. Bundle ID `com.openai.codex`, embedded Team ID `2DC432GLL2`, stapled notarization ticket, and package metadata identifying an `openai/codex/codex-apps/electron` build all indicate OpenAI origin. **However, `codesign --verify --deep --strict` returns “invalid signature (code or signature have been modified)”**. The exact inspected archive therefore lacks verified provenance. No access controls were bypassed. |
| [`openai/codex` issue #33220](https://github.com/openai/codex/issues/33220), [#33224](https://github.com/openai/codex/issues/33224), [#34240](https://github.com/openai/codex/issues/34240) | **C** for reporter claims; an OpenAI-hosted issue is not an OpenAI-maintained specification | Hypotheses and reported behavior only. No maintainer statement found that establishes a V2 contract. |
| Local `assets/kodee` package | Asset facts only; **not a specification** | Manifest, WebP header, decoded dimensions, hashes. |

The package's code observations are recorded below because they are independently inspectable and far more precise than community lore. The failed signature check prevents promoting this particular copy to an unqualified Tier A shipped artifact. Even if a pristine copy confirms the same code, it would specify the observed Desktop build, not the public TUI.

## 3. Exact source SHAs and application version

| Item | Exact identity |
|---|---|
| `openai/codex` HEAD | `1cc7e2361237ce7244430ee1d581c77f95c57ac8`; commit 2026-09-28 01:10:27 UTC. This supersedes the project's old `55543d87724bb66bdd51bde65254feb9b4c9ed10` pin. |
| `openai/skills` HEAD | `49f948faa9258a0c61caceaf225e179651397431`; commit 2026-06-23 19:36:12 −07:00. This happens to equal the old research pin. |
| Installed app | `CFBundleShortVersionString=26.924.22138`; `CFBundleVersion=11645`; bundle ID `com.openai.codex`; arm64. |
| App archive SHA-256 | `d0ba973179d2f717affd39e012b64a095464a54a51c6bccb7bc6b3d2a1cfba80`. |
| Inspected archive members | `.vite/build/src-BSSLXJxP.js` SHA-256 `50c7cf9c144594cfa6f33298e977f0b144976cc70c579f59fdc7242615c4af98`; `webview/assets/app-initial-d817715f10a0.js` SHA-256 `0c24aeb875e80ae2d41532534b227dd976c9a8f5644a4e10a3d2cb4ffa4047ff`; `webview/assets/avatar-overlay-native-page-131b06409d13.js` SHA-256 `0a7eb8ea8a608a393a7b1cdc3376e777fc9b99c3b58b3b28c9c5ae7ee79e8ce0`; `.vite/build/main-C5425b_s.js` SHA-256 `91a68c5f690e60033152a34cf0bf5c4234caeb9ddb47586fd3ef5b017bb29d64`. |

The archive members are minified, so the identifiers below (`FS`, `RS`, `V6`, `O_a`, `I_a`, `P_a`) are build-local symbols rather than stable APIs. The archive has a readable ASAR header and readable JavaScript payloads; no decryption or protected-resource access was used.

## 4. Current Codex TUI behavior

At the current Codex SHA, [`catalog.rs`](https://github.com/openai/codex/blob/1cc7e2361237ce7244430ee1d581c77f95c57ac8/codex-rs/tui/src/pets/catalog.rs) fixes 192×208 cells, eight columns, nine rows, and therefore 1536×1872. [`model.rs`](https://github.com/openai/codex/blob/1cc7e2361237ce7244430ee1d581c77f95c57ac8/codex-rs/tui/src/pets/model.rs) requires the decoded sheet dimensions to equal that exact size before optional `frame` geometry is checked. A 1536×2288 Kodee sheet fails this check even if its `pet.json` parses. Its `PetFile` has `id`, `displayName`, `description`, `spritesheetPath`, `frame`, and `animations`; it has **no** `spriteVersionNumber`. Serde's default unknown-field handling ignores that version key, so it cannot select V2. A search across all ten current TUI pet files found no V2 profile, 11-row constant, 2288-pixel sheet, or look-direction renderer; `image_protocol.rs`'s unrelated `SIXEL_CACHE_VERSION="v2"` is a terminal cache version.

The TUI's built-in table covers rows 0–8. Non-idle built-ins play their primary row three times, append the idle sequence, then loop at the appended idle start. Idle frame durations are 1680, 660, 660, 840, 840, and 1920 ms. Custom `animations` can override or extend the table, with uniform `1/fps` timing, optional `loop`, and a named `fallback`; this is the **TUI schema**, not evidence that Desktop accepts those fields. [`ambient.rs`](https://github.com/openai/codex/blob/1cc7e2361237ce7244430ee1d581c77f95c57ac8/codex-rs/tui/src/pets/ambient.rs) selects task states and implements the TUI's timing/fallback mechanics. It does not select a look direction.

## 5. Current OpenAI Hatch Pet behavior

The current official [`SKILL.md`](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/hatch-pet/SKILL.md), [`codex-pet-contract.md`](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/hatch-pet/references/codex-pet-contract.md), [`animation-rows.md`](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/hatch-pet/references/animation-rows.md), and [`validate_atlas.py`](https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/hatch-pet/scripts/validate_atlas.py) all specify **V1 only**: PNG or WebP, 1536×1872, eight columns, nine rows, 192×208 cells. The validator fixes `ROWS=9`, checks alpha/transparent unused cells, and only addresses those nine rows. The authoring guide lists row 0 idle as 280, 110, 110, 140, 140, 320 ms; the executing Desktop and TUI code multiply those values by six for playback. The skill has no `spriteVersionNumber`, V2, 2288, 11-row, gaze, or look-direction guidance. All current scripts and references were searched for the requested terms; the only “Version 2.0” hit is the Apache license version.

Classification: **A. V1 only** for the current public skill. It does not supply a V2 validator or establish V2 unused-cell requirements. Its wording that the app reads one fixed 9-row atlas is stale relative to the observed local app code; that is a surface/version difference, not proof that the public skill defines V2.

## 6. Official Desktop / ChatGPT evidence

The public `openai/codex` tree at the recorded SHA has no `codex-apps` directory; the installed package's `owl-electron-app.json` says it was packaged from `openai/codex/codex-apps/electron/out/ChatGPT-darwin-arm64/ChatGPT.app`. Thus the relevant Desktop source tree is **not publicly inspectable in the official repository**. The bundled, minified application code is inspectable, subject to the provenance caveat in §2.

Targeted searches of official OpenAI documentation and the two official repositories did not locate a published V2 pet specification. Search absence is only a scoped negative result; it is not proof that no private or unindexed specification exists.

The local package exposes two distinct paths:

- **Main-process local custom-pet loader:** `src-BSSLXJxP.js` defines `FS` (manifest parser), `RS` (image geometry), `BS`/`VS` (discovery/load), `WS`/`GS`/`KS` (PNG/WebP header probes). This is runtime implementation, not generation metadata.
- **Webview renderer:** `app-initial-d817715f10a0.js` defines `V6` (versioned geometry and row counts), `O_a` (standard animation frames), `b_a` (play sequence), `P_a` (CSS frame renderer), and `I_a` (look selection). `avatar-overlay-native-page-131b06409d13.js` calls the exported look selector and supplies its result to the pet component. These are runtime paths, not merely asset-generation hints.

The `requiredFramesByRow` arrays in `V6` describe V1 `[6,8,8,4,5,8,6,6,6]` and V2 `[6,8,8,4,5,8,6,6,6,8,8]`. Within the inspected renderer they are declarations; they are **not evidence of a pixel-level validator**. No official readable bundled V2 authoring schema or validator was identified.

## 7. Supplied Kodee facts

[`pet.json`](/Users/yet/development/Multiplatform/Pets/assets/kodee/pet.json) contains `id="kodee"`, `displayName="Kodee"`, `description`, `spriteVersionNumber=2`, and `spritesheetPath="spritesheet.webp"`. [`spritesheet.webp`](/Users/yet/development/Multiplatform/Pets/assets/kodee/spritesheet.webp) is a RIFF WebP image whose decoded dimensions are **1536×2288**. Its current SHA-256 is `c414c906b3536c4d0c3e166c00d9b852b247a033a0d0ee566c4396ce226cba4a`; manifest SHA-256 is `12a356ac934d22aaab3571d7d65ced26c3f1153470125d949902d7ca81937caa`. Dividing by the observed 192×208 cell size yields an **8×11 grid hypothesis**; the dimensions alone say nothing about the two added rows' semantics. No visual interpretation of the Kodee pixels was used to derive the contract.

## 8. Manifest V2 evidence

The observed Desktop `FS` parser has exact spelling `spriteVersionNumber` and accepts literal `1` or `2`, defaulting a missing field to `1`. `id` and `displayName` are optional nonempty strings; `description` is nullable/optional and trimmed; `spritesheetPath` is a nonempty trimmed string defaulting to `spritesheet.webp`. The loader reads `pet.json` beneath a `pets/<folder>` directory (with a legacy `avatars/<folder>/avatar.json` path), rejects a resolved spritesheet path outside that directory, and silently drops a folder on parse/read/geometry error. It takes the folder name as the custom selector. `frame` and `animations` are **not in this observed Desktop manifest parser**, despite their presence in TUI `PetFile`. The observed Desktop code uses fixed built-in animations. Unsupported version values fail the parser; a missing version selects 1. The remote install path can infer 1 or 2 from sheet dimensions when no version is supplied. The cloud-artwork read path also infers 1/2 from decoded dimensions.

The parser is implemented through a bundled schema library whose object type appears to strip unknown keys by default, but the precise unknown-field policy is **not independently verified** by a black-box case. Do not turn that inference into a portable requirement.

## 9. Geometry evidence

The observed Desktop loader `RS` requires width 1536 and height 1872 when version 1, or height 2288 when version 2. It recognizes PNG/WebP by bytes before checking dimensions. The renderer `V6` gives V2 `width=1536`, `height=2288`, `cellWidth=192`, `cellHeight=208`, `columns=8`, `rows=11`, and V1 the same width/cell/columns with `height=1872`, `rows=9`. The renderer positions CSS backgrounds with eight columns and the selected row count. This is explicit implementation evidence for the observed build; it is not derived from Kodee.

The current TUI remains 1536×1872, even for a manifest that declares version 2. The two official surfaces therefore **coexist with different capabilities**; V2 is not a universal Codex pet contract.

## 10. Standard animation evidence

The observed Desktop renderer has **one** `D_a` standard table for both V1 and V2: idle row 0 columns 0–5; running-right row 1 columns 0–7; running-left row 2 columns 0–7; waving row 3 columns 0–3; jumping row 4 columns 0–4; failed row 5 columns 0–7; waiting row 6 columns 0–5; running row 7 columns 0–5; review row 8 columns 0–5. `V6`'s V1 and V2 `requiredFramesByRow` arrays repeat those same first nine counts. This verifies **index/count semantics in this renderer**; it does not assert that the image bytes, poses, or artwork in rows 0–8 are identical across assets or versions.

The TUI defines the same standard row indices and counts but has its own manifest override API and host-state selection. A Desktop V2 implementation must not inherit TUI custom-animation behavior by assumption.

## 11. Timing evidence

The observed Desktop `O_a` table uses idle base durations 280, 110, 110, 140, 140, 320 ms and multiplies each by six for the actual idle playback sequence: **1680, 660, 660, 840, 840, 1920 ms**. Other rows use uniform duration except a longer last frame: running-right/left 120/220 ms, waving 140/280, jumping 140/280, failed 140/240, waiting 150/260, running 120/220, review 150/280. The same `D_a` is used regardless of version. These numbers match the TUI's executing default table, including the sixfold idle duration, and resolve the public skill's shorter idle authoring numbers for this build's playback.

For a selected look frame, `P_a` directly sets `backgroundPosition`; `I_a` assigns `frameDurationMs=0` to the look frame, but it is **not stepped as a timed animation**. On reducing motion, the renderer selects the first standard frame instead of running the sequence when no look frame overrides it. Exact system setting plumbing was not independently traced.

## 12. Loop and fallback evidence

In observed Desktop `b_a`, idle plays its six multiplied-duration frames and loops from index 0. Every other standard state concatenates its primary row **three times**, then appends the multiplied-duration idle frames; `loopStartIndex` equals the length of the three primary passes. In `P_a`, a timeout is scheduled using the currently displayed frame's `frameDurationMs`; after the last frame, it jumps to `loopStartIndex` and continues. A non-looping sequence instead holds its final frame, but the fixed standard table uses a loop start. This is an exact static-to-idle chain for the built-in Desktop states.

The observed Desktop manifest supplies no per-animation `fallback` property, so TUI custom fallback semantics, recursive fallback, one-hop fallback, and invalid fallback-name handling **cannot be asserted for Desktop V2**. The standard Desktop chain appends idle directly; calling that a manifest fallback would misstate the implementation. If a library exposes a generic fallback API for V2, its behavior lacks first-party Desktop evidence.

## 13. Look-direction evidence

Observed Desktop `I_a(rect, point, version)` returns `null` unless `version===2`. It computes `dx=point.x−(rect.left+rect.width/2)` and `dy=point.y−(rect.top+rect.height/2)` in the page's screen-like coordinate system (positive x right, positive y down). It returns `null` when `hypot(dx,dy)≤1` CSS pixel. Otherwise it computes `angle=(atan2(dx,−dy)·180/π+360)%360`, then `sector=round(angle/22.5)%16`, `column=sector%8`, `row=9+floor(sector/8)`. Thus **0° is up, angles advance clockwise**, row 9 covers sectors 0–7 (0° through 157.5°), and row 10 covers sectors 8–15 (180° through 337.5°). Nearest-sector rounding and modulo wrap are explicit; the `Math.round` boundary behavior applies at exact half-sector values. There is no interpolation. The 1-pixel neutral threshold is measured from the pet rectangle center, not a broad cursor-proximity deadzone.

In the observed overlay, the point passed to this function is selected from an active Computer Use cursor, a quick-chat caret point, or a received `avatar-overlay-computer-use-cursor-changed` point. The call is gated by having both a selected pet and a point. It is **not a general physical-mouse follower** in this path. A non-null look frame is passed as `lookFrame` to the pet component. `P_a` sets that frame's CSS background position and exits its effect, so look **replaces the displayed standard animation frame while present**; on `null`, the standard state animation effect runs. This proves a reachable code path, not observed activation for Kodee in this session. Reports of physical mouse motion alone failing to activate look rows are consistent with this code, but those user reports are non-normative.

## 14. Image-format evidence

Observed Desktop `GS` recognizes PNG signature/IHDR dimensions; `KS` recognizes RIFF/WEBP and dimensions from `VP8X`, `VP8L`, or `VP8` chunks. The local loader `RS` accepts those two formats by signature and dimensions, irrespective of the filename extension. The remote installer additionally requires response `Content-Type` of `image/png` or `image/webp` and enforces a 20 MiB byte limit. The official V1 authoring skill also prescribes PNG or WebP. These are **different paths**; the 20 MiB limit is not established as a local custom-pet limit.

The observed header probe does not reject a WebP `VP8X` animation flag, nor does it inspect APNG animation chunks. It hands image bytes to the webview as a data URL. **Animated-container acceptance and actual frame behavior are unconfirmed**: header acceptance alone cannot prove the browser will render a stable sprite atlas. No first-party V2 alpha or pixel-content validator was found in the local loader. For V2 unused cells, `requiredFramesByRow` gives the intended counts (all eight cells used in rows 9–10), but no enforced transparency rule for unused cells in rows 0–8 was located in the observed runtime. The public validator's rule applies to V1 only.

## 15. V1 coexistence evidence

Observed Desktop `FS` defaults a missing version to 1, accepts explicit 1 and 2, and rejects other values. `RS` binds each to its exact height; V1 and V2 are loaded through the same discovery path. The renderer chooses nine or eleven rows from the selected pet's version, and the look selector returns `null` for V1. Its built-in catalog declares version 2 for the bundled pets. The remote install and cloud-artwork paths sometimes derive version from decoded dimensions rather than trusting a manifest. This is build-specific Desktop coexistence evidence. The public TUI neither recognizes V2 nor changes its V1 validation based on `spriteVersionNumber`.

## 16. Black-box runtime evidence

**None obtained.** The installed app is available as readable files, but the computer-use interface refused access to `com.openai.codex` (“Computer Use is not allowed to use the app … for safety reasons”). No Kodee installation, state playback, look-angle transition, neutral transition, or animated-image experiment was run. No physical cursor behavior or exact threshold is claimed from observation. The source-level calculation in §13 remains build-specific evidence, not a black-box measurement.

## 17. Third-party claims (non-normative)

Issue reporters in [#33220](https://github.com/openai/codex/issues/33220) describe V2 geometry and a Windows loader omission; [#33224](https://github.com/openai/codex/issues/33224) describes Computer Use cursor versus physical mouse; [#34240](https://github.com/openai/codex/issues/34240) reports look rows not activating under physical mouse movement. These are **reporter observations**, not OpenAI-maintainer contract statements. Community validators, galleries, copied V2 documents, and third-party repositories were not used to set any contract value. Earlier suggestions that ChatGPT-generated “V2” may use a 9-row sheet were not verified here and must not be promoted to a rule. The observed app code is the sole basis for the exact angle calculation above; the Kodee asset and issue descriptions are not.

## 18. Confirmed contract table

“Tier A evidence” means publicly verifiable official source **for the stated surface**. “App code” in Tier B means the inspected local package with the signature-verification caveat in §2; it is not a validated OpenAI release artifact. `VERIFIED` is reserved for facts fully established by admitted Tier A evidence or the exact local fact itself; `PARTIALLY VERIFIED` identifies build-specific evidence with a provenance/scope gap.

| Property | Claimed value | Tier A evidence | Tier B evidence | Status |
|---|---|---|---|---|
| Current public TUI V2 support | None; V2 sheet rejected | Current `catalog.rs` and `model.rs` exact 1536×1872 and no version field | — | VERIFIED |
| Current public Hatch Pet V2 authoring | None; 9-row V1 only | Current skill, references, scripts, validator | — | VERIFIED |
| Desktop manifest version | Missing→1, 1→V1, 2→V2; other→reject | None publicly | App `FS` parser; remote inference path | PARTIALLY VERIFIED |
| Desktop V2 atlas | Exactly 1536×2288 | None publicly | App `RS`, `V6`; Kodee dimensions are corroborative asset facts only | PARTIALLY VERIFIED |
| Desktop V2 cell/grid | 192×208, 8×11 | None publicly | App `V6` | PARTIALLY VERIFIED |
| Standard rows 0–8 | Same row/index/count table for V1 and V2 renderer | TUI/skill establish V1 only | App shared `D_a`, `V6` counts | PARTIALLY VERIFIED |
| Standard timing | Shared per-state durations; idle multiplied ×6 | TUI executing V1 timing | App shared `O_a`, `b_a`, `P_a` | PARTIALLY VERIFIED |
| Standard loop/idle chain | Non-idle ×3 then appended idle loop | TUI V1 default table | App `b_a`, `P_a` | PARTIALLY VERIFIED |
| Desktop custom fallback | Generic `fallback`, one-hop/recursive behavior | TUI has a different custom-animation schema | No Desktop manifest field or fallback implementation found | UNCONFIRMED |
| Rows 9–10 | 16 look cells, sectors 0–15 | None publicly | App `V6` counts, `I_a` mapping | PARTIALLY VERIFIED |
| Angle convention | 0° up, clockwise, nearest 22.5°, row-major | None publicly | App `I_a` calculation | PARTIALLY VERIFIED |
| Neutral/override | ≤1 px from pet center→no look; non-null look overrides state display | None publicly | App `I_a`, `P_a` | PARTIALLY VERIFIED |
| Look trigger | Computer Use/caret point, not automatically physical mouse | None publicly | App overlay point selection and `I_a` call | PARTIALLY VERIFIED |
| Unused-cell rule | Transparent after each standard row's last used frame | Official V1 skill validator only | V2 count metadata; no V2 enforcement found | UNCONFIRMED |
| Image formats | PNG and WebP | Official V1 skill specifies both | App `GS`/`KS`/`RS`; remote MIME check | PARTIALLY VERIFIED |
| Animated-container behavior | Static atlas required / animation rejected | None for V2 | Header parser does not decide playback semantics | UNCONFIRMED |
| Desktop unknown manifest fields | Ignored, stripped, or rejected | None for Desktop | Schema-library default suggests stripping, untested | UNCONFIRMED |
| V1/V2 coexistence | Both in Desktop; V1 only in TUI | TUI V1 evidence | App `FS`, `RS`, row selector, look gate | PARTIALLY VERIFIED |
| Kodee package declaration | Version 2; 1536×2288 WebP | — | Local asset fact, not runtime evidence | VERIFIED |

## 19. Unconfirmed items and history audit

The remaining blockers are: cryptographically verified provenance of the inspected desktop build; published or otherwise authenticated V2 Desktop source; V2 unused-cell and alpha validation rules; behavior of animated PNG/WebP containers; precise Desktop unknown-field behavior; any Desktop custom-animation/fallback contract; and black-box confirmation that Kodee loads and that the look path activates under its actual supported input. The evidence also does not establish a cross-version promise that future V2 builds preserve this build's table. These are real gaps, not values to fill from the sheet's appearance.

Default-branch Git history was inspected, including deleted files. In `openai/codex`'s `codex-rs/tui/src/pets` history, the pet implementation was introduced at `95b332c82028b4ef0ee896ebcba2005180feefe3` (2026-05-12) and last touched at `5ee2bdf1e0e04064bc427ae31a49a01e8f7064df` (2026-09-19). `git log -G` over that path found **no** historical `spriteVersionNumber`, 2288, 11-row constant, or look-direction implementation. In `openai/skills`, Hatch Pet was introduced at `af9b54f235d0d56c6b4410be54d578b0fda4ddfc` (2026-05-01) and its workflow replaced at `c25113bf4c64c8dba6bfe61acf06051d79aa43f6` (2026-05-12), which deleted eight older helper scripts. `git log -G` over the complete Hatch Pet path, including those deleted files, found no V2/2288/look terms. Remote tag lookups for names containing `pet` or `hatch` returned no matches. A remote name-filtered branch check found no obviously relevant V2 implementation branch. **This is not an exhaustive audit of every one of thousands of public Codex branches, every tag's contents, or every file outside those paths.** No short-lived V2 contract was found in the searched histories.

## 20. Implementation recommendation

**Do not implement normative V2 support now.** The local desktop package supplies a useful, concrete research lead and a testable candidate model, but the package-verification failure and remaining behavior gaps prevent calling it a portable contract. Obtain a verifiably signed official app copy or public OpenAI source/documentation for the V2 implementation, then test the exact Kodee package through a permitted app UI and resolve the unknowns above. Only after that evidence exists should V2 parser/profile/API choices be specified. Until then, Kodee remains blocked unless the user accepts an explicitly non-normative compatibility mode.
