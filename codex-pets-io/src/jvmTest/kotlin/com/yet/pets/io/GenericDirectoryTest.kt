package com.yet.pets.io

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun genericDirectoryManifest(
    spritesheetPath: String? = null,
    defaultAnimation: String = "blink",
): String {
    val path = if (spritesheetPath != null) "\"spritesheetPath\": \"$spritesheetPath\"," else ""
    return """
        {
          "schema": "pets-kmp",
          "schemaVersion": 1,
          "id": "tiny",
          "displayName": "Tiny",
          $path
          "frame": {"width": 32, "height": 40},
          "defaultAnimation": "$defaultAnimation",
          "animations": [
            {"key": "blink", "loopStart": 0, "frames": [{"index": 0, "durationMs": 120}, {"index": 5, "durationMs": 120}]},
            {"key": "dance", "frames": [{"index": 1, "durationMs": 100}]}
          ]
        }
    """.trimIndent()
}

/**
 * Real generic directory loading through the PUBLIC
 * [PetLoader.loadPetsKmpDirectory] entry point (JVM temp dirs): valid pets
 * load, traversal is rejected, the spritesheet default applies, and an
 * avatar.json-only package does NOT load (generic discovery is pet.json
 * only). Each test gets an isolated temp root, cleaned up afterwards.
 */
class GenericDirectoryTest {

    private val roots = mutableListOf<java.nio.file.Path>()

    private fun newRoot(): java.nio.file.Path {
        val root = Files.createTempDirectory("codex-pets-generic-dir-test")
        roots.add(root)
        return root
    }

    @AfterTest
    fun tearDown() {
        for (root in roots) {
            root.toFile().walkBottomUp().forEach { it.delete() }
        }
        roots.clear()
    }

    private fun writePackage(
        dir: java.nio.file.Path,
        manifest: String,
        sheetName: String = "spritesheet.webp",
        manifestName: String = "pet.json",
    ) {
        dir.resolve(manifestName).toFile().writeText(manifest)
        dir.resolve(sheetName).toFile().writeBytes(webpVp8Bytes(width = 96, height = 80))
    }

    @Test
    fun validGenericDirectoryLoads() {
        val root = newRoot()
        writePackage(root, genericDirectoryManifest())
        val outcome = PetLoader.loadPetsKmpDirectory(root.toString())
        assertIs<PetLoadOutcome.Success>(outcome, "valid generic directory must load, got $outcome")
        assertEquals(3, outcome.definition.geometry.columns)
        assertEquals(2, outcome.definition.geometry.rows)
        assertEquals(6, outcome.definition.frameCount)
        assertEquals("tiny", outcome.definition.id)
        assertEquals("blink", outcome.definition.defaultAnimationKey.value)
    }

    @Test
    fun traversalIsRejected() {
        val root = newRoot()
        root.resolve("pet.json").toFile().writeText(
            genericDirectoryManifest(spritesheetPath = "../evil.webp"),
        )
        root.resolve("spritesheet.webp").toFile().writeBytes(webpVp8Bytes(width = 96, height = 80))
        val outcome = PetLoader.loadPetsKmpDirectory(root.toString())
        assertIs<PetLoadOutcome.Failure>(outcome)
        assertTrue(outcome.errors.any { it is PetLoadError.InvalidSpritesheetPath })
    }

    @Test
    fun spritesheetPathDefaultsToSpritesheetWebp() {
        // Absent spritesheetPath resolves to spritesheet.webp.
        val root = newRoot()
        writePackage(root, genericDirectoryManifest())
        val outcome = PetLoader.loadPetsKmpDirectory(root.toString())
        assertIs<PetLoadOutcome.Success>(outcome, "default sheet path must load, got $outcome")
    }

    @Test
    fun avatarJsonOnlyDirectoryDoesNotLoad() {
        // Generic discovery requires pet.json only.
        val root = newRoot()
        writePackage(root, genericDirectoryManifest(), manifestName = "avatar.json")
        val outcome = PetLoader.loadPetsKmpDirectory(root.toString())
        assertIs<PetLoadOutcome.Failure>(outcome, "avatar.json-only generic directory must fail, got $outcome")
        assertTrue(outcome.errors.any { it is PetLoadError.MissingManifest })
    }

    @Test
    fun petJsonPreferredWhenBothPresent() {
        val root = newRoot()
        writePackage(root, genericDirectoryManifest())
        root.resolve("avatar.json").toFile().writeText("{}")
        val outcome = PetLoader.loadPetsKmpDirectory(root.toString())
        assertIs<PetLoadOutcome.Success>(outcome, "pet.json must win, got $outcome")
        assertEquals("blink", outcome.definition.defaultAnimationKey.value)
    }
}
