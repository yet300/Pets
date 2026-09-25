package com.yet.pets.io

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Directory and ZIP loaders must compose to equivalent normalized definitions
 * for identical manifest + image bytes (only the fallbackId source may differ).
 */
class LoaderEquivalenceTest {

    private val manifest = manifestJson(
        id = "same",
        displayName = "Same",
        description = "identical inputs",
        extra = "\"animations\": {\"dance\": {\"frames\": [0, 1], \"fps\": 4.0}}",
    )
    private val sheet = webpVp8Bytes()

    @Test
    fun directoryAndZipAgree() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifest, sheet)
        val fromDir = loadPetDirectory(fs, "/pkg".toPath(), PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(fromDir)

        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifest.encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", sheet),
            ),
        )
        val fromZip = PetLoader.loadPetZip(zip, "pkg", PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(fromZip)

        assertEquals(fromDir.definition, fromZip.definition)
        assertEquals(
            fromDir.definition.animationKeys.map { it.value },
            fromZip.definition.animationKeys.map { it.value },
        )
        assertEquals(fromDir.definition.geometry, fromZip.definition.geometry)
        assertTrue(fromDir.spritesheetBytes.contentEquals(fromZip.spritesheetBytes))
    }

    @Test
    fun equivalenceHoldsForDeflatedAndNestedVariants() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/bella", manifest, sheet)
        val fromDir = loadPetDirectory(fs, "/bella".toPath(), PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(fromDir)

        val nestedDeflated = buildZip(
            listOf(
                ZipEntrySpec("bella/pet.json", manifest.encodeToByteArray(), ZIP_METHOD_DEFLATED),
                ZipEntrySpec("bella/spritesheet.webp", sheet, ZIP_METHOD_DEFLATED),
            ),
        )
        val fromZip = PetLoader.loadPetZip(nestedDeflated, "ignored", PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(fromZip)

        // Same package identity: nested folder name matches the directory name.
        assertEquals(fromDir.definition, fromZip.definition)
    }

    @Test
    fun outcomeEqualityIsContentBased() {
        val a = PetLoadOutcome.Success(fromDirDefinition(), webpVp8Bytes())
        val b = PetLoadOutcome.Success(fromDirDefinition(), webpVp8Bytes())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun fromDirDefinition(): com.yet.pets.core.PetDefinition {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifest, sheet)
        val outcome = loadPetDirectory(fs, "/pkg".toPath(), PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(outcome)
        return outcome.definition
    }
}

class OutcomeAndLimitsTest {

    @Test
    fun defaultLimitsMatchApprovedValues() {
        val limits = PetPackageLimits.Default
        assertEquals(64L * 1024L, limits.maxManifestBytes)
        assertEquals(8L * 1024L * 1024L, limits.maxSpritesheetBytes)
        assertEquals(64, limits.maxZipEntries)
        assertEquals(16L * 1024L * 1024L, limits.maxCompressedArchiveBytes)
        assertEquals(32L * 1024L * 1024L, limits.maxUncompressedArchiveBytes)
        assertEquals(32L * 1024L * 1024L, limits.maxEntryUncompressedBytes)
        assertEquals(100.0, limits.maxCompressionRatio)
    }

    @Test
    fun limitsConstructorIsTotal() {
        // No require(): arbitrary values construct; load time judges them.
        PetPackageLimits(maxManifestBytes = -1L, maxCompressionRatio = 0.0)
    }

    @Test
    fun insaneLimitsFailAtLoadTime() {
        val badLimits = listOf(
            PetPackageLimits(maxManifestBytes = 0L),
            PetPackageLimits(maxZipEntries = -2),
            PetPackageLimits(maxCompressionRatio = 0.5),
            PetPackageLimits(maxCompressionRatio = Double.NaN),
        )
        for (limits in badLimits) {
            val outcome = PetLoader.loadPetZip(petZipForLimits(), "pet", limits)
            assertIs<PetLoadOutcome.Failure>(outcome)
            assertIs<PetLoadError.InvalidLimits>(outcome.errors.single())
        }
    }

    private fun petZipForLimits(): ByteArray = buildZip(
        listOf(
            ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
            ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
        ),
    )

    @Test
    fun failureSnapshotsErrors() {
        val errors = mutableListOf<PetLoadError>(PetLoadError.MissingManifest("x"))
        val failure = PetLoadOutcome.Failure(errors)
        errors.clear()
        assertEquals(1, failure.errors.size)
    }

    @Test
    fun errorMessagesAreStable() {
        assertEquals(
            "missing pet.json or avatar.json in /pkg",
            PetLoadError.MissingManifest("/pkg").message,
        )
        assertEquals(
            "missing spritesheet s.webp",
            PetLoadError.MissingSpritesheet("s.webp").message,
        )
    }
}
