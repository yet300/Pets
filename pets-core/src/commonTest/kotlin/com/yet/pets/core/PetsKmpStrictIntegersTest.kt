package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val strictInfo = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)

/**
 * Builds a minimal valid generic manifest with raw-token injection for one
 * integer field at a time. A `null` token means the member is absent.
 */
private fun strictManifest(
    schemaVersionToken: String? = "1",
    widthToken: String? = "32",
    heightToken: String? = "40",
    loopStartToken: String? = null,
    indexToken: String? = "0",
    durationToken: String? = "100",
): String {
    val members = mutableListOf("\"schema\": \"pets-kmp\"")
    if (schemaVersionToken != null) members += "\"schemaVersion\": $schemaVersionToken"
    members += "\"id\": \"s\""
    members += "\"displayName\": \"S\""
    val frameMembers = mutableListOf<String>()
    if (widthToken != null) frameMembers += "\"width\": $widthToken"
    if (heightToken != null) frameMembers += "\"height\": $heightToken"
    members += "\"frame\": { ${frameMembers.joinToString(", ")} }"
    members += "\"defaultAnimation\": \"idle\""
    val frameEntryMembers = mutableListOf<String>()
    if (indexToken != null) frameEntryMembers += "\"index\": $indexToken"
    if (durationToken != null) frameEntryMembers += "\"durationMs\": $durationToken"
    val animationMembers = mutableListOf("\"key\": \"idle\"")
    if (loopStartToken != null) animationMembers += "\"loopStart\": $loopStartToken"
    animationMembers += "\"frames\": [{ ${frameEntryMembers.joinToString(", ")} }]"
    members += "\"animations\": [{ ${animationMembers.joinToString(", ")} }]"
    return "{ ${members.joinToString(", ")} }"
}

private fun strictOutcome(manifestJson: String): PetsKmpParseOutcome =
    PetsKmpPackageParser.parseTrustedMetadata(manifestJson, strictInfo)

private fun assertStrictRejected(manifestJson: String, label: String) {
    val outcome = strictOutcome(manifestJson)
    assertIs<PetsKmpParseOutcome.Failure>(outcome, "$label must fail, got $outcome")
    val malformed = outcome.report.errors.filterIsInstance<PetsKmpError.MalformedManifest>()
    assertTrue(malformed.isNotEmpty(), "$label must be MalformedManifest, got ${outcome.report.errors}")
    assertTrue(
        malformed.all { it.message.contains("strict integers") },
        "$label must carry the deterministic format-owned message, got ${outcome.report.errors}",
    )
}

/**
 * Permanent wire-contract locks for Pets KMP v1 strict integers (P1-03):
 * every integer field MUST be an unquoted JSON integer token
 * `-?(0|[1-9][0-9]*)`. Quoted, decimal, exponent, and wrong-type spellings
 * are rejected; ordinary tokens succeed; lexical acceptance never implies
 * semantic acceptance (range rules still apply afterwards); nothing crashes.
 */
class PetsKmpStrictIntegersTest {

    // Wrong JSON type/syntax spellings rejected for EVERY integer field.
    private val badSpellings = listOf(
        "\"1\"",
        "\"0\"",
        "1.0",
        "0.0",
        "1e0",
        "1E0",
        "1E+0",
        "true",
        "{}",
        "[]",
    )

    @Test
    fun schemaVersionRejectsNonIntegerSyntax() {
        for (spelling in badSpellings) {
            assertStrictRejected(strictManifest(schemaVersionToken = spelling), "schemaVersion=$spelling")
        }
    }

    @Test
    fun frameWidthRejectsNonIntegerSyntax() {
        for (spelling in badSpellings) {
            assertStrictRejected(strictManifest(widthToken = spelling), "frame.width=$spelling")
        }
    }

    @Test
    fun frameHeightRejectsNonIntegerSyntax() {
        for (spelling in badSpellings) {
            assertStrictRejected(strictManifest(heightToken = spelling), "frame.height=$spelling")
        }
    }

    @Test
    fun loopStartRejectsNonIntegerSyntax() {
        for (spelling in badSpellings) {
            assertStrictRejected(strictManifest(loopStartToken = spelling), "loopStart=$spelling")
        }
    }

    @Test
    fun frameIndexRejectsNonIntegerSyntax() {
        for (spelling in badSpellings) {
            assertStrictRejected(strictManifest(indexToken = spelling), "frame.index=$spelling")
        }
    }

    @Test
    fun durationMsRejectsNonIntegerSyntax() {
        for (spelling in badSpellings) {
            assertStrictRejected(strictManifest(durationToken = spelling), "durationMs=$spelling")
        }
    }

    @Test
    fun ordinaryIntegerTokensSucceed() {
        val outcome = strictOutcome(
            strictManifest(
                schemaVersionToken = "1",
                widthToken = "32",
                heightToken = "40",
                loopStartToken = "0",
                indexToken = "5",
                durationToken = "2147483647",
            ),
        )
        assertIs<PetsKmpParseOutcome.Success>(outcome, "ordinary integer tokens must succeed, got $outcome")
        assertTrue(outcome.definition.animation("idle") != null)
    }

