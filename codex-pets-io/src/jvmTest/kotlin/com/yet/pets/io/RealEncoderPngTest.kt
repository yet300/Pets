package com.yet.pets.io

import com.yet.pets.io.internal.image.ImageProbe
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-encoder PNG coverage (F-04/F-16): `javax.imageio` produces the fixture,
 * so the probe is validated against an independent implementation rather than
 * only self-built headers.
 */
class RealEncoderPngTest {

    private fun encodePng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.background = java.awt.Color(10, 20, 30, 40)
        graphics.clearRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", out), "no PNG writer available")
        return out.toByteArray()
    }

    @Test
    fun realEncoderPngProbes() {
        val bytes = encodePng(1536, 1872)
        val info = ImageProbe.probe(bytes)
        assertTrue(info != null, "real PNG must probe")
        assertEquals(1536, info.width)
        assertEquals(1872, info.height)
    }

    @Test
    fun realEncoderPngBadIhdrCrcRejected() {
        val bytes = encodePng(64, 64)
        // IHDR chunk: 8-byte sig + 4 length + 4 type + 13 payload + 4 CRC.
        // Corrupt one CRC byte (offset 8 + 4 + 4 + 13 = 29).
        val corrupted = bytes.copyOf()
        corrupted[29] = (corrupted[29] + 1).toByte()
        assertEquals(null, ImageProbe.probe(corrupted))
    }

    @Test
    fun realEncoderPngLoadsThroughDirectory() {
        val root = Files.createTempDirectory("real-png-dir")
        try {
            root.resolve("pet.json").toFile().writeText(manifestJson())
            root.resolve("spritesheet.webp").toFile().writeBytes(encodePng(1536, 1872))
            val outcome = PetLoader.loadPetDirectory(root.toString(), PetPackageLimits.Default)
            assertTrue(outcome is PetLoadOutcome.Success, "got $outcome")
        } finally {
            root.toFile().walkBottomUp().forEach { it.delete() }
        }
    }
}
