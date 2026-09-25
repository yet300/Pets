# Phase 1 — Apple Interop Audit (`:codex-pets-core`)

**Mode:** read-only adversarial audit. No production Kotlin code was changed.
**Gate: `REMEDIATION REQUIRED`** (P0 findings below block any ABI freeze).

## 0. Environment and reproducibility

| Item | Value |
|---|---|
| Kotlin | 2.4.20 (`gradle/libs.versions.toml`) |
| Gradle | 9.7.0 |
| Xcode | 27.0 (27A266a), iPhoneSimulator SDK 27.0 |
| Swift | 6.4 (`swift-driver 1.168.6`) |
| Host | macOS 27.0 arm64 |
| Test device | Booted simulator iPhone 17, iOS 27.0 (`79BF70F8-…`), execution via `xcrun simctl spawn booted <simulator-binary>` |
| Library targets | `iosArm64`, `iosSimulatorArm64`, framework base name `CodexPetsCore` |
| Audited artifact | `codex-pets-core/build/bin/iosSimulatorArm64/releaseFramework/CodexPetsCore.framework`, header SHA-256 `3ed395c0…288089` (950 lines) |

**Build note (blocking, see P2-3).** The checked-in `gradle/libs.versions.toml`
references versions `coil` and `androidx-lifecycle` from `[libraries]` without
declaring them, so **every** Gradle invocation fails catalog validation before
any task runs:

```
org.gradle.api.InvalidUserDataException
> Undefined version reference
    In version catalog libs, version reference 'coil' doesn't exist
```

To obtain a framework from "the repository's actual configuration", the repo
was copied to `/tmp/PetsAudit` (shadow copy, original untouched) and **only
there** the two missing inert versions were added (`androidx-lifecycle =
"2.9.4"`, `coil = "3.3.0"`; `:codex-pets-core` does not depend on either).
Fresh `linkReleaseFrameworkIosSimulatorArm64` in the shadow copy produced a
header **byte-identical** to the repo's prebuilt one (`diff` clean). All Swift
tests below link the freshly built shadow framework. No `src/`, `api/`, or
build-logic file was modified anywhere.

**Harness locations (all outside production sources):** Swift consumer
`/tmp/pets-swift-smoke/`, JVM Duration probe `/tmp/duration-probe/`
(Kotlin/JVM 2.4.20 stdlib — the same common `Duration.kt` source Native uses),
stdlib source `/tmp/Duration-2.4.0.kt` (JetBrains/kotlin tag `2.4.0`,
representation unchanged across 2.3.x → 2.4.x → master).

## 1. Duration internal representation (stdlib source fact)

`Duration` is `@JvmInline value class Duration internal constructor(private
val rawValue: Long)` with a **packed** representation, not nanoseconds:

```kotlin
private val value: Long get() = rawValue shr 1
private inline val unitDiscriminator: Int get() = rawValue.toInt() and 1
private fun isInNanos() = unitDiscriminator == 0
private fun isInMillis() = unitDiscriminator == 1
```

```kotlin
private fun durationOfNanos(normalNanos: Long) = Duration.fromRawValue(normalNanos shl 1)
private fun durationOfMillis(normalMillis: Long) = Duration.fromRawValue((normalMillis shl 1) + 1)
```

```kotlin
internal const val NANOS_IN_MILLIS = 1_000_000
internal const val MAX_NANOS = Long.MAX_VALUE / 2 / NANOS_IN_MILLIS * NANOS_IN_MILLIS - 1
internal const val MAX_MILLIS = Long.MAX_VALUE / 2
```

Consequences:

- Nanos-range durations (|value| ≤ ~4.6e18 ns, ≈ ±146 years): exported
  `rawValue = nanos << 1` (**2× nanoseconds**, LSB always 0). Only `ZERO`
  coincides (0 == 0).
- Millis-range durations (larger magnitudes): `rawValue = (millis << 1) + 1`
  (**odd**, completely different scale from nanos).
- Odd `rawValue`s are "denormalized" millis values the stdlib itself rejects
  under assertions; `isPositive()` is implemented as `rawValue > 0`, so even
  millis-`0` (`rawValue = 1`) tests **positive**.

So the implementation report's claim — *"finite Duration int64 values are
exact nanoseconds"* — is **false**. Answer to the audit's question: **(B)
packed raw representation**.

### 1b. Experimental raw-value table (JVM, Kotlin 2.4.20 stdlib, private `rawValue` via reflection)

