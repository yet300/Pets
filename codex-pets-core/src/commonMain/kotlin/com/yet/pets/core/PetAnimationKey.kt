package com.yet.pets.core

/**
 * Immutable identifier for a pet animation track.
 *
 * Generic runtime: arbitrary user-defined keys are supported (for example
 * `sleep`, `eat`, `dance`, `happy`, `spin`). Animations are intentionally NOT
 * a closed enum. Use [PetAnimations] constants only for the known Codex V1
 * compatibility states.
 *
 * A regular class (not a value/inline class) so the type, its constructor, and
 * its [value] export naturally to Swift.
 *
 * Validity: [value] must be non-blank and at most [MAX_KEY_LENGTH] characters.
 * Equality and hashCode are deterministic string equality over [value].
 */
public class PetAnimationKey(public val value: String) {
    public companion object {
        /** Sensible upper bound for animation keys; no platform-dependent behavior. */
        public const val MAX_KEY_LENGTH: Int = 64
    }

    init {
        require(value.isNotBlank()) { "animation key must be non-blank" }
        require(value.length <= MAX_KEY_LENGTH) {
            "animation key must be at most $MAX_KEY_LENGTH characters, got ${value.length}"
        }
    }

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
