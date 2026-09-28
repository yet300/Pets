package com.yet.pets.io

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import com.yet.pets.io.internal.fs.loadPetDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Permanent regression tests for manifest confinement (F-01). The manifest
 * leaf passes the same canonical containment as the spritesheet; the preferred
 * `pet.json` never silently yields to `avatar.json` when unsafe.
 */
class ManifestSecurityTest {

    private fun fs(): FakeFileSystem = FakeFileSystem().apply { allowSymlinks = true }

    private fun regularManifest(
        fs: FakeFileSystem,
        dir: String,
        name: String,
        manifest: String = manifestJson(id = "ok"),
    ) {
        fs.createDirectories(dir.toPath())
        fs.writeText("$dir/$name".toPath(), manifest)
        fs.writeBytes("$dir/spritesheet.webp".toPath(), webpVp8Bytes())
    }

    private fun loadDir(dir: String, fs: FakeFileSystem): PetLoadOutcome =
        loadPetDirectory(fs, dir.toPath(), PetPackageLimits.Default)

    // -- pet.json matrix --

    @Test
    fun petJsonRegularLoads() {
        val fs = fs()
        regularManifest(fs, "/pkg", "pet.json")
        val outcome = loadDir("/pkg", fs)
        assertIs<PetLoadOutcome.Success>(outcome)
        assertEquals("ok", outcome.definition.id)
    }

    @Test
    fun petJsonInRootSymlinkLoads() {
        val fs = fs()
        regularManifest(fs, "/pkg", "real.json", manifestJson(id = "linked"))
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        // Real manifest lives under another name; pet.json links inside the root.
        fs.createSymlink("/pkg/pet.json".toPath(), "/pkg/real.json".toPath())
        val outcome = loadDir("/pkg", fs)
        assertIs<PetLoadOutcome.Success>(outcome)
        assertEquals("linked", outcome.definition.id)
    }

    @Test
    fun petJsonEscapingSymlinkFails() {
        val fs = fs()
        fs.createDirectories("/pkg".toPath())
        fs.createDirectories("/outside".toPath())
        fs.writeText("/outside/secret.json".toPath(), manifestJson(id = "escaped"))
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.createSymlink("/pkg/pet.json".toPath(), "/outside/secret.json".toPath())
        val outcome = loadDir("/pkg", fs)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.PathEscape>(failure.errors.single())
    }

    @Test
    fun petJsonDanglingSymlinkIsDanglingManifest() {
        val fs = fs()
        fs.createDirectories("/pkg".toPath())
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.createSymlink("/pkg/pet.json".toPath(), "/pkg/nowhere.json".toPath())
        val outcome = loadDir("/pkg", fs)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.DanglingManifest>(failure.errors.single())
    }

    // -- avatar.json matrix --

    @Test
    fun avatarJsonRegularLoads() {
        val fs = fs()
        regularManifest(fs, "/pkg", "avatar.json", manifestJson(id = "legacy"))
        val outcome = loadDir("/pkg", fs)
        assertIs<PetLoadOutcome.Success>(outcome)
        assertEquals("legacy", outcome.definition.id)
    }

    @Test
    fun avatarJsonInRootSymlinkLoads() {
        val fs = fs()
        regularManifest(fs, "/pkg", "real.json", manifestJson(id = "linked"))
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.createSymlink("/pkg/avatar.json".toPath(), "/pkg/real.json".toPath())
        val outcome = loadDir("/pkg", fs)
        assertIs<PetLoadOutcome.Success>(outcome)
    }

    @Test
    fun avatarJsonEscapingSymlinkFails() {
        val fs = fs()
        fs.createDirectories("/pkg".toPath())
        fs.createDirectories("/outside".toPath())
        fs.writeText("/outside/secret.json".toPath(), manifestJson(id = "escaped"))
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.createSymlink("/pkg/avatar.json".toPath(), "/outside/secret.json".toPath())
        val outcome = loadDir("/pkg", fs)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.PathEscape>(failure.errors.single())
    }

    @Test
    fun avatarJsonDanglingSymlinkIsDanglingManifest() {
        val fs = fs()
        fs.createDirectories("/pkg".toPath())
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.createSymlink("/pkg/avatar.json".toPath(), "/pkg/nowhere.json".toPath())
        val outcome = loadDir("/pkg", fs)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.DanglingManifest>(failure.errors.single())
    }

    // -- precedence under attack --

    @Test
    fun validPetWinsOverEscapingAvatar() {
        val fs = fs()
        regularManifest(fs, "/pkg", "pet.json", manifestJson(id = "pet"))
        fs.createDirectories("/outside".toPath())
        fs.writeText("/outside/secret.json".toPath(), manifestJson(id = "escaped"))
        fs.allowSymlinks = true
        fs.createSymlink("/pkg/avatar.json".toPath(), "/outside/secret.json".toPath())
        val outcome = loadDir("/pkg", fs)
        assertIs<PetLoadOutcome.Success>(outcome)
        assertEquals("pet", outcome.definition.id)
    }

    @Test
    fun escapingPetFailsDespiteValidAvatar() {
        val fs = fs()
        fs.createDirectories("/pkg".toPath())
        fs.createDirectories("/outside".toPath())
        fs.writeText("/outside/secret.json".toPath(), manifestJson(id = "escaped"))
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.writeText("/pkg/avatar.json".toPath(), manifestJson(id = "avatar"))
        fs.createSymlink("/pkg/pet.json".toPath(), "/outside/secret.json".toPath())
        val outcome = loadDir("/pkg", fs)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.PathEscape>(failure.errors.single())
    }

    @Test
    fun danglingPetFailsDespiteValidAvatar() {
        val fs = fs()
        fs.createDirectories("/pkg".toPath())
        fs.writeBytes("/pkg/spritesheet.webp".toPath(), webpVp8Bytes())
        fs.writeText("/pkg/avatar.json".toPath(), manifestJson(id = "avatar"))
        fs.createSymlink("/pkg/pet.json".toPath(), "/pkg/nowhere.json".toPath())
        val outcome = loadDir("/pkg", fs)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.DanglingManifest>(failure.errors.single())
    }
}
