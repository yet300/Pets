package com.yet.pets.compose

/**
 * Outcome of decoding the encoded spritesheet bytes held by a [PetPlayerState].
 *
 * Decoding is synchronous and runs exactly once per state inputs (see
 * [rememberPetPlayerState]): by the time the state exists the outcome is final,
 * so there is deliberately no `Loading` state.
 *
 * Encoded image bytes are foreign input: corrupt, truncated, or unsupported
 * bytes yield [Failed], never a crash. [androidx.compose.ui.graphics.ImageBitmap]
 * itself stays an internal implementation detail — consumers branch on this
 * outcome instead.
 */
public sealed interface PetAtlasState {
    /** Exactly one atlas image was decoded and is ready to render. */
    public data object Ready : PetAtlasState

    /**
     * Decoding failed; the renderer draws nothing (an empty placeholder that
     * still preserves the sprite aspect ratio).
     *
     * @property reason short human-readable cause (for example
     *   `"empty spritesheet bytes"` or the underlying decoder message).
     *   Never blank.
     */
    public data class Failed(public val reason: String) : PetAtlasState
}
