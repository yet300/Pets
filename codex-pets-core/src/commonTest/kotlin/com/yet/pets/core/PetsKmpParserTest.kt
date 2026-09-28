package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun petsKmpSuccess(manifestJson: String, info: SpritesheetInfo): PetDefinition {
    val outcome = PetsKmpPackageParser.parseTrustedMetadata(manifestJson, info)
    assertIs<PetsKmpParseOutcome.Success>(outcome, "expected generic success, got $outcome")
    return outcome.definition
}

private fun petsKmpFailure(manifestJson: String, info: SpritesheetInfo): PetsKmpReport {
    val outcome = PetsKmpPackageParser.parseTrustedMetadata(manifestJson, info)
    assertIs<PetsKmpParseOutcome.Failure>(outcome, "expected generic failure, got $outcome")
    return outcome.report
}

private fun genericManifest(
    schema: String? = "\"pets-kmp\"",
    schemaVersion: String? = "1",
    id: String? = "\"p\"",
    displayName: String? = "\"P\"",
    defaultAnimation: String? = "\"idle\"",
    animations: String = """[{"key":"idle","frames":[{"index":0,"durationMs":200}]}]""",
    frame: String? = """{"width":192,"height":208}""",
    extra: String = "",
): String {
    val parts = mutableListOf<String>()
    if (schema != null) parts += "\"schema\": $schema"
    if (schemaVersion != null) parts += "\"schemaVersion\": $schemaVersion"
    if (id != null) parts += "\"id\": $id"
    if (displayName != null) parts += "\"displayName\": $displayName"
    if (frame != null) parts += "\"frame\": $frame"
    if (defaultAnimation != null) parts += "\"defaultAnimation\": $defaultAnimation"
    parts += "\"animations\": $animations"
    if (extra.isNotEmpty()) parts += extra
    return "{${parts.joinToString(",")}}"
}

private val v1Info = SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG)

/**
 * Deterministic generic-format tests (§33): manifest, geometry, animation,
 * and limits boundaries. Proves arbitrary keys and arbitrary valid geometry
 * without Codex assumptions.
 */
class PetsKmpParserTest {

