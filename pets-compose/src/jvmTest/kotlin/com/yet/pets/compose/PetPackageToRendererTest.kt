package com.yet.pets.compose

import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.samplePetAnimation
import com.yet.pets.io.PetLoadOutcome
import com.yet.pets.io.PetLoader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-shape integration fixture: a legal CLI V1 package (1536x1872 atlas,
 * default manifest) flows from the io loader's public outcome into the
 * compose renderer through public APIs only.
 *
 * The atlas is generated at test time (blank grayscale PNG: fast to encode,
 * tiny on disk) so no binary or third-party asset is vendored. This fixture
 * doubles as proof that compose consumes `PetLoadOutcome.Success` without
 * depending on io in production (io appears only in this jvmTest scope).
 */
@OptIn(ExperimentalTestApi::class)
class PetPackageToRendererTest {

    private fun atlasPng(width: Int = 1536, height: Int = 1872): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", out), "no PNG writer")
        return out.toByteArray()
    }

    private fun packageZip(manifestJson: String, spritesheetName: String, spritesheet: ByteArray): ByteArray {
        // STORED entries with upfront sizes/crc: java.util.zip then emits no
        // data descriptors, which the io loader rejects (like the CLI tools
        // that author real packages, sizes are known before writing).
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            addStored(zip, "pet.json", manifestJson.toByteArray(Charsets.UTF_8))
            addStored(zip, spritesheetName, spritesheet)
        }
        return out.toByteArray()
    }

    private fun addStored(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = java.util.zip.CRC32()
        crc.update(data)
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun assertPublicRender(definition: PetDefinition, bytes: ByteArray) {
        runComposeUiTest {
            var state: PetPlayerState? = null
            setContent {
                val player = rememberPetPlayerState(definition, bytes)
                state = player
                CodexPet(player)
            }
            waitUntil(timeoutMillis = 10_000) { state?.atlasState == PetAtlasState.Ready }
            onNodeWithTag(CodexPetTag).assertExists()
            assertEquals(0, state?.currentSample?.spriteIndex)
            val rect = assertNotNull(definition.geometry.sourceRectForOrNull(state!!.currentSample.spriteIndex))
            assertEquals(192, rect.width)
            assertEquals(208, rect.height)
        }
    }

    @Test
    fun rawBytesFlowUsesOnlyPublicParserAndRenderer() {
        val image = atlasPng()
        val parsed = PetPackageParser.parse("{}".encodeToByteArray(), image, "raw")
        val success = assertIs<PetParseOutcome.Success>(parsed)
        assertPublicRender(success.definition, image)
    }

    @Test
    fun ioSuccessFeedsComposeRenderer() {
        val zip = packageZip(
            manifestJson = """{"id": "bella", "displayName": "Bella", "spritesheetPath": "spritesheet.png"}""",
            spritesheetName = "spritesheet.png",
            spritesheet = atlasPng(),
        )
        val outcome = PetLoader.loadPetZip(zip, fallbackId = "bella")
        assertIs<PetLoadOutcome.Success>(outcome, "expected io success, got $outcome")

        // Manifest id wins over the caller fallback.
        assertEquals("bella", outcome.definition.id)
        assertEquals(1536, outcome.definition.geometry.atlasWidth)
        assertEquals(1872, outcome.definition.geometry.atlasHeight)

        // The handed-off bytes decode to exactly one atlas of profile size.
        val decoded = decodeAtlasBytes(outcome.spritesheetBytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(1536, bitmap.width)
        assertEquals(1872, bitmap.height)

        // The player starts on the requested animation's frame zero and the
        // renderer resolves its source region from core geometry.
        val state = PetPlayerState(outcome.definition, decoded, PetAnimations.Idle) { 0L }
        assertEquals(
            samplePetAnimation(outcome.definition, PetAnimations.Idle, 0L),
            state.currentSample,
        )
        val params = assertNotNull(state.drawParamsFor(state.currentSample.spriteIndex))
        assertEquals(0, params.srcLeft)
        assertEquals(0, params.srcTop)
        assertEquals(192, params.srcWidth)
        assertEquals(208, params.srcHeight)
        assertPublicRender(outcome.definition, outcome.spritesheetBytes)
    }
}
