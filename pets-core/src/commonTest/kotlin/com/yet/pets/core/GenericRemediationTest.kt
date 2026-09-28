package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val remediationInfo = SpritesheetInfo(96, 80, SpritesheetFormat.PNG)

private fun remediationManifest(
    key: String = "dance",
    defaultAnimation: String = "dance",
    extra: String = "",
): String = """
    {
      "schema": "pets-kmp",
      "schemaVersion": 1,
      "id": "r",
      "displayName": "R",
      "frame": {"width": 32, "height": 40},
      "defaultAnimation": "$defaultAnimation",
      "animations": [{"key": "$key", "frames": [{"index": 0, "durationMs": 100}]}]
      $extra
    }
""".trimIndent()

/**
 * Permanent generic regression locks for the remediation:
 * key normalization, unknown-field tolerance, unknown-request policy,
 * frame-capacity semantics, manifest-owned identity, and explicit defaults.
 */
class GenericRemediationTest {

    @Test
    fun manifestKeysAreTrimmedAndCaseSensitive() {
        // " dance " normalizes to "dance"; "Dance" is a different key.
        val manifest = """
            {
              "schema": "pets-kmp",
              "schemaVersion": 1,
              "id": "r",
              "displayName": "R",
              "frame": {"width": 32, "height": 40},
              "defaultAnimation": "dance",
              "animations": [
                {"key": " dance ", "frames": [{"index": 0, "durationMs": 100}]},
                {"key": "Dance", "frames": [{"index": 1, "durationMs": 100}]}
              ]
            }
        """.trimIndent()
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(manifest, remediationInfo)
        val definition = assertIs<PetsKmpParseOutcome.Success>(outcome).definition
        assertTrue(definition.animation("dance") != null)
        assertTrue(definition.animation("Dance") != null)
        assertNull(definition.animation(" dance "))
        assertEquals(0, definition.animation("dance")!!.frames.single().spriteIndex)
        assertEquals(1, definition.animation("Dance")!!.frames.single().spriteIndex)
    }

    @Test
    fun blankAfterTrimKeysAreInvalid() {
        for (key in listOf("", "   ")) {
            val outcome = PetsKmpPackageParser.parseTrustedMetadata(
                remediationManifest(key = key, defaultAnimation = "dance"),
                remediationInfo,
            )
            assertIs<PetsKmpParseOutcome.Failure>(outcome, "key='$key' must fail")
            assertTrue(outcome.report.errors.any { it is PetsKmpError.InvalidAnimationKey })
        }
    }

    @Test
    fun directModelKeysPreserveExactValue() {
        // Format normalization != model constructor validation: the model
        // preserves the exact value, including whitespace.
        assertEquals(" dance ", PetAnimationKey(" dance ").value)
        assertTrue(PetAnimationKey(" dance ") != PetAnimationKey("dance"))
    }

    @Test
    fun unknownFieldsAreIgnoredAtEveryLevel() {
        // Forward-compatible: unknown fields are ignored at any object level
        // handled by the normal DTO decoder; schemaVersion still gates versions.
        val manifest = """
            {
              "schema": "pets-kmp",
              "schemaVersion": 1,
              "futureTopLevel": {"nested": [1, 2, 3]},
              "id": "r",
              "displayName": "R",
              "frame": {"width": 32, "height": 40, "futureCell": true},
              "defaultAnimation": "dance",
              "animations": [
                {"key": "dance", "futureAnim": "x", "frames": [{"index": 0, "durationMs": 100, "futureFrame": 0}]}
              ]
            }
        """.trimIndent()
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(manifest, remediationInfo)
        assertIs<PetsKmpParseOutcome.Success>(outcome, "unknown fields must be ignored, got $outcome")
        val versioned = PetsKmpPackageParser.parseTrustedMetadata(
            manifest.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            remediationInfo,
        )
        assertIs<PetsKmpParseOutcome.Failure>(versioned)
        assertTrue(versioned.report.errors.any { it is PetsKmpError.UnsupportedSchemaVersion })
    }

