# Public API Proposal (pre-implementation)

Module split: `core` (pure) · `io` (okio) · `network` (Ktor, optional, over io) ·
`compose` (renderer) · `desktop` (window helpers). Package root `com.yet.pets.*`.
`explicitApi()` strict from Phase 1. No `ImageBitmap` outside `compose`/`desktop`.

Core dependency budget: Kotlin stdlib + `kotlinx-serialization-json` only.
No coroutines in core: there are no loaders in core (all I/O lives in
`io`/`network`).

> **Phase 1 Apple Interop Amendment.** Empirical Apple-interop results changed
> the public representation (not the upstream contract): public
> `kotlin.time.Duration` is forbidden (it exports its packed `rawValue`, not
> nanoseconds) — all public time is explicitly named nanos `Long`; the
> value-class `PetAnimationKey` became a regular class; the public animation
> `Map` became an interop-safe lookup API; invariant-heavy constructors are
> internal. `kotlin.time.Duration` remains usable internally only and never
> appears in public signatures.

## 1. Layer separation

| Layer | Types | Notes |
|---|---|---|
| Raw serialized manifest | `CodexPetManifest`, `FrameSpec`, `AnimationSpec` (`@Serializable`, unknown-field tolerant) | mirrors CLI `PetFile` 1:1, incl. `loop` rename |
| Normalized runtime | `PetDefinition`, `PetAnimation`, `PetFrame`, `PetAnimationKey`, `AtlasGeometry` | CLI V1 profile only; no V2 types in v1 |
| Loading | `PetLoader.loadPetDirectory/loadPetZip` | `io` module, okio `Path` |
| Runtime compatibility validation | `CodexCompatibilityValidator` → `PetCompatibilityReport` | pure core, reproduces machine-enforced CLI rules; used by loaders |
| Authoring QA validation | `CodexAuthoringValidator` → `PetAuthoringReport` | hatch-pet QA recommendations; advisory only, never gates loading |
| Compose | `PetPlayerState`, `rememberPetPlayerState`, `CodexPet()` | single atlas decode, region draws |
| Desktop | `PetOverlayWindow`, drag/placement helpers | thin over compose |

## 2. Core API (implemented in Phase 1)

```kotlin
// Regular immutable class: natural Swift type, constructor, and value equality.
// Arbitrary names accepted without validation.
class PetAnimationKey(val value: String)

object PetAnimations {
    val Idle = PetAnimationKey("idle")
    // ... RunningRight/Left, Waving, Jumping, Failed, Waiting, Running, Review,
    // plus CLI aliases MoveRight/MoveLeft/Wave/Bounce/Sad
}

data class AtlasGeometry internal constructor(
    val atlasWidth: Int, val atlasHeight: Int,
    val columns: Int, val rows: Int,
    val cellWidth: Int, val cellHeight: Int,
) {
    val frameCapacity: Long
    // Safe public geometry: null for out-of-range indices. Deterministic and
    // documented; public callers never get an exception or silent wrap.
    fun sourceRectForOrNull(spriteIndex: Int): IntRect?
}

// Core-owned plain rect (left/top/width/height Ints). Core never imports Compose.
data class IntRect(val left: Int, val top: Int, val width: Int, val height: Int)

// Internal unchecked fast path for validated playback indices (same math as
// sourceRectForOrNull, no null branch). Not public API.
internal fun AtlasGeometry.sourceRectFor(spriteIndex: Int): IntRect

class PetFrame internal constructor(
    val spriteIndex: Int,
    val durationNanos: Long,   // explicit nanos; internal Duration math only
)

class PetAnimation internal constructor(
    frames: List<PetFrame>,
    val loopStart: Int?,              // null = one-shot, then hold + fallback (CLI parity)
    val fallback: PetAnimationKey,    // default Idle
) {
    val frames: List<PetFrame>  // defensive snapshot
}

// Normalized runtime data ONLY. No diagnostics: parse/load/validation warnings
// live in PetCompatibilityReport / PetAuthoringReport / PetLoadOutcome, never here.
// No public animation map: the lookup below is the interop-safe surface.
class PetDefinition internal constructor(
    val id: String, val displayName: String, val description: String,
    val geometry: AtlasGeometry,
    val frameCount: Int,
    animations: Map<PetAnimationKey, PetAnimation>, // internal; snapshotted
) {
    val animationKeys: List<PetAnimationKey>  // sorted by key value (deterministic)
    fun animation(key: PetAnimationKey): PetAnimation?
    fun animation(name: String): PetAnimation?
}

// Pure player: Codex animation-model parity with deterministic host scheduling
// (see §2.1). Resolves requested key (unknown -> idle), evaluates prefix/loop
// sections, performs AT MOST ONE fallback hop, and reports both the current
// sprite and the nanos until the next frame change.
data class PetPlaybackSample(
    val animation: PetAnimationKey,  // the animation actually evaluated (post-hop)
    val spriteIndex: Int,
    val nextFrameInNanos: Long?,     // null = static; never negative
)

fun samplePetAnimation(
    definition: PetDefinition,
    requestedAnimation: PetAnimationKey,
    elapsedNanos: Long,              // literal nanoseconds; <= 0 coerces to zero
): PetPlaybackSample

// Reduced-motion / static preview: first idle frame, no timer. Belongs in core
// as a pure helper so every host pins the same frame.
fun staticIdleSpriteIndex(definition: PetDefinition): Int
```

