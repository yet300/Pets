package com.yet.pets.core

/**
 * Result of sampling an animation at one instant. All time is plain
 * nanoseconds (`Long`); no packed representations cross this API.
 *
 * @property animation the animation actually evaluated (post fallback hop).
 * @property spriteIndex atlas sprite index to display.
 * @property nextFrameInNanos nanoseconds until the next frame change, or `null`
 *   when the resulting state is static (looping single frame, or a completed
 *   one-shot whose fallback is itself static). Never negative.
 */
public data class PetPlaybackSample(
    public val animation: PetAnimationKey,
    public val spriteIndex: Int,
    public val nextFrameInNanos: Long?,
)

/**
 * First frame of the definition-owned default animation for reduced-motion /
 * static previews. Pure so every host pins the same frame. Generic: uses
 * [PetDefinition.defaultAnimationKey], never a hard-coded `idle`.
 */
public fun staticDefaultSpriteIndex(definition: PetDefinition): Int =
    definition.animation(definition.defaultAnimationKey)?.frames?.first()?.spriteIndex ?: 0

/**
 * First idle frame for reduced-motion / static previews.
 *
 * Codex-compatibility wrapper: delegates to [staticDefaultSpriteIndex].
 * For Codex V1 definitions the default animation is `idle`, so behavior is
 * identical. Generic callers should use [staticDefaultSpriteIndex].
 */
@Deprecated(
    "Codex-specific wrapper; use staticDefaultSpriteIndex for generic pets.",
    ReplaceWith("staticDefaultSpriteIndex(definition)"),
)
public fun staticIdleSpriteIndex(definition: PetDefinition): Int =
    staticDefaultSpriteIndex(definition)

/**
 * Pure animation sampler.
 *
 * Generic runtime with Codex animation-model parity and deterministic host
 * scheduling:
 * - unknown requested keys resolve to the definition-owned default animation
 *   (generic runtime playback policy for an unknown requested key; separate
 *   from [PetAnimation.fallback], which is a declared animation transition),
 * - prefix (`0 until loopStart`) plays once, suffix loops via duration modulo,
 * - a completed non-looping animation with `fallback == null` (the generic
 *   one-shot) holds the final frame of the SELECTED animation with
 *   `nextFrameInNanos == null`,
 * - a completed non-looping animation with a non-null fallback performs AT
 *   MOST ONE fallback hop (no recursion: the fallback's own completion is
 *   never inspected),
 * - the fallback is evaluated with the SAME original elapsed clock (never reset),
 * - scheduling ([PetPlaybackSample.nextFrameInNanos]) is our deterministic
 *   improvement: a non-looping single frame reports the remaining nanos to its
 *   fallback transition instead of the reference host's "no wake-up" quirk,
 * - a dangling non-null fallback on a manually-built (validator-bypassed)
 *   definition resolves to the definition default to stay total; parser output
 *   can never dangle because fallback existence is validated (deliberate
 *   hardening, not parity).
 *
 * For Codex V1 definitions the default animation is `idle`, so the exact
 * Codex V1 semantics (unknown -> idle, fallback -> idle) are preserved.
 *
 * Elapsed handling is total and documented: `elapsedNanos <= 0` coerces to zero
 * (negative is impossible on the reference monotonic clock; clamping is our
 * hardening), and `Long.MAX_VALUE` saturates deterministically. All sums use
 * saturating `Long` arithmetic and never wrap; modulo divisors are always
 * positive. Complexity is O(frames) — never proportional to elapsed.
 */
public fun samplePetAnimation(
    definition: PetDefinition,
    requestedAnimation: PetAnimationKey,
    elapsedNanos: Long,
): PetPlaybackSample {
    val defaultKey = definition.defaultAnimationKey
    val selectedKey = definition.animation(requestedAnimation)?.let { requestedAnimation }
        ?: defaultKey
    val selected = definition.animation(selectedKey) ?: definition.animation(defaultKey)
        ?: return PetPlaybackSample(defaultKey, 0, null)
    val elapsed = if (elapsedNanos <= 0L) 0L else elapsedNanos

    if (selected.loopStart == null && elapsed >= totalNanos(selected)) {
        val fallbackKey = selected.fallback
        if (fallbackKey == null) {
            // Generic one-shot: play frames once, then hold the final frame of
            // the SELECTED animation forever. No transition, no wake-up.
            return frameAt(selected, selectedKey, elapsed)
        }
        val fallback = definition.animation(fallbackKey) ?: definition.animation(defaultKey)
        val evaluatedKey = if (definition.animation(fallbackKey) != null) fallbackKey else defaultKey
        if (fallback == null) return PetPlaybackSample(defaultKey, 0, null)
        return evaluate(fallback, evaluatedKey, elapsed)
    }
    return evaluate(selected, selectedKey, elapsed)
}

private fun totalNanos(animation: PetAnimation): Long {
    var total = 0L
    for (frame in animation.frames) {
        total = saturatingAdd(total, frame.durationNanos)
    }
    return total
}

private fun saturatingAdd(a: Long, b: Long): Long =
    if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

private fun evaluate(
    animation: PetAnimation,
    key: PetAnimationKey,
    elapsedNanos: Long,
): PetPlaybackSample {
    val loopStart = animation.loopStart?.takeIf { it < animation.frames.size }
    if (loopStart != null) {
        if (animation.frames.size == 1) {
            // Looping single frame: static, no scheduled change.
            return PetPlaybackSample(key, animation.frames[0].spriteIndex, null)
        }
        val total = totalNanos(animation)
        var prefix = 0L
        for (index in 0 until loopStart) {
            prefix = saturatingAdd(prefix, animation.frames[index].durationNanos)
        }
        val loopDuration = total - prefix // > 0: every frame duration is positive
        val effective = if (elapsedNanos >= total && loopDuration > 0) {
            prefix + ((elapsedNanos - prefix) % loopDuration)
        } else {
            elapsedNanos
        }
        return frameAt(animation, key, effective)
    }
    // One-shot reached only pre-completion here (completion hops before calling).
    return frameAt(animation, key, elapsedNanos)
}

private fun frameAt(
    animation: PetAnimation,
    key: PetAnimationKey,
    elapsedNanos: Long,
): PetPlaybackSample {
    var remaining = elapsedNanos
    for (frame in animation.frames) {
        val frameNanos = frame.durationNanos
        if (remaining < frameNanos) {
            return PetPlaybackSample(key, frame.spriteIndex, frameNanos - remaining)
        }
        remaining -= frameNanos
    }
    // Only reachable if elapsed >= total on a one-shot path: hold the last frame.
    val last = animation.frames.last()
    return PetPlaybackSample(key, last.spriteIndex, null)
}
