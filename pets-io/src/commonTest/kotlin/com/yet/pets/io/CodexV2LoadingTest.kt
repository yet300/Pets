package com.yet.pets.io

import com.yet.pets.core.CodexV2Error
import com.yet.pets.io.internal.fs.loadCodexV2DirectoryFromFs
import com.yet.pets.io.internal.zip.Crc32
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/** WebP fixtures are metadata-only synthetic payloads, not decoded pixel fixtures. */
class CodexV2LoadingTest {
    private val manifest = """{"spriteVersionNumber":2}"""
    private val sheet = webpVp8Bytes(1536, 2288)
    private fun directory(json: String = manifest, bytes: ByteArray = sheet, limits: PetPackageLimits = PetPackageLimits.Default): PetLoadOutcome {
        val fs = FakeFileSystem()
        fs.writePetPackage("/pets/bella", json, bytes)
        return loadCodexV2DirectoryFromFs(fs, "/pets/bella".toPath(), limits)
    }
    private fun zip(json: String = manifest, bytes: ByteArray = sheet, prefix: String = "", fallback: String = "pet", limits: PetPackageLimits = PetPackageLimits.Default): PetLoadOutcome =
        PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec(prefix + "pet.json", json.encodeToByteArray()), ZipEntrySpec(prefix + "spritesheet.webp", bytes))), fallback, limits)
    private fun failure(outcome: PetLoadOutcome): PetLoadError = assertIs<PetLoadOutcome.Failure>(outcome).errors.single()
    private fun coreError(outcome: PetLoadOutcome): CodexV2Error = assertIs<PetLoadError.CodexV2CompatibilityFailure>(failure(outcome)).report.errors.single()

    @Test fun directoryAndRootAndNestedZipLoadExplicitV2() {
        for (outcome in listOf(directory(), zip(), zip(prefix="bella/"))) {
            val success = assertIs<PetLoadOutcome.Success>(outcome)
            assertEquals(88, success.definition.frameCount)
            assertEquals(11, success.definition.geometry.rows)
            assertNotNull(success.definition.animation("look-337.5"))
            assertContentEquals(sheet, success.spritesheetBytes)
        }
    }
    @Test fun identityUsesDirectoryAndZipFallbackUnlessManifestOverrides() {
        assertEquals("bella", assertIs<PetLoadOutcome.Success>(directory()).definition.id)
        assertEquals("chosen", assertIs<PetLoadOutcome.Success>(zip(fallback="chosen")).definition.id)
        assertEquals("bella", assertIs<PetLoadOutcome.Success>(zip(prefix="bella/", fallback="ignored")).definition.id)
        val explicit = """{"spriteVersionNumber":2,"id":"own","displayName":"Own"}"""
        for (outcome in listOf(directory(explicit), zip(explicit))) assertEquals("own", assertIs<PetLoadOutcome.Success>(outcome).definition.id)
    }
    @Test fun directoryAndZipNeverDiscoverLegacyAvatarJson() {
        val fs = FakeFileSystem()
        fs.writePetPackage("/bella", manifest, sheet, manifestName="avatar.json")
        assertIs<PetLoadError.MissingManifest>(failure(loadCodexV2DirectoryFromFs(fs, "/bella".toPath(), PetPackageLimits.Default)))
        for (prefix in listOf("", "bella/")) assertIs<PetLoadError.MissingManifest>(failure(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec(prefix+"avatar.json", manifest.encodeToByteArray()),ZipEntrySpec(prefix+"spritesheet.webp",sheet))))))
    }
    @Test fun preferredPetJsonWinsAndInvalidPreferredManifestNeverFallsBack() {
        for (json in listOf(manifest,"{}")) {
            val fs=FakeFileSystem(); fs.writePetPackage("/bella",json,sheet)
            fs.writeText("/bella/avatar.json".toPath(),manifest)
            val dir=loadCodexV2DirectoryFromFs(fs,"/bella".toPath(),PetPackageLimits.Default)
            val archive=PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("pet.json",json.encodeToByteArray()),ZipEntrySpec("avatar.json",manifest.encodeToByteArray()),ZipEntrySpec("spritesheet.webp",sheet))))
            for(outcome in listOf(dir,archive)) if(json==manifest) assertIs<PetLoadOutcome.Success>(outcome) else assertIs<CodexV2Error.UnsupportedVersion>(coreError(outcome))
        }
    }
    @Test fun semanticFailuresRetainTypedV2Report() {
        for (outcome in listOf(directory("{}"),zip("{}"))) assertIs<CodexV2Error.UnsupportedVersion>(coreError(outcome))
        for (outcome in listOf(directory(bytes=webpVp8Bytes()),zip(bytes=webpVp8Bytes()))) assertIs<CodexV2Error.GeometryMismatch>(coreError(outcome))
        for (outcome in listOf(directory("{"),zip("{"))) assertIs<CodexV2Error.MalformedManifest>(coreError(outcome))
    }
    @Test fun explicitV2RouteDoesNotSniffV1OrGenericFormats() {
        assertIs<CodexV2Error.UnsupportedVersion>(coreError(zip("{}")))
        assertIs<PetLoadOutcome.Failure>(PetLoader.loadPetZip(buildZip(listOf(ZipEntrySpec("pet.json",manifest.encodeToByteArray()),ZipEntrySpec("spritesheet.webp",sheet)))))
        assertIs<PetLoadOutcome.Failure>(PetLoader.loadPetsKmpZip(buildZip(listOf(ZipEntrySpec("pet.json",manifest.encodeToByteArray()),ZipEntrySpec("spritesheet.webp",sheet)))))
    }
    @Test fun unsafeManifestSpritesheetPathsAreRejectedBeforeAssetRead() {
        for(path in listOf("../evil.webp","/evil.webp","C:/evil.webp","assets\\\\evil.webp")) {
            val json="""{"spriteVersionNumber":2,"spritesheetPath":"$path"}"""
            for(outcome in listOf(directory(json),zip(json))) assertIs<PetLoadError.InvalidSpritesheetPath>(failure(outcome),path)
        }
    }
    @Test fun nestedAssetPathLoadsInBothTransports() {
        val json="""{"spriteVersionNumber":2,"spritesheetPath":"assets/sheet.webp"}"""
        val fs=FakeFileSystem(); fs.createDirectories("/bella/assets".toPath()); fs.writeText("/bella/pet.json".toPath(),json); fs.writeBytes("/bella/assets/sheet.webp".toPath(),sheet)
        assertIs<PetLoadOutcome.Success>(loadCodexV2DirectoryFromFs(fs,"/bella".toPath(),PetPackageLimits.Default))
        assertIs<PetLoadOutcome.Success>(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("bella/pet.json",json.encodeToByteArray()),ZipEntrySpec("bella/assets/sheet.webp",sheet)))))
    }
    @Test fun archiveEntryTraversalAndDuplicateCaseFoldAreRejected() {
        for(name in listOf("pet.json","PET.JSON")) assertIs<PetLoadError.DuplicateEntry>(failure(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("pet.json",manifest.encodeToByteArray()),ZipEntrySpec(name,manifest.encodeToByteArray()),ZipEntrySpec("spritesheet.webp",sheet))))))
        assertIs<PetLoadError.InvalidArchive>(failure(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("../pet.json",manifest.encodeToByteArray()))))))
    }
    @Test fun zipCrcAndSymlinksUseSharedSecurityChecks() {
        val wrong=Crc32.of(sheet)+1
        assertIs<PetLoadError.InvalidArchive>(failure(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("pet.json",manifest.encodeToByteArray()),ZipEntrySpec("spritesheet.webp",sheet,localCrcOverride=wrong,centralCrcOverride=wrong))))))
        assertIs<PetLoadError.SymlinkEntry>(failure(PetLoader.loadCodexV2Zip(buildZip(listOf(ZipEntrySpec("pet.json",manifest.encodeToByteArray()),ZipEntrySpec("spritesheet.webp",sheet,unixMode=ZIP_MODE_SYMLINK))))))
    }
    @Test fun directorySymlinksRemainConfinedForManifestAndAsset() {
        for(leaf in listOf("pet.json","spritesheet.webp")) {
            val fs=FakeFileSystem(); fs.allowSymlinks=true; fs.writePetPackage("/bella",manifest,sheet)
            fs.writeBytes("/outside".toPath(),if(leaf=="pet.json") manifest.encodeToByteArray() else sheet)
            fs.delete(("/bella/"+leaf).toPath()); fs.createSymlink(("/bella/"+leaf).toPath(),"/outside".toPath())
            assertIs<PetLoadError.PathEscape>(failure(loadCodexV2DirectoryFromFs(fs,"/bella".toPath(),PetPackageLimits.Default)))
        }
        val fs=FakeFileSystem(); fs.allowSymlinks=true; fs.writePetPackage("/bella",manifest,sheet)
        fs.writeBytes("/bella/real.webp".toPath(),sheet); fs.delete("/bella/spritesheet.webp".toPath()); fs.createSymlink("/bella/spritesheet.webp".toPath(),"/bella/real.webp".toPath())
        assertIs<PetLoadOutcome.Success>(loadCodexV2DirectoryFromFs(fs,"/bella".toPath(),PetPackageLimits.Default))
    }
    @Test fun rawParserRejectsMalformedImagesAndIncompletePng() {
        for(bytes in listOf(byteArrayOf(1,2,3),pngBytes(1536,2288))) for(outcome in listOf(directory(bytes=bytes),zip(bytes=bytes))) assertIs<CodexV2Error.InvalidSpritesheetBytes>(coreError(outcome))
    }
    @Test fun staticPngChunkContainerLoadsButApngIsRejected() {
        // Valid chunk framing and CRCs; IDAT pixels are deliberately not decoded by this metadata parser.
        val static=pngContainer(false); val animated=pngContainer(true)
        for(outcome in listOf(directory(bytes=static),zip(bytes=static))) assertIs<PetLoadOutcome.Success>(outcome)
        for(outcome in listOf(directory(bytes=animated),zip(bytes=animated))) assertIs<CodexV2Error.InvalidSpritesheetBytes>(coreError(outcome))
    }
    @Test fun callerLimitsCanTightenBothTransports() {
        for(limits in listOf(PetPackageLimits(maxManifestBytes=1),PetPackageLimits(maxSpritesheetBytes=1))) for(outcome in listOf(directory(limits=limits),zip(limits=limits))) assertIs<PetLoadError.LimitExceeded>(failure(outcome))
        assertIs<PetLoadError.LimitExceeded>(failure(zip(limits=PetPackageLimits(maxCompressedArchiveBytes=1))))
        assertIs<PetLoadError.InvalidLimits>(failure(directory(limits=PetPackageLimits(maxManifestBytes=0))))
        assertIs<PetLoadError.InvalidLimits>(failure(zip(limits=PetPackageLimits(maxZipEntries=0))))
    }
    @Test fun callerLimitsCannotRelaxCoreManifestOrImageCaps() {
        val relaxed=PetPackageLimits(maxManifestBytes=100_000,maxSpritesheetBytes=10_000_000)
        val oversized=manifest+" ".repeat(65536)
        for(outcome in listOf(directory(oversized,limits=relaxed),zip(oversized,limits=relaxed))) assertIs<CodexV2Error.InputLimitExceeded>(coreError(outcome))
        val big=ByteArray(8*1024*1024+1); sheet.copyInto(big)
        for(outcome in listOf(directory(bytes=big,limits=relaxed),zip(bytes=big,limits=relaxed))) assertIs<CodexV2Error.InputLimitExceeded>(coreError(outcome))
    }
    private fun pngContainer(animated:Boolean):ByteArray {
        fun chunk(type:String,data:ByteArray)=ByteWriter().apply { be32(data.size.toLong()); ascii(type); raw(data); be32(Crc32.of(type.encodeToByteArray()+data)) }.build()
        val animation=if(animated) chunk("acTL",ByteWriter().apply { be32(2); be32(0) }.build()) else byteArrayOf()
        return pngBytes(1536,2288)+animation+chunk("IDAT",byteArrayOf(1))+chunk("IEND",byteArrayOf())
    }
}