`sourceRectForOrNull` is the only public geometry entry point: invalid indices
return null by contract. Validated internal playback paths may use the internal
unchecked helper for performance. Core never imports Compose; the renderer
converts `IntRect` to Compose geometry.

No `LookDirection`, no `lookDirection` field, no V2 sketch types: V2 has no
first-party source, so v1 public API carries no V2 concepts. Later animation
names (including any future look states) fit the existing open
`PetAnimationKey` model without breaking changes.

### 2.1 Fallback semantics: exactly ONE hop (normative)

The CLI reference (`ambient.rs::current_animation`) does NOT recursively
traverse fallback chains. Its effective behavior, which `samplePetAnimation`
reproduces for the animation model:

1. Resolve the requested key, falling back to `idle` if absent.
2. If that selected animation is non-looping AND its total duration has elapsed:
   resolve exactly that animation's `fallback` and use it.
3. Evaluate the selected/fallback animation with the SAME original elapsed clock
   (not reset to zero) — a fallback with its own prefix/loop structure starts
   mid-track; far-future `elapsed` lands deterministically inside it.
4. STOP. Never inspect whether the fallback itself has completed and should fall
   back again. A → B → C with A and B non-looping and elapsed beyond both still
   selects **B at the same elapsed time**, never C.

Fallback cycles therefore need no runtime traversal or cycle detection; only
reference-existence validation (every `fallback` names an existing animation,
`idle` guaranteed present).

### 2.2 Scheduling: deterministic improvement, not TUI-scheduler parity

The reference `current_animation_frame()` returns no next-frame delay for
animations with ≤ 1 frame, so a single-frame non-looping animation never
schedules its own fallback wake-up in the TUI host. That is a host scheduling
quirk, and we deliberately do NOT copy it:

- If a non-looping single-frame animation has not yet reached its duration,
  `nextFrameInNanos` is the remaining time until its fallback transition.
- Once elapsed ≥ total duration, the normal single fallback hop applies.
- Post-hop, `nextFrameInNanos` follows the evaluated animation (null only when the
  resulting state is itself static).

Wording rule for docs/tests: **"Codex animation-model parity with deterministic
host scheduling"** — selection/frame semantics match the reference; scheduling
(`nextFrameInNanos`) is our deterministic improvement.

## 3. `Result` policy — do NOT use Kotlin `Result` in public API

- Kotlin `Result` is a value class with throw-on-unwrap semantics, poor Swift
  interop (unusable as a return type through Kotlin/Native), and forces
  exception-style handling on callers. Rejected for all public signatures.
- Instead: explicit sealed outcomes per layer, e.g.

```kotlin
sealed interface PetLoadOutcome {
    // Encoded spritesheet bytes at the handoff (bounded by the 8 MiB sheet limit).
    // No cross-module asset abstraction: compose decodes these into exactly one
    // ImageBitmap and must NOT depend on io. No Okio types in core or compose.
    data class Success(val definition: PetDefinition, val spritesheetBytes: ByteArray) : PetLoadOutcome
    data class Failure(val errors: List<PetLoadError>) : PetLoadOutcome
}
sealed interface PetLoadError { data class MissingManifest(...); data class BadDimensions(...);
    data class PathEscape(...); data class AmbiguousPackage(...); data class LimitExceeded(...); ... }
```

- Runtime compatibility validation returns `PetCompatibilityReport(errors, warnings)`;
  loading composes validation + I/O into `PetLoadOutcome`. Authoring QA returns a
  separate `PetAuthoringReport` advisory object that never appears in the load
  path. Unknown manifest fields are ignored by default (CLI serde parity);
  `kotlinx.serialization` does not report ignored-key names, and no custom
  unknown-key tracking is added (no demonstrated value). Internal code may throw;
  public boundaries convert to outcomes. No `getOrThrow()` in samples/docs for
  untrusted packages (see §6).

