package com.yet.pets.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Skiko decode for iOS (device + simulator): the same Skia codec family as
 * JVM Desktop (see `jvmMain`), sharing one code path for all non-Android
 * targets.
 */
internal actual fun decodePlatformImageBytes(bytes: ByteArray): ImageBitmap? =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
