package com.yet.pets.core

/**
 * Immutable identifier for a pet animation track.
 *
 * Generic normalized identifier shared by multiple adapters: accepts
 * arbitrary [String] values (any length, blank, or whitespace) with no
 * format-specific restrictions at this layer, so no adapter's contract can
 * leak into another's. Animations are intentionally NOT a closed enum: user
 * keys such as `sleep`, `eat`, `dance`, `happy`, or `spin` need no library
 * change. Use [PetAnimations] constants only for the known Codex V1
 * compatibility states.
 *
 * Format-level rules live in each adapter's own normalization, never here:
 * Pets KMP v1 trims manifest keys and rejects blank-after-trim (remaining
 * safe via the 64 KiB manifest cap); the Codex V1 adapter preserves upstream
 * acceptance exactly, including empty (`""` selects idle fallbacks) and
 * literal whitespace names.
 *
 * A regular class (not a value/inline class) so the type, its constructor, and
 * its [value] export naturally to Swift.
 *
 * Equality and hashCode are deterministic case-sensitive string equality
 * over [value]: `PetAnimationKey(" dance ")` differs from
 * `PetAnimationKey("dance")`.
 */
public class PetAnimationKey(public val value: String) {
    override fun equals(other: Any?): Boolean =
        other is PetAnimationKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "PetAnimationKey(value=$value)"
}

/**
 * Typed constants for the animation names defined by the Codex CLI V1 contract,
 * including the aliases the reference runtime registers
 * (`move_right`, `move_left`, `wave`, `bounce`, `sad`).
 */
public object PetAnimations {
    public val Idle: PetAnimationKey = PetAnimationKey("idle")
    public val RunningRight: PetAnimationKey = PetAnimationKey("running-right")
    public val RunningLeft: PetAnimationKey = PetAnimationKey("running-left")
    public val Waving: PetAnimationKey = PetAnimationKey("waving")
    public val Jumping: PetAnimationKey = PetAnimationKey("jumping")
    public val Failed: PetAnimationKey = PetAnimationKey("failed")
    public val Waiting: PetAnimationKey = PetAnimationKey("waiting")
    public val Running: PetAnimationKey = PetAnimationKey("running")
    public val Review: PetAnimationKey = PetAnimationKey("review")
    public val MoveRight: PetAnimationKey = PetAnimationKey("move_right")
    public val MoveLeft: PetAnimationKey = PetAnimationKey("move_left")
    public val Wave: PetAnimationKey = PetAnimationKey("wave")
    public val Bounce: PetAnimationKey = PetAnimationKey("bounce")
    public val Sad: PetAnimationKey = PetAnimationKey("sad")
}