| Kotlin Duration | `inWholeNanoseconds` | exported `raw` | `raw shr 1` | LSB | `raw == nanos`? |
|---|---|---|---|---|---|
| `0.nanoseconds` | 0 | 0 | 0 | 0 | true (only this one) |
| `1.nanoseconds` | 1 | **2** | 1 | 0 | **false** |
| `2.nanoseconds` | 2 | **4** | 2 | 0 | **false** |
| `1.microseconds` | 1000 | **2000** | 1000 | 0 | **false** |
| `1.milliseconds` | 1000000 | **2000000** | 1000000 | 0 | **false** |
| `500.milliseconds` | 500000000 | **1000000000** | 500000000 | 0 | **false** |
| `1680.milliseconds` (idle frame 0) | 1680000000 | **3360000000** | 1680000000 | 0 | **false** |
| `1.seconds` | 1000000000 | **2000000000** | 1000000000 | 0 | **false** |
| `(1.0/8.0).seconds` (parser 8 fps) | 125000000 | **250000000** | 125000000 | 0 | **false** |
| `1000.days` | 86400000000000000 | **172800000000000000** | 86400000000000000 | 0 | **false** |
| `200000.days` (millis storage) | 9223372036854775807 (saturated) | **34560000000001** | 17280000000000 (ms) | **1** | **false** |
| `Long.MAX_VALUE.nanoseconds` (millis storage) | 9223372036854000000 | **18446744073709** | 9223372036854 (ms) | **1** | **false** |

Kotlin/Native uses this same common source (only `durationAssertionsEnabled`
and decimal formatting are `expect`/`actual`), so the packing applies to the
iOS framework identically — confirmed at runtime in §5.

## 2. Generated Apple surface (exact signatures)

`PetAnimationKey` appears **zero times** in the 950-line header
(`grep -c PetAnimationKey == 0`). Every key position is erased to `id`
(Swift `Any`). Duration positions split: non-null → `int64_t`, nullable →
`id`. Verbatim excerpts:

```objc
// PetFrame: Duration unboxed to int64_t RAW value (CodexPetsCore.h:560-588)
__attribute__((swift_name("PetFrame")))
@interface CPCPetFrame : CPCBase
- (instancetype)initWithSpriteIndex:(int32_t)spriteIndex duration:(int64_t)duration
    __attribute__((swift_name("init(spriteIndex:duration:)")));
@property (readonly) int64_t duration __attribute__((swift_name("duration")));
@property (readonly) int32_t spriteIndex __attribute__((swift_name("spriteIndex")));
@end
```

```objc
// Playback: key erased to id, elapsed is int64_t RAW (CodexPetsCore.h:868-897)
__attribute__((swift_name("PlaybackKt")))
@interface CPCPlaybackKt : CPCBase
+ (CPCPetPlaybackSample *)samplePetAnimationDefinition:(CPCPetDefinition *)definition
    requestedAnimation:(id)requestedAnimation elapsed:(int64_t)elapsed
    __attribute__((swift_name("samplePetAnimation(definition:requestedAnimation:elapsed:)")));
+ (int32_t)staticIdleSpriteIndexDefinition:(CPCPetDefinition *)definition
    __attribute__((swift_name("staticIdleSpriteIndex(definition:)")));
@end
```

```objc
// PetPlaybackSample: BOTH key and nullable Duration erase to id (699-739)
__attribute__((swift_name("PetPlaybackSample")))
@interface CPCPetPlaybackSample : CPCBase
- (instancetype)initWithAnimation:(id)animation spriteIndex:(int32_t)spriteIndex
    nextFrameIn:(id _Nullable)nextFrameIn
    __attribute__((swift_name("init(animation:spriteIndex:nextFrameIn:)")));
@property (readonly) id animation __attribute__((swift_name("animation")));
@property (readonly) id _Nullable nextFrameIn __attribute__((swift_name("nextFrameIn")));
@property (readonly) int32_t spriteIndex __attribute__((swift_name("spriteIndex")));
@end
```

