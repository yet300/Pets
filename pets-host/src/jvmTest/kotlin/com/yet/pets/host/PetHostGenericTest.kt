package com.yet.pets.host

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import com.yet.pets.core.samplePetAnimation
import com.yet.pets.core.staticDefaultSpriteIndex
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun genericNoIdleDefinition(): com.yet.pets.core.PetDefinition {
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
            {"key": "dance", "frames": [{"index": 2, "durationMs": 100}]},
            {"key": "walk", "frames": [{"index": 3, "durationMs": 100}]}
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

private fun atlasPng(width: Int = 96, height: Int = 80): ByteArray {
    val out = ByteArrayOutputStream()
    check(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out))
    return out.toByteArray()
}

/**
 * Host genericity: a definition with NO animation named `idle` still
 * composes, plays an arbitrary key, pins to default, and resumes.
 */
@OptIn(ExperimentalTestApi::class)
class PetHostGenericTest {

    @Test
    fun hostComposesWithNoIdleAnimation() {
        runComposeUiTest {
            val definition = genericNoIdleDefinition()
            val host = PetHostState()
            setContent {
                PetHost(
                    definition = definition,
                    spritesheetBytes = atlasPng(),
                    state = host,
                    mode = PetHostMode.InApp,
                )
            }
            waitForIdle()
            assertTrue(host.isVisible)
        }
    }

