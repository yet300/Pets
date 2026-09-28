package com.yet.pets.compose

import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import com.yet.pets.core.samplePetAnimation
import com.yet.pets.core.staticDefaultSpriteIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Generic player proofs: a definition whose default is NOT `idle` must start,
 * pin, and resume on that default with no idle assumptions.
 */
class GenericPlayerStateTest {

    private fun standDefinition(): PetDefinition {
        val manifest = """
            {
              "schema": "pets-kmp",
              "schemaVersion": 1,
              "id": "g",
              "displayName": "G",
              "frame": {"width": 32, "height": 40},
              "defaultAnimation": "stand",
              "animations": [
                {"key": "stand", "loopStart": 0, "frames": [{"index": 0, "durationMs": 100}, {"index": 1, "durationMs": 100}]},
                {"key": "dance", "frames": [{"index": 2, "durationMs": 100}]}
              ]
            }
        """.trimIndent()
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(
            manifest,
            SpritesheetInfo(96, 80, SpritesheetFormat.PNG),
        )
        assertIs<PetsKmpParseOutcome.Success>(outcome)
        assertNull(outcome.definition.animation("idle"))
        return outcome.definition
    }

    private val noAtlas = DecodedAtlas(null, PetAtlasState.Failed("test-only"))

    @Test
    fun initialStateUsesStandNotIdle() {
        val definition = standDefinition()
        var now = 0L
        val state = PetPlayerState(definition, noAtlas) { now }
        assertEquals(PetAnimationKey("stand"), state.currentSample.animation)
        assertEquals(samplePetAnimation(definition, PetAnimationKey("stand"), 0L), state.currentSample)
    }

    @Test
    fun pinToDefaultUsesStandAndResumeRestoresDance() {
        val definition = standDefinition()
        var now = 0L
        val state = PetPlayerState(definition, noAtlas) { now }
        state.play(PetAnimationKey("dance"))
        assertEquals(PetAnimationKey("dance"), state.currentSample.animation)
        state.pinToDefault()
        assertTrue(state.isPinned)
        assertEquals(PetAnimationKey("stand"), state.currentSample.animation)
        assertEquals(staticDefaultSpriteIndex(definition), state.currentSample.spriteIndex)
        state.resume()
        assertEquals(PetAnimationKey("dance"), state.currentSample.animation)
    }

    @Test
    fun deprecatedPinToIdleDelegatesToDefault() {
        val definition = standDefinition()
        var now = 0L
        val state = PetPlayerState(definition, noAtlas) { now }
        @Suppress("DEPRECATION")
        state.pinToIdle()
        assertTrue(state.isPinned)
        assertEquals(PetAnimationKey("stand"), state.currentSample.animation)
    }
}
