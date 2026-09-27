package com.yet.pets.io.internal.zip

/** Raw DEFLATE with verified end marker and exact compressed-byte consumption. */
internal expect fun inflateRawExact(compressed: ByteArray, cap: Long): ByteArray
