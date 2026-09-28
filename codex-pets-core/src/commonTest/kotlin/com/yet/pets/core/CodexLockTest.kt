package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun codexSuccess(json: String): PetDefinition {
    val outcome = PetPackageParser.parseTrustedMetadata(
        json,
        "test",
        SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
    )
    assertIs<PetParseOutcome.Success>(outcome, "expected Codex success, got $outcome")
    return outcome.definition
}

private fun codexFailure(json: String, info: SpritesheetInfo): PetCompatibilityReport {
    val outcome = PetPackageParser.parseTrustedMetadata(json, "test", info)
    assertIs<PetParseOutcome.Failure>(outcome, "expected Codex failure, got $outcome")
    return outcome.report
}

/**
 * Lock tests for verified Codex V1 behavior. These must remain green through
 * the generic-runtime extraction: 1536x1872 succeeds, timings/aliases/one-hop
 * unchanged, and V2 never activates a Codex profile.
 */
class CodexLockTest {

    @Test
    fun verified1536x1872Succeeds() {
        val definition = codexSuccess("{}")
        assertEquals(1536, definition.geometry.atlasWidth)
        assertEquals(1872, definition.geometry.atlasHeight)
        assertEquals(8, definition.geometry.columns)
        assertEquals(9, definition.geometry.rows)
        assertEquals(192, definition.geometry.cellWidth)
        assertEquals(208, definition.geometry.cellHeight)
        assertEquals(72, definition.frameCount)
        assertEquals(PetAnimations.Idle, definition.defaultAnimationKey)
    }

    @Test
    fun originalKodee2288FailsCodexLoader() {
        // Exact original assets/kodee/pet.json declares spriteVersionNumber=2
        // with a 1536x2288 sheet. The strict Codex V1 loader must reject it.
        val manifest = """{"id":"kodee","displayName":"Kodee","description":"x","spriteVersionNumber":2,"spritesheetPath":"spritesheet.webp"}"""
        val report = codexFailure(manifest, SpritesheetInfo(1536, 2288, SpritesheetFormat.WEBP))
        assertTrue(report.errors.any { it is PetCompatibilityError.UnsupportedAtlasDimensions })
    }

    @Test
    fun spriteVersionNumber2DoesNotActivateCodexProfile() {
        // Unknown fields are ignored by the Codex DTO; version 2 must not
        // change geometry, timings, or acceptance. A V1 sheet with version 2
        // still parses as V1; a V2-dimension sheet still fails.
        val v1WithVersion2 = codexSuccess("""{"spriteVersionNumber":2}""")
        assertEquals(72, v1WithVersion2.frameCount)
        assertEquals(PetAnimations.Idle, v1WithVersion2.defaultAnimationKey)

        val v2Dims = codexFailure(
            """{"spriteVersionNumber":2}""",
            SpritesheetInfo(1536, 2288, SpritesheetFormat.WEBP),
        )
        assertTrue(v2Dims.errors.any { it is PetCompatibilityError.UnsupportedAtlasDimensions })
    }

