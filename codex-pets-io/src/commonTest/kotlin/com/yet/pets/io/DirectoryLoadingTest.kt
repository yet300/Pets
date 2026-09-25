package com.yet.pets.io

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun loadDir(
    fs: FakeFileSystem,
    dir: String,
    limits: PetPackageLimits = PetPackageLimits.Default,
): PetLoadOutcome = loadPetDirectory(fs, dir.toPath(), limits)

private fun loadDirSuccess(
    fs: FakeFileSystem,
    dir: String,
    limits: PetPackageLimits = PetPackageLimits.Default,
): PetLoadOutcome.Success {
    val outcome = loadDir(fs, dir, limits)
    assertIs<PetLoadOutcome.Success>(outcome, "expected success, got $outcome")
    return outcome
}

private fun loadDirFailure(
    fs: FakeFileSystem,
    dir: String,
    limits: PetPackageLimits = PetPackageLimits.Default,
): List<PetLoadError> {
    val outcome = loadDir(fs, dir, limits)
    assertIs<PetLoadOutcome.Failure>(outcome, "expected failure, got $outcome")
    return outcome.errors
}

class DirectoryLoadingTest {

    @Test
    fun validPetJsonDirectory() {
        val fs = FakeFileSystem()
        val sheet = webpVp8Bytes()
        fs.writePetPackage("/pkg", manifestJson(id = "bella", displayName = "Bella"), sheet)
        val success = loadDirSuccess(fs, "/pkg")
        assertEquals("bella", success.definition.id)
        assertEquals("Bella", success.definition.displayName)
        assertEquals(72, success.definition.frameCount)
        assertTrue(success.spritesheetBytes.contentEquals(sheet))
    }

    @Test
    fun validAvatarJsonLegacyDirectory() {
        val fs = FakeFileSystem()
        fs.writePetPackage(
            "/pkg",
            manifestJson(id = "legacy"),
            webpVp8Bytes(),
            manifestName = "avatar.json",
        )
        val success = loadDirSuccess(fs, "/pkg")
        assertEquals("legacy", success.definition.id)
    }

