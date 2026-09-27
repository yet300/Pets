package com.yet.pets.io

import com.yet.pets.io.internal.zip.Crc32
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.Deflater
import okio.DeflaterSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem

internal const val SHEET_W = 1536
internal const val SHEET_H = 1872

internal fun manifestJson(
    id: String? = null,
    displayName: String? = null,
    description: String? = null,
    spritesheetPath: String? = null,
    extra: String = "",
): String {
    val parts = mutableListOf<String>()
    if (id != null) parts += "\"id\": \"$id\""
    if (displayName != null) parts += "\"displayName\": \"$displayName\""
    if (description != null) parts += "\"description\": \"$description\""
    if (spritesheetPath != null) parts += "\"spritesheetPath\": \"$spritesheetPath\""
    if (extra.isNotEmpty()) parts += extra
    return "{${parts.joinToString(",")}}"
}

/** Int-based byte collector (avoids Byte/Int literal mixing). */
internal class ByteWriter {
    private val data = ArrayList<Byte>()
    fun b(value: Int) {
        data.add((value and 0xFF).toByte())
    }

    fun bytes(vararg values: Int) {
        for (v in values) b(v)
    }

    fun raw(bytes: ByteArray) {
        for (x in bytes) data.add(x)
    }

    fun ascii(text: String) {
        raw(text.encodeToByteArray())
    }

    fun be16(value: Int) {
        b(value ushr 8)
        b(value)
    }

    fun be32(value: Long) {
        b((value ushr 24).toInt())
        b((value ushr 16).toInt())
        b((value ushr 8).toInt())
        b(value.toInt())
    }

    fun le16(value: Int) {
        b(value)
        b(value ushr 8)
    }

    fun le24(value: Int) {
        b(value)
        b(value ushr 8)
        b(value ushr 16)
    }

    fun le32(value: Long) {
        b(value.toInt())
        b((value ushr 8).toInt())
        b((value ushr 16).toInt())
        b((value ushr 24).toInt())
    }

    fun build(): ByteArray = data.toByteArray()

    val size: Int get() = data.size
}

// -- Synthetic image fixtures (headers only; the probe never reads pixels). --

internal fun pngBytes(width: Int = SHEET_W, height: Int = SHEET_H, badCrc: Boolean = false): ByteArray {
    val ihdr = ByteWriter().apply {
        be32(width.toLong())
        be32(height.toLong())
        bytes(8, 6, 0, 0, 0)
    }.build()
    val crc = Crc32.of("IHDR".encodeToByteArray() + ihdr)
    val stored = if (badCrc) crc + 1 else crc
    return ByteWriter().apply {
        bytes(137, 80, 78, 71, 13, 10, 26, 10)
        be32(13)
        ascii("IHDR")
        raw(ihdr)
        be32(stored)
    }.build()
}

internal fun gifBytes(
    width: Int = SHEET_W,
    height: Int = SHEET_H,
    version: String = "GIF89a",
): ByteArray =
    ByteWriter().apply {
        ascii(version)
        le16(width)
        le16(height)
        bytes(0x70, 0, 0)
    }.build()

internal fun jpegBytes(width: Int = SHEET_W, height: Int = SHEET_H, withApp0: Boolean = true): ByteArray =
    ByteWriter().apply {
        bytes(0xFF, 0xD8) // SOI
        if (withApp0) {
            bytes(0xFF, 0xE0) // APP0
            be16(16)
            ascii("JFIF\u0000")
            bytes(1, 1, 0, 0, 1, 0, 1, 0, 0)
        }
        bytes(0xFF, 0xC0) // SOF0
        be16(11)
        bytes(8) // precision
        be16(height)
        be16(width)
        bytes(3, 1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1)
        bytes(0xFF, 0xD9) // EOI
    }.build()

