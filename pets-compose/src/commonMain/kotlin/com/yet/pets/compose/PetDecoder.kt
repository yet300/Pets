package com.yet.pets.compose

import androidx.compose.ui.graphics.ImageBitmap
import com.yet.pets.core.EncodedSpritesheetProbe
import com.yet.pets.core.PetInputLimits

/**
 * Result of the single atlas decode owned by a [PetPlayerState].
 *
 * Internal: the decoded [bitmap] never crosses a public boundary (see
 * [PetAtlasState] for the public outcome surface).
 */
internal data class DecodedAtlas(
    val bitmap: ImageBitmap?,
    val state: PetAtlasState,
)

/**
 * Decodes encoded spritesheet bytes (JPEG, PNG, GIF, or WebP) into the single
 * atlas image for one pet.
 *
 * Decoder design (spike result, Compose Multiplatform 1.12.0):
 *
 * - JVM Desktop, iOS arm64, iOS simulator arm64 (Skiko targets):
 *   `org.jetbrains.skia.Image.makeFromEncoded` is a `commonMain` Skiko API and
 *   `androidx.compose.ui.graphics.toComposeImageBitmap` converts it to the
 *   Compose atlas. Both arrive transitively via the `compose-ui` dependency
 *   the renderer needs anyway.
 * - Android: the platform decoder, `BitmapFactory.decodeByteArray` plus
 *   `asImageBitmap` (Skia is not exposed on the Android target).
 *
 * The platform split is exactly one small internal `expect`/`actual`
 * ([decodePlatformImageBytes]); there is no Coil, no image-loading framework,
 * no network loader, and no public decoder abstraction.
 *
 * Static PNG, JPEG, GIF (first frame), and WebP VP8/VP8L/VP8X are accepted.
 * Animated WebP is rejected by the core metadata probe before platform decode
 * because Android API 24 cannot decode it. Independent vectors run on JVM,
 * iOS simulator, and Android API 24/modern device tests.
 *
 * Skiko exceptions and Android `BitmapFactory` null results from undecodable
 * bytes map to [PetAtlasState.Failed]. This layer cannot guarantee that a
 * platform's native image codec is safe from process-level faults.
 */
internal fun decodeAtlasBytes(bytes: ByteArray): DecodedAtlas {
    if (bytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES) {
        return DecodedAtlas(null, PetAtlasState.Failed("spritesheet exceeds ${PetInputLimits.MAX_SPRITESHEET_BYTES} bytes"))
    }
    if (bytes.isEmpty()) {
        return DecodedAtlas(null, PetAtlasState.Failed("empty spritesheet bytes"))
    }
    if (EncodedSpritesheetProbe.probe(bytes) == null) {
        return DecodedAtlas(null, PetAtlasState.Failed("unrecognized, malformed, or animated spritesheet bytes"))
    }
    return try {
        val bitmap = decodePlatformImageBytes(bytes)
            ?: return DecodedAtlas(null, PetAtlasState.Failed("undecodable spritesheet bytes"))
        DecodedAtlas(bitmap, PetAtlasState.Ready)
    } catch (e: Exception) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "decode error"
        DecodedAtlas(null, PetAtlasState.Failed("undecodable spritesheet bytes: $detail"))
    }
}

/**
 * Platform decode of one encoded image into an [ImageBitmap], or `null` when
 * the bytes are not a decodable image. Never exposes platform decoder types.
 */
internal expect fun decodePlatformImageBytes(bytes: ByteArray): ImageBitmap?
