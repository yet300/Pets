package com.yet.pets.compose

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full format matrix on JVM/Desktop (real Skia decode): every format io/core
 * recognize (JPEG, PNG, GIF, WebP) decodes to the expected atlas dimensions,
 * and malformed/truncated inputs fail safely without crashing.
 *
 * PNG/JPEG/GIF fixtures are generated at test time via `javax.imageio` (no
 * binaries); WebP has no JDK encoder, so a minimal 1x1 VP8 lossy WebP test
 * vector is embedded as Base64.
 */
class AtlasDecoderFormatTest {

    private fun encodeImage(width: Int, height: Int, format: String, imageType: Int): ByteArray {
        val image = BufferedImage(width, height, imageType)
        val graphics = image.createGraphics()
        graphics.paint = java.awt.Color(120, 40, 200)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, format, out), "no $format writer")
        return out.toByteArray()
    }

    @Test
    fun pngDecodesWithExpectedDimensions() {
        val bytes = encodeImage(16, 12, "png", BufferedImage.TYPE_INT_ARGB)
        val decoded = decodeAtlasBytes(bytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(16, bitmap.width)
        assertEquals(12, bitmap.height)
    }

    @Test
    fun jpegDecodesWithExpectedDimensions() {
        val bytes = encodeImage(16, 12, "jpg", BufferedImage.TYPE_INT_RGB)
        val decoded = decodeAtlasBytes(bytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(16, bitmap.width)
        assertEquals(12, bitmap.height)
    }

    @Test
    fun gifDecodesFirstFrameWithExpectedDimensions() {
        val bytes = encodeImage(16, 12, "gif", BufferedImage.TYPE_BYTE_INDEXED)
        val decoded = decodeAtlasBytes(bytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(16, bitmap.width)
        assertEquals(12, bitmap.height)
    }

    @Test
    fun webpDecodesWithExpectedDimensions() {
        // Minimal 1x1 VP8 lossy WebP; VP8L and VP8X use independent vectors
        // in the common JVM/iOS format suite.
        val bytes = Base64.decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        assertTrue(bytes.size < 100, "fixture must stay tiny, was ${bytes.size}")
        val decoded = decodeAtlasBytes(bytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
    }

    @Test
    fun truncatedJpegFailsSafely() {
        val bytes = encodeImage(32, 32, "jpg", BufferedImage.TYPE_INT_RGB)
        val decoded = decodeAtlasBytes(bytes.copyOf(bytes.size / 3))
        assertTrue(decoded.state is PetAtlasState.Failed, "expected Failed, got ${decoded.state}")
    }

    @Test
    fun corruptHeaderFailsSafely() {
        // Valid PNG length, invalid signature: the decoder must reject, not crash.
        val bytes = encodeImage(8, 8, "png", BufferedImage.TYPE_INT_ARGB)
        bytes[0] = 0x00
        bytes[1] = 0x11
        val decoded = decodeAtlasBytes(bytes)
        assertTrue(decoded.state is PetAtlasState.Failed, "expected Failed, got ${decoded.state}")
    }
}