    @Test
    fun hostPlayArbitraryKeyPinToDefaultAndResume() {
        val definition = genericNoIdleDefinition()
        // Pure sampling path: proves default semantics without UI.
        val initial = samplePetAnimation(definition, PetAnimationKey("stand"), 0L)
        assertEquals(PetAnimationKey("stand"), initial.animation)
        val unknown = samplePetAnimation(definition, PetAnimationKey("idle"), 0L)
        assertEquals(PetAnimationKey("stand"), unknown.animation)
        assertEquals(0, staticDefaultSpriteIndex(definition))

        // Host composition with the same definition.
        runComposeUiTest {
            val host = PetHostState()
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = definition,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                runOnIdle { host.play(PetAnimationKey("dance")) }
                waitForIdle()
                assertEquals(PetAnimationKey("dance"), host.requestedAnimation)
                runOnIdle { host.pinToDefault() }
                waitForIdle()
                assertTrue(host.isPinned)
                runOnIdle { host.resume() }
                waitForIdle()
                assertTrue(!host.isPinned)
                assertEquals(PetAnimationKey("dance"), host.requestedAnimation)
                assertTrue(captured != null)
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }

    @Test
    fun unknownKeyResolvesToStandThroughSampling() {
        val definition = genericNoIdleDefinition()
        val at = samplePetAnimation(definition, PetAnimationKey("idle"), 0L)
        assertEquals(PetAnimationKey("stand"), at.animation)
    }

    @Test
    fun initialNullIntentBindsToStandWithoutMutation() {
        // P1-01: a fresh state exposes no explicit request; binding resolves
        // the player to the definition default without rewriting public intent.
        val definition = genericNoIdleDefinition()
        runComposeUiTest {
            val host = PetHostState()
            assertNull(host.requestedAnimation)
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = definition,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                assertNull(host.requestedAnimation)
                runOnIdle {
                    val sample = captured!!.currentSample
                    assertEquals(PetAnimationKey("stand"), sample.animation)
                    assertEquals(0, sample.spriteIndex)
                    assertTrue(sample.animation != PetAnimationKey("idle"))
                }
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }

    @Test
    fun rebindNullIntentFollowsNewDefault() {
        // Same state rebound from default stand (A) to default sleep (B):
        // player follows, public intent stays null, no idle is injected.
        val definitionB = sleepDefinition()
        runComposeUiTest {
            val host = PetHostState()
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = genericNoIdleDefinition(),
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                runOnIdle {
                    assertEquals(PetAnimationKey("stand"), captured!!.currentSample.animation)
                }
                setContent {
                    PetHost(
                        definition = definitionB,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                assertNull(host.requestedAnimation)
                runOnIdle {
                    val sample = captured!!.currentSample
                    assertEquals(PetAnimationKey("sleep"), sample.animation)
                    assertTrue(sample.animation != PetAnimationKey("idle"))
                }
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }

    @Test
    fun explicitPlaySurvivesRebind() {
        // play(dance) is explicit intent: rebinding to a definition that also
        // has dance preserves the request and the player.
        val definitionB = sleepDefinition()
        runComposeUiTest {
            val host = PetHostState()
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = genericNoIdleDefinition(),
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                runOnIdle { host.play(PetAnimationKey("dance")) }
                waitForIdle()
                assertEquals(PetAnimationKey("dance"), host.requestedAnimation)
                setContent {
                    PetHost(
                        definition = definitionB,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                assertEquals(PetAnimationKey("dance"), host.requestedAnimation)
                runOnIdle {
                    assertEquals(PetAnimationKey("dance"), captured!!.currentSample.animation)
                }
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }

    @Test
    fun explicitPlayUnknownToNewDefinitionResolvesToDefault() {
        // Documented generic policy: an explicit request missing from the
        // rebound definition samples the new default, while public intent
        // keeps the explicit key (never silently rewritten).
        val sleepOnly = sleepOnlyDefinition()
        runComposeUiTest {
            val host = PetHostState()
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = genericNoIdleDefinition(),
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                runOnIdle { host.play(PetAnimationKey("dance")) }
                waitForIdle()
                setContent {
                    PetHost(
                        definition = sleepOnly,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                assertEquals(PetAnimationKey("dance"), host.requestedAnimation)
                runOnIdle {
                    assertEquals(PetAnimationKey("sleep"), captured!!.currentSample.animation)
                }
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }

    @Test
    fun pinResumeWithNullIntentStaysOnStand() {
        // requestedAnimation = null, default = stand:
        // initial -> stand, pin -> first stand frame, resume -> stand.
        val definition = genericNoIdleDefinition()
        runComposeUiTest {
            val host = PetHostState()
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = definition,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                runOnIdle {
                    assertEquals(PetAnimationKey("stand"), captured!!.currentSample.animation)
                }
                runOnIdle { host.pinToDefault() }
                waitForIdle()
                assertTrue(host.isPinned)
                assertNull(host.requestedAnimation)
                runOnIdle {
                    val pinned = captured!!.currentSample
                    assertEquals(PetAnimationKey("stand"), pinned.animation)
                    assertEquals(0, pinned.spriteIndex)
                    assertNull(pinned.nextFrameInNanos)
                }
                runOnIdle { host.resume() }
                waitForIdle()
                assertTrue(!host.isPinned)
                assertNull(host.requestedAnimation)
                runOnIdle {
                    assertEquals(PetAnimationKey("stand"), captured!!.currentSample.animation)
                }
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }

    @Test
    fun pinPlayWhilePinnedResumeExplicit() {
        // play(dance), pin, play(walk) while pinned, resume:
        // pinned visual -> first default frame, public request -> walk,
        // resume -> walk. No idle anywhere.
        val definition = genericNoIdleDefinition()
        runComposeUiTest {
            val host = PetHostState()
            var captured: com.yet.pets.compose.PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { captured = it }
            try {
                setContent {
                    PetHost(
                        definition = definition,
                        spritesheetBytes = atlasPng(),
                        state = host,
                        mode = PetHostMode.InApp,
                    )
                }
                waitForIdle()
                runOnIdle { host.play(PetAnimationKey("dance")) }
                waitForIdle()
                runOnIdle { host.pinToDefault() }
                waitForIdle()
                runOnIdle { host.play(PetAnimationKey("walk")) }
                waitForIdle()
                assertTrue(host.isPinned)
                assertEquals(PetAnimationKey("walk"), host.requestedAnimation)
                runOnIdle {
                    val pinned = captured!!.currentSample
                    assertEquals(PetAnimationKey("stand"), pinned.animation)
                    assertEquals(0, pinned.spriteIndex)
                }
                runOnIdle { host.resume() }
                waitForIdle()
                assertTrue(!host.isPinned)
                assertEquals(PetAnimationKey("walk"), host.requestedAnimation)
                runOnIdle {
                    assertEquals(PetAnimationKey("walk"), captured!!.currentSample.animation)
                }
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
    }
}

private fun sleepDefinition(): com.yet.pets.core.PetDefinition {
    val manifest = """
        {
          "schema": "pets-kmp",
          "schemaVersion": 1,
          "id": "h",
          "displayName": "H",
          "frame": {"width": 32, "height": 40},
          "defaultAnimation": "sleep",
          "animations": [
            {"key": "sleep", "loopStart": 0, "frames": [{"index": 4, "durationMs": 100}, {"index": 5, "durationMs": 100}]},
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
    assertNull(outcome.definition.animation("stand"))
    return outcome.definition
}

private fun sleepOnlyDefinition(): com.yet.pets.core.PetDefinition {
    val manifest = """
        {
          "schema": "pets-kmp",
          "schemaVersion": 1,
          "id": "o",
          "displayName": "O",
          "frame": {"width": 32, "height": 40},
          "defaultAnimation": "sleep",
          "animations": [
            {"key": "sleep", "loopStart": 0, "frames": [{"index": 4, "durationMs": 100}]}
          ]
        }
    """.trimIndent()
    val outcome = PetsKmpPackageParser.parseTrustedMetadata(
        manifest,
        SpritesheetInfo(96, 80, SpritesheetFormat.PNG),
    )
    assertIs<PetsKmpParseOutcome.Success>(outcome)
    assertNull(outcome.definition.animation("idle"))
    assertNull(outcome.definition.animation("dance"))
    return outcome.definition
}
