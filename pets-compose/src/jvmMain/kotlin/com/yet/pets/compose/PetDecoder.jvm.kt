package com.yet.pets.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Skiko decode for JVM Desktop: the same Skia codec family as iOS
 * (see `iosMain`), sharing one code path for all non-Android targets.
 */
internal actual fun decodePlatformImageBytes(bytes: ByteArray): ImageBitmap? =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
