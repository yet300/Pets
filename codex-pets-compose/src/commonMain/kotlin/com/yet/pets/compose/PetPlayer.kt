package com.yet.pets.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yet.pets.core.PetDefinition

/**
 * Remembers the [PetPlayerState] for one pet.
 *
 * Decoding: the spritesheet bytes are defensively copied exactly once, then
 * decoded exactly once into a single atlas image (see [PetAtlasState]).
 * Neither the copy nor the decode repeats on recomposition.
 *
 * ByteArray identity: `ByteArray` equality is referential, so a NEW array
 * instance counts as new image content and triggers exactly one re-decode,
 * while passing the same instance never re-decodes (no multi-megabyte hashing
 * on any frame). Because the bytes are copied up front, caller-side mutation
 * after construction can neither corrupt rendering nor silently update it —
 * to display new content, pass a new array. A new [PetDefinition] instance
 * likewise triggers one re-decode; both inputs are part of the remember key.
 *
 * Memory: at most one transient copy (bounded by the Phase 2 limit of 8 MiB
 * encoded) plus the single decoded atlas (1536x1872 RGBA ≈ 11.5 MiB for a
 * CLI V1 pet). The copy is dropped after decoding — never retained, never
 * re-copied.
 *
 * @param definition normalized pet definition (core parsing result).
 * @param spritesheetBytes encoded spritesheet (JPEG, PNG, GIF, or WebP) from
 *   any source — the library does not know or care how they were transported.
 */
@Composable
public fun rememberPetPlayerState(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
): PetPlayerState = rememberPetPlayerState(definition, spritesheetBytes, ::decodeAtlasBytes)

/**
 * Internal overload with an injectable decoder so tests can count decodes and
 * supply fakes without touching Skia.
 */
@Composable
internal fun rememberPetPlayerState(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    decoder: (ByteArray) -> DecodedAtlas,
): PetPlayerState {
    val decoded = remember(definition, spritesheetBytes) {
        decoder(spritesheetBytes.copyOf())
    }
    return remember(definition, decoded) {
        PetPlayerState(definition, decoded)
    }
}
