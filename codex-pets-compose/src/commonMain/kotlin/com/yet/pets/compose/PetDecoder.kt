package com.yet.pets.compose

import androidx.compose.ui.graphics.ImageBitmap

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
 * Codec notes: Skia (JVM/iOS) decodes JPEG, PNG, GIF, and WebP (lossy and
 * lossless); Android's `BitmapFactory` (minSdk 24) decodes JPEG, PNG, GIF,
 * and WebP via the OS codec. Animated inputs (GIF/WebP) decode to their first
 * frame, which is all the contract needs — a pet uses exactly one static
 * atlas and frames are atlas subregions. Format behavior is verified by the
 * JVM format tests plus the common smoke test (which also executes on iOS
 * simulator); Android-host execution is unavailable in this environment, so
 * the Android path is the canonical two-call platform decode and is covered
 * by compilation plus the shared failure-contract tests.
 *
 * Failure is total and typed: Skiko throws `IllegalArgumentException` on
 * undecodable bytes and `BitmapFactory` returns `null`; both map to
 * [PetAtlasState.Failed]. No platform decoder exception escapes, and the
 * process can never SIGABRT on foreign bytes.
 */
internal fun decodeAtlasBytes(bytes: ByteArray): DecodedAtlas {
    if (bytes.isEmpty()) {
        return DecodedAtlas(null, PetAtlasState.Failed("empty spritesheet bytes"))
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