```objc
// PetAnimations: all 14 constants erase to id (280-307)
__attribute__((swift_name("PetAnimations")))
@interface CPCPetAnimations : CPCBase
@property (class, readonly, getter=shared) CPCPetAnimations *shared;
@property (readonly) id Bounce;
@property (readonly) id Failed;
@property (readonly) id Idle;
@property (readonly) id Jumping;
@property (readonly) id MoveLeft;
@property (readonly) id MoveRight;
@property (readonly) id Review;
@property (readonly) id Running;
@property (readonly) id RunningLeft;
@property (readonly) id RunningRight;
@property (readonly) id Sad;
@property (readonly) id Waiting;
@property (readonly) id Wave;
@property (readonly) id Waving;
@end
```

```objc
// PetAnimation.fallback + PetDefinition.animations erased (262-272, 539-552)
- (instancetype)initWithFrames:(NSArray<CPCPetFrame *> *)frames
    loopStart:(CPCInt * _Nullable)loopStart fallback:(id)fallback ...;
@property (readonly) id fallback;
- (instancetype)initWithId:(NSString *)id displayName:(NSString *)displayName
    description:(NSString *)description geometry:(CPCAtlasGeometry *)geometry
    frameCount:(int32_t)frameCount animations:(NSDictionary<id, CPCPetAnimation *> *)animations ...;
@property (readonly) NSDictionary<id, CPCPetAnimation *> *animations;
```

```objc
// Sealed outcomes export as protocols + concrete classes (656-686, 742-772)
__attribute__((swift_name("PetParseOutcome"))) @protocol CPCPetParseOutcome @end
@interface CPCPetParseOutcomeFailure : CPCBase <CPCPetParseOutcome>
- (instancetype)initWithReport:(CPCPetCompatibilityReport *)report ...;
@property (readonly) CPCPetCompatibilityReport *report; @end
@interface CPCPetParseOutcomeSuccess : CPCBase <CPCPetParseOutcome>
- (instancetype)initWithDefinition:(CPCPetDefinition *)definition
    spritesheetPath:(NSString *)spritesheetPath ...;
@property (readonly) CPCPetDefinition *definition;
@property (readonly) NSString *spritesheetPath; @end
// (PetSpritesheetPathOutcome mirrors this shape: Success(path:), Failure(error:).)
```

```objc
// Parser facade (619-653)
@interface CPCPetPackageParser : CPCBase
@property (class, readonly, getter=shared) CPCPetPackageParser *shared;
- (id<CPCPetParseOutcome>)parseManifestJson:(NSString *)manifestJson
    fallbackId:(NSString *)fallbackId spritesheet:(CPCSpritesheetInfo *)spritesheet
    __attribute__((swift_name("parse(manifestJson:fallbackId:spritesheet:)")));
- (id<CPCPetSpritesheetPathOutcome>)spritesheetPathOfManifestJson:(NSString *)manifestJson ...;
- (id<CPCPetParseOutcome>)parseManifestBytes:(CPCKotlinByteArray *)manifestBytes ...;
- (id<CPCPetSpritesheetPathOutcome>)spritesheetPathOfManifestBytes:(CPCKotlinByteArray *)manifestBytes ...;
@end
```

```objc
// Report: typed error classes, list properties (509-525)
@interface CPCPetCompatibilityReport : CPCBase
- (instancetype)initWithErrors:(NSArray<id<CPCPetCompatibilityError>> *)errors
    warnings:(NSArray<NSString *> *)warnings ...;
@property (readonly) NSArray<id<CPCPetCompatibilityError>> *errors;
@property (readonly) BOOL isCompatible;
@property (readonly) NSArray<NSString *> *warnings;
@end
```

Swift-imported types derived by intentional type-error reveals and used
throughout the probes:

- `PetAnimations.shared.Idle: Any` (all constants `Any`)
- `PetFrame(spriteIndex: Int32, duration: Int64)`, `.duration: Int64`
- `PlaybackKt.samplePetAnimation(definition:requestedAnimation:elapsed:)`,
  `requestedAnimation: Any!`, `elapsed: Int64`
- `PetPlaybackSample.animation: Any`, `.nextFrameIn: Any?`, `.spriteIndex: Int32`
- `PetDefinition.animations: [AnyHashable: PetAnimation]`
- `PetAnimation(frames: [PetFrame], loopStart: KotlinInt?, fallback: Any!)`
- No `PetAnimationKey` type; no `KotlinDuration` type (both: "cannot find in scope").

## 3. Swift smoke consumer — what was compiled and run

All files live in `/tmp/pets-swift-smoke/` (never in production sources),
compiled with
`xcrun swiftc -sdk <iphonesimulator> -target arm64-apple-ios27.0-simulator
-F <releaseFramework> -framework CodexPetsCore` and executed on the booted
simulator. Full sources are reproduced here so the audit is re-runnable.

