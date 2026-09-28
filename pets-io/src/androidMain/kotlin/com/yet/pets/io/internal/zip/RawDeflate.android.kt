package com.yet.pets.io.internal.zip

import java.util.zip.Inflater
import okio.Buffer

internal actual fun inflateRawExact(compressed: ByteArray, cap: Long): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(compressed)
        val output = Buffer()
        val chunk = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(chunk)
            if (count > 0) {
                if (output.size + count > cap) throw IllegalArgumentException("deflate output exceeds $cap bytes")
                output.write(chunk, 0, count)
            } else if (inflater.needsInput() || inflater.needsDictionary()) {
                throw IllegalArgumentException("truncated or dictionary-based deflate stream")
            } else {
                throw IllegalArgumentException("deflate made no progress")
            }
        }
        if (inflater.bytesRead != compressed.size.toLong()) {
            throw IllegalArgumentException("trailing compressed bytes")
        }
        return output.readByteArray()
    } finally {
        inflater.end()
    }
}
