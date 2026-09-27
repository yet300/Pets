package com.yet.pets.io.internal.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream_s

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun inflateRawExact(compressed: ByteArray, cap: Long): ByteArray = memScoped {
    val stream = alloc<z_stream_s>()
    stream.zalloc = null
    stream.zfree = null
    stream.opaque = null
    if (inflateInit2(stream.ptr, -15) != Z_OK) {
        throw IllegalArgumentException("cannot initialize raw deflate")
    }
    try {
        val output = Buffer()
        val chunk = ByteArray(8192)
        compressed.asUByteArray().usePinned { input ->
            stream.next_in = if (compressed.isNotEmpty()) input.addressOf(0) else null
            stream.avail_in = compressed.size.toUInt()
            while (true) {
                val beforeIn = stream.total_in
                chunk.asUByteArray().usePinned { target ->
                    stream.next_out = target.addressOf(0)
                    stream.avail_out = chunk.size.toUInt()
                    val result = inflate(stream.ptr, Z_NO_FLUSH)
                    val produced = chunk.size - stream.avail_out.toInt()
                    if (produced > 0) {
                        if (output.size + produced > cap) {
                            throw IllegalArgumentException("deflate output exceeds $cap bytes")
                        }
                        output.write(chunk, 0, produced)
                    }
                    if (result == Z_STREAM_END) {
                        if (stream.total_in.toLong() != compressed.size.toLong()) {
                            throw IllegalArgumentException("trailing compressed bytes")
                        }
                        return@memScoped output.readByteArray()
                    }
                    if (result != Z_OK || (produced == 0 && stream.total_in == beforeIn) ||
                        (stream.avail_in == 0u && produced == 0)
                    ) {
                        throw IllegalArgumentException("truncated or malformed deflate stream")
                    }
                }
            }
        }
        error("unreachable")
    } finally {
        inflateEnd(stream.ptr)
    }
}