internal fun webpVp8Bytes(width: Int = SHEET_W, height: Int = SHEET_H): ByteArray {
    val payload = ByteWriter().apply {
        bytes(0x2D, 0x10, 0x00) // 3-byte frame tag (arbitrary)
        bytes(0x9D, 0x01, 0x2A) // start code
        le16(width and 0x3FFF)
        le16(height and 0x3FFF)
        repeat(64) { b(0x07) } // filler frame bytes
    }.build()
    return riffWebp("VP8 ".encodeToByteArray(), payload)
}

internal fun webpVp8lBytes(width: Int = SHEET_W, height: Int = SHEET_H): ByteArray {
    val w = (width - 1) and 0x3FFF
    val h = (height - 1) and 0x3FFF
    val bits = (w.toLong() or (h.toLong() shl 14)).toInt()
    val payload = ByteWriter().apply {
        bytes(0x2F)
        le32(bits.toLong())
        repeat(32) { b(0x11) }
    }.build()
    return riffWebp("VP8L".encodeToByteArray(), payload)
}

internal fun webpVp8xBytes(width: Int = SHEET_W, height: Int = SHEET_H): ByteArray {
    // Layout verified against a real animated sample (300x225 ground truth).
    val w = width - 1
    val h = height - 1
    val payload = ByteWriter().apply {
        bytes(0x10) // feature flags (arbitrary)
        bytes(0, 0, 0) // reserved
        le24(w)
        le24(h)
    }.build()
    return riffWebp("VP8X".encodeToByteArray(), payload)
}

private fun riffWebp(fourCc: ByteArray, payload: ByteArray): ByteArray {
    val padded = if (payload.size % 2 == 1) payload + byteArrayOf(0) else payload
    return ByteWriter().apply {
        ascii("RIFF")
        le32((4 + 8 + padded.size).toLong())
        ascii("WEBP")
        raw(fourCc)
        le32(payload.size.toLong())
        raw(padded)
    }.build()
}

// -- Fake filesystem pet packages. --

internal fun FileSystem.writeText(path: Path, text: String) {
    val buffered = sink(path).buffer()
    try {
        buffered.writeUtf8(text)
    } finally {
        buffered.close()
    }
}

internal fun FileSystem.writeBytes(path: Path, bytes: ByteArray) {
    val buffered = sink(path).buffer()
    try {
        buffered.write(bytes)
    } finally {
        buffered.close()
    }
}

internal fun FakeFileSystem.writePetPackage(
    dir: String,
    manifest: String,
    sheetBytes: ByteArray = webpVp8Bytes(),
    sheetName: String = "spritesheet.webp",
    manifestName: String = "pet.json",
) {
    createDirectories(dir.toPath())
    writeText("$dir/$manifestName".toPath(), manifest)
    writeBytes("$dir/$sheetName".toPath(), sheetBytes)
}

// -- Synthetic ZIP builder (stored + deflated, correct CRCs). --

internal const val ZIP_METHOD_STORED = 0
internal const val ZIP_METHOD_DEFLATED = 8
internal const val ZIP_MODE_SYMLINK: Long = 0xA000L
internal const val ZIP_MADE_BY_UNIX = 3

internal class ZipEntrySpec(
    val name: String,
    val data: ByteArray = ByteArray(0),
    val method: Int = ZIP_METHOD_STORED,
    val unixMode: Long? = null,
    val encrypted: Boolean = false,
    val directory: Boolean = false,
    /** Raw name bytes override (for malformed-encoding tests). */
    val nameBytesOverride: ByteArray? = null,
    /** Override flags in BOTH headers (for flag-policy tests). */
    val flagsOverride: Int? = null,
    /** Local-header-only overrides (for central/local consistency tests). */
    val localNameOverride: String? = null,
    val localFlagsOverride: Int? = null,
    val localCrcOverride: Long? = null,
    val localCompSizeOverride: Long? = null,
    val localUncompSizeOverride: Long? = null,
    /** Central-directory overrides (for lying-metadata tests). */
    val centralCompSizeOverride: Long? = null,
    val centralUncompSizeOverride: Long? = null,
    val centralCrcOverride: Long? = null,
    val centralLocalOffsetOverride: Long? = null,
    /** Exact raw DEFLATE payload override for consumption regressions. */
    val compressedOverride: ByteArray? = null,
)

