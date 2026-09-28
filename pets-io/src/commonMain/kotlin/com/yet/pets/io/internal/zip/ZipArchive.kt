package com.yet.pets.io.internal.zip

import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError
import com.yet.pets.io.PetPackageLimits

/**
 * Structural ZIP parser: EOCD, central directory, and local headers. Supports
 * ONLY what pet packages require: stored + deflated entries, single-disk
 * archives, no ZIP64. Deliberately not a generic ZIP library.
 *
 * Trust model (fail closed):
 * - Central and local headers are cross-checked (filename bytes, method,
 *   flags, CRC, compressed/uncompressed sizes). Any divergence is
 *   [PetLoadError.InvalidArchive]: the central directory may not define one
 *   logical file while its local header defines another.
 * - Every entry owns explicit ranges: `[localHeaderStart, dataEnd)`. Ranges
 *   use `Long` arithmetic, must lie inside the archive, must not intersect the
 *   central directory, and must not overlap each other (O(n²) at n ≤ 64).
 * - Flag policy: `0x0001` (encrypted) and `0x0040` (strong encryption) mark
 *   entries encrypted; `0x0008` (data descriptor) is rejected outright because
 *   local size/CRC fields would be placeholders this reader never consults;
 *   `0x0800` (UTF-8 names) is accepted-and-ignored (decoding is unconditionally
 *   UTF-8); all other bits are ignored and documented as lenient.
 * - STORED entries require `compressedSize == uncompressedSize`.
 * - Symlink detection is Unix-attribute-based but creator-agnostic: any entry
 *   whose mode bits read `S_IFLNK` (`0xA000`) is flagged, because callers
 *   reject symlinks unconditionally (defense-in-depth; archives are never
 *   extracted, so an undetected representation stays inert payload bytes).
 *
 * No extraction to disk, no temp files, no `java.util.zip` in common code.
 */
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
            val entries = reader.readCentralDirectory(eocd, limits)
            checkNoOverlaps(entries)
            return ZipArchive(entries)
        }

        private fun checkNoOverlaps(entries: List<ZipEntry>) {
            for (i in entries.indices) {
                for (j in i + 1 until entries.size) {
                    val a = entries[i]
                    val b = entries[j]
                    if (a.localHeaderStart < b.dataEnd && b.localHeaderStart < a.dataEnd) {
                        throw AbortWith(
                            PetLoadError.InvalidArchive(
                                "archive entries overlap: ${a.normalizedPath} and ${b.normalizedPath}",
                            ),
                        )
                    }
                }
            }
        }
    }
}

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
    /** Inclusive start of the local header. */
    val localHeaderStart: Long,
    /** Inclusive start of payload data. */
    val dataStart: Long,
    /** Exclusive end of payload data. */
    val dataEnd: Long,
)

private class EocdRecord(
    val entryCount: Int,
    val centralSize: Long,
    val centralOffset: Long,
)

private const val EOCD_SIG = 0x06054b50L
private const val CENTRAL_SIG = 0x02014b50L
private const val LOCAL_SIG = 0x04034b50L
private const val EOCD_MIN_SIZE = 22L
private const val EOCD_MAX_COMMENT = 65535L
private const val METHOD_STORED = 0
private const val METHOD_DEFLATED = 8
private const val FLAG_ENCRYPTED = 0x1
private const val FLAG_STRONG_ENCRYPTION = 0x40
private const val FLAG_DATA_DESCRIPTOR = 0x8
private const val S_IFLNK = 0xA000L
private const val S_IFMT = 0xF000L

private class Cursor(private val bytes: ByteArray) {
    val size: Long get() = bytes.size.toLong()

