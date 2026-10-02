package com.pets.example

import com.yet.pets.core.PetAnimationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PetGalleryStateTest {
    private val manifest = """{"schema":"pets-kmp","schemaVersion":1,"id":"cat","displayName":"Cat","frame":{"width":1,"height":1},"defaultAnimation":"stand","animations":[{"key":"stand","loopStart":0,"frames":[{"index":0,"durationMs":100}]},{"key":"dance","loopStart":0,"frames":[{"index":0,"durationMs":100}]}]}""".encodeToByteArray()
    private val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,-60,-119,0,0,0,11,73,68,65,84,120,-100,99,0,1,0,0,5,0,1,-95,-11,100,64,0,0,0,0,73,69,78,68,-82,66,96,-126)

    @Test fun codexV1ImportUsesExistingParserContract() {
        val direct = com.yet.pets.core.CodexPetPackageParser.parse(
            ImportFixtures.codexManifest, ImportFixtures.codexSheet, fallbackId = "pet",
        )
        kotlin.test.assertIs<com.yet.pets.core.PetParseOutcome.Success>(direct)
        val state = PetGalleryState(manifest, png)
        assertTrue(state.importPet(ImportFixtures.codexManifest, ImportFixtures.codexSheet))
        assertEquals("V1 Fixture", state.selectedPet.definition.displayName)
        assertTrue(PetAnimationKey("hello") in state.selectedPet.definition.animationKeys)
        state.play(PetAnimationKey("hello"))
        assertEquals("hello", state.overlayAnimation.value)
        assertEquals(state.selectedPet, state.overlayPet)
    }

    @Test fun bundledPetAndAddPage() {
        val state = PetGalleryState(manifest, png)
        assertEquals(1, state.pets.size)
        assertEquals(2, state.pageCount)
        state.selectPage(1)
        assertEquals(0, state.selectedPetIndex)
    }

    @Test fun importAndSwitchAndAction() {
        val state = PetGalleryState(manifest, png)
        assertTrue(state.importPet(manifest, png))
        assertEquals(2, state.pets.size)
        state.selectPage(1)
        assertEquals(1, state.selectedPetIndex)
        state.play(PetAnimationKey("dance"))
        assertEquals("dance", state.selectedAnimation.value)
        assertEquals("dance", state.overlayAnimation.value)
        state.selectPage(2)
        assertEquals(1, state.selectedPetIndex)
        assertEquals("Cat", state.overlayPet.definition.displayName)
    }

    @Test fun invalidImportDoesNotEnterPager() {
        val state = PetGalleryState(manifest, png)
        assertFalse(state.importPet("{}".encodeToByteArray(), png))
        assertEquals(1, state.pets.size)
    }
}
