package com.yet.pets.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun loadZip(
    bytes: ByteArray,
    fallbackId: String = "pet",
    limits: PetPackageLimits = PetPackageLimits.Default,
): PetLoadOutcome = PetLoader.loadPetZip(bytes, fallbackId, limits)

private fun zipSuccess(
    bytes: ByteArray,
    fallbackId: String = "pet",
    limits: PetPackageLimits = PetPackageLimits.Default,
): PetLoadOutcome.Success {
    val outcome = loadZip(bytes, fallbackId, limits)
    assertIs<PetLoadOutcome.Success>(outcome, "expected success, got $outcome")
    return outcome
}

private fun zipFailure(
    bytes: ByteArray,
    fallbackId: String = "pet",
    limits: PetPackageLimits = PetPackageLimits.Default,
): List<PetLoadError> {
    val outcome = loadZip(bytes, fallbackId, limits)
    assertIs<PetLoadOutcome.Failure>(outcome, "expected failure, got $outcome")
    return outcome.errors
}

private fun petZip(
    manifest: String = manifestJson(id = "zipped"),
    sheet: ByteArray = webpVp8Bytes(),
    manifestName: String = "pet.json",
    sheetName: String = "spritesheet.webp",
    prefix: String = "",
): ByteArray {
    val specs = mutableListOf<ZipEntrySpec>()
    specs += ZipEntrySpec(prefix + manifestName, manifest.encodeToByteArray())
    specs += ZipEntrySpec(prefix + sheetName, sheet)
    return buildZip(specs)
}

class ZipLoadingTest {

    @Test
    fun validRootPackage() {
        val sheet = webpVp8Bytes()
        val success = zipSuccess(petZip(sheet = sheet))
        assertEquals("zipped", success.definition.id)
        assertEquals(72, success.definition.frameCount)
        assertTrue(success.spritesheetBytes.contentEquals(sheet))
    }