    fun u16(at: Long): Int {
        bounds(at, 2)
        val i = at.toInt()
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(at: Long): Long {
        bounds(at, 4)
        val i = at.toInt()
        return (bytes[i].toLong() and 0xFF) or
            ((bytes[i + 1].toLong() and 0xFF) shl 8) or
            ((bytes[i + 2].toLong() and 0xFF) shl 16) or
            ((bytes[i + 3].toLong() and 0xFF) shl 24)
    }

    fun slice(offset: Long, length: Long): ByteArray {
        bounds(offset, length)
        val o = offset.toInt()
        return bytes.copyOfRange(o, o + length.toInt())
    }

    private fun bounds(offset: Long, length: Long) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw AbortWith(PetLoadError.InvalidArchive("archive is truncated or malformed"))
        }
    }

    fun findEocd(): EocdRecord {
        if (size < EOCD_MIN_SIZE) {
            throw AbortWith(PetLoadError.InvalidArchive("archive too small to be a ZIP"))
        }
        val searchStart = size - EOCD_MIN_SIZE
        val searchEnd = maxOf(0L, size - EOCD_MIN_SIZE - EOCD_MAX_COMMENT)
        var at = searchStart
        while (at >= searchEnd) {
            if (u32(at) == EOCD_SIG) {
                val record = readEocd(at)
                if (record != null) return record
            }
            at--
        }
        throw AbortWith(PetLoadError.InvalidArchive("missing end-of-central-directory record"))
    }

    private fun readEocd(at: Long): EocdRecord? {
        val diskNumber = u16(at + 4)
        val cdDisk = u16(at + 6)
        val entriesThisDisk = u16(at + 8)
        val entriesTotal = u16(at + 10)
        val cdSize = u32(at + 12)
        val cdOffset = u32(at + 16)
        val commentLength = u16(at + 20)
        if (at + EOCD_MIN_SIZE + commentLength != size) return null
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
        var at = eocd.centralOffset
        val centralEnd = eocd.centralOffset + eocd.centralSize
        repeat(eocd.entryCount) { index ->
            if (at + 46 > centralEnd) {
                throw AbortWith(
                    PetLoadError.InvalidArchive("central directory entry $index exceeds declared size"),
                )
            }
            if (u32(at) != CENTRAL_SIG) {
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
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                throw AbortWith(PetLoadError.InvalidArchive("unsupported compression method $method"))
            }
            if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                throw AbortWith(PetLoadError.InvalidArchive("ZIP64 archives are not supported"))
            }
            checkFlagPolicy(flags)
            if (method == METHOD_STORED && compSize != uncompSize) {
                throw AbortWith(
                    PetLoadError.InvalidArchive("stored entry declares compressedSize != uncompressedSize"),
                )
            }
            val nameBytesAt = at + 46
            val recordEnd = nameBytesAt + nameLength + extraLength + commentLength
            if (recordEnd > centralEnd) {
                throw AbortWith(
                    PetLoadError.InvalidArchive("central directory entry $index exceeds declared size"),
                )
            }
            bounds(nameBytesAt, (nameLength + extraLength + commentLength).toLong())
            // Names decode UTF-8 with U+FFFD substitution (verified identical on JVM
            // and Native); the path policy runs on the decoded string, so undecodable
            // bytes cannot smuggle separators or traversal. Legacy CP437 names are
            // NOT supported and fail closed (lookup miss / duplicate / invalid).
            val rawName = slice(nameBytesAt, nameLength.toLong()).decodeToString()
            val centralNameBytes = slice(nameBytesAt, nameLength.toLong())
            val local = readLocalHeader(localOffset, centralNameBytes, method, flags, crc, compSize, uncompSize)
            val normalizedPath = normalizeZipEntryPath(rawName)
            val isDirectory = rawName.endsWith('/')
            val isSymlink = ((externalAttrs ushr 16) and S_IFMT) == S_IFLNK
            val isEncrypted = (flags and FLAG_ENCRYPTED) != 0 || (flags and FLAG_STRONG_ENCRYPTION) != 0
            // Entry spans live strictly before the central directory: no entry
            // range may intersect [centralOffset, centralOffset + centralSize).
            if (local.dataEnd > eocd.centralOffset) {
                throw AbortWith(
                    PetLoadError.InvalidArchive("entry range intersects the central directory"),
                )
            }
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
                localHeaderStart = local.localHeaderStart,
                dataStart = local.dataStart,
                dataEnd = local.dataEnd,
            )
            at = recordEnd
        }
        if (at != centralEnd) {
            throw AbortWith(
                PetLoadError.InvalidArchive(
                    "central directory size mismatch: walked ${at - eocd.centralOffset} of ${eocd.centralSize} bytes",
                ),
            )
        }
        checkCompressionRatio(compressedTotal, uncompressedTotal, limits)
        return entries
    }

    private fun checkFlagPolicy(flags: Int) {
        if ((flags and FLAG_ENCRYPTED) != 0 || (flags and FLAG_STRONG_ENCRYPTION) != 0) {
            // Recorded on the entry; the package layer rejects encrypted entries.
            return
        }
        if ((flags and FLAG_DATA_DESCRIPTOR) != 0) {
            throw AbortWith(
                PetLoadError.InvalidArchive("data-descriptor entries are not supported"),
            )
        }
        // Bit 0x0800 (UTF-8 names): accepted and ignored — decoding is
        // unconditionally UTF-8. All other bits: ignored (lenient, recorded).
    }

    private class LocalRanges(
        val localHeaderStart: Long,
        val dataStart: Long,
        val dataEnd: Long,
    )

    /**
     * Parses the local header and cross-checks it against central metadata.
     * Central sizes are authoritative for reading (data descriptors are
     * rejected, so no placeholder-zero case survives), but any divergence in
     * filename bytes, method, flags, CRC, or sizes rejects the archive.
     */
    private fun readLocalHeader(
        localOffset: Long,
        centralName: ByteArray,
        method: Int,
        flags: Int,
        crc: Long,
        compSize: Long,
        uncompSize: Long,
    ): LocalRanges {
        if (localOffset > size) {
            throw AbortWith(PetLoadError.InvalidArchive("local header out of bounds"))
        }
        if (u32(localOffset) != LOCAL_SIG) {
            throw AbortWith(PetLoadError.InvalidArchive("local header has a bad signature"))
        }
        if (u16(localOffset + 8) != method) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central method mismatch"))
        }
        if (u16(localOffset + 6) != flags) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central flags mismatch"))
        }
        if (u32(localOffset + 14) != crc) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central CRC mismatch"))
        }
        if (u32(localOffset + 18) != compSize) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central compressed-size mismatch"))
        }
        if (u32(localOffset + 22) != uncompSize) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central uncompressed-size mismatch"))
        }
        val nameLength = u16(localOffset + 26)
        val extraLength = u16(localOffset + 28)
        val dataStart = localOffset + 30 + nameLength + extraLength
        if (dataStart > size) {
            throw AbortWith(PetLoadError.InvalidArchive("entry data out of bounds"))
        }
        if (!slice(localOffset + 30, nameLength.toLong()).contentEquals(centralName)) {
            throw AbortWith(PetLoadError.InvalidArchive("local/central filename mismatch"))
        }
        val dataEnd = dataStart + compSize
        if (dataEnd > size || dataEnd < dataStart) {
            throw AbortWith(PetLoadError.InvalidArchive("entry payload out of bounds"))
        }
        return LocalRanges(localOffset, dataStart, dataEnd)
    }
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
    if (ratioExceeds(uncompressedTotal, compressedTotal, limits.maxCompressionRatio)) {
        throw AbortWith(
            PetLoadError.LimitExceeded(
                "compressionRatio",
                "archive compression ratio exceeds ${limits.maxCompressionRatio}x",
            ),
        )
    }
}