    @Test
    fun petJsonWinsOverAvatarJson() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(id = "from-pet"), webpVp8Bytes())
        fs.writeText("/pkg/avatar.json".toPath(), manifestJson(id = "from-avatar"))
        val success = loadDirSuccess(fs, "/pkg")
        assertEquals("from-pet", success.definition.id)
    }

    @Test
    fun missingManifest() {
        val fs = FakeFileSystem()
        fs.createDirectories("/pkg".toPath())
        val errors = loadDirFailure(fs, "/pkg")
        val error = errors.single()
        assertIs<PetLoadError.MissingManifest>(error)
    }

    @Test
    fun missingPackageRoot() {
        val fs = FakeFileSystem()
        val errors = loadDirFailure(fs, "/nope")
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { it is PetLoadError.IoFailure || it is PetLoadError.MissingManifest })
    }

    @Test
    fun missingSpritesheet() {
        val fs = FakeFileSystem()
        fs.createDirectories("/pkg".toPath())
        fs.writeText("/pkg/pet.json".toPath(), manifestJson(id = "x"))
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.MissingSpritesheet>(errors.single())
    }

    @Test
    fun blankSpritesheetPathDefaults() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(spritesheetPath = "   "), webpVp8Bytes())
        val success = loadDirSuccess(fs, "/pkg")
        assertEquals(72, success.definition.frameCount)
    }

    @Test
    fun nestedSpritesheetPathAllowed() {
        val fs = FakeFileSystem()
        fs.createDirectories("/pkg/assets".toPath())
        fs.writeText("/pkg/pet.json".toPath(), manifestJson(spritesheetPath = "assets/sheet.webp"))
        fs.writeBytes("/pkg/assets/sheet.webp".toPath(), webpVp8Bytes())
        val success = loadDirSuccess(fs, "/pkg")
        assertEquals(72, success.definition.frameCount)
    }

    @Test
    fun dotSegmentPathAllowed() {
        val fs = FakeFileSystem()
        fs.createDirectories("/pkg/assets".toPath())
        fs.writeText("/pkg/pet.json".toPath(), manifestJson(spritesheetPath = "assets/./sheet.webp"))
        fs.writeBytes("/pkg/assets/sheet.webp".toPath(), webpVp8Bytes())
        loadDirSuccess(fs, "/pkg")
    }

    @Test
    fun parentTraversalRejectedLexically() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(spritesheetPath = "../outside.webp"), webpVp8Bytes())
        // Sibling file exists to prove lexical rejection happens before filesystem probing.
        fs.writeBytes("/outside.webp".toPath(), webpVp8Bytes())
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.InvalidSpritesheetPath>(errors.single())
    }

    @Test
    fun absoluteUnixPathRejected() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(spritesheetPath = "/pkg/spritesheet.webp"), webpVp8Bytes())
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.InvalidSpritesheetPath>(errors.single())
    }

    @Test
    fun windowsDrivePathRejected() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(spritesheetPath = "C:/pets/sheet.webp"), webpVp8Bytes())
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.InvalidSpritesheetPath>(errors.single())
    }

    @Test
    fun backslashPathRejected() {
        val fs = FakeFileSystem()
        // JSON needs a doubled backslash to encode one literal backslash.
        fs.writePetPackage("/pkg", manifestJson(spritesheetPath = "assets\\\\sheet.webp"), webpVp8Bytes())
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.InvalidSpritesheetPath>(errors.single())
    }

    @Test
    fun fakeFilesystemInRootSymlinkAllowed() {
        val fs = FakeFileSystem()
        fs.allowSymlinks = true
        val sheet = webpVp8Bytes()
        fs.createDirectories("/pkg".toPath())
        fs.writeText("/pkg/pet.json".toPath(), manifestJson(spritesheetPath = "link.webp"))
        fs.writeBytes("/pkg/real.webp".toPath(), sheet)
        fs.createSymlink("/pkg/link.webp".toPath(), "/pkg/real.webp".toPath())
        val success = loadDirSuccess(fs, "/pkg")
        assertTrue(success.spritesheetBytes.contentEquals(sheet))
    }

    @Test
    fun manifestTooLarge() {
        val fs = FakeFileSystem()
        val big = manifestJson(description = "x".repeat(70 * 1024))
        fs.writePetPackage("/pkg", big, webpVp8Bytes())
        val errors = loadDirFailure(fs, "/pkg")
        val error = errors.single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("manifestBytes", error.limit)
    }

    @Test
    fun spritesheetTooLarge() {
        val fs = FakeFileSystem()
        val big = ByteArray(8 * 1024 * 1024 + 1)
        webpVp8Bytes().copyInto(big)
        fs.writePetPackage("/pkg", manifestJson(), big)
        val errors = loadDirFailure(fs, "/pkg")
        val error = errors.single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("spritesheetBytes", error.limit)
    }

    @Test
    fun malformedImageFails() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(), byteArrayOf(1, 2, 3, 4))
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.UnsupportedImageFormat>(errors.single())
    }

    @Test
    fun wrongDimensionsBecomeCompatibilityFailure() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(), webpVp8Bytes(100, 100))
        val errors = loadDirFailure(fs, "/pkg")
        val error = errors.single()
        assertIs<PetLoadError.CompatibilityFailure>(error)
        assertTrue(!error.report.isCompatible)
    }

    @Test
    fun explicitManifestIdWins() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pkg", manifestJson(id = "chefito"), webpVp8Bytes())
        assertEquals("chefito", loadDirSuccess(fs, "/pkg").definition.id)
    }

    @Test
    fun missingIdFallsBackToDirectoryBasename() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/mydir", manifestJson(), webpVp8Bytes())
        val success = loadDirSuccess(fs, "/mydir")
        assertEquals("mydir", success.definition.id)
        assertEquals("mydir", success.definition.displayName)
    }

    @Test
    fun invalidLimitsRejectedBeforeTouchingData() {
        val fs = FakeFileSystem()
        val errors = loadDirFailure(fs, "/nothing", PetPackageLimits(maxManifestBytes = 0L))
        val error = errors.single()
        assertIs<PetLoadError.InvalidLimits>(error)
    }

    @Test
    fun malformedManifestBecomesCompatibilityFailure() {
        val fs = FakeFileSystem()
        fs.createDirectories("/pkg".toPath())
        fs.writeText("/pkg/pet.json".toPath(), "{oops")
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        val errors = loadDirFailure(fs, "/pkg")
        assertIs<PetLoadError.CompatibilityFailure>(errors.single())
    }

    @Test
    fun customAnimationsSurviveDirectoryLoad() {
        val fs = FakeFileSystem()
        fs.writePetPackage(
            "/pkg",
            manifestJson(extra = "\"animations\": {\"dance\": {\"frames\": [0, 1], \"fps\": 2.0}}"),
            webpVp8Bytes(),
        )
        val success = loadDirSuccess(fs, "/pkg")
        val dance = success.definition.animation("dance")!!
        assertEquals(listOf(0, 1), dance.frames.map { it.spriteIndex })
        assertEquals(listOf(500_000_000L, 500_000_000L), dance.frames.map { it.durationNanos })
    }

    @Test
    fun allPinnedFormatsLoad() {
        val sheets = mapOf(
            "a.png" to pngBytes(),
            "b.gif" to gifBytes(),
            "c.jpg" to jpegBytes(),
            "d.webp" to webpVp8Bytes(),
        )
        for ((name, bytes) in sheets) {
            val fs = FakeFileSystem()
            fs.writePetPackage("/pkg", manifestJson(spritesheetPath = name), bytes, sheetName = name)
            loadDirSuccess(fs, "/pkg")
        }
    }
}
