package com.yet.pets.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private fun genericManifest(
    defaultAnimation: String = "blink",
    animations: String = """[{"key":"blink","loopStart":0,"frames":[{"index":0,"durationMs":120},{"index":5,"durationMs":120}]},{"key":"dance","frames":[{"index":1,"durationMs":100}]}]""",
    frame: String = """{"width":32,"height":40}""",
): String = """
    {
      "schema": "pets-kmp",
      "schemaVersion": 1,
      "id": "tiny",
      "displayName": "Tiny",
      "frame": $frame,
      "defaultAnimation": "$defaultAnimation",
      "animations": $animations
    }
""".trimIndent()

/**
 * Generic loader tests: explicit Pets KMP entry points reuse the identical
 * secure implementation; Codex and generic loaders never sniff each other.
 */
class PetsKmpLoadingTest {

    @Test
    fun genericZipLoadsSyntheticPet() {
        val sheet = webpVp8Bytes(width = 96, height = 80)
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", genericManifest().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", sheet),
            ),
        )
        val outcome = PetLoader.loadPetsKmpZip(zip)
        assertIs<PetLoadOutcome.Success>(outcome)
        assertEquals(3, outcome.definition.geometry.columns)
        assertEquals(2, outcome.definition.geometry.rows)
        assertEquals(6, outcome.definition.frameCount)
        assertEquals("blink", outcome.definition.defaultAnimationKey.value)
    }

    @Test
    fun genericDirectoryLoadsSyntheticPet() {
        val fs = FakeFileSystem()
        fs.writePetPackage(
            "/pkg",
            genericManifest(),
            sheetBytes = webpVp8Bytes(width = 96, height = 80),
        )
        // Directory loader uses the platform filesystem internally; exercise the
        // shared tail through the ZIP path here and the directory path on JVM.
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", genericManifest().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes(width = 96, height = 80)),
            ),
        )
        val outcome = PetLoader.loadPetsKmpZip(zip)
        assertIs<PetLoadOutcome.Success>(outcome)
        assertTrue(fs.exists("/pkg/pet.json".toPath()))
    }

    @Test
    fun codexLoaderRejectsGenericManifest() {
        val sheet = webpVp8Bytes(width = 96, height = 80)
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", genericManifest().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", sheet),
            ),
        )
        val outcome = PetLoader.loadPetZip(zip)
        assertIs<PetLoadOutcome.Failure>(outcome)
    }

    @Test
    fun genericLoaderRejectsCodexManifest() {
        val sheet = webpVp8Bytes()
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", "{}".encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", sheet),
            ),
        )
        val outcome = PetLoader.loadPetsKmpZip(zip)
        assertIs<PetLoadOutcome.Failure>(outcome)
        val error = outcome.errors.single()
        assertIs<PetLoadError.PetsKmpCompatibilityFailure>(error)
    }

    @Test
    fun genericZipEnforcesTraversalProtection() {
        val sheet = webpVp8Bytes(width = 96, height = 80)
        val manifest = genericManifest().replace(
            "\"frame\"",
            "\"spritesheetPath\": \"../evil.webp\", \"frame\"",
        )
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifest.encodeToByteArray()),
                ZipEntrySpec("evil.webp", sheet),
            ),
        )
        val outcome = PetLoader.loadPetsKmpZip(zip)
        assertIs<PetLoadOutcome.Failure>(outcome)
        assertTrue(outcome.errors.any { it is PetLoadError.InvalidSpritesheetPath })
    }

    @Test
    fun genericZipRejectsDuplicateEntries() {
        val sheet = webpVp8Bytes(width = 96, height = 80)
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", genericManifest().encodeToByteArray()),
                ZipEntrySpec("PET.JSON", genericManifest().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", sheet),
            ),
        )
        val outcome = PetLoader.loadPetsKmpZip(zip)
        assertIs<PetLoadOutcome.Failure>(outcome)
        assertTrue(outcome.errors.any { it is PetLoadError.DuplicateEntry })
    }
}
