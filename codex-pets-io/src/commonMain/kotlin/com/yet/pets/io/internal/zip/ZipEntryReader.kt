package com.yet.pets.io.internal.zip

import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError

/**
 * Entry payload reader: stored slices and raw-deflate inflation with
 * independent read caps, exact-length checks, and unconditional CRC32
 * verification (CRC32 of empty input is 0 — no special case).
 */
internal fun readZipEntryData(
    archive: ByteArray,
    entry: ZipEntry,
    cap: Long,
    capName: String,
): ByteArray {
    if (entry.uncompressedSize > cap) {
        throw AbortWith(
            PetLoadError.LimitExceeded(
                capName,
                "entry ${entry.normalizedPath} declares ${entry.uncompressedSize} bytes, cap is $cap",
            ),
        )
    }
    val output: ByteArray = try {
        // Method 0 is stored (the parser accepts only 0/8); anything else is deflated.
        if (entry.method == 0) {
            readStored(archive, entry)
        } else {
            readDeflated(archive, entry, cap)
        }
    } catch (e: AbortWith) {
        throw e
    } catch (e: Exception) {
        throw AbortWith(PetLoadError.InvalidArchive("cannot read entry ${entry.normalizedPath}: ${e.message}"))
    }
    if (output.size.toLong() != entry.uncompressedSize) {
        throw AbortWith(
            PetLoadError.InvalidArchive("entry ${entry.normalizedPath} size mismatch"),
        )
    }
    if (Crc32.of(output) != entry.crc) {
        throw AbortWith(
            PetLoadError.InvalidArchive("entry ${entry.normalizedPath} CRC mismatch"),
        )
    }
    return output
}

private fun readStored(archive: ByteArray, entry: ZipEntry): ByteArray {
    if (entry.dataStart > archive.size || entry.dataEnd > archive.size) {
        throw AbortWith(PetLoadError.InvalidArchive("entry ${entry.normalizedPath} is truncated"))
    }
    return archive.copyOfRange(entry.dataStart.toInt(), entry.dataEnd.toInt())
}

private fun readDeflated(archive: ByteArray, entry: ZipEntry, cap: Long): ByteArray {
    if (entry.dataStart > archive.size || entry.dataEnd > archive.size) {
        throw AbortWith(PetLoadError.InvalidArchive("entry ${entry.normalizedPath} is truncated"))
    }
    val compressed = archive.copyOfRange(entry.dataStart.toInt(), entry.dataEnd.toInt())
    return inflateRawExact(compressed, cap)
}