### 3.1 `probe_main.swift` — COMPILED OK (one benign `#NoUsage` warning), ran to `DONE`

Covers task items A–E: Idle access, custom "dance" manifest parse, map
inspection, elapsed sweep 0/1/2/1000/1e6/5e8/1e9/packed-1680ms, playback calls,
failure-path outcome, path outcome, validator.

### 3.2 `probe_roundtrip.swift` — COMPILED OK; process ABORTED at R4 (finding, §6)

Box-key introspection (`responds(to:)`, `as? String`, `Mirror`), pass box key
vs `NSString` key to the sampler, `nextFrameIn` casts, Swift-side
`PetDefinition` construction with string keys.

### 3.3 `probe_stolen.swift` — COMPILED OK, ran to `DONE`

Rebuilds a `PetDefinition` from box keys laundered out of a parsed definition.

### 3.4 `probe_odd.swift` — COMPILED OK, ran to `DONE`

Odd-raw elapsed values `1000001`, `1999999` plus negative `-5`.

### 3.5 `probe_crash.swift` — COMPILED OK, ran WITHOUT crashing (finding, §5)

`PetFrame(spriteIndex:duration:)` with raw `2` then raw `1`.

### 3.6 Negative compile tests — all fail as documented in §6/`n1…n6`

`PetAnimationKey("dance")`, `Idle.value`, `let k: PetAnimationKey`,
`fr.duration.inWholeNanoseconds`, `KotlinDuration`, `-> Int64 { s.nextFrameIn }`.

## 4. Runtime results (simulator, verbatim highlights)

### Idle access (A)

```
A1 idle description: idle
A2 idle dynamic type: __NSCFString
A3 idle as? String: Optional("idle")
A4 Idle identical instance (===): false
A5 Idle isEqual: true
```

Direct value-class-typed members unbox to the underlying `NSString`: usable
*by accident*, untyped (`Any`), undiscoverable.

### Parse + map contents (C)

```
C1 outcome dynamic type: CPCPetParseOutcomeSuccess
C2 outcome is PetParseOutcomeSuccess; path=spritesheet.webp
C3 definition id=smoke frameCount=72
C4 animations count=15
C5 key dyntype=CPC_kobjcc0 key desc=PetAnimationKey(value=dance) frames=2
... (all 15 keys are CPC_kobjcc0 boxes, incl. idle + custom dance)
C6 lookup by Idle key object FAILED
C9 lookup by NSString("dance"): nil
```

Map keys coming **out of Kotlin through generics are opaque boxes**
(`CPC_kobjcc0`, the Native value-class box), a *different* representation
from the direct `Idle` accessor (`NSString`). Neither lookup direction works:
`NSString` keys miss boxed maps; the `Idle` object misses too (C6).

### Elapsed sweep on idle, definition from §C (E — the Duration proof)

Idle frame 0 is Kotlin `1680.milliseconds` (nanos 1680000000, packed raw
3360000000). Observed:

```
E raw=0:               sprite=0 nextFrameIn=Optional(1.68s)
E raw=1:               sprite=0 nextFrameIn=Optional(1.68s)      <- naive "1ns" == ZERO
E raw=2:               sprite=0 nextFrameIn=Optional(1.679999999s) <- 1 real ns = raw 2
E raw=1000:            sprite=0 nextFrameIn=Optional(1.679999500s) <- raw 1000 = 500ns
E raw=1000000:         sprite=0 nextFrameIn=Optional(1.679500s)   <- naive "1ms" == 500us
E raw=500000000:       sprite=0 nextFrameIn=Optional(1.43s)       <- naive "500ms" == 250ms
E raw=1000000000:      sprite=0 nextFrameIn=Optional(1.18s)       <- naive "1s" == 500ms
E raw=3360000000:      sprite=1 nextFrameIn=Optional(660ms)       <- packed 1680ms: EXACT frame advance
```

Even raws are interpreted as **2× nanoseconds** (callers get half the intended
time). The packed value for the full frame lands exactly on the next frame,
proving Kotlin reads the parameter as packed `rawValue`.

Odd-raw catastrophe (exact pre-run predictions confirmed):

