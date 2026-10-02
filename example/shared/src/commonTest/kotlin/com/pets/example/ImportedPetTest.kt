package com.pets.example

import com.yet.pets.core.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pets_kmp.example.shared.generated.resources.Res
import kotlin.test.*

@OptIn(ExperimentalResourceApi::class)
class ImportedPetTest {
    private val lookKeys = listOf(
        "000", "022.5", "045", "067.5", "090", "112.5", "135", "157.5",
        "180", "202.5", "225", "247.5", "270", "292.5", "315", "337.5",
    ).map { PetAnimationKey("look-$it") }
    private val manifest = ImportFixtures.codexManifest
    private val sheet = ImportFixtures.codexSheet

    @Test fun bothSupportedFormatsNormalizeAndKeepCustomKeys() {
        assertEquals(ManifestFormat.CodexV1, classifyPetManifest(manifest))
        val direct = assertIs<PetParseOutcome.Success>(CodexPetPackageParser.parse(manifest, sheet, "v1"))
        val imported = assertIs<ImportedPetResult.Success>(parseImportedPet(manifest, sheet, "v1"))
        assertEquals(direct.definition, imported.definition)
        assertTrue(PetAnimationKey("hello") in imported.definition.animationKeys)
        assertEquals(listOf(0, 1), imported.definition.animation("hello")!!.frames.map { it.spriteIndex })
        assertEquals("v1", imported.definition.id)
        assertEquals(ManifestFormat.CodexV1, classifyPetManifest("{}".encodeToByteArray()))
        assertIs<ImportedPetResult.Success>(parseImportedPet("{}".encodeToByteArray(), sheet, "empty"))
    }

    @Test fun exactKodeeManifestsUseDistinctRoutes() = runBlocking {
        val image = Res.readBytes("files/kodee/spritesheet.webp")
        assertEquals(ManifestFormat.CodexV2, classifyPetManifest(ImportFixtures.originalKodeeManifest))
        val v2 = assertIs<ImportedPetResult.Success>(parseImportedPet(ImportFixtures.originalKodeeManifest, image))
        val direct = assertIs<CodexV2ParseOutcome.Success>(CodexV2PetPackageParser.parse(ImportFixtures.originalKodeeManifest, image))
        assertEquals(direct.definition, v2.definition)
        assertEquals(lookKeys, v2.definition.animationKeys.filter { it.value.startsWith("look-") })
        for ((index, key) in lookKeys.withIndex()) {
            assertEquals(listOf(72 + index), v2.definition.animation(key)!!.frames.map { it.spriteIndex })
        }
        assertEquals(ManifestFormat.PetsKmpV1, classifyPetManifest(ImportFixtures.genericKodeeManifest))
        val result = assertIs<ImportedPetResult.Success>(parseImportedPet(ImportFixtures.genericKodeeManifest, image, "ignored"))
        assertEquals("kodee", result.definition.id)
    }

    @Test fun malformedAndUnknownAreDistinct() {
        for (bytes in listOf("{broken".encodeToByteArray(), byteArrayOf(-1))) {
            assertIs<ImportedPetResult.InvalidManifest>(parseImportedPet(bytes, sheet))
        }
        for (json in listOf("{\"schema\":\"other\"}", "[]", "null", "{\"alien\":true}",
            "{\"schema\":\"pets-kmp\",\"schemaVersion\":2}",
            "{\"spriteVersionNumber\":3}", "{\"spriteVersionNumber\":\"2\"}",
            "{\"spriteVersionNumber\":2.0}", "{\"spriteVersionNumber\":2e0}", "{\"schema\":\"pets-kmp\",\"schemaVersion\":\"1\"}")) {
            assertEquals(ManifestFormat.Unknown, classifyPetManifest(json.encodeToByteArray()))
            assertIs<ImportedPetResult.UnsupportedFormat>(parseImportedPet(json.encodeToByteArray(), sheet))
        }
    }

    @Test fun explicitV2WinsEvenWithGenericIdentityOrV1Dimensions() {
        val json = "{\"schema\":\"pets-kmp\",\"schemaVersion\":1,\"spriteVersionNumber\":2}".encodeToByteArray()
        assertEquals(ManifestFormat.CodexV2, classifyPetManifest(json))
        val failure = assertIs<ImportedPetResult.InvalidSpritesheet>(parseImportedPet(json, sheet))
        assertTrue(failure.codexV2Report!!.errors.any { it is CodexV2Error.GeometryMismatch })
        assertNull(failure.petsKmpReport)
        assertNull(failure.codexReport)
    }

