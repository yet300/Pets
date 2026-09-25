package com.yet.pets.io

import okio.Buffer
import okio.BufferedSource
import okio.Inflater
import okio.InflaterSource
import okio.buffer

/**
 * Minimal ZIP central-directory reader for pet packages. Supports ONLY what
 * Phase 2 requires: stored + deflated entries, single-disk archives, no ZIP64.
 * This is deliberately not a generic ZIP library.
 *
 * Design (no extraction to disk, no temp files, no `java.util.zip` in common):
 * the central directory is parsed from the in-memory archive with strict bounds
 * checks; entry payloads are sliced from the same buffer; deflated payloads go
 * through Okio's common `Inflater(true)` (raw deflate, platform zlib).
 * Central-directory sizes are advisory: every actual read is independently
 * bounded and CRC-verified. Any structural violation becomes [PetLoadError]
 * (via [AbortWith] or [PetLoadError.InvalidArchive]), never an uncaught exception.
 */
private const val ZIP_EOCD_SIG = 0x06054b50
private const val ZIP_CENTRAL_SIG = 0x02014b50
private const val ZIP_LOCAL_SIG = 0x04034b50
private const val ZIP_EOCD_MIN_SIZE = 22
private const val ZIP_EOCD_MAX_COMMENT = 65535
private const val ZIP_METHOD_STORED = 0
private const val ZIP_METHOD_DEFLATED = 8
private const val ZIP_FLAG_ENCRYPTED = 0x1

internal class ZipArchive private constructor(
    val entries: List<ZipEntry>,
) {
    companion object {
        fun parse(bytes: ByteArray, limits: PetPackageLimits): ZipArchive {
            val reader = Cursor(bytes)
            val eocd = reader.findEocd()
            if (eocd.entryCount > limits.maxZipEntries) {
                throw AbortWith(
                    PetLoadError.LimitExceeded(
                        "zipEntries",
                        "archive has ${eocd.entryCount} entries, limit is ${limits.maxZipEntries}",
                    ),
                )
            }
            return ZipArchive(reader.readCentralDirectory(eocd, limits))
        }
    }
}

/** Thrown internally to unwind with a typed error; caught at the loader boundary. */
internal class AbortWith(val error: PetLoadError) : Exception()

internal class ZipEntry(
    val rawName: String,
    val normalizedPath: String,
    val method: Int,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val isEncrypted: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc: Long,
    val dataOffset: Int,
)

private class EocdRecord(
    val entryCount: Int,
    val centralSize: Long,
    val centralOffset: Long,
)

private class Cursor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun u16(at: Int): Int {
        bounds(at, 2)
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(at: Int): Long {
        bounds(at, 4)
        return (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
    }

    fun slice(offset: Int, length: Int): ByteArray {
        bounds(offset, length)
        return bytes.copyOfRange(offset, offset + length)
    }

    private fun bounds(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw AbortWith(PetLoadError.InvalidArchive("archive is truncated or malformed"))
        }
    }

    fun findEocd(): EocdRecord {
        if (size < ZIP_EOCD_MIN_SIZE) {
            throw AbortWith(PetLoadError.InvalidArchive("archive too small to be a ZIP"))
        }
        val searchStart = size - ZIP_EOCD_MIN_SIZE
        val searchEnd = maxOf(0, size - ZIP_EOCD_MIN_SIZE - ZIP_EOCD_MAX_COMMENT)
        var at = searchStart
        while (at >= searchEnd) {
            if (u32(at) == ZIP_EOCD_SIG.toLong()) {
                val record = readEocd(at)
                if (record != null) return record
            }
            at--
        }
        throw AbortWith(PetLoadError.InvalidArchive("missing end-of-central-directory record"))
    }

    private fun readEocd(at: Int): EocdRecord? {
        val diskNumber = u16(at + 4)
        val cdDisk = u16(at + 6)
        val entriesThisDisk = u16(at + 8)
        val entriesTotal = u16(at + 10)
        val cdSize = u32(at + 12)
        val cdOffset = u32(at + 16)
        val commentLength = u16(at + 20)
        if (at + ZIP_EOCD_MIN_SIZE + commentLength != size) return null
        if (diskNumber != 0 || cdDisk != 0 || entriesThisDisk != entriesTotal) {
            throw AbortWith(PetLoadError.InvalidArchive("multi-disk archives are not supported"))
        }
        if (entriesTotal == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw AbortWith(PetLoadError.InvalidArchive("ZIP64 archives are not supported"))
        }
        if (cdOffset > size || cdSize > size || cdOffset > size - cdSize) {
            throw AbortWith(PetLoadError.InvalidArchive("central directory out of bounds"))
        }
        return EocdRecord(entriesTotal, cdSize, cdOffset)
    }

