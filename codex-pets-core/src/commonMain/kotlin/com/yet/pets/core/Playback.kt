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
 * First idle frame for reduced-motion / static previews. Pure so every host
 * pins the same frame.
 */
public fun staticIdleSpriteIndex(definition: PetDefinition): Int =
    definition.animation(PetAnimations.Idle)?.frames?.first()?.spriteIndex ?: 0

/**
 * Pure animation sampler.
 *
 * Codex animation-model parity with deterministic host scheduling:
 * - unknown requested keys resolve to `idle`,
 * - prefix (`0 until loopStart`) plays once, suffix loops via duration modulo,
 * - a completed non-looping animation performs AT MOST ONE fallback hop
 *   (no recursion: the fallback's own completion is never inspected),
 * - the fallback is evaluated with the SAME original elapsed clock (never reset),
 * - scheduling ([PetPlaybackSample.nextFrameInNanos]) is our deterministic
 *   improvement: a non-looping single frame reports the remaining nanos to its
 *   fallback transition instead of the reference host's "no wake-up" quirk,
 * - a dangling fallback on a manually-built (validator-bypassed) definition
 *   resolves to `idle` to stay total; parser output can never dangle because
 *   fallback existence is validated (deliberate hardening, not parity).
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
    val selectedKey = definition.animation(requestedAnimation)?.let { requestedAnimation }
        ?: PetAnimations.Idle
    val selected = definition.animation(selectedKey) ?: definition.animation(PetAnimations.Idle)
        ?: return PetPlaybackSample(PetAnimations.Idle, 0, null)
    val elapsed = if (elapsedNanos <= 0L) 0L else elapsedNanos

    if (selected.loopStart == null && elapsed >= totalNanos(selected)) {
        val fallbackKey = selected.fallback
        val fallback = definition.animation(fallbackKey) ?: definition.animation(PetAnimations.Idle)
        val evaluatedKey = if (definition.animation(fallbackKey) != null) fallbackKey else PetAnimations.Idle
        if (fallback == null) return PetPlaybackSample(PetAnimations.Idle, 0, null)
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
