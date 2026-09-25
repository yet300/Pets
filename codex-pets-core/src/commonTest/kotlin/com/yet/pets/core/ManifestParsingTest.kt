package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val WEBP_1536x1872 = SpritesheetInfo(1536, 1872, SpritesheetFormat.WEBP)

internal fun parseSuccess(json: String, fallback: String = "pet"): PetParseOutcome.Success {
    val outcome = PetPackageParser.parse(json, fallback, WEBP_1536x1872)
    assertIs<PetParseOutcome.Success>(outcome, "expected success, got $outcome")
    return outcome
}

internal fun parseFailure(json: String, fallback: String = "pet"): PetCompatibilityReport {
    val outcome = PetPackageParser.parse(json, fallback, WEBP_1536x1872)
    assertIs<PetParseOutcome.Failure>(outcome, "expected failure, got $outcome")
    return outcome.report
}

class ManifestParsingTest {

    @Test
    fun emptyManifestUsesAllDefaults() {
        val success = parseSuccess("{}", "mydir")
        assertEquals("mydir", success.definition.id)
        assertEquals("mydir", success.definition.displayName)
        assertEquals("", success.definition.description)
        assertEquals("spritesheet.webp", success.spritesheetPath)
        assertEquals(72, success.definition.frameCount)
        // Literal profile geometry, not implementation-helper vs implementation-helper.
        assertEquals(AtlasGeometry(1536, 1872, 8, 9, 192, 208), success.definition.geometry)
    }

    @Test
    fun allFieldsAreHonored() {
        val success = parseSuccess(
            """{
                "id": "chefito",
                "displayName": "Chefito",
                "description": "  A tiny chef  ",
                "spritesheetPath": "art/sheet.png"
            }""",
            "fallback-dir",
        )
        assertEquals("chefito", success.definition.id)
        assertEquals("Chefito", success.definition.displayName)
        assertEquals("A tiny chef", success.definition.description)
        assertEquals("art/sheet.png", success.spritesheetPath)
    }

    @Test
    fun blankStringsCountAsAbsent() {
        val success = parseSuccess(
            """{"id": "   ", "displayName": "", "description": "  ", "spritesheetPath": " "}""",
            "fallback-dir",
        )
        assertEquals("fallback-dir", success.definition.id)
        assertEquals("fallback-dir", success.definition.displayName)
        assertEquals("", success.definition.description)
        assertEquals("spritesheet.webp", success.spritesheetPath)
    }

    @Test
    fun unknownFieldsAreIgnoredIncludingSpriteVersionNumber() {
        val success = parseSuccess(
            """{
                "id": "v2pet",
                "spriteVersionNumber": 2,
                "futureField": {"nested": [1, 2, 3]},
                "anotherOne": 42
            }""",
        )
        assertEquals("v2pet", success.definition.id)
        assertEquals(72, success.definition.frameCount)
    }

    @Test
    fun malformedJsonReturnsTypedError() {
        for (bad in listOf("{", "[1,2", "not json", "", "null")) {
            val report = parseFailure(bad)
            assertTrue(report.errors.isNotEmpty(), "expected errors for $bad")
            assertIs<PetCompatibilityError.MalformedManifest>(report.errors.first())
        }
    }

    @Test
    fun schemaTypeMismatchReturnsTypedError() {
        val report = parseFailure("""{"frame": {"width": "wide", "height": 208, "columns": 8, "rows": 9}}""")
        assertIs<PetCompatibilityError.MalformedManifest>(report.errors.first())
    }

    @Test
    fun byteArrayOverloadParses() {
        val outcome = PetPackageParser.parse(
            """{"id": "bytes"}""".encodeToByteArray(),
            "fallback",
            WEBP_1536x1872,
        )
        assertIs<PetParseOutcome.Success>(outcome)
        assertEquals("bytes", outcome.definition.id)
    }

    @Test
    fun invalidUtf8BytesReturnTypedError() {
        val outcome = PetPackageParser.parse(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "fallback",
            WEBP_1536x1872,
        )
        assertIs<PetParseOutcome.Failure>(outcome)
        assertIs<PetCompatibilityError.MalformedManifest>(outcome.report.errors.first())
    }

    @Test
    fun spritesheetPathFacadeReturnsDefault() {
        val outcome = PetPackageParser.spritesheetPathOf("{}")
        assertIs<PetSpritesheetPathOutcome.Success>(outcome)
        assertEquals("spritesheet.webp", outcome.path)
    }

    @Test
    fun spritesheetPathFacadeReturnsTrimmedValue() {
        val outcome = PetPackageParser.spritesheetPathOf("""{"spritesheetPath": "  a/b.webp "}""")
        assertIs<PetSpritesheetPathOutcome.Success>(outcome)
        assertEquals("a/b.webp", outcome.path)
    }

    @Test
    fun spritesheetPathFacadeReportsMalformedJson() {
        val outcome = PetPackageParser.spritesheetPathOf("{oops")
        assertIs<PetSpritesheetPathOutcome.Failure>(outcome)
        assertIs<PetCompatibilityError.MalformedManifest>(outcome.error)
    }

    @Test
    fun spritesheetPathFacadeRejectsInvalidUtf8Bytes() {
        val outcome = PetPackageParser.spritesheetPathOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertIs<PetSpritesheetPathOutcome.Failure>(outcome)
        assertIs<PetCompatibilityError.MalformedManifest>(outcome.error)
    }

    @Test
    fun uintRangeDimensionsFailJsonDecoding() {
        // Documented P2 taxonomy deviation: 4294967295 fits upstream u32 (then fails
        // grid validation) but overflows Kotlin Int, failing JSON decoding instead.
        // Reject in both; only the typed variant differs.
        val report = parseFailure(
            """{"frame": {"width": 4294967295, "height": 208, "columns": 8, "rows": 9}}""",
        )
        assertIs<PetCompatibilityError.MalformedManifest>(report.errors.first())
    }

    @Test
    fun negativeDimensionsFailSemanticValidation() {
        // Same deviation, other direction: upstream serde rejects negatives during
        // u32 decoding; KMP Int DTOs decode then fail InvalidFrameGrid.
        val report = parseFailure(
            """{"frame": {"width": -192, "height": 208, "columns": 8, "rows": 9}}""",
        )
        assertIs<PetCompatibilityError.InvalidFrameGrid>(report.errors.first())
    }

    @Test
    fun absolutePathPassesThroughCoreUnchanged() {
        // Lexical rejection is the io layer's job; core only applies the default.
        val success = parseSuccess("""{"spritesheetPath": "/abs/evil.webp"}""")
        assertEquals("/abs/evil.webp", success.spritesheetPath)
    }

    @Test
    fun unsupportedDimensionsFail() {
        val outcome = PetPackageParser.parse(
            "{}",
            "pet",
            SpritesheetInfo(100, 100, SpritesheetFormat.WEBP),
        )
        assertIs<PetParseOutcome.Failure>(outcome)
        assertIs<PetCompatibilityError.UnsupportedAtlasDimensions>(outcome.report.errors.first())
    }

    @Test
    fun unknownFormatFails() {
        val outcome = PetPackageParser.parse(
            "{}",
            "pet",
            SpritesheetInfo(1536, 1872, SpritesheetFormat.UNKNOWN),
        )
        assertIs<PetParseOutcome.Failure>(outcome)
        assertIs<PetCompatibilityError.UnsupportedSpritesheetFormat>(outcome.report.errors.first())
    }

    @Test
    fun pngFormatAccepted() {
        val outcome = PetPackageParser.parse("{}", "pet", SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG))
        assertIs<PetParseOutcome.Success>(outcome)
    }
}