    @Test
    fun schemaMissingFails() {
        val report = petsKmpFailure(genericManifest(schema = null), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.InvalidSchema })
    }

    @Test
    fun schemaWrongFails() {
        val report = petsKmpFailure(genericManifest(schema = "\"codex\""), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.InvalidSchema })
    }

    @Test
    fun versionMissingFails() {
        val report = petsKmpFailure(genericManifest(schemaVersion = null), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.UnsupportedSchemaVersion })
    }

    @Test
    fun versionUnsupportedFails() {
        val report = petsKmpFailure(genericManifest(schemaVersion = "2"), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.UnsupportedSchemaVersion })
    }

    @Test
    fun defaultAnimationMissingFails() {
        val report = petsKmpFailure(genericManifest(defaultAnimation = null), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.UnknownDefaultAnimation })
    }

    @Test
    fun defaultAnimationUnknownFails() {
        val report = petsKmpFailure(genericManifest(defaultAnimation = "\"ghost\""), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.UnknownDefaultAnimation })
    }

    @Test
    fun duplicateAnimationKeyFails() {
        val report = petsKmpFailure(
            genericManifest(
                animations = """[{"key":"idle","frames":[{"index":0,"durationMs":100}]},{"key":"idle","frames":[{"index":1,"durationMs":100}]}]""",
            ),
            v1Info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.DuplicateAnimationKey })
    }

    @Test
    fun emptyAnimationFails() {
        val report = petsKmpFailure(
            genericManifest(animations = """[{"key":"idle","frames":[]}]"""),
            v1Info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.EmptyAnimationFrames })
    }

    @Test
    fun invalidFrameIndexFails() {
        // Capacity for 1536x1872/192x208 is 72; index 72 is out of range.
        val report = petsKmpFailure(
            genericManifest(animations = """[{"key":"idle","frames":[{"index":72,"durationMs":100}]}]"""),
            v1Info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.InvalidFrameIndex })
    }

    @Test
    fun durationZeroFails() {
        val report = petsKmpFailure(
            genericManifest(animations = """[{"key":"idle","frames":[{"index":0,"durationMs":0}]}]"""),
            v1Info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.InvalidDuration })
    }

    @Test
    fun durationNegativeFails() {
        val report = petsKmpFailure(
            genericManifest(animations = """[{"key":"idle","frames":[{"index":0,"durationMs":-5}]}]"""),
            v1Info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.InvalidDuration })
    }

    @Test
    fun durationOverflowFails() {
        // Long.MAX_VALUE ms overflows nanos conversion and must be rejected.
        val report = petsKmpFailure(
            genericManifest(
                animations = """[{"key":"idle","frames":[{"index":0,"durationMs":9223372036854775807}]}]""",
            ),
            v1Info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.InvalidDuration })
    }

    @Test
    fun invalidLoopStartFails() {
        val tooBig = petsKmpFailure(
            genericManifest(
                animations = """[{"key":"idle","loopStart":1,"frames":[{"index":0,"durationMs":100}]}]""",
            ),
            v1Info,
        )
        assertTrue(tooBig.errors.any { it is PetsKmpError.InvalidLoopStart })
        val negative = petsKmpFailure(
            genericManifest(
                animations = """[{"key":"idle","loopStart":-1,"frames":[{"index":0,"durationMs":100}]}]""",
            ),
            v1Info,
        )
        assertTrue(negative.errors.any { it is PetsKmpError.InvalidLoopStart })
    }

    @Test
    fun arbitraryValidGridSucceeds() {
        // §29 synthetic: cell 32x40, atlas 96x80, grid 3x2.
        val info = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)
        val definition = petsKmpSuccess(
            genericManifest(
                frame = """{"width":32,"height":40}""",
                defaultAnimation = "\"blink\"",
                animations = """[{"key":"blink","loopStart":0,"frames":[{"index":0,"durationMs":120},{"index":5,"durationMs":120}]},{"key":"dance","frames":[{"index":1,"durationMs":100},{"index":2,"durationMs":100}]}]""",
            ),
            info,
        )
        assertEquals(3, definition.geometry.columns)
        assertEquals(2, definition.geometry.rows)
        assertEquals(6, definition.frameCount)
        assertEquals(6L, definition.geometry.frameCapacity)
        assertEquals(PetAnimationKey("blink"), definition.defaultAnimationKey)
        assertTrue(definition.animation("blink") != null)
        assertTrue(definition.animation("dance") != null)
    }

    @Test
    fun nonDivisibleDimensionsFail() {
        val info = SpritesheetInfo(100, 80, SpritesheetFormat.PNG)
        val report = petsKmpFailure(
            genericManifest(frame = """{"width":32,"height":40}"""),
            info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.InvalidGrid })
    }

    @Test
    fun frameIndexAtCapacityMinusOneSucceeds() {
        val info = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)
        val definition = petsKmpSuccess(
            genericManifest(
                frame = """{"width":32,"height":40}""",
                animations = """[{"key":"idle","frames":[{"index":5,"durationMs":100}]}]""",
            ),
            info,
        )
        assertEquals(5, definition.animation("idle")!!.frames.single().spriteIndex)
    }

    @Test
    fun frameIndexEqualCapacityFails() {
        val info = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)
        val report = petsKmpFailure(
            genericManifest(
                frame = """{"width":32,"height":40}""",
                animations = """[{"key":"idle","frames":[{"index":6,"durationMs":100}]}]""",
            ),
            info,
        )
        assertTrue(report.errors.any { it is PetsKmpError.InvalidFrameIndex })
    }

    @Test
    fun arbitraryKeysLoopingAndOneShotHold() {
        val info = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)
        val definition = petsKmpSuccess(
            genericManifest(
                frame = """{"width":32,"height":40}""",
                defaultAnimation = "\"dance\"",
                animations = """[{"key":"blink","loopStart":0,"frames":[{"index":0,"durationMs":100},{"index":1,"durationMs":100}]},{"key":"dance","frames":[{"index":2,"durationMs":100},{"index":3,"durationMs":100}]}]""",
            ),
            info,
        )
        // Looping restarts; one-shot holds its final frame (no default append).
        assertEquals(0, samplePetAnimation(definition, PetAnimationKey("blink"), 0L).spriteIndex)
        assertEquals(0, samplePetAnimation(definition, PetAnimationKey("blink"), 200_000_000L).spriteIndex)
        val held = samplePetAnimation(definition, PetAnimationKey("dance"), 5_000_000_000L)
        assertEquals(PetAnimationKey("dance"), held.animation)
        assertEquals(3, held.spriteIndex)
    }

    @Test
    fun manifestBoundaryEnforced() {
        val big = ByteArray(com.yet.pets.core.PetInputLimits.MAX_MANIFEST_BYTES + 1)
        val outcome = PetsKmpPackageParser.parse(big, ByteArray(0))
        assertIs<PetsKmpParseOutcome.Failure>(outcome)
        assertTrue(outcome.report.errors.any { it is PetsKmpError.InputLimitExceeded })
    }

    @Test
    fun spritesheetBoundaryEnforced() {
        val manifest = genericManifest().encodeToByteArray()
        val big = ByteArray(com.yet.pets.core.PetInputLimits.MAX_SPRITESHEET_BYTES + 1)
        val outcome = PetsKmpPackageParser.parse(manifest, big)
        assertIs<PetsKmpParseOutcome.Failure>(outcome)
        assertTrue(outcome.report.errors.any { it is PetsKmpError.InputLimitExceeded })
    }

    @Test
    fun missingIdAndDisplayNameFail() {
        val noId = petsKmpFailure(genericManifest(id = null), v1Info)
        assertTrue(noId.errors.any { it is PetsKmpError.MissingId })
        val noName = petsKmpFailure(genericManifest(displayName = null), v1Info)
        assertTrue(noName.errors.any { it is PetsKmpError.MissingDisplayName })
    }

    @Test
    fun emptyAnimationsFail() {
        val report = petsKmpFailure(genericManifest(animations = "[]"), v1Info)
        assertTrue(report.errors.any { it is PetsKmpError.EmptyAnimations })
    }
}
