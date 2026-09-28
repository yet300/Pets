package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private fun oneShotDefinition(
    frames: String,
    key: String = "dance",
    defaultAnimation: String = "stand",
): PetDefinition {
    val manifest = """
        {
          "schema": "pets-kmp",
          "schemaVersion": 1,
          "id": "o",
          "displayName": "O",
          "frame": {"width": 32, "height": 40},
          "defaultAnimation": "$defaultAnimation",
          "animations": [
            {"key": "stand", "loopStart": 0, "frames": [{"index": 0, "durationMs": 100}]},
            {"key": "$key", "frames": $frames}
          ]
        }
    """.trimIndent()
    val outcome = PetsKmpPackageParser.parseTrustedMetadata(
        manifest,
        SpritesheetInfo(96, 80, SpritesheetFormat.PNG),
    )
    assertIs<PetsKmpParseOutcome.Success>(outcome, "expected one-shot fixture, got $outcome")
    return outcome.definition
}

/**
 * Permanent one-shot boundary locks (P1-02): a generic one-shot
 * (`loopStart == null`, `fallback == null`) plays its frames once and then
 * holds its final frame forever with `nextFrameInNanos == null`. The held
 * sample keeps the SELECTED animation key — never a fabricated fallback.
 */
class GenericOneShotTest {

    @Test
    fun genericOneShotHasNullFallback() {
        val definition = oneShotDefinition("""[{"index": 2, "durationMs": 100}]""")
        assertNull(
            definition.animation("dance")!!.fallback,
            "generic one-shots must expose fallback == null",
        )
    }

    @Test
    fun singleFrameOneShotBoundaries() {
        // 1 ms frame: total = 1_000_000 ns.
        val definition = oneShotDefinition("""[{"index": 2, "durationMs": 1}]""")
        val key = PetAnimationKey("dance")

        val atZero = samplePetAnimation(definition, key, 0L)
        assertEquals(key, atZero.animation)
        assertEquals(2, atZero.spriteIndex)
        assertEquals(1_000_000L, atZero.nextFrameInNanos)

        val beforeEnd = samplePetAnimation(definition, key, 999_999L)
        assertEquals(key, beforeEnd.animation)
        assertEquals(2, beforeEnd.spriteIndex)
        assertEquals(1L, beforeEnd.nextFrameInNanos)

        for (elapsed in listOf(1_000_000L, 1_000_001L, Long.MAX_VALUE)) {
            val held = samplePetAnimation(definition, key, elapsed)
            assertEquals(key, held.animation, "t=$elapsed")
            assertEquals(2, held.spriteIndex, "t=$elapsed")
            assertNull(held.nextFrameInNanos, "t=$elapsed")
        }
    }

    @Test
    fun multiFrameOneShotBoundaries() {
        // Frames [0, 1, 2] at 1 ms each: boundaries at 1M/2M, total 3M ns.
        val definition = oneShotDefinition(
            """[{"index": 0, "durationMs": 1},{"index": 1, "durationMs": 1},{"index": 2, "durationMs": 1}]""",
        )
        val key = PetAnimationKey("dance")

        fun assertFrame(elapsed: Long, sprite: Int, next: Long?) {
            val sample = samplePetAnimation(definition, key, elapsed)
            assertEquals(key, sample.animation, "t=$elapsed")
            assertEquals(sprite, sample.spriteIndex, "t=$elapsed")
            assertEquals(next, sample.nextFrameInNanos, "t=$elapsed")
        }

        assertFrame(0L, 0, 1_000_000L)
        assertFrame(999_999L, 0, 1L)
        assertFrame(1_000_000L, 1, 1_000_000L)
        assertFrame(1_999_999L, 1, 1L)
        assertFrame(2_000_000L, 2, 1_000_000L)
        assertFrame(2_999_999L, 2, 1L)
        // At and after total: final frame held forever, no wake-up.
        assertFrame(3_000_000L, 2, null)
        assertFrame(3_000_001L, 2, null)
        assertFrame(Long.MAX_VALUE, 2, null)
    }

    @Test
    fun oneShotHoldDoesNotAppendDefault() {
        val definition = oneShotDefinition(
            """[{"index": 1, "durationMs": 100},{"index": 2, "durationMs": 100}]""",
        )
        val held = samplePetAnimation(definition, PetAnimationKey("dance"), 60_000_000_000L)
        assertEquals(PetAnimationKey("dance"), held.animation)
        assertEquals(2, held.spriteIndex)
        assertNull(held.nextFrameInNanos)
    }

    @Test
    fun loopingPrefixSuffixStillWorks() {
        // loopStart = 1 over [0, 1, 2] at 1 ms: 0, 1, 2, 1, 2, 1, ...
        val manifest = """
            {
              "schema": "pets-kmp",
              "schemaVersion": 1,
              "id": "l",
              "displayName": "L",
              "frame": {"width": 32, "height": 40},
              "defaultAnimation": "loop",
              "animations": [
                {"key": "loop", "loopStart": 1, "frames": [{"index": 0, "durationMs": 1},{"index": 1, "durationMs": 1},{"index": 2, "durationMs": 1}]}
              ]
            }
        """.trimIndent()
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(
            manifest,
            SpritesheetInfo(96, 80, SpritesheetFormat.PNG),
        )
        val definition = assertIs<PetsKmpParseOutcome.Success>(outcome).definition
        val key = PetAnimationKey("loop")
        val sprites = listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L)
            .map { samplePetAnimation(definition, key, it).spriteIndex }
        assertEquals(listOf(0, 1, 2, 1, 2, 1), sprites)
    }
}
