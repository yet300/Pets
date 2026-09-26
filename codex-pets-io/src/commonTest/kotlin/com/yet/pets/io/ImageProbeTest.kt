package com.yet.pets.io

import com.yet.pets.io.internal.image.ImageProbe
import com.yet.pets.io.internal.zip.Crc32

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageProbeTest {

    @Test
    fun pngDimensions() {
        val info = ImageProbe.probe(pngBytes(1536, 1872))!!
        assertEquals(1536, info.width)
        assertEquals(1872, info.height)
        assertEquals(com.yet.pets.core.SpritesheetFormat.PNG, info.format)
    }

    @Test
    fun gifDimensions() {
        val info = ImageProbe.probe(gifBytes(800, 600))!!
        assertEquals(800, info.width)
        assertEquals(600, info.height)
        assertEquals(com.yet.pets.core.SpritesheetFormat.GIF, info.format)
    }

    @Test
    fun jpegDimensionsWithAndWithoutApp0() {
        for (withApp0 in listOf(true, false)) {
            val info = ImageProbe.probe(jpegBytes(1536, 1872, withApp0))!!
            assertEquals(1536, info.width, "app0=$withApp0")
            assertEquals(1872, info.height, "app0=$withApp0")
            assertEquals(com.yet.pets.core.SpritesheetFormat.JPEG, info.format)
        }
    }

    @Test
    fun webpVp8Dimensions() {
        val info = ImageProbe.probe(webpVp8Bytes(550, 368))!!
        assertEquals(550, info.width)
        assertEquals(368, info.height)
        assertEquals(com.yet.pets.core.SpritesheetFormat.WEBP, info.format)
    }

    @Test
    fun webpVp8lDimensions() {
        val info = ImageProbe.probe(webpVp8lBytes(300, 225))!!
        assertEquals(300, info.width)
        assertEquals(225, info.height)
        assertEquals(com.yet.pets.core.SpritesheetFormat.WEBP, info.format)
    }

    @Test
    fun webpVp8xDimensions() {
        // Layout verified against a real animated sample (300x225 ground truth).
        val info = ImageProbe.probe(webpVp8xBytes(300, 225))!!
        assertEquals(300, info.width)
        assertEquals(225, info.height)
        assertEquals(com.yet.pets.core.SpritesheetFormat.WEBP, info.format)
    }

    @Test
    fun gif87aDimensions() {
        val info = ImageProbe.probe(gifBytes(800, 600, version = "GIF87a"))!!
        assertEquals(800, info.width)
        assertEquals(600, info.height)
        assertEquals(com.yet.pets.core.SpritesheetFormat.GIF, info.format)
    }

    @Test
    fun pngBadIhdrCrcRejected() {
        assertEquals(null, ImageProbe.probe(pngBytes(badCrc = true)))
    }

    @Test
    fun pngTruncatedCrcRejected() {
        // Valid IHDR + payload, but the 4 CRC bytes are missing.
        assertEquals(null, ImageProbe.probe(pngBytes().copyOfRange(0, 8 + 8 + 13)))
    }

    @Test
    fun pngBadIhdrSizeRejected() {
        val good = pngBytes()
        // IHDR length field (offset 8) claims 12 instead of 13.
        val bad = good.copyOf()
        bad[11] = 12
        assertEquals(null, ImageProbe.probe(bad))
    }

    @Test
    fun pngZeroDimensionsRejected() {
        assertEquals(null, ImageProbe.probe(pngBytes(0, 600)))
        assertEquals(null, ImageProbe.probe(pngBytes(800, 0)))
    }

    @Test
    fun pngValid1536x1872() {
        val info = ImageProbe.probe(pngBytes(1536, 1872))!!
        assertEquals(1536, info.width)
        assertEquals(1872, info.height)
    }

    @Test
    fun randomBytesUnrecognized() {
        assertEquals(null, ImageProbe.probe(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertEquals(null, ImageProbe.probe(ByteArray(0)))
        assertEquals(null, ImageProbe.probe(ByteArray(4096) { (it * 31).toByte() }))
    }

    @Test
    fun truncatedHeadersRejected() {
        assertEquals(null, ImageProbe.probe(pngBytes().copyOfRange(0, 20)))
        assertEquals(null, ImageProbe.probe(gifBytes().copyOfRange(0, 8)))
        assertEquals(null, ImageProbe.probe(jpegBytes().copyOfRange(0, 4)))
        assertEquals(null, ImageProbe.probe(webpVp8Bytes().copyOfRange(0, 20)))
    }

    @Test
    fun correctMagicCorruptStructureRejected() {
        // PNG with non-IHDR first chunk.
        val badPng = pngBytes().copyOf()
        "XXXX".encodeToByteArray().copyInto(badPng, 12)
        assertEquals(null, ImageProbe.probe(badPng))
        // WebP with lying RIFF size (claims beyond buffer).
        val badWebp = webpVp8Bytes().copyOf()
        badWebp[4] = 0xFF.toByte()
        badWebp[5] = 0xFF.toByte()
        badWebp[6] = 0x7F.toByte()
        assertEquals(null, ImageProbe.probe(badWebp))
        // WebP with bad VP8 start code.
        val badVp8 = webpVp8Bytes().copyOf()
        badVp8[23] = 0x00
        assertEquals(null, ImageProbe.probe(badVp8))
        // JPEG with malformed segment length (length < 2).
        val badJpeg = jpegBytes().copyOf()
        badJpeg[4] = 0x00
        badJpeg[5] = 0x01
        assertEquals(null, ImageProbe.probe(badJpeg))
        // JPEG with no SOF (SOI + EOI only).
        assertEquals(
            null,
            ImageProbe.probe(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())),
        )
    }

    @Test
    fun oversizedDeclaredDimensionsRejected() {
        assertEquals(null, ImageProbe.probe(pngBytes(200_000, 200_000)))
        assertEquals(null, ImageProbe.probe(gifBytes(0, 600)))
    }

    @Test
    fun jpegSkipsMultipleMetadataSegments() {
        // Two APP segments before SOF.
        val base = jpegBytes(640, 480, withApp0 = true).toMutableList()
        val app1 = mutableListOf<Byte>()
        app1 += byteArrayOf(0xFF.toByte(), 0xE1.toByte()).toList()
        app1 += byteArrayOf(0x00, 0x0A).toList() // length 10 = 2 header + 8 payload
        app1 += "Exif\u0000\u0000AB".encodeToByteArray().toList()
        val withExtra = (base.subList(0, 2) + app1 + base.subList(2, base.size)).toByteArray()
        val info = ImageProbe.probe(withExtra)!!
        assertEquals(640, info.width)
        assertEquals(480, info.height)
    }

    @Test
    fun crc32KnownVector() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
    }
}