    @Test fun parserReportsStayAvailableAndFailuresNeverAddPets() {
        val state = PetGalleryState(ImportFixtures.genericKodeeManifest, sheet)
        // Same grid cell size, but the generic fixture's frame indices remain within the V1 sheet.
        for (bad in listOf(byteArrayOf(1,2,3), sheet.copyOf(20))) {
            val result = assertIs<ImportedPetResult.InvalidSpritesheet>(parseImportedPet(manifest, bad))
            assertNotNull(result.codexReport)
            assertFalse(state.importPet(manifest, bad))
            assertEquals(1, state.pets.size)
            assertEquals("Invalid spritesheet", state.importError)
        }
        val invalid = "{\"animations\":{\"bad\":{\"frames\":[999]}}}".encodeToByteArray()
        val result = assertIs<ImportedPetResult.ParseFailure>(parseImportedPet(invalid, sheet))
        assertTrue(result.codexReport!!.errors.any { it is PetCompatibilityError.SpriteIndexOutOfRange })
        assertFalse(state.importPet(invalid, sheet))
        assertEquals(1, state.pets.size)
    }

    @Test fun mismatchedSheetHasTypedFailureAndDoesNotAdd() = runBlocking {
        val mismatched = Res.readBytes("files/kodee/spritesheet.webp")
        val failure = assertIs<ImportedPetResult.InvalidSpritesheet>(parseImportedPet(manifest, mismatched))
        assertTrue(failure.codexReport!!.errors.any { it is PetCompatibilityError.UnsupportedAtlasDimensions })
        val state = PetGalleryState(ImportFixtures.genericKodeeManifest, mismatched)
        assertFalse(state.importPet(manifest, mismatched))
        assertEquals(1, state.pets.size)
    }

    @Test fun v2PolicyAndImageFailuresKeepReportsAndNeverAdd() = runBlocking {
        val image = Res.readBytes("files/kodee/spritesheet.webp")
        val state = PetGalleryState(ImportFixtures.genericKodeeManifest, image)
        for (bad in listOf(byteArrayOf(1, 2, 3), sheet, ByteArray(PetInputLimits.MAX_SPRITESHEET_BYTES + 1))) {
            val result = assertIs<ImportedPetResult.InvalidSpritesheet>(parseImportedPet(ImportFixtures.originalKodeeManifest, bad))
            val direct = assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(ImportFixtures.originalKodeeManifest, bad))
            assertEquals(direct.report, result.codexV2Report)
            assertFalse(state.importPet(ImportFixtures.originalKodeeManifest, bad))
            assertEquals(1, state.pets.size)
            assertEquals("Invalid spritesheet", state.importError)
        }
        for (member in listOf("frame", "animations")) {
            val bytes = "{\"spriteVersionNumber\":2,\"$member\":null}".encodeToByteArray()
            val failure = assertIs<ImportedPetResult.ParseFailure>(parseImportedPet(bytes, image))
            val direct = assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(bytes, image))
            assertEquals(direct.report, failure.codexV2Report)
            assertTrue(failure.codexV2Report!!.errors.any { it is CodexV2Error.UnsupportedOverride })
            assertFalse(state.importPet(bytes, image))
            assertEquals(1, state.pets.size)
        }
        val malformedMetadata = "{\"spriteVersionNumber\":2,\"id\":[]}".encodeToByteArray()
        assertNotNull(assertIs<ImportedPetResult.InvalidManifest>(parseImportedPet(malformedMetadata, image)).codexV2Report)
        Unit
    }

    @Test fun filenameFallbackIsDeterministicAndGenericIdentityIsManifestOwned() {
        assertEquals("pet", importedPetFallbackId("pet.json"))
        assertEquals("my.pet", importedPetFallbackId("/folder/my.pet.json"))
        assertEquals("imported-pet", importedPetFallbackId(".json"))
        assertEquals("imported-pet", importedPetFallbackId(" "))
        assertEquals("plain", importedPetFallbackId("plain"))
        assertEquals("imported-pet", assertIs<ImportedPetResult.Success>(parseImportedPet(manifest, sheet)).definition.id)
    }
}
