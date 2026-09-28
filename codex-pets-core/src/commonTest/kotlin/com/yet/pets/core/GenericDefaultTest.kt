package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Generic-default proofs: the runtime is definition-owned, never hard-coded
 * idle. A pet whose default is `stand` must start, pin, and resolve there.
 */
class GenericDefaultTest {

    private fun standDefinition(): PetDefinition {
        val info = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)
        val manifest = """
            {
              "schema": "pets-kmp",
              "schemaVersion": 1,
              "id": "m",
              "displayName": "M",
              "frame": {"width": 32, "height": 40},
              "defaultAnimation": "stand",
              "animations": [
                {"key": "stand", "loopStart": 0, "frames": [{"index": 0, "durationMs": 100}, {"index": 1, "durationMs": 100}]},
                {"key": "walk", "frames": [{"index": 2, "durationMs": 100}]}
              ]
            }
        """.trimIndent()
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(manifest, "m", info)
        assertIs<PetsKmpParseOutcome.Success>(outcome)
        return outcome.definition
    }

    @Test
    fun initialPlayerStateUsesStand() {
        val definition = standDefinition()
        assertEquals(PetAnimationKey("stand"), definition.defaultAnimationKey)
        val at = samplePetAnimation(definition, PetAnimationKey("stand"), 0L)
        assertEquals(PetAnimationKey("stand"), at.animation)
        assertEquals(0, at.spriteIndex)
    }

    @Test
    fun unknownKeyResolvesToStandNotIdle() {
        val definition = standDefinition()
        assertNull(definition.animation("idle"))
        val at = samplePetAnimation(definition, PetAnimationKey("nope"), 50_000_000L)
        assertEquals(PetAnimationKey("stand"), at.animation)
    }

    @Test
    fun reducedMotionPinUsesStand() {
        val definition = standDefinition()
        assertEquals(0, staticDefaultSpriteIndex(definition))
        // Codex wrapper delegates to the same generic implementation.
        assertEquals(staticDefaultSpriteIndex(definition), staticIdleSpriteIndex(definition))
    }

    @Test
    fun oneShotWalkHoldsWithoutAppendingDefault() {
        val definition = standDefinition()
        val held = samplePetAnimation(definition, PetAnimationKey("walk"), 5_000_000_000L)
        assertEquals(PetAnimationKey("walk"), held.animation)
        assertEquals(2, held.spriteIndex)
        assertNull(held.nextFrameInNanos)
    }
}
