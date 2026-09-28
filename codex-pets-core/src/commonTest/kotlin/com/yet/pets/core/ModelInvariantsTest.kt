package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelInvariantsTest {

    @Test
    fun keyHasValueEquality() {
        assertEquals(PetAnimationKey("dance"), PetAnimationKey("dance"))
        assertEquals(PetAnimationKey("dance").hashCode(), PetAnimationKey("dance").hashCode())
        assertEquals("PetAnimationKey(value=dance)", PetAnimationKey("dance").toString())
        assertTrue(PetAnimationKey("dance") != PetAnimationKey("idle"))
        // Arbitrary valid names (sleep, eat, dance, happy, spin, …) need no
        // library change; blank and overlong keys are rejected.
        assertEquals("dance", PetAnimationKey("dance").value)
        assertFailsWith<IllegalArgumentException> { PetAnimationKey("") }
        assertFailsWith<IllegalArgumentException> { PetAnimationKey("   ") }
        assertFailsWith<IllegalArgumentException> { PetAnimationKey("a".repeat(65)) }
        assertEquals("a".repeat(64), PetAnimationKey("a".repeat(64)).value)
    }

    @Test
    fun frameRejectsBadIndexAndNanos() {
        assertFailsWith<IllegalArgumentException> { PetFrame(-1, 100_000_000L) }
        assertFailsWith<IllegalArgumentException> { PetFrame(0, 0L) }
        assertFailsWith<IllegalArgumentException> { PetFrame(0, -5L) }
    }

    @Test
    fun animationRejectsEmptyFramesAndBadLoopStart() {
        assertFailsWith<IllegalArgumentException> {
            PetAnimation(emptyList(), 0, PetAnimations.Idle)
        }
        assertFailsWith<IllegalArgumentException> {
            PetAnimation(listOf(PetFrame(0, 100_000_000L)), 1, PetAnimations.Idle)
        }
        assertFailsWith<IllegalArgumentException> {
            PetAnimation(listOf(PetFrame(0, 100_000_000L)), -1, PetAnimations.Idle)
        }
    }

    @Test
    fun definitionRejectsStructuralViolations() {
        val geometry = CodexV1.defaultGeometry()
        val idle = mapOf(PetAnimations.Idle to CodexV1.idleAnimation())
        assertFailsWith<IllegalArgumentException> {
            PetDefinition("a", "A", "", geometry, 0, idle)
        }
        assertFailsWith<IllegalArgumentException> {
            PetDefinition("a", "A", "", geometry, 71, idle)
        }
        assertFailsWith<IllegalArgumentException> {
            PetDefinition("a", "A", "", geometry, 72, emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            PetDefinition(
                "a", "A", "", geometry, 72,
                mapOf(PetAnimationKey("x") to CodexV1.idleAnimation()),
            )
        }
    }

    @Test
    fun definitionExposesInteropSafeLookup() {
        val definition = parseSuccess("{}").let { assertTrue(it is PetParseOutcome.Success); it.definition }
        // Deterministic sorted-by-value ordering.
        val keys = definition.animationKeys.map { it.value }
        assertEquals(keys.sorted(), keys)
        assertEquals(14, keys.size)
        assertTrue(keys.contains("idle"))
        // Lookup by key and by plain name.
        assertEquals(
            definition.animation(PetAnimations.Idle),
            definition.animation("idle"),
        )
        assertNull(definition.animation(PetAnimationKey("nope")))
        assertNull(definition.animation("nope"))
    }

    @Test
    fun definitionSnapshotsCallerCollections() {
        val frames = mutableListOf(PetFrame(0, 100_000_000L))
        val animation = PetAnimation(frames, 0, PetAnimations.Idle)
        frames.add(PetFrame(1, 100_000_000L))
        assertEquals(1, animation.frames.size)

        val map = mutableMapOf(PetAnimations.Idle to animation)
        val definition = PetDefinition("a", "A", "", CodexV1.defaultGeometry(), 72, map)
        map[PetAnimationKey("x")] = animation
        assertEquals(listOf(PetAnimations.Idle), definition.animationKeys)
    }

    @Test
    fun snapshotGuaranteeIsIsolationNotCastProofing() {
        // Documented contract: later mutation of caller-owned collections cannot
        // mutate model state. No claim of JVM cast-proof immutability.
        val map = mutableMapOf(PetAnimations.Idle to CodexV1.idleAnimation())
        val definition = PetDefinition("a", "A", "", CodexV1.defaultGeometry(), 72, map)
        map.clear()
        assertEquals(1, definition.animationKeys.size)
    }

    @Test
    fun reportSnapshotsCallerCollections() {
        val errors = mutableListOf<PetCompatibilityError>(
            PetCompatibilityError.EmptyAnimationFrames("x"),
        )
        val report = PetCompatibilityReport(errors)
        errors.clear()
        assertEquals(1, report.errors.size)
        assertTrue(!report.isCompatible)
    }

    @Test
    fun spritesheetInfoConstructorIsTotal() {
        // No validation here: foreign facts flow into typed errors, never throws.
        val info = SpritesheetInfo(0, -3, SpritesheetFormat.UNKNOWN)
        val definition = parseSuccess("{}").let { assertTrue(it is PetParseOutcome.Success); it.definition }
        val report = CodexCompatibilityValidator.validate(definition, info)
        assertTrue(!report.isCompatible)
        assertTrue(
            report.errors.any { it is PetCompatibilityError.UnsupportedAtlasDimensions } &&
                report.errors.any { it is PetCompatibilityError.UnsupportedSpritesheetFormat },
        )
    }

    @Test
    fun standaloneValidatorCatchesSemanticViolations() {
        // Dangling fallback is constructible (structural) but not compatible.
        val animations = CodexV1.defaultAnimations().toMutableMap()
        animations[PetAnimationKey("a")] = PetAnimation(
            listOf(PetFrame(0, 100_000_000L)),
            null,
            PetAnimationKey("ghost"),
        )
        val dangling = PetDefinition(
            "a", "A", "", CodexV1.defaultGeometry(), CodexV1.FRAME_COUNT, animations,
        )
        val report = CodexCompatibilityValidator.validate(
            dangling,
            SpritesheetInfo(1536, 1872, SpritesheetFormat.WEBP),
        )
        assertTrue(!report.isCompatible)
        val error = report.errors.first()
        assertIs<PetCompatibilityError.UnknownFallback>(error)
        assertEquals("a", error.animation)

        // Out-of-range index is constructible but not compatible.
        val animations2 = CodexV1.defaultAnimations().toMutableMap()
        animations2[PetAnimationKey("b")] = PetAnimation(
            listOf(PetFrame(500, 100_000_000L)),
            0,
            PetAnimations.Idle,
        )
        val badIndex = PetDefinition(
            "a", "A", "", CodexV1.defaultGeometry(), CodexV1.FRAME_COUNT, animations2,
        )
        val report2 = CodexCompatibilityValidator.validate(
            badIndex,
            SpritesheetInfo(1536, 1872, SpritesheetFormat.WEBP),
        )
        assertIs<PetCompatibilityError.SpriteIndexOutOfRange>(report2.errors.first())

        // A clean manual definition validates.
        val clean = PetDefinition(
            "a", "A", "", CodexV1.defaultGeometry(), CodexV1.FRAME_COUNT,
            CodexV1.defaultAnimations(),
        )
        val cleanReport = CodexCompatibilityValidator.validate(
            clean,
            SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        assertTrue(cleanReport.isCompatible)
        assertTrue(cleanReport.warnings.isEmpty())
    }

    @Test
    fun errorMessagesAreStable() {
        assertEquals(
            "animation idle must include at least one frame",
            PetCompatibilityError.EmptyAnimationFrames("idle").message,
        )
        assertEquals(
            "animation idle fps must be finite and between 0 and 60.0, got 0.0",
            PetCompatibilityError.InvalidFps("idle", 0.0).message,
        )
    }
}