    fun readCentralDirectory(eocd: EocdRecord, limits: PetPackageLimits): List<ZipEntry> {
        val entries = ArrayList<ZipEntry>(eocd.entryCount)
        var compressedTotal = 0L
        var uncompressedTotal = 0L
        var at = eocd.centralOffset.toInt()
        repeat(eocd.entryCount) { index ->
            if (u32(at) != ZIP_CENTRAL_SIG.toLong()) {
                throw AbortWith(
                    PetLoadError.InvalidArchive("central directory entry $index has a bad signature"),
                )
            }
            val versionMadeBy = u16(at + 4)
            val flags = u16(at + 8)
            val method = u16(at + 10)
            val crc = u32(at + 16)
            val compSize = u32(at + 20)
            val uncompSize = u32(at + 24)
            val nameLength = u16(at + 28)
            val extraLength = u16(at + 30)
            val commentLength = u16(at + 32)
            val diskStart = u16(at + 34)
            val externalAttrs = u32(at + 38)
            val localOffset = u32(at + 42)
            if (diskStart != 0) {
                throw AbortWith(PetLoadError.InvalidArchive("multi-disk archives are not supported"))
            }
            if (method != ZIP_METHOD_STORED && method != ZIP_METHOD_DEFLATED) {
                throw AbortWith(PetLoadError.InvalidArchive("unsupported compression method $method"))
            }
            if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                throw AbortWith(PetLoadError.InvalidArchive("ZIP64 archives are not supported"))
            }
            val nameBytesAt = at + 46
            bounds(nameBytesAt, nameLength + extraLength + commentLength)
            // Names decode UTF-8 with U+FFFD substitution; the path policy below
            // runs on the decoded string, so undecodable bytes cannot smuggle
            // separators or traversal segments.
            val rawName = slice(nameBytesAt, nameLength).decodeToString()
            val dataOffset = readLocalDataOffset(localOffset, method)
            val normalizedPath = normalizeZipEntryPath(rawName)
            val isDirectory = rawName.endsWith('/')
            val isUnix = (versionMadeBy ushr 8) == 3
            val isSymlink = isUnix && ((externalAttrs ushr 16) and 0xF000L) == 0xA000L
            val isEncrypted = (flags and ZIP_FLAG_ENCRYPTED) != 0
            compressedTotal = addCapped(compressedTotal, compSize, limits.maxCompressedArchiveBytes) {
                PetLoadError.LimitExceeded(
                    "compressedArchiveBytes",
                    "archive compressed total exceeds ${limits.maxCompressedArchiveBytes} byte budget",
                )
            }
            uncompressedTotal = addCapped(uncompressedTotal, uncompSize, limits.maxUncompressedArchiveBytes) {
                PetLoadError.LimitExceeded(
                    "uncompressedArchiveBytes",
                    "archive uncompressed total exceeds ${limits.maxUncompressedArchiveBytes} byte budget",
                )
            }
            entries += ZipEntry(
                rawName = rawName,
                normalizedPath = normalizedPath,
                method = method,
                isDirectory = isDirectory,
                isSymlink = isSymlink,
                isEncrypted = isEncrypted,
                compressedSize = compSize,
                uncompressedSize = uncompSize,
                crc = crc,
                dataOffset = dataOffset,
            )
            at = nameBytesAt + nameLength + extraLength + commentLength
        }
        checkCompressionRatio(compressedTotal, uncompressedTotal, limits)
        return entries
    }

    private fun readLocalDataOffset(localOffset: Long, method: Int): Int {
        if (localOffset > size) {
            throw AbortWith(PetLoadError.InvalidArchive("local header out of bounds"))
        }
        val at = localOffset.toInt()
        if (u32(at) != ZIP_LOCAL_SIG.toLong()) {
            throw AbortWith(PetLoadError.InvalidArchive("local header has a bad signature"))
        }
        if (u16(at + 8) != method) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central method mismatch"))
        }
        val nameLength = u16(at + 26)
        val extraLength = u16(at + 28)
        val dataOffset = at + 30 + nameLength + extraLength
        if (dataOffset > size) {
            throw AbortWith(PetLoadError.InvalidArchive("entry data out of bounds"))
        }
        return dataOffset
    }
}

