package com.yet.pets.compose

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Platform decode for Android via the OS codec. `BitmapFactory` returns
 * `null` (rather than throwing) for undecodable bytes; the common wrapper
 * maps that to [PetAtlasState.Failed].
 */
internal actual fun decodePlatformImageBytes(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
