package com.yet.pets.io

import com.yet.pets.core.CodexV2Error
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** Public directory boundary and independent encoder coverage of the raw V2 PNG guard. */
class CodexV2DirectoryTest {
    @Test fun realEncoderPngLoadsThroughPublicDirectoryAndZip() {
        val bytes=ByteArrayOutputStream().use { out ->
            assertTrue(ImageIO.write(BufferedImage(1536,2288,BufferedImage.TYPE_INT_ARGB),"png",out))
            out.toByteArray()
        }
        val json="""{"spriteVersionNumber":2,"spritesheetPath":"sheet.png"}"""
        val root=Files.createTempDirectory("pets-v2-real-png")
        try {
            Files.write(root.resolve("pet.json"),json.encodeToByteArray())
            Files.write(root.resolve("sheet.png"),bytes)
            val directory=assertIs<PetLoadOutcome.Success>(PetLoader.loadCodexV2Directory(root.toString()))
            assertEquals(root.fileName.toString(),directory.definition.id)
            val archive=assertIs<PetLoadOutcome.Success>(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("pet.json",json.encodeToByteArray()),ZipEntrySpec("sheet.png",bytes))),root.fileName.toString()))
            assertEquals(directory.definition,archive.definition)
            assertContentEquals(bytes,directory.spritesheetBytes)
        } finally { root.toFile().walkBottomUp().forEach { it.delete() } }
    }
    @Test fun publicDirectoryRequiresPetJsonAndReturnsTypedSemanticFailure() {
        val root=Files.createTempDirectory("pets-v2-directory")
        try {
            Files.write(root.resolve("avatar.json"),"""{"spriteVersionNumber":2}""".encodeToByteArray())
            Files.write(root.resolve("spritesheet.webp"),webpVp8Bytes(1536,2288))
            assertIs<PetLoadError.MissingManifest>(assertIs<PetLoadOutcome.Failure>(PetLoader.loadCodexV2Directory(root.toString())).errors.single())
            Files.write(root.resolve("pet.json"),"{}".encodeToByteArray())
            val error=assertIs<PetLoadError.CodexV2CompatibilityFailure>(assertIs<PetLoadOutcome.Failure>(PetLoader.loadCodexV2Directory(root.toString())).errors.single())
            assertIs<CodexV2Error.UnsupportedVersion>(error.report.errors.single())
        } finally { root.toFile().walkBottomUp().forEach { it.delete() } }
    }
}
