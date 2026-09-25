package com.yet.pets.io

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real-filesystem JVM tests for security behavior that must match actual host
 * canonicalization/symlink semantics. FakeFileSystem alone cannot prove these.
 *
 * Each test gets an isolated temp root, cleaned up afterwards.
 */
class RealFilesystemSecurityTest {

    private val roots = mutableListOf<java.nio.file.Path>()

    private fun newRoot(): java.nio.file.Path {
        val root = Files.createTempDirectory("codex-pets-io-test")
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

    private fun writePackage(dir: java.nio.file.Path, sheetName: String = "spritesheet.webp") {
        dir.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = sheetName))
        dir.resolve(sheetName).toFile().writeBytes(webpVp8Bytes())
    }

    private fun loadDir(dir: java.nio.file.Path): PetLoadOutcome =
        PetLoader.loadPetDirectory(dir.toString(), PetPackageLimits.Default)

    @Test
    fun inRootFileSymlinkAllowed() {
        val root = newRoot()
        val target = root.resolve("real.webp")
        target.toFile().writeBytes(webpVp8Bytes())
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "link.webp"))
        root.resolve("link.webp").createSymbolicLinkPointingTo(target)
        val outcome = loadDir(root)
        assertIs<PetLoadOutcome.Success>(outcome, "in-root symlink must load: $outcome")
    }

    @Test
    fun inRootDirectorySymlinkAllowed() {
        val root = newRoot()
        val realDir = root.resolve("assets")
        realDir.createDirectories()
        realDir.resolve("sheet.webp").toFile().writeBytes(webpVp8Bytes())
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "linked/sheet.webp"))
        root.resolve("linked").createSymbolicLinkPointingTo(realDir)
        val outcome = loadDir(root)
        assertIs<PetLoadOutcome.Success>(outcome, "in-root dir symlink must load: $outcome")
    }

    @Test
    fun fileSymlinkEscapeRejected() {
        val root = newRoot()
        val outside = Files.createTempFile("outside", ".webp")
        roots.add(outside)
        outside.toFile().writeBytes(webpVp8Bytes())
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "evil.webp"))
        root.resolve("evil.webp").createSymbolicLinkPointingTo(outside)
        val outcome = loadDir(root)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.PathEscape>(failure.errors.single())
    }

    @Test
    fun parentDirectorySymlinkEscapeRejected() {
        val root = newRoot()
        val outsideDir = Files.createTempDirectory("outside-dir")
        roots.add(outsideDir)
        outsideDir.resolve("sheet.webp").toFile().writeBytes(webpVp8Bytes())
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "linked/sheet.webp"))
        root.resolve("linked").createSymbolicLinkPointingTo(outsideDir)
        val outcome = loadDir(root)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.PathEscape>(failure.errors.single())
    }

    @Test
    fun danglingSymlinkIsTypedFailure() {
        val root = newRoot()
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "ghost.webp"))
        root.resolve("ghost.webp").createSymbolicLinkPointingTo(root.resolve("nowhere.webp"))
        val outcome = loadDir(root)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertTrue(
            failure.errors.single() is PetLoadError.DanglingSymlink ||
                failure.errors.single() is PetLoadError.MissingSpritesheet,
            "got ${failure.errors.single()}",
        )
    }

    @Test
    fun foobarConfusionRejected() {
        // /root/foo must NOT contain /root/foobar: segment-aware containment.
        val root = newRoot()
        val sibling = root.resolveSibling("foobar-${root.fileName}")
        sibling.createDirectories()
        roots.add(sibling)
        sibling.resolve("sheet.webp").toFile().writeBytes(webpVp8Bytes())
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "sheet.webp"))
        // Symlink root/foo -> sibling dir, then address root/foo/sheet.webp.
        root.resolve("foo").createSymbolicLinkPointingTo(sibling)
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "foo/sheet.webp"))
        val outcome = loadDir(root)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        // Either escape (canonical target outside root) — never silent success.
        assertTrue(
            failure.errors.single() is PetLoadError.PathEscape ||
                failure.errors.single() is PetLoadError.MissingSpritesheet,
            "got ${failure.errors.single()}",
        )
    }

    @Test
    fun canonicalEscapeViaNestedDotdotRejected() {
        val root = newRoot()
        root.resolve("pet.json").toFile().writeText(manifestJson(spritesheetPath = "sub/../../evil.webp"))
        val outcome = loadDir(root)
        // Lexical `..` rejection fires before canonicalization.
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.InvalidSpritesheetPath>(failure.errors.single())
    }

    @Test
    fun validRealDirectoryLoads() {
        val root = newRoot()
        writePackage(root)
        val outcome = loadDir(root)
        val success = assertIs<PetLoadOutcome.Success>(outcome)
        assertTrue(success.definition.id == root.fileName.toString())
    }

    @Test
    fun realManifestLimitEnforced() {
        val root = newRoot()
        root.resolve("pet.json").toFile().writeText(manifestJson(description = "x".repeat(70 * 1024)))
        root.resolve("spritesheet.webp").toFile().writeBytes(webpVp8Bytes())
        val outcome = loadDir(root)
        val failure = assertIs<PetLoadOutcome.Failure>(outcome)
        assertIs<PetLoadError.LimitExceeded>(failure.errors.single())
    }

    @Test
    fun fileSystemSystemUsedWithoutExplicitHandle() {
        // Public String API resolves against the real filesystem.
        val root = newRoot()
        writePackage(root)
        val fsOutcome = loadPetDirectory(FileSystem.SYSTEM, root.toString().toPath(), PetPackageLimits.Default)
        assertIs<PetLoadOutcome.Success>(fsOutcome)
    }
}