    @Test
    fun unknownRequestedAnimationResolvesToDefinitionDefault() {
        // GENERIC RUNTIME playback policy (not a manifest fallback, which does
        // not exist in v1): an unknown requested key samples the definition
        // default. Documented here, not redesigned.
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(
            remediationManifest(key = "stand", defaultAnimation = "stand"),
            remediationInfo,
        )
        val definition = assertIs<PetsKmpParseOutcome.Success>(outcome).definition
        assertNull(definition.animation("idle"))
        val at = samplePetAnimation(definition, PetAnimationKey("typo"), 0L)
        assertEquals(PetAnimationKey("stand"), at.animation)
        assertEquals(definition.defaultAnimationKey, at.animation)
    }

    @Test
    fun frameCountIsAtlasCapacity() {
        // frameCount == columns * rows (addressable cells), not used frames.
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(
            remediationManifest(),
            remediationInfo,
        )
        val definition = assertIs<PetsKmpParseOutcome.Success>(outcome).definition
        assertEquals(6, definition.frameCount)
        assertEquals(definition.geometry.frameCapacity, definition.frameCount.toLong())
        assertEquals(1, definition.animation("dance")!!.frames.size)
    }

    @Test
    fun genericParseNeedsNoFallbackIdentity() {
        // The public generic API has no fallbackId parameter: identity is
        // manifest-owned. These calls lock that shape at compile time; the
        // trusted path succeeds on the manifest alone, the bounded path
        // rejects empty sheet bytes.
        val manifest = remediationManifest().encodeToByteArray()
        val trusted = PetsKmpPackageParser.parseTrustedMetadata(manifest, remediationInfo)
        val trustedDefinition = assertIs<PetsKmpParseOutcome.Success>(trusted).definition
        assertEquals("r", trustedDefinition.id)
        val bounded = PetsKmpPackageParser.parse(manifest, ByteArray(0))
        assertIs<PetsKmpParseOutcome.Failure>(bounded)
        assertTrue(bounded.report.errors.any { it is PetsKmpError.InvalidSpritesheetBytes })
    }

    @Test
    fun genericIdentityIsManifestOwned() {
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(
            remediationManifest(),
            remediationInfo,
        )
        val definition = assertIs<PetsKmpParseOutcome.Success>(outcome).definition
        assertEquals("r", definition.id)
        assertEquals("R", definition.displayName)
    }

    @Test
    fun bothAdaptersSupplyExplicitDefaultKeys() {
        // §26 audit: no adapter relies on a constructor default. The generic
        // parser honors a non-idle manifest default; the Codex adapter sets idle.
        val generic = assertIs<PetsKmpParseOutcome.Success>(
            PetsKmpPackageParser.parseTrustedMetadata(
                remediationManifest(key = "stand", defaultAnimation = "stand"),
                remediationInfo,
            ),
        ).definition
        assertEquals(PetAnimationKey("stand"), generic.defaultAnimationKey)

        val codex = assertIs<PetParseOutcome.Success>(
            PetPackageParser.parseTrustedMetadata(
                "{}",
                "test",
                SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
            ),
        ).definition
        assertEquals(PetAnimations.Idle, codex.defaultAnimationKey)
    }

    @Test
    fun longManifestKeysAcceptedWithinManifestCap() {
        // Pets KMP imposes no separate string cap: a 1000-character manifest
        // key is accepted (bounded by the 64 KiB manifest cap instead).
        val key = "k".repeat(1000)
        val outcome = PetsKmpPackageParser.parseTrustedMetadata(
            remediationManifest(key = key, defaultAnimation = key),
            remediationInfo,
        )
        val definition = assertIs<PetsKmpParseOutcome.Success>(outcome).definition
        assertEquals(PetAnimationKey(key), definition.defaultAnimationKey)
    }
}
