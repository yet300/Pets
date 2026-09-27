package com.yet.pets.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yet.pets.core.PetDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect

/**
 * Remembers the [PetPlayerState] for one pet.
 *
 * Decoding: the spritesheet bytes are defensively copied and decoded on a
 * background dispatcher by a composition-owned coroutine (see [PetAtlasState]).
 * Neither the copy nor the decode repeats on recomposition.
 *
 * ByteArray identity: `ByteArray` equality is referential, so a NEW array
 * instance counts as new image content and triggers exactly one re-decode,
 * while passing the same instance never re-decodes (no multi-megabyte hashing
 * on any frame). The background job takes a snapshot before decoding; callers
 * must keep the array unchanged until it reaches Ready or Failed. Then mutation
 * cannot corrupt rendering or silently update it —
 * to display new content, pass a new array. A new [PetDefinition] instance
 * likewise triggers one re-decode; both inputs are part of the remember key.
 *
 * Memory: at most one transient copy (bounded here at 8 MiB
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
    val state = remember(definition, spritesheetBytes) {
        PetPlayerState(definition, DecodedAtlas(null, PetAtlasState.Loading))
    }
    LaunchedEffect(state) {
        val decoded = withContext(Dispatchers.Default) {
            if (spritesheetBytes.size > com.yet.pets.core.PetInputLimits.MAX_SPRITESHEET_BYTES) {
                DecodedAtlas(null, PetAtlasState.Failed("spritesheet exceeds 8 MiB"))
            } else {
                decoder(spritesheetBytes.copyOf())
            }
        }
        state.completeDecode(decoded)
    }
    return state
}