/**
 * Exact integer ratio decision: `uncompressed <= ratio * compressed` passes.
 * The public `Double` is normalized once to an exact rational (num/den); absurd
 * magnitudes saturate to "never trips" rather than overflowing.
 */
internal class RatioLimit private constructor(val num: Long, val den: Long) {
    companion object {
        fun of(ratio: Double): RatioLimit {
            require(ratio.isFinite() && ratio >= 1.0) { "ratio must be finite and >= 1, got $ratio" }
            if (ratio > 9_000_000_000_000.0) return RatioLimit(Long.MAX_VALUE, 1L)
            val scaled = (ratio * 1_000_000.0).toLong()
            return RatioLimit(scaled, 1_000_000L)
        }
    }

    /** True when `uncompressed` strictly exceeds `ratio * compressed`. */
    fun exceeded(uncompressed: Long, compressed: Long): Boolean {
        if (uncompressed == 0L) return false
        if (compressed == 0L) return true
        // whole = uncompressed/compressed (exact); compare whole against num/den,
        // then the remainder exactly: rem*den > (num%den)*compressed.
        // Bounds: rem < compressed <= archive bytes (fits Int range in practice,
        // Long in theory); den <= 1e6; num%den < den. All products fit Long.
        val whole = uncompressed / compressed
        val denDiv = num / den
        if (whole != denDiv) return whole > denDiv
        val rem = uncompressed % compressed
        return rem * den > (num % den) * compressed
    }
}

internal fun ratioExceeds(uncompressed: Long, compressed: Long, ratio: Double): Boolean =
    RatioLimit.of(ratio).exceeded(uncompressed, compressed)
