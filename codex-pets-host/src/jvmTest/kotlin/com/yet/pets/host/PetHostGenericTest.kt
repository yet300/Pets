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
}
