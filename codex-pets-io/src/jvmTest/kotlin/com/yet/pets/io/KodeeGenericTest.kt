package com.yet.pets.io

import com.yet.pets.core.PetCompatibilityError
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun repoFile(vararg parts: String): File {
    // Walk up from the module dir (or any working dir) to the repo root
    // containing `assets/kodee`, without hard-coding Gradle internals.
    var dir = File(System.getProperty("user.dir"))
    repeat(8) {
        val candidate = File(dir, listOf("assets", "kodee", parts.last()).joinToString(File.separator))
        if (candidate.exists()) return File(dir, (listOf("assets", "kodee") + parts.toList()).joinToString(File.separator))
        dir = dir.parentFile ?: return@repeat
    }
    // Fallback: relative to the module directory.
    return File("assets/kodee/${parts.last()}").let {
        if (it.exists()) it else File("../assets/kodee/${parts.last()}")
    }
}

/**
 * Kodee asset tests through PUBLIC APIs.
 *
 * - The SEPARATE generic manifest (`pet.pets-kmp.json`) plus the EXACT
 *   supplied spritesheet must load as a generic pet (1536x2288, 8x11).
 * - The ORIGINAL `pet.json` (spriteVersionNumber=2) plus the EXACT same
 *   spritesheet must still FAIL the strict Codex V1 loader. Intentional.
 */
class KodeeGenericTest {

    @Test
    fun genericKodeeManifestLoadsExactSpritesheet() {
        val manifestBytes = repoFile("pet.pets-kmp.json").readBytes()
        val sheetBytes = repoFile("spritesheet.webp").readBytes()
        val outcome = PetLoader.loadPetsKmpZip(
            buildZipForTest(manifestBytes, sheetBytes),
            fallbackId = "kodee",
        )
        assertIs<PetLoadOutcome.Success>(outcome, "generic Kodee must load, got $outcome")
        val definition = outcome.definition
        assertEquals(1536, definition.geometry.atlasWidth)
        assertEquals(2288, definition.geometry.atlasHeight)
        assertEquals(8, definition.geometry.columns)
        assertEquals(11, definition.geometry.rows)
        assertEquals(88, definition.frameCount)
        assertEquals("idle", definition.defaultAnimationKey.value)
        for (key in listOf("idle", "wave", "jump", "happy", "rest")) {
            assertTrue(definition.animation(key) != null, "missing animation $key")
        }
    }

    @Test
    fun originalKodeePackageStillRejectedByStrictCodexLoader() {
        val manifestBytes = repoFile("pet.json").readBytes()
        val sheetBytes = repoFile("spritesheet.webp").readBytes()
        val outcome = PetLoader.loadPetZip(
            buildZipForTest(manifestBytes, sheetBytes),
            fallbackId = "kodee",
        )
        assertIs<PetLoadOutcome.Failure>(outcome, "original Kodee must fail Codex V1, got $outcome")
        val failure = outcome.errors.filterIsInstance<PetLoadError.CompatibilityFailure>().single()
        assertTrue(
            failure.report.errors.any { it is PetCompatibilityError.UnsupportedAtlasDimensions },
            "expected UnsupportedAtlasDimensions, got ${failure.report.errors}",
        )
    }

    private fun buildZipForTest(manifestBytes: ByteArray, sheetBytes: ByteArray): ByteArray =
        buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestBytes),
                ZipEntrySpec("spritesheet.webp", sheetBytes),
            ),
        )
}