```
Q raw=0:         sprite=0 next=Optional(1.68s)
Q raw=-5:        sprite=0 next=Optional(1.68s)    (negative coerces to 0: correct)
Q raw=1000001:   sprite=5 next=Optional(1.6s)     (naive ~1ms -> 500 SECONDS -> looped to frame 5)
Q raw=1999999:   sprite=3 next=Optional(441ms)    (naive ~2ms -> ~1000s -> frame 3)
```

`raw=1000001` is odd → millis storage, value `1000001>>1 = 500000` ms =
500 s ≫ 6.6 s idle loop → `500000 mod 6600 = 5000` ms → frame 5, remainder
1600 ms. Observed exactly `sprite=5 next=1.6s`. Any odd integer a Swift caller
passes as "nanoseconds" flips the unit bit and detonates by ~500000×.

### Pass-through keys (D)

```
D3 NSString-dance@0: sprite=0 anim=dance     (NSString param unboxes; Kotlin finds dance)
R2 viaBox: sprite=0 anim=idle animDyn=__NSCFString
R2 viaStr: sprite=0 anim=idle animDyn=NSTaggedPointerString
```

Function **parameters** statically typed `PetAnimationKey` unbox, so both a box
and an `NSString` cross the boundary and resolve correctly at runtime — but
only by luck of the ABI, with zero type safety.

### Construction from Swift (R4 — hard crash)

```
R4 = PetDefinition(... animations: ["idle": animIdle, "dance": animDance])
=> Uncaught Kotlin exception: kotlin.IllegalArgumentException:
   animations must contain idle after normalization
   ... Program will be terminated.  (SIGABRT, signal 6)
```

Preceded by the runtime warning: *"Function doesn't have or inherit @Throws
annotation and thus exception isn't propagated … Program will be
terminated."* `NSString` keys are **not** converted to `PetAnimationKey` by
the `NSDictionary → Map` bridge, the `require(containsKey(Idle))` invariant
fires, and Swift receives a **process kill, not a catchable error**. The only
working construction path is laundering unforgeable box keys out of a parsed
definition (`probe_stolen`: `S2 rebuilt … OK`, `S3 dance@packed250ms sprite=1`
correct, `S4 staticIdle=0`).

### Outcomes / reports (F/G/H — PASS)

```
F1 bad outcome type: CPCPetParseOutcomeFailure
F2 failure report compatible=false errors=1
F3 error dyntype=CPCPetCompatibilityErrorMalformedManifest message=invalid pet manifest: ...
F4 cast to MalformedManifest OK
G2 path=spritesheet.webp
H1 validate compatible=true errors=0 warnings=0
```

`as? PetParseOutcomeSuccess/Failure`, `.definition/.report`,
`.errors` iteration, and downcasts to concrete error classes all work:
"outcomes" are genuinely type-safe/navigable, not merely present.

### `nextFrameIn` opacity (R3)

```
R3 nfi dyntype=Optional<Any> desc=Optional(1.68s)
R3 nfi as? Int64=nil as? NSNumber=nil as? String=nil
```

Only `description` (Kotlin `toString`) is observable. The scheduling output is
write-only decoration from Swift.

### Degenerate raw (X)

```
X1 duration raw=2 OK readback=2 (packed 1ns)
X2 UNREACHABLE duration raw=1 readback=1     <- NO crash
```