/**
 * Normalizes one ZIP entry name to a virtual package path. Rejects absolute
 * paths, parent traversal, and drive/prefix escapes — the archive fails closed.
 * Backslashes are literal characters (never separators); entries never touch a
 * real filesystem, so they cannot escape through them.
 */
internal fun normalizeZipEntryPath(rawName: String): String {
    val stripped = if (rawName.endsWith('/')) rawName.dropLast(1) else rawName
    if (stripped.isEmpty()) {
        throw AbortWith(PetLoadError.InvalidArchive("archive contains an empty entry name"))
    }
    if (stripped.startsWith('/')) {
        throw AbortWith(PetLoadError.InvalidArchive("archive contains absolute entry: $rawName"))
    }
    val segments = stripped.split('/')
    for (segment in segments) {
        if (segment.isEmpty() || segment == "." || segment == "..") {
            throw AbortWith(PetLoadError.InvalidArchive("archive contains unsafe entry: $rawName"))
        }
    }
    // Reject empty segments from `//` as well (covered above): split produces "".
    val first = segments.first()
    if (first.length >= 2 && first[1] == ':' && first[0].isLetter()) {
        throw AbortWith(PetLoadError.InvalidArchive("archive contains drive-prefixed entry: $rawName"))
    }
    return segments.joinToString("/")
}

private inline fun addCapped(current: Long, add: Long, cap: Long, error: () -> PetLoadError): Long {
    if (add < 0 || current > cap - add) throw AbortWith(error())
    return current + add
}

private fun checkCompressionRatio(compressedTotal: Long, uncompressedTotal: Long, limits: PetPackageLimits) {
    if (uncompressedTotal == 0L) return
    if (compressedTotal == 0L) {
        throw AbortWith(
            PetLoadError.LimitExceeded(
                "compressionRatio",
                "archive expands from 0 compressed bytes",
            ),
        )
    }
    // Totals are capped at 32 MiB: Double division is exact here.
    if (uncompressedTotal.toDouble() / compressedTotal.toDouble() > limits.maxCompressionRatio) {
        throw AbortWith(
            PetLoadError.LimitExceeded(
                "compressionRatio",
                "archive compression ratio exceeds ${limits.maxCompressionRatio}x",
            ),
        )
    }
}

/**
 * Reads one entry's payload with independent bounds: declared sizes are advisory
 * and every byte actually read counts against [cap]. Verifies exact length and
 * CRC32, then returns the bytes.
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
    if (entry.uncompressedSize > 0 && Crc32.of(output) != entry.crc) {
        throw AbortWith(
            PetLoadError.InvalidArchive("entry ${entry.normalizedPath} CRC mismatch"),
        )
    }
    return output
}

private fun readStored(archive: ByteArray, entry: ZipEntry): ByteArray {
    if (entry.uncompressedSize > archive.size - entry.dataOffset) {
        throw AbortWith(PetLoadError.InvalidArchive("entry ${entry.normalizedPath} is truncated"))
    }
    val size = entry.uncompressedSize.toInt()
    return archive.copyOfRange(entry.dataOffset, entry.dataOffset + size)
}

private fun readDeflated(archive: ByteArray, entry: ZipEntry, cap: Long): ByteArray {
    if (entry.compressedSize > archive.size - entry.dataOffset) {
        throw AbortWith(PetLoadError.InvalidArchive("entry ${entry.normalizedPath} is truncated"))
    }
    val compressed = archive.copyOfRange(
        entry.dataOffset,
        entry.dataOffset + entry.compressedSize.toInt(),
    )
    val inflater = Inflater(true)
    try {
        val source: BufferedSource = InflaterSource(Buffer().write(compressed), inflater).buffer()
        val out = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(out, 8192)
            if (read == -1L) break
            total += read
            if (total > cap) {
                throw AbortWith(
                    PetLoadError.LimitExceeded(
                        "entryBytes",
                        "entry ${entry.normalizedPath} expands beyond $cap bytes",
                    ),
                )
            }
        }
        return out.readByteArray()
    } finally {
        inflater.end()
    }
}
