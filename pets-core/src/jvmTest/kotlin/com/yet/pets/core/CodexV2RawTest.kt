package com.yet.pets.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class CodexV2RawTest {
    private val manifest = """{"spriteVersionNumber":2}""".encodeToByteArray()
    private fun image(format: String): ByteArray {
        val buffer=ByteArrayOutputStream()
        val rgb=if(format=="jpeg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        assertTrue(ImageIO.write(BufferedImage(1536,2288,rgb),format,buffer))
        return buffer.toByteArray()
    }
    private fun fixture(name: String): ByteArray {
        val file=listOf(File("../assets/kodee/$name"),File("assets/kodee/$name")).first { it.isFile }
        return file.readBytes()
    }
    @Test fun originalKodeeRawPairSucceeds() {
        val success=assertIs<CodexV2ParseOutcome.Success>(CodexV2PetPackageParser.parse(fixture("pet.json"),fixture("spritesheet.webp")))
        assertEquals("kodee",success.definition.id); assertEquals("Kodee",success.definition.displayName)
        assertEquals(88,success.definition.frameCount)
    }
    @Test fun realStaticPngPairSucceedsAndRawWrongFormatsFail() {
        assertIs<CodexV2ParseOutcome.Success>(CodexV2PetPackageParser.parse(manifest,image("png")))
        for (format in listOf("jpeg","gif")) {
            assertIs<CodexV2Error.UnsupportedSpritesheetFormat>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(manifest,image(format))).report.errors.single())
        }
    }
    @Test fun apngControlChunkIsRejectedAndPayloadTextIsNotScanned() {
        val png=image("png")
        // A valid CRC on an APNG control chunk, inserted after IHDR.
        val payload=byteArrayOf(0,0,0,2,0,0,0,0)
        val animated=png.copyOfRange(0,33)+chunk("acTL",payload)+png.copyOfRange(33,png.size)
        assertIs<CodexV2Error.InvalidSpritesheetBytes>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(manifest,animated)).report.errors.single())
        // Harmless text containing acTL must not be mistaken for a chunk type.
        val static=png.copyOfRange(0,33)+chunk("tEXt","note\u0000acTL".encodeToByteArray())+png.copyOfRange(33,png.size)
        assertIs<CodexV2ParseOutcome.Success>(CodexV2PetPackageParser.parse(manifest,static))
        for (bad in listOf(png.copyOf(33),png.copyOf(png.size-1),png.copyOf().also { it[33]=0x7f })) {
            assertIs<CodexV2Error.InvalidSpritesheetBytes>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(manifest,bad)).report.errors.single())
        }
    }
    @Test fun rawInputBoundsAreEnforcedBeforeInspection() {
        val png=image("png")
        val over=ByteArray(PetInputLimits.MAX_MANIFEST_BYTES+1)
        assertIs<CodexV2Error.InputLimitExceeded>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(over,png)).report.errors.single())
        assertIs<CodexV2Error.InputLimitExceeded>(assertIs<CodexV2SpritesheetPathOutcome.Failure>(CodexV2PetPackageParser.spritesheetPathOf(over)).error)
        for(size in listOf(PetInputLimits.MAX_SPRITESHEET_BYTES-1,PetInputLimits.MAX_SPRITESHEET_BYTES)) {
            // Valid ancillary PNG padding keeps both exact byte boundaries real.
            val padding=chunk("tEXt",ByteArray(size-png.size-12))
            val filled=png.copyOfRange(0,png.size-12)+padding+png.copyOfRange(png.size-12,png.size)
            assertEquals(size,filled.size)
            assertIs<CodexV2ParseOutcome.Success>(CodexV2PetPackageParser.parse(manifest,filled))
        }
    }
    private fun chunk(name: String,payload: ByteArray): ByteArray {
        val type=name.encodeToByteArray(); val crc=java.util.zip.CRC32().apply { update(type); update(payload) }.value
        return ByteArray(4) { (payload.size ushr (24-8*it)).toByte() }+type+payload+ByteArray(4) { (crc ushr (24-8*it)).toByte() }
    }
}