## 4. IO API

Raw serialization DTOs (`CodexPetManifest`, `FrameSpec`, `AnimationSpec`) are
**internal**: they mirror an upstream implementation detail that may evolve, and
no consumer use case justifies the ABI commitment. Public surface = normalized
stable models + parsing/loading APIs.

```kotlin
data class PetPackageLimits( /* defaults per §5.4 of ARCHITECTURE.md */ )
fun loadPetDirectory(dir: Path, limits: PetPackageLimits = ...): PetLoadOutcome
fun loadPetZip(
    bytes: ByteArray,
    fallbackId: String = "pet",   // identity source when the archive is anonymous (see §4.1)
    limits: PetPackageLimits = ...,
): PetLoadOutcome
// Core parsing facade (implemented in Phase 1 as PetPackageParser; raw DTOs stay internal):
// - spritesheetPathOf(manifestJson|Bytes): PetSpritesheetPathOutcome — effective path
//   (trimmed value or "spritesheet.webp"); no path safety checks (io's job).
// - parse(manifestJson|Bytes, fallbackId, spritesheet: SpritesheetInfo): PetParseOutcome —
//   Success(definition, spritesheetPath) or Failure(PetCompatibilityReport).
// Standalone re-check: CodexCompatibilityValidator.validate(definition, spritesheet).
// normalizePetIdentity(manifestId, displayName, description, fallbackId): PetIdentity.
// IO-OWNED (lives in :codex-pets-io, never in core): decodes image bytes into facts.
interface SpritesheetInfoProbe { fun probe(bytes: ByteArray): SpritesheetInfo }
```

### 4.1 Deterministic identity normalization (v1)

The CLI falls back to the pet directory name for absent `id`/`displayName`; byte
inputs have no directory, so:

```text
manifestId   = trimmed non-empty manifest.id               (else null)
fallbackId   = directory basename              (loadPetDirectory)
             | top-level package dir name      (nested ZIP layout)
             | loadPetZip fallbackId parameter (root-layout ZIP; default "pet")
             | URL path filename minus ".zip"  (loadPetZipFromUrl, post-redirect;
                                               default "pet" when absent/blank)
             | "pet"                            (last resort — never random)

pet.id          = manifestId ?: fallbackId
pet.displayName = trimmed non-empty manifest.displayName ?: manifestId ?: fallbackId
description     = trimmed manifest description ?: ""
```

Manifest id always wins over any fallback. Blank/whitespace-only values are
treated as absent everywhere. No random IDs.

## 5. Network / Compose / Desktop API

```kotlin
// network — pet-oriented URL loading. v1 semantic: HTTPS URL -> ZIP pet package
// -> bounded download -> io ZIP parser. Depends on io (core <- io <- network),
// so Ktor stays out of every consumer that does not load from URL.
// suspend, cancellable, https-only (redirects re-validated), 60 s timeout.
// Remote cap = PetPackageLimits max compressed archive bytes (16 MiB default);
// DownloadPolicy may LOWER it, never raise it above the archive hard limit.
// This is remote CUSTOM packages, not OpenAI's built-in CDN (4 MiB image cap
// stays in compatibility research only).
suspend fun loadPetZipFromUrl(
    url: String,
    limits: PetPackageLimits = PetPackageLimits.Default,
    policy: DownloadPolicy = DownloadPolicy.Default,
): PetLoadOutcome

// compose — ONE atlas ImageBitmap per pet, subregion draws, lifecycle-safe.
// Named PetPlayerState (not PetState) to avoid collision with pet/animation-state vocabulary.
@Composable fun rememberPetPlayerState(definition: PetDefinition, spritesheetBytes: ByteArray): PetPlayerState
@Composable fun CodexPet(state: PetPlayerState, animation: PetAnimationKey, modifier: Modifier = ...)
class PetPlayerState {
    val currentSample: PetPlaybackSample  // driven internally by core samplePetAnimation + recompose timer
    fun pinToIdle()                       // reduced-motion: static first idle frame, timer stopped
}

// desktop — thin overlay helpers over compose
@Composable fun PetOverlayWindow(state: PetPlayerState, ...)
```

## 6. Primary README example (no `getOrThrow`, untrusted input safe)

```kotlin
when (val outcome = loadPetZip(zipBytes)) {
    is PetLoadOutcome.Success -> CodexPet(
        state = rememberPetPlayerState(outcome.definition, outcome.spritesheetBytes),
        animation = PetAnimations.Idle,
    )
    is PetLoadOutcome.Failure ->
        PetLoadErrorPanel(errors = outcome.errors) // never crash on foreign zips
}
```
