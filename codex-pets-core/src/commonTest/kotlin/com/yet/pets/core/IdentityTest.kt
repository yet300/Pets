package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdentityTest {

    @Test
    fun explicitManifestIdWinsOverFallback() {
        val identity = normalizePetIdentity("chefito", null, null, "dir")
        assertEquals(PetIdentity("chefito", "chefito", ""), identity)
    }

    @Test
    fun displayNamePrecedenceIsDisplayNameThenManifestIdThenFallback() {
        assertEquals("Pretty", normalizePetIdentity("id", "Pretty", null, "dir").displayName)
        assertEquals("id", normalizePetIdentity("id", null, null, "dir").displayName)
        assertEquals("dir", normalizePetIdentity(null, null, null, "dir").displayName)
    }

    @Test
    fun blankValuesCountAsAbsent() {
        val identity = normalizePetIdentity("  ", "\t", "  padded  ", "  dir  ")
        assertEquals(PetIdentity("dir", "dir", "padded"), identity)
    }

    @Test
    fun blankFallbackIdNormalizesToPet() {
        assertEquals("pet", normalizePetIdentity(null, null, null, "").id)
        assertEquals("pet", normalizePetIdentity(null, null, null, "   ").id)
    }

    @Test
    fun descriptionDefaultsToEmpty() {
        assertEquals("", normalizePetIdentity(null, null, null, "dir").description)
        assertEquals("", normalizePetIdentity(null, null, "   ", "dir").description)
    }

    @Test
    fun identityFlowsThroughParse() {
        val missing = parseSuccess("{}", "nested-pkg")
        assertEquals("nested-pkg", missing.definition.id)
        assertEquals("nested-pkg", missing.definition.displayName)

        val explicit = parseSuccess("""{"id": "m", "displayName": "D"}""", "nested-pkg")
        assertEquals("m", explicit.definition.id)
        assertEquals("D", explicit.definition.displayName)
    }

    @Test
    fun blankManifestIdFallsBackToCallerIdentity() {
        val success = parseSuccess("""{"id": " ", "displayName": " "}""", "zip-root")
        assertEquals("zip-root", success.definition.id)
        assertEquals("zip-root", success.definition.displayName)
    }

    @Test
    fun urlStyleFallbackIsUsedVerbatim() {
        // Core does not parse URLs; the caller passes the derived name in.
        val success = parseSuccess("{}", "bella")
        assertEquals("bella", success.definition.id)
        assertIs<PetParseOutcome.Success>(success)
    }
}
