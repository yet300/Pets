package com.yet.pets.host

import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.yet.pets.compose.PetAtlasState
import com.yet.pets.compose.PetPlayerState
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
private fun pngBytes(width: Double, height: Double): ByteArray {
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), false, 1.0)
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    val png = UIImagePNGRepresentation(requireNotNull(image))!!
    val bytes = ByteArray(png.length.toInt())
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), png.bytes, png.length) }
    return bytes
}

private fun genericStandDefinition(): PetDefinition {
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
    val parsed = PetsKmpPackageParser.parseTrustedMetadata(
        manifest,
        SpritesheetInfo(96, 80, SpritesheetFormat.PNG),
    )
    val definition = assertIs<PetsKmpParseOutcome.Success>(parsed).definition
    assertNull(definition.animation("idle"))
    return definition
}

@OptIn(ExperimentalTestApi::class, ExperimentalForeignApi::class)
class HostIosInAppUiTest {
    @Test
    fun inAppCompositionVisibilityAndPosition() = runComposeUiTest {
        val parsed = PetPackageParser.parseTrustedMetadata(
            "{}", "ios-test", SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        val definition = assertIs<PetParseOutcome.Success>(parsed).definition
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(1536.0, 1872.0), false, 1.0)
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val png = UIImagePNGRepresentation(requireNotNull(image))!!
        val bytes = ByteArray(png.length.toInt())
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), png.bytes, png.length) }
        val state = PetHostState()
        var atlas: PetAtlasState? = null
        PetHostPlayerTestProbe.onPlayer = { atlas = it.atlasState }
        try {
        setContent { PetHost(definition, bytes, state, PetHostMode.InApp) }
        waitUntil(timeoutMillis = 10_000) { atlas is PetAtlasState.Ready }
        state.moveTo(20f, 30f)
        state.hide()
        waitForIdle()
        assertEquals(20f, state.xDp)
        assertEquals(30f, state.yDp)
        assertEquals(false, state.isVisible)
        state.show()
        waitForIdle()
        assertEquals(true, state.isVisible)
        } finally {
            PetHostPlayerTestProbe.onPlayer = null
        }
    }

    @Test
    fun genericNoIdleInAppReadyDefaultPlayPinResume() = runComposeUiTest {
        // Permanent no-idle regression: a generic pet with default stand and
        // no idle track reaches Ready on a real decoded atlas, starts on
        // stand with a null explicit request, plays dance, pins to stand,
        // and resumes dance.
        val definition = genericStandDefinition()
        val bytes = pngBytes(96.0, 80.0)
        val state = PetHostState()
        assertNull(state.requestedAnimation)
        var player: PetPlayerState? = null
        PetHostPlayerTestProbe.onPlayer = { player = it }
        try {
            setContent { PetHost(definition, bytes, state, PetHostMode.InApp) }
            waitUntil(timeoutMillis = 10_000) { player?.atlasState is PetAtlasState.Ready }
            // Bound with no explicit request: player follows the definition
            // default, public intent stays null, no idle anywhere.
            assertNull(state.requestedAnimation)
            assertEquals(PetAnimationKey("stand"), player!!.currentSample.animation)
            assertTrue(player!!.currentSample.animation != PetAnimationKey("idle"))

            runOnIdle { state.play(PetAnimationKey("dance")) }
            waitForIdle()
            assertEquals(PetAnimationKey("dance"), state.requestedAnimation)
            assertEquals(PetAnimationKey("dance"), player!!.currentSample.animation)

            runOnIdle { state.pinToDefault() }
            waitForIdle()
            assertTrue(state.isPinned)
            assertEquals(PetAnimationKey("stand"), player!!.currentSample.animation)
            assertEquals(0, player!!.currentSample.spriteIndex)

            runOnIdle { state.resume() }
            waitForIdle()
            assertTrue(!state.isPinned)
            assertEquals(PetAnimationKey("dance"), state.requestedAnimation)
            assertEquals(PetAnimationKey("dance"), player!!.currentSample.animation)
        } finally {
            PetHostPlayerTestProbe.onPlayer = null
        }
    }
}