    @Test
    fun codexTimingsUnchanged() {
        val definition = codexSuccess("{}")
        val idle = definition.animation(PetAnimations.Idle)!!
        assertEquals(
            listOf(1_680_000_000L, 660_000_000L, 660_000_000L, 840_000_000L, 840_000_000L, 1_920_000_000L),
            idle.frames.map { it.durationNanos },
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), idle.frames.map { it.spriteIndex })
        assertEquals(0, idle.loopStart)
        // Non-idle built-ins play primary 3x then appended idle loop.
        val running = definition.animation(PetAnimations.Running)!!
        assertEquals(18, running.loopStart)
        assertEquals(56, samplePetAnimation(definition, PetAnimations.Running, 0L).spriteIndex)
        assertEquals(0, samplePetAnimation(definition, PetAnimations.Running, 2_460_000_000L).spriteIndex)
    }

    @Test
    fun codexAliasesUnchanged() {
        val definition = codexSuccess("{}")
        assertEquals(
            definition.animation(PetAnimations.RunningRight),
            definition.animation(PetAnimations.MoveRight),
        )
        assertEquals(
            definition.animation(PetAnimations.RunningLeft),
            definition.animation(PetAnimations.MoveLeft),
        )
        assertEquals(
            definition.animation(PetAnimations.Waving),
            definition.animation(PetAnimations.Wave),
        )
        assertEquals(
            definition.animation(PetAnimations.Jumping),
            definition.animation(PetAnimations.Bounce),
        )
        assertEquals(
            definition.animation(PetAnimations.Failed),
            definition.animation(PetAnimations.Sad),
        )
    }

    @Test
    fun customAnimationWith65CharNameAccepted() {
        // P1-04 regression: Phase 4 accepted arbitrary custom names; the
        // generic 64-character policy must never leak into the Codex adapter.
        val name = "a".repeat(65)
        val definition = codexSuccess(
            """{"animations": {"$name": {"frames": [0], "fps": 8}}}""",
        )
        val custom = definition.animation(name)
        assertTrue(custom != null, "65-char Codex custom animation must be accepted")
        assertEquals(PetAnimations.Idle, custom.fallback)
        assertEquals(0, samplePetAnimation(definition, PetAnimationKey(name), 0L).spriteIndex)
    }

    @Test
    fun longCustomNameWithValidFramesFpsFallbackAccepted() {
        val name = "b".repeat(100)
        val definition = codexSuccess(
            """{"animations": {"$name": {"frames": [0, 1], "fps": 8, "loop": false, "fallback": "idle"}}}""",
        )
        val custom = definition.animation(name)!!
        assertEquals(null, custom.loopStart)
        assertEquals(PetAnimations.Idle, custom.fallback)
        // One-shot completes into idle with the same clock.
        val held = samplePetAnimation(definition, PetAnimationKey(name), 5_000_000_000L)
        assertEquals(PetAnimations.Idle, held.animation)
    }

    @Test
    fun substantiallyLongerKeyStillBoundedByManifestCap() {
        // No hidden Codex-specific cap: a 5000-character name is accepted
        // (safely bounded by the 64 KiB manifest input cap).
        val name = "c".repeat(5000)
        val definition = codexSuccess(
            """{"animations": {"$name": {"frames": [3], "fps": 8}}}""",
        )
        assertTrue(definition.animation(name) != null)
    }

    @Test
    fun codexEmptyCustomAnimationKeyAccepted() {
        // Phase-4 parity: the old key model accepted arbitrary strings, so an
        // empty custom name is a literal key, not a rejection.
        val definition = codexSuccess("""{"animations": {"": {"frames": [4], "fps": 8}}}""")
        assertTrue(definition.animation("") != null)
        assertEquals(4, samplePetAnimation(definition, PetAnimationKey(""), 0L).spriteIndex)
    }

    @Test
    fun codexEmptyVsWhitespaceFallbackSemantics() {
        // fallback == "" selects idle; whitespace is a literal name that fails
        // fallback-existence validation unless such a key exists.
        val empty = codexSuccess("""{"animations": {"x": {"frames": [0], "fps": 8, "fallback": ""}}}""")
        assertEquals(PetAnimations.Idle, empty.animation("x")!!.fallback)

        val whitespace = codexFailure(
            """{"animations": {"y": {"frames": [0], "fps": 8, "fallback": " "}}}""",
            SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        val unknown = whitespace.errors.filterIsInstance<PetCompatibilityError.UnknownFallback>().single()
        assertEquals("y", unknown.animation)
        assertEquals(" ", unknown.fallback)
    }

    @Test
    fun codexUnknownRequestedAnimationResolvesToIdle() {
        val definition = codexSuccess("{}")
        val at = samplePetAnimation(definition, PetAnimationKey("typo"), 0L)
        assertEquals(PetAnimations.Idle, at.animation)
    }

    @Test
    fun codexFallbackHopUsesSameOriginalElapsed() {
        // One-shot a (100 ms) falls back to looping b ([7:100ms, 8:100ms]).
        // At t=150 ms the SAME clock evaluates b (frame 8); a reset clock
        // would show frame 7.
        val animations = CodexV1.defaultAnimations().toMutableMap()
        animations[PetAnimationKey("a")] = PetAnimation(
            listOf(PetFrame(0, 100_000_000L)),
            loopStart = null,
            fallback = PetAnimationKey("b"),
        )
        animations[PetAnimationKey("b")] = PetAnimation(
            listOf(PetFrame(7, 100_000_000L), PetFrame(8, 100_000_000L)),
            loopStart = 0,
            fallback = PetAnimations.Idle,
        )
        val definition = PetDefinition(
            "t", "T", "", CodexV1.defaultGeometry(), CodexV1.FRAME_COUNT, animations,
            defaultAnimationKey = PetAnimations.Idle,
        )
        val at = samplePetAnimation(definition, PetAnimationKey("a"), 150_000_000L)
        assertEquals(PetAnimationKey("b"), at.animation)
        assertEquals(8, at.spriteIndex)
    }

    @Test
    fun codexOneHopUnchanged() {
        val animations = CodexV1.defaultAnimations().toMutableMap()
        animations[PetAnimationKey("a")] = PetAnimation(
            listOf(PetFrame(0, 100_000_000L)),
            loopStart = null,
            fallback = PetAnimationKey("b"),
        )
        animations[PetAnimationKey("b")] = PetAnimation(
            listOf(PetFrame(7, 100_000_000L)),
            loopStart = null,
            fallback = PetAnimationKey("c"),
        )
        animations[PetAnimationKey("c")] = PetAnimation(
            listOf(PetFrame(9, 100_000_000L)),
            loopStart = 0,
            fallback = PetAnimations.Idle,
        )
        val definition = PetDefinition(
            "t", "T", "", CodexV1.defaultGeometry(), CodexV1.FRAME_COUNT, animations,
            defaultAnimationKey = PetAnimations.Idle,
        )
        val at = samplePetAnimation(definition, PetAnimationKey("a"), 5_000_000_000L)
        // A -> B -> C stays on B (exactly one hop), even far beyond both.
        assertEquals(PetAnimationKey("b"), at.animation)
        assertEquals(7, at.spriteIndex)
    }
}
