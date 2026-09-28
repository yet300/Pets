package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CustomAnimationsTest {

    private fun customOf(json: String, name: String = "custom"): PetAnimation {
        val success = parseSuccess(json)
        assertTrue(success is PetParseOutcome.Success)
        val animation = success.definition.animation(PetAnimationKey(name))
        assertTrue(animation != null)
        return animation
    }

    @Test
    fun customEntryOverridesDefault() {
        val idle = customOf("""{"animations": {"idle": {"frames": [7, 7], "fps": 2.0}}}""", "idle")
        assertEquals(listOf(7, 7), idle.frames.map { it.spriteIndex })
        assertEquals(listOf(500_000_000L, 500_000_000L), idle.frames.map { it.durationNanos })
        assertEquals(0, idle.loopStart)
    }

    @Test
    fun customEntryAddsNewKey() {
        val custom = customOf(
            """{"animations": {"custom": {"frames": [1, 2], "fps": 2.0, "loop": false, "fallback": "idle"}}}""",
        )
        assertEquals(listOf(1, 2), custom.frames.map { it.spriteIndex })
        assertEquals(listOf(500_000_000L, 500_000_000L), custom.frames.map { it.durationNanos })
        assertEquals(null, custom.loopStart)
        assertEquals(PetAnimations.Idle, custom.fallback)
    }

    @Test
    fun customIdleOverrideStillLeavesCorrectNormalizedIdle() {
        val success = parseSuccess("""{"animations": {"idle": {"frames": [3], "fps": 4.0}}}""")
        assertTrue(success is PetParseOutcome.Success)
        val idle = success.definition.animation(PetAnimations.Idle)!!
        assertEquals(listOf(3), idle.frames.map { it.spriteIndex })
        assertEquals(250_000_000L, idle.frames.single().durationNanos)
        assertEquals(0, idle.loopStart)
        assertEquals(PetAnimations.Idle, idle.fallback)
    }

    @Test
    fun aliasOverrideReplacesAliasOnly() {
        val success = parseSuccess(
            """{"animations": {"move_right": {"frames": [0], "fps": 1.0}}}""",
        )
        assertTrue(success is PetParseOutcome.Success)
        val alias = success.definition.animation(PetAnimations.MoveRight)!!
        assertEquals(listOf(0), alias.frames.map { it.spriteIndex })
        assertEquals(1_000_000_000L, alias.frames.single().durationNanos)
        // The canonical key is untouched.
        assertEquals(30, success.definition.animation(PetAnimations.RunningRight)!!.frames.size)
    }

    @Test
    fun reverseOrderForwardFallbackReference() {
        // b sorts before the key it references; validation runs after all inserts.
        val success = parseSuccess(
            """{"animations": {
                "b": {"frames": [1], "loop": false, "fallback": "zebra"},
                "zebra": {"frames": [2]}
            }}""",
        )
        assertTrue(success is PetParseOutcome.Success)
        assertEquals(
            PetAnimationKey("zebra"),
            success.definition.animation(PetAnimationKey("b"))!!.fallback,
        )
    }

    @Test
    fun defaultsAreFps8LoopTrueFallbackIdle() {
        val custom = customOf("""{"animations": {"custom": {"frames": [1]}}}""")
        assertEquals(listOf(125_000_000L), custom.frames.map { it.durationNanos })
        assertEquals(0, custom.loopStart)
        assertEquals(PetAnimations.Idle, custom.fallback)
    }

    @Test
    fun loopFalseMeansOneShot() {
        val custom = customOf("""{"animations": {"custom": {"frames": [1], "loop": false}}}""")
        assertEquals(null, custom.loopStart)
    }

    @Test
    fun loopTrueMeansLoopFromZero() {
        val custom = customOf("""{"animations": {"custom": {"frames": [1], "loop": true}}}""")
        assertEquals(0, custom.loopStart)
    }

    @Test
    fun emptyFallbackDefaultsToIdle() {
        val custom = customOf("""{"animations": {"custom": {"frames": [1], "fallback": ""}}}""")
        assertEquals(PetAnimations.Idle, custom.fallback)
    }

    @Test
    fun whitespaceFallbackIsLiteralAndFailsExistence() {
        // Exact upstream semantics: only "" defaults; whitespace stays literal.
        for (fallback in listOf(" ", "   ", " idle ")) {
            val escaped = fallback.replace(" ", "\\u0020")
            val report = parseFailure(
                """{"animations": {"custom": {"frames": [1], "fallback": "$escaped"}}}""",
            )
            val error = report.errors.single()
            assertIs<PetCompatibilityError.UnknownFallback>(error)
            assertEquals("custom", error.animation)
            assertEquals(fallback, error.fallback)
        }
    }

    @Test
    fun explicitIdleFallbackAccepted() {
        val custom = customOf("""{"animations": {"custom": {"frames": [1], "fallback": "idle"}}}""")
        assertEquals(PetAnimations.Idle, custom.fallback)
    }

    @Test
    fun sixtyFpsIsAcceptedWithExactNanos() {
        val custom = customOf("""{"animations": {"custom": {"frames": [0, 1], "fps": 60.0}}}""")
        assertEquals(16_666_667L, custom.frames[0].durationNanos)
        assertEquals(listOf(16_666_667L, 16_666_667L), custom.frames.map { it.durationNanos })
    }

    @Test
    fun fractionalFpsPreservesPrecision() {
        val fps5994 = customOf("""{"animations": {"custom": {"frames": [0], "fps": 59.94}}}""")
        assertEquals(16_683_350L, fps5994.frames.single().durationNanos)
        val fps24 = customOf("""{"animations": {"custom": {"frames": [0], "fps": 24.0}}}""")
        assertEquals(41_666_667L, fps24.frames.single().durationNanos)
    }

    @Test
    fun fpsToDurationNanosTaxonomy() {
        // Direct Double-level pinning (JSON cannot express NaN/Infinity literals).
        assertEquals(125_000_000L, fpsToDurationNanos(8.0))
        assertEquals(41_666_667L, fpsToDurationNanos(24.0))
        assertEquals(16_683_350L, fpsToDurationNanos(59.94))
        assertEquals(16_666_667L, fpsToDurationNanos(60.0))
        assertNull(fpsToDurationNanos(Double.NaN))
        assertNull(fpsToDurationNanos(Double.POSITIVE_INFINITY))
        assertNull(fpsToDurationNanos(Double.NEGATIVE_INFINITY))
        assertNull(fpsToDurationNanos(0.0))
        assertNull(fpsToDurationNanos(-1.0))
        assertNull(fpsToDurationNanos(60.0001))
        assertNull(fpsToDurationNanos(1e308))
        // Smallest normal: 1/fps overflows the duration range -> hardening rejection.
        assertNull(fpsToDurationNanos(2.2250738585072014e-308))
        // Subnormal: 1/fps is +Infinity -> hardening rejection.
        assertNull(fpsToDurationNanos(4.9e-324))
    }

    @Test
    fun invalidFpsValuesRejected() {
        for (fps in listOf("0", "-1", "60.0001", "1e308")) {
            val report = parseFailure("""{"animations": {"custom": {"frames": [0], "fps": $fps}}}""")
            val error = report.errors.first()
            assertIs<PetCompatibilityError.InvalidFps>(error, "fps=$fps")
            assertEquals("custom", error.animation)
        }
    }

    @Test
    fun nonNumericFpsLiteralIsMalformedJson() {
        // JSON has no NaN/Infinity literals; strict decoding rejects them as schema errors.
        for (literal in listOf("NaN", "Infinity", "-Infinity")) {
            val report = parseFailure("""{"animations": {"custom": {"frames": [0], "fps": $literal}}}""")
            assertIs<PetCompatibilityError.MalformedManifest>(report.errors.first(), literal)
        }
    }

    @Test
    fun subnormalFpsYieldsNonFiniteDurationAndIsRejected() {
        // 4.9e-324 -> 1/fps overflows to infinity: hardening, not upstream parity.
        val report = parseFailure("""{"animations": {"custom": {"frames": [0], "fps": 4.9e-324}}}""")
        assertIs<PetCompatibilityError.InvalidFps>(report.errors.first())
    }

    @Test
    fun overflowingJsonNumberIsMalformed() {
        // 1e999 overflows Double during JSON decoding: strict parsers reject it
        // before FPS validation ever runs. Typed, but as a schema error.
        val report = parseFailure("""{"animations": {"custom": {"frames": [0], "fps": 1e999}}}""")
        assertIs<PetCompatibilityError.MalformedManifest>(report.errors.first())
    }

    @Test
    fun emptyFramesRejected() {
        val report = parseFailure("""{"animations": {"idle": {"frames": []}}}""")
        val error = report.errors.first()
        assertIs<PetCompatibilityError.EmptyAnimationFrames>(error)
        assertEquals("idle", error.animation)
    }

    @Test
    fun outOfRangeIndexRejected() {
        val report = parseFailure("""{"animations": {"idle": {"frames": [72]}}}""")
        val error = report.errors.first()
        assertIs<PetCompatibilityError.SpriteIndexOutOfRange>(error)
        assertEquals("idle", error.animation)
        assertEquals(72, error.index)
        assertEquals(72, error.frameCount)
    }

    @Test
    fun negativeIndexRejectedWithSemanticError() {
        // Documented P2 taxonomy deviation: upstream serde rejects negatives during
        // usize decoding (parse error); KMP Int DTOs decode then fail semantic
        // validation. Reject in both; typed variant differs.
        val report = parseFailure("""{"animations": {"idle": {"frames": [-1]}}}""")
        assertIs<PetCompatibilityError.SpriteIndexOutOfRange>(report.errors.first())
    }

    @Test
    fun missingFallbackTargetRejected() {
        val report = parseFailure(
            """{"animations": {"wave": {"frames": [1], "loop": false, "fallback": "missing"}}}""",
        )
        val error = report.errors.first()
        assertIs<PetCompatibilityError.UnknownFallback>(error)
        assertEquals("wave", error.animation)
        assertEquals("missing", error.fallback)
    }

    @Test
    fun customFallbackToCustomAnimationAccepted() {
        val success = parseSuccess(
            """{"animations": {
                "a": {"frames": [1], "loop": false, "fallback": "b"},
                "b": {"frames": [2]}
            }}""",
        )
        assertTrue(success is PetParseOutcome.Success)
        assertEquals(
            PetAnimationKey("b"),
            success.definition.animation(PetAnimationKey("a"))!!.fallback,
        )
    }

    @Test
    fun multipleAnimationErrorsAreAllReported() {
        val report = parseFailure(
            """{"animations": {
                "bad1": {"frames": []},
                "bad2": {"frames": [0], "fps": 0.0}
            }}""",
        )
        assertEquals(2, report.errors.size)
        // Deterministic order: sorted by animation name.
        assertIs<PetCompatibilityError.EmptyAnimationFrames>(report.errors[0])
        assertIs<PetCompatibilityError.InvalidFps>(report.errors[1])
    }

    @Test
    fun internalFpsMathAgreesWithParser() {
        // The parser must use exactly this conversion (not integer-ms truncation).
        assertEquals((1.0 / 60.0).seconds.inWholeNanoseconds, fpsToDurationNanos(60.0))
        assertEquals((1.0 / 59.94).seconds.inWholeNanoseconds, fpsToDurationNanos(59.94))
    }
}