internal fun deflateRaw(data: ByteArray): ByteArray {
    val out = Buffer()
    val sink = DeflaterSink(out, Deflater(6, true))
    sink.write(Buffer().write(data), data.size.toLong())
    sink.close()
    return out.readByteArray()
}

internal fun buildZip(specs: List<ZipEntrySpec>): ByteArray {
    val out = ByteWriter()
    data class Central(val spec: ZipEntrySpec, val crc: Long, val comp: ByteArray, val offset: Int)
    val centrals = mutableListOf<Central>()
    for (spec in specs) {
        val offset = out.size
        val payload = if (spec.directory) {
            ByteArray(0)
        } else if (spec.method == ZIP_METHOD_DEFLATED) {
            spec.compressedOverride ?: deflateRaw(spec.data)
        } else {
            spec.data
        }
        val crc = Crc32.of(if (spec.directory) ByteArray(0) else spec.data)
        val nameBytes = spec.nameBytesOverride ?: spec.name.encodeToByteArray()
        val localNameBytes = spec.localNameOverride?.encodeToByteArray() ?: nameBytes
        val flags = spec.flagsOverride ?: if (spec.encrypted) 1 else 0
        val localFlags = spec.localFlagsOverride ?: flags
        val uncomp = if (spec.directory) 0 else spec.data.size.toLong()
        out.le32(0x04034b50L) // local signature
        out.le16(20) // version needed
        out.le16(localFlags)
        out.le16(spec.method)
        out.le16(0)
        out.le16(0) // time/date
        out.le32(spec.localCrcOverride ?: crc)
        out.le32(spec.localCompSizeOverride ?: payload.size.toLong())
        out.le32(spec.localUncompSizeOverride ?: uncomp)
        out.le16(localNameBytes.size)
        out.le16(0) // extra
        out.raw(localNameBytes)
        out.raw(payload)
        centrals += Central(spec, crc, payload, offset)
    }
    val centralOffset = out.size
    for (c in centrals) {
        val nameBytes = c.spec.nameBytesOverride ?: c.spec.name.encodeToByteArray()
        val flags = c.spec.flagsOverride ?: if (c.spec.encrypted) 1 else 0
        val madeBy = if (c.spec.unixMode != null) (ZIP_MADE_BY_UNIX shl 8) or 20 else 20
        val extAttrs = if (c.spec.unixMode != null) (c.spec.unixMode shl 16) else 0L
        val uncomp = if (c.spec.directory) 0 else c.spec.data.size.toLong()
        out.le32(0x02014b50L)
        out.le16(madeBy)
        out.le16(20)
        out.le16(flags)
        out.le16(c.spec.method)
        out.le16(0)
        out.le16(0)
        out.le32(c.spec.centralCrcOverride ?: c.crc)
        out.le32(c.spec.centralCompSizeOverride ?: c.comp.size.toLong())
        out.le32(c.spec.centralUncompSizeOverride ?: uncomp)
        out.le16(nameBytes.size)
        out.le16(0)
        out.le16(0)
        out.le16(0) // extra/comment/disk
        out.bytes(0, 0) // internal attrs
        out.le32(extAttrs)
        out.le32(c.spec.centralLocalOffsetOverride ?: c.offset.toLong())
        out.raw(nameBytes)
    }
    val centralSize = out.size - centralOffset
    out.le32(0x06054b50L)
    out.le16(0)
    out.le16(0)
    out.le16(centrals.size)
    out.le16(centrals.size)
    out.le32(centralSize.toLong())
    out.le32(centralOffset.toLong())
    out.le16(0) // comment
    return out.build()
}