    @Test
    fun negativeOneIsLexicallyAcceptedThenSemanticallyValidated() {
        // -1 matches the grammar; each field's range rule rejects it afterwards.
        val version = strictOutcome(strictManifest(schemaVersionToken = "-1"))
        assertIs<PetsKmpParseOutcome.Failure>(version)
        assertTrue(version.report.errors.any { it is PetsKmpError.UnsupportedSchemaVersion })

        val width = strictOutcome(strictManifest(widthToken = "-1"))
        assertIs<PetsKmpParseOutcome.Failure>(width)
        assertTrue(width.report.errors.any { it is PetsKmpError.InvalidFrameSize })

        val loopStart = strictOutcome(strictManifest(loopStartToken = "-1"))
        assertIs<PetsKmpParseOutcome.Failure>(loopStart)
        assertTrue(loopStart.report.errors.any { it is PetsKmpError.InvalidLoopStart })

        val index = strictOutcome(strictManifest(indexToken = "-1"))
        assertIs<PetsKmpParseOutcome.Failure>(index)
        assertTrue(index.report.errors.any { it is PetsKmpError.InvalidFrameIndex })

        val duration = strictOutcome(strictManifest(durationToken = "-1"))
        assertIs<PetsKmpParseOutcome.Failure>(duration)
        assertTrue(duration.report.errors.any { it is PetsKmpError.InvalidDuration })
    }

    @Test
    fun intOverflowIsRejectedWithoutCrash() {
        for (field in listOf("schemaVersion", "width", "loopStart", "index")) {
            val manifest = when (field) {
                "schemaVersion" -> strictManifest(schemaVersionToken = "2147483648")
                "width" -> strictManifest(widthToken = "2147483648")
                "loopStart" -> strictManifest(loopStartToken = "2147483648")
                else -> strictManifest(indexToken = "2147483648")
            }
            assertStrictRejected(manifest, "$field=2147483648")
        }
        assertStrictRejected(strictManifest(durationToken = "9223372036854775808"), "durationMs=Long.MAX+1")
        assertStrictRejected(
            strictManifest(durationToken = "99999999999999999999999"),
            "durationMs=huge",
        )
    }

    @Test
    fun durationLongBoundariesAreHandled() {
        // Long.MAX_VALUE ms overflows nanos conversion: typed InvalidDuration.
        val overflow = strictOutcome(strictManifest(durationToken = "9223372036854775807"))
        assertIs<PetsKmpParseOutcome.Failure>(overflow)
        assertTrue(overflow.report.errors.any { it is PetsKmpError.InvalidDuration })
    }

    @Test
    fun nullMembersDoNotCrash() {
        // Null/absent members flow to semantic validation, never a crash.
        // loopStart null/absent is a valid one-shot.
        val nullLoop = strictOutcome(strictManifest(loopStartToken = "null"))
        assertIs<PetsKmpParseOutcome.Success>(nullLoop, "null loopStart is a one-shot, got $nullLoop")

        val nullVersion = strictOutcome(strictManifest(schemaVersionToken = "null"))
        assertIs<PetsKmpParseOutcome.Failure>(nullVersion)
        assertTrue(nullVersion.report.errors.any { it is PetsKmpError.UnsupportedSchemaVersion })

        val nullWidth = strictOutcome(strictManifest(widthToken = "null"))
        assertIs<PetsKmpParseOutcome.Failure>(nullWidth)
        assertTrue(nullWidth.report.errors.any { it is PetsKmpError.InvalidFrameSize })

        val nullIndex = strictOutcome(strictManifest(indexToken = "null"))
        assertIs<PetsKmpParseOutcome.Failure>(nullIndex)
        assertTrue(nullIndex.report.errors.any { it is PetsKmpError.InvalidFrameIndex })

        val nullDuration = strictOutcome(strictManifest(durationToken = "null"))
        assertIs<PetsKmpParseOutcome.Failure>(nullDuration)
        assertTrue(nullDuration.report.errors.any { it is PetsKmpError.InvalidDuration })
    }

    @Test
    fun malformedStructureDoesNotCrash() {
        // Non-object members where objects are expected: typed failure, no crash.
        val frameArray = strictManifest()
            .replace("\"frame\": { \"width\": 32, \"height\": 40 }", "\"frame\": [32, 40]")
        val frameOutcome = strictOutcome(frameArray)
        assertIs<PetsKmpParseOutcome.Failure>(frameOutcome, "frame=array must fail, got $frameOutcome")
        val notObject = strictOutcome("[1, 2, 3]")
        assertIs<PetsKmpParseOutcome.Failure>(notObject)
    }

    @Test
    fun leadingZeroSyntaxIsRejected() {
        // "01" is not -?(0|[1-9][0-9]*): leading zeros are rejected.
        assertStrictRejected(strictManifest(schemaVersionToken = "01"), "schemaVersion=01")
        assertStrictRejected(strictManifest(indexToken = "00"), "index=00")
    }
}