    @Test
    fun validRootPackageDeflated() {
        val sheet = webpVp8Bytes()
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "d").encodeToByteArray(), ZIP_METHOD_DEFLATED),
                ZipEntrySpec("spritesheet.webp", sheet, ZIP_METHOD_DEFLATED),
            ),
        )
        val success = zipSuccess(zip)
        assertEquals("d", success.definition.id)
        assertTrue(success.spritesheetBytes.contentEquals(sheet))
    }

    @Test
    fun validNestedPackage() {
        // No manifest id: the top-level directory name becomes the fallback identity.
        val success = zipSuccess(petZip(prefix = "bella/", manifest = manifestJson()))
        assertEquals("bella", success.definition.id)
        assertEquals("bella", success.definition.displayName)
    }

    @Test
    fun legacyAvatarJsonPackage() {
        val zip = petZip(manifestName = "avatar.json", manifest = manifestJson(id = "old"))
        assertEquals("old", zipSuccess(zip).definition.id)
    }

    @Test
    fun petJsonPreferredOverAvatarJson() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(id = "p").encodeToByteArray()),
                ZipEntrySpec("avatar.json", manifestJson(id = "a").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertEquals("p", zipSuccess(zip).definition.id)
    }

    @Test
    fun noManifest() {
        val zip = buildZip(listOf(ZipEntrySpec("spritesheet.webp", webpVp8Bytes())))
        val errors = zipFailure(zip)
        assertIs<PetLoadError.MissingManifest>(errors.single())
    }

    @Test
    fun rootPlusNestedAmbiguity() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("other/pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("other/spritesheet.webp", webpVp8Bytes()),
            ),
        )
        val errors = zipFailure(zip)
        assertIs<PetLoadError.AmbiguousPackage>(errors.single())
    }

    @Test
    fun twoNestedRootsAmbiguity() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("a/pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("a/spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("b/pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("b/spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.AmbiguousPackage>(zipFailure(zip).single())
    }

    @Test
    fun duplicateEntriesRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        val errors = zipFailure(zip)
        assertIs<PetLoadError.DuplicateEntry>(errors.single())
    }

    @Test
    fun caseCollisionRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("PET.JSON", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.DuplicateEntry>(zipFailure(zip).single())
    }

    @Test
    fun zipSlipTraversalRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("../evil.webp", byteArrayOf(1)),
            ),
        )
        val errors = zipFailure(zip)
        assertIs<PetLoadError.InvalidArchive>(errors.single())
    }

    @Test
    fun absoluteEntryRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("/abs.webp", byteArrayOf(1)),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipFailure(zip).single())
    }

    @Test
    fun windowsPrefixEntryRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("C:/evil.webp", byteArrayOf(1)),
            ),
        )
        assertIs<PetLoadError.InvalidArchive>(zipFailure(zip).single())
    }

    @Test
    fun symlinkEntryRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("link.webp", byteArrayOf(), unixMode = ZIP_MODE_SYMLINK),
            ),
        )
        val errors = zipFailure(zip)
        assertIs<PetLoadError.SymlinkEntry>(errors.single())
    }

    @Test
    fun encryptedEntryRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("secret.webp", byteArrayOf(1, 2, 3), encrypted = true),
            ),
        )
        assertIs<PetLoadError.EncryptedArchive>(zipFailure(zip).single())
    }

    @Test
    fun corruptCentralDirectoryRejected() {
        val zip = petZip().copyOf()
        // No comment: EOCD occupies the last 22 bytes; read the central-dir
        // offset from it and smash the first central-directory signature.
        val eocdAt = zip.size - 22
        val cdOffset = ((zip[eocdAt + 16].toInt() and 0xFF)) or
            ((zip[eocdAt + 17].toInt() and 0xFF) shl 8) or
            ((zip[eocdAt + 18].toInt() and 0xFF) shl 16) or
            ((zip[eocdAt + 19].toInt() and 0xFF) shl 24)
        zip[cdOffset] = (zip[cdOffset] + 1).toByte()
        val errors = zipFailure(zip)
        assertTrue(errors.single() is PetLoadError.InvalidArchive)
    }

    @Test
    fun truncatedZipRejected() {
        val zip = petZip()
        val errors = zipFailure(zip.copyOfRange(0, zip.size / 2))
        assertIs<PetLoadError.InvalidArchive>(errors.single())
    }

    @Test
    fun notAZipRejected() {
        assertIs<PetLoadError.InvalidArchive>(zipFailure("definitely not a zip".encodeToByteArray()).single())
        assertIs<PetLoadError.InvalidArchive>(zipFailure(ByteArray(0)).single())
    }

    @Test
    fun crcMismatchRejected() {
        val sheet = webpVp8Bytes()
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", sheet, ZIP_METHOD_DEFLATED),
            ),
        )
        // Flip a byte inside the deflated spritesheet payload: inflation either
        // errors or yields different bytes, both of which fail closed.
        val corrupted = zip.copyOf()
        val marker = "spritesheet.webp".encodeToByteArray()
        val nameAt = corrupted.indices.first { i ->
            i + marker.size <= corrupted.size &&
                marker.indices.all { j -> corrupted[i + j] == marker[j] }
        }
        // Builder writes no local extra: payload starts right after the name.
        val dataAt = nameAt + marker.size
        corrupted[dataAt] = (corrupted[dataAt] + 1).toByte()
        val errors = zipFailure(corrupted)
        val error = errors.single()
        assertTrue(error is PetLoadError.InvalidArchive, "got $error")
    }

    @Test
    fun entryCountLimit() {
        val specs = mutableListOf(
            ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
            ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
        )
        repeat(63) { i -> specs += ZipEntrySpec("extra-$i.bin", byteArrayOf(1)) }
        val errors = zipFailure(buildZip(specs))
        val error = errors.single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("zipEntries", error.limit)
    }

    @Test
    fun rawArchiveCapCheckedBeforeIndexing() {
        val big = ByteArray((16L * 1024L * 1024L + 1L).toInt()) { 0x50 }
        val errors = zipFailure(big)
        val error = errors.single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("compressedArchiveBytes", error.limit)
    }

    @Test
    fun uncompressedTotalLimit() {
        // Deflated zeros: compressed total stays small while the declared
        // uncompressed total (40 MiB) trips the cap during indexing.
        val payload = ByteArray(40 * 1024 * 1024) // 40 MiB zeros
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("padding.bin", payload, ZIP_METHOD_DEFLATED),
            ),
        )
        val error = zipFailure(zip).single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("uncompressedArchiveBytes", error.limit)
    }

    @Test
    fun perEntryLimit() {
        // Just over the 8 MiB spritesheet cap but small enough to pass totals.
        val payload = ByteArray(8 * 1024 * 1024 + 16) { 0x41 }
        val withBig = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(extra = "\"spritesheetPath\": \"big.bin\"").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("big.bin", payload),
            ),
        )
        val error = zipFailure(withBig).single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("spritesheetBytes", error.limit)
    }

    @Test
    fun compressionRatioTripwire() {
        // 1 MiB of zeros deflates to ~1 KiB: ratio ~1000x.
        val payload = ByteArray(1024 * 1024)
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
                ZipEntrySpec("bomb.bin", payload, ZIP_METHOD_DEFLATED),
            ),
        )
        val error = zipFailure(zip).single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("compressionRatio", error.limit)
    }

    @Test
    fun compressedTotalLimitIsolated() {
        // Custom tight budget trips during indexing, independent of the raw cap.
        val zip = petZip()
        val limits = PetPackageLimits.Default.copy(maxCompressedArchiveBytes = 64L)
        val error = zipFailure(zip, limits = limits).single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("compressedArchiveBytes", error.limit)
    }

    @Test
    fun manifestTooLarge() {
        val bigManifest = manifestJson(description = "x".repeat(70 * 1024))
        val zip = petZip(manifest = bigManifest)
        val error = zipFailure(zip).single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("manifestBytes", error.limit)
    }

    @Test
    fun spritesheetTooLarge() {
        val big = ByteArray(8 * 1024 * 1024 + 1)
        webpVp8Bytes().copyInto(big)
        val zip = petZip(sheet = big)
        val error = zipFailure(zip).single()
        assertIs<PetLoadError.LimitExceeded>(error)
        assertEquals("spritesheetBytes", error.limit)
    }

    @Test
    fun invalidImageMetadata() {
        val zip = petZip(sheet = byteArrayOf(1, 2, 3, 4, 5))
        assertIs<PetLoadError.UnsupportedImageFormat>(zipFailure(zip).single())
    }

    @Test
    fun wrongAtlasDimensions() {
        val zip = petZip(sheet = webpVp8Bytes(100, 100))
        val error = zipFailure(zip).single()
        assertIs<PetLoadError.CompatibilityFailure>(error)
        assertTrue(!error.report.isCompatible)
    }

    @Test
    fun customAnimationsSurviveZipLoad() {
        val zip = petZip(
            manifest = manifestJson(extra = "\"animations\": {\"dance\": {\"frames\": [0, 1], \"fps\": 2.0}}"),
        )
        val success = zipSuccess(zip)
        val dance = success.definition.animation("dance")!!
        assertEquals(listOf(0, 1), dance.frames.map { it.spriteIndex })
    }

    @Test
    fun rootFallbackIdDefaultAndExplicit() {
        // manifestJson() carries no id: the fallbackId parameter decides.
        val anonymous = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson().encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertEquals("pet", zipSuccess(anonymous).definition.id)
        val explicit = zipSuccess(anonymous, fallbackId = "bella")
        assertEquals("bella", explicit.definition.id)
    }

    @Test
    fun nestedFallbackDerivedFromFolder() {
        assertEquals("wat", zipSuccess(petZip(prefix = "wat/", manifest = manifestJson())).definition.id)
    }

    @Test
    fun assetUnderNestedPrefixRequired() {
        // Manifest at root referencing a nested path resolves under root, not elsewhere.
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(extra = "\"spritesheetPath\": \"sub/sheet.webp\"").encodeToByteArray()),
                ZipEntrySpec("sub/sheet.webp", webpVp8Bytes()),
            ),
        )
        zipSuccess(zip)
        // Same layout but asset at a different prefix -> missing.
        val wrong = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(extra = "\"spritesheetPath\": \"sub/sheet.webp\"").encodeToByteArray()),
                ZipEntrySpec("other/sheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.MissingSpritesheet>(zipFailure(wrong).single())
    }

    @Test
    fun manifestPathEscapeRejected() {
        val zip = buildZip(
            listOf(
                ZipEntrySpec("pet.json", manifestJson(extra = "\"spritesheetPath\": \"../x.webp\"").encodeToByteArray()),
                ZipEntrySpec("spritesheet.webp", webpVp8Bytes()),
            ),
        )
        assertIs<PetLoadError.InvalidSpritesheetPath>(zipFailure(zip).single())
    }
}