`raw=1` is millis-`0` (denormalized ZERO the stdlib's own assertions reject),
yet `isPositive()` (`rawValue > 0`) lets it **pass** `PetFrame` validation,
creating a 0-length frame that silently corrupts sampler arithmetic instead of
failing fast.

## 5. Duration representation proof (verdict)

`Swift Int64 == duration.inWholeNanoseconds` is **false** for every finite
nonzero duration tested (12/12 JVM cases; 8/8 simulator cases). The exported
`int64_t` is Kotlin's packed `rawValue` (`value << 1 | unitDiscriminator`):
nanos-range values arrive doubled, millis-range values arrive as
`2×millis+1`, odd values misread by ~500000×, and sub-second scheduling
(`nextFrameIn`) is additionally sealed inside an opaque box. **Any API that
requires Swift callers to manufacture or interpret that raw value —
`PetFrame.duration`, `samplePetAnimation elapsed:`, `PetPlaybackSample.nextFrameIn`
— is classified unusable/unsafe as specified.** The "exact nanoseconds" claim
is refuted by source (§1), JVM table (§1b), and simulator runtime (§4) alike.

## 6. Custom key audit (verdict)

| Probe | Result |
|---|---|
| Type `PetAnimationKey` visible in Swift | **No** — `cannot find 'PetAnimationKey' in scope` (init and type positions); 0 mentions in header |
| Read `.value` / string of a key | **No** — `Any has no member 'value'`; box `responds(to:"value")==false`, `as? String==nil`, `Mirror` children 0; only `description` leaks (`PetAnimationKey(value=dance)`) |
| Construct arbitrary `"dance"` key | **No typed path.** `NSString("dance")` happens to unbox through *parameters* (D3) but this is untyped coincidence, and the same string **fails** as a map key and **kills the process** in `PetDefinition` init |
| Use built-in keys | Partially — `Idle` arrives as `NSString("idle")` and works in params, but **fails** as a lookup key against Kotlin-built maps (C6) |
| Use custom names back from Kotlin collections | **No** — keys are opaque `CPC_kobjcc0` boxes; no equality with strings, no extraction, no re-keying; reusable only as opaque tokens, and unforgeable for new maps |

The headline feature (open-ended animation names) is **not correctly usable
from Swift**: untyped on the way in, opaque on the way out, asymmetric between
direct members (`NSString`) and generic collections (boxes), and crashing
(`SIGABRT`, uncatchable) on the natural construction path.

## 7. Sealed outcomes (verdict)

**Usable = actually type-safe/navigable.** `PetParseOutcome` /
`PetSpritesheetPathOutcome` import as protocols with concrete
`Success`/`Failure` classes; `as?` downcasts, associated
`definition`/`report`/`path`/`error` accessors, `PetCompatibilityReport`
`errors`/`warnings`/`isCompatible`, and per-case error payloads
(`animation`, `index`, `fps`, `width/height`, `format`, `message`) all compiled
and ran correctly (§4 F/G/H). No finding; this is the interop pattern the rest
of the API should follow.

## 8. Collection interop (verdict)

- `Map<PetAnimationKey, PetAnimation>` → `[AnyHashable: PetAnimation]`: keys
  are opaque boxes; subscripting requires a hazardous `as! AnyHashable` cast
  of an `Any`; string-keyed lookup returns `nil`; `Idle`-object lookup returns
  `nil`; the map cannot be rebuilt from Swift with natural keys (SIGABRT).
  **The inline-class map key is indeed the more serious problem**: it poisons
  both directions, not just single parameters.
- `List<PetFrame>` → `[PetFrame]`: fine structurally; payload `duration`
  carries the P0 Duration flaw.
- Error/warning lists → `[any PetCompatibilityError]` / `[String]`: fully
  usable, typed downcasts verified.

## 9. Findings

- **P0-1 — Duration exports packed rawValue, not nanoseconds.** Refutes the
  "exact nanoseconds" claim. `PetFrame.duration` + `samplePetAnimation
  elapsed:` misinterpret every nonzero value Swift manufactures (2× in nanos
  range, unit-flip ~500000× for odd values, different scale in millis range);
  `nextFrameIn` is additionally an opaque box. All time-based APIs are
  unusable/unsafe from Swift until remediated.
- **P0-2 — `PetAnimationKey` erased to `Any` with split representation.**
  Direct members surface as `NSString`, generic-collection positions as opaque
  `CPC_kobjcc0` boxes with no accessor, no conversion, no introspection.
  Arbitrary custom keys cannot be constructed through any typed API.
- **P0-3 — Swift-side `PetDefinition` construction aborts the process.**
  `NSString` map keys are not bridged to `PetAnimationKey`; the `idle`
  invariant throws an uncaught Kotlin exception (no `@Throws` → termination,
  SIGABRT), uncatchable from Swift. Only key-laundering from parsed output works.
- **P1-1 — `nextFrameIn` is opaque (`Any?`, all casts nil).** Deterministic
  scheduling, a stated core improvement, is invisible to Swift hosts.
- **P1-2 — Map interop asymmetry.** Same logical key has two ObjC identities
  (`NSString` vs box) with broken equality across them; lookups fail both ways.
- **P2-1 — `description` renamed `description_`** (NSObject clash). Cosmetic.
- **P2-2 — `loopStart: KotlinInt?`** (boxed). Usable, uner­gonomic.
- **P2-3 — Repository does not build as checked in.** `gradle/libs.versions.toml`
  omits `coil` / `androidx-lifecycle` versions; all Gradle invocations fail
  catalog validation. Remediate so the framework (and this audit) is
  reproducible from a clean checkout.

## 10. Recommended remediation (proposals only — NOT implemented)

### Duration

- **Option A — keep `Duration` in Kotlin API + Apple-specific facade.**
  Add an Apple-visible parallel API taking/returning explicit units
  (e.g. `…Nanos: Int64` / `…Millis: Double`) implemented inside the module
  (where `Duration.fromRawValue` is reachable), and hide raw-`Duration`
  members from ObjC (`@HiddenFromObjC` where possible). *Trade-offs:* zero
  churn to shared Kotlin call sites; two APIs to maintain; the raw-`int64_t`
  members remain in the header unless hidden, so misuse stays possible; ABI
  impact is purely additive if the facade is new declarations.
- **Option B — replace public `Duration` with a normal cross-platform
  `PetDuration` type.** E.g. a regular (non-inline) class or an inline class
  with *documented nanos* semantics (`value class PetDuration(val nanos:
  Long)` — its `int64_t` *is* nanos by construction). *Trade-offs:* one
  honest cross-platform API; breaks Kotlin source/binary compatibility
  (signature type names change; ObjC shape may stay `int64_t`, which is a
  rare ObjC-stable/Kotlin-breaking migration); loses direct stdlib `Duration`
  operators (needs explicit conversions); millis-range/overflow semantics must
  be re-specified (saturation vs error).
- **Option C — expose primitive time units in the stable API.**
  `durationNanos: Long` (and where needed `durationMillis: Double`) as the
  stable surface; keep `Duration` for internal computation or as
  `@HiddenFromObjC` conveniences. *Trade-offs:* most explicit and hardest to
  misuse; most boilerplate/duplication; additive ABI if added as new members,
  breaking if it replaces existing ones. Pairs well with B (primitives now,
  newtype later).

Recommendation: **C for the frozen stable surface** (nanos `Long` is
unambiguous across every host), with B evaluated before the ABI freeze if a
richer cross-platform time type is wanted. A alone leaves the footgun
exported.

### `PetAnimationKey`

- **Option A — factory around the existing value class** (e.g.
  `PetAnimationKeys.of("dance")`). *Trade-offs:* additive, no Kotlin churn —
  but the return type still erases to `id`/`Any`, map keys stay opaque boxes,
  lookups stay broken, and nothing becomes typed. Fixes "construct" only in
  the weakest untyped sense. **Insufficient alone.**
- **Option B — convert to a regular immutable class** (e.g.
  `class PetAnimationKey(val value: String)` with value equality, or a data
  class). Exports as a real `CPCPetAnimationKey` with a typed `value`
  property; params, properties, and `NSDictionary` keys become consistently
  typed; equality works uniformly; construction is natural
  (`PetAnimationKey(value: "dance")`). *Trade-offs:* Kotlin source/binary
  break (copy semantics, `==` already value-based, destructuring if data
  class); one small allocation per key (negligible vs parse/sampler costs);
  requires auditing `hashCode/equals` stability for persisted maps (trivially
  satisfied by string equality). **This is the correct fix** and also resolves
  P0-3/P1-2, since bridged `NSString` keys and typed keys become interconvertible
  at an explicit boundary instead of two irreconcilable box identities.

Recommendation: **B** (regular class), plus decide the string-bridge rule
explicitly (convert `NSString` map keys on import, or reject with a typed
`Failure` — never `require` + abort). If B is rejected, A must be combined
with a typed box + explicit key-conversion layer, which is strictly more
machinery for a worse result.

### Cross-cutting

- Return typed `Failure` reports instead of `require` for anything reachable
  with foreign (Swift-bridged) inputs; reserve `require`/abort for genuine
  programmer errors on already-validated Kotlin objects. Annotate any
  remaining throwing public API so failures surface as `NSError`, never
  `SIGABRT`.
- Repair `gradle/libs.versions.toml` (declare the missing versions or drop the
  unused library entries) so the framework rebuilds from a clean checkout.
- Freeze the ObjC header into the audit trail on every release (this report's
  §2 pattern) and re-run the simulator probes in CI before any ABI-freeze
  claim.

## 11. Gate

**`REMEDIATION REQUIRED`** — P0-1 (Duration packing), P0-2 (key erasure), and
P0-3 (construction abort) each independently block a Swift consumer from using
the API correctly. Phase 2 must not begin; no production code was modified by
this audit.
