package com.yet.pets.host

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.yet.pets.compose.CodexPet
import com.yet.pets.compose.PetAtlasState
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import com.yet.pets.core.samplePetAnimation
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun testDefinition(json: String = "{}"): PetDefinition {
    val outcome = PetPackageParser.parseTrustedMetadata(
        json,
        "test",
        SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
    )
    check(outcome is PetParseOutcome.Success) { "expected parse success, got $outcome" }
    return outcome.definition
}

private val tinyPngBytes: ByteArray
    get() = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

private fun fullAtlasPng(): ByteArray {
    val out = ByteArrayOutputStream()
    check(ImageIO.write(BufferedImage(1536, 1872, BufferedImage.TYPE_INT_ARGB), "png", out))
    return out.toByteArray()
}

private class FakeJvmWindow : JvmOverlayWindow {
    var widthPx = 0
    var heightPx = 0
    val moves = mutableListOf<Pair<Int, Int>>()
    var visibleCalls = mutableListOf<Boolean>()
    var disposeCalls = 0
    var showing = false

    override fun configure(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
    }

    override fun moveTo(xPx: Int, yPx: Int) {
        moves.add(Pair(xPx, yPx))
    }

    override fun setVisible(visible: Boolean) {
        showing = visible
        visibleCalls.add(visible)
    }

    override fun dispose() {
        disposeCalls++
        showing = false
    }

    override val isShowing: Boolean get() = showing
}

@OptIn(ExperimentalTestApi::class)
class PetHostJvmTest {

    @Test
    fun inAppHostComposesWithoutCrash() {
        runComposeUiTest {
            val definition = testDefinition()
            val host = PetHostState()
            setContent {
                PetHost(
                    definition = definition,
                    spritesheetBytes = tinyPngBytes,
                    state = host,
                    mode = PetHostMode.InApp,
                )
            }
            waitForIdle()
            // Successful composition without crash proves the common InApp
            // path works on JVM; host intent is untouched by composition.
            assertTrue(host.isVisible)
            assertEquals(PetAnimations.Idle, host.requestedAnimation)
        }
    }

    @Test
    fun hiddenHostStaysHidden() {
        runComposeUiTest {
            val definition = testDefinition()
            val host = PetHostState(visible = false)
            setContent {
                PetHost(
                    definition = definition,
                    spritesheetBytes = tinyPngBytes,
                    state = host,
                    mode = PetHostMode.InApp,
                )
            }
            waitForIdle()
            assertFalse(host.isVisible)
        }
    }

    @Test
    fun overlayControllerShowMoveHideWithFakeWindow() {
        val fake = FakeJvmWindow()
        val controller = JvmPetOverlayController(fake, densityScale = 2f)
        controller.configure(192, 208)
        assertEquals(192, fake.widthPx)
        assertEquals(208, fake.heightPx)

        controller.showAt(10f, 20f)
        assertEquals(listOf(Pair(20, 40)), fake.moves)
        assertEquals(listOf(true), fake.visibleCalls)
        assertTrue(fake.showing)

        controller.moveTo(30f, 40f)
        assertEquals(listOf(Pair(20, 40), Pair(60, 80)), fake.moves)
        assertTrue(fake.showing)

        // Position synchronization: dp converts deterministically.
        assertEquals(20, controller.dpToPx(10f))
        assertEquals(5f, controller.pxToDp(10))

        controller.hide()
        assertEquals(listOf(true, false), fake.visibleCalls)
        assertFalse(fake.showing)

        controller.dispose()
        assertEquals(1, fake.disposeCalls)
        // Hiding never terminates the app: the controller only toggles window
        // visibility (asserted above); no exit call exists in production code.
    }

    @Test
    fun overlayControllerAllowsNegativeDesktopCoordinates() {
        val fake = FakeJvmWindow()
        val controller = JvmPetOverlayController(fake, densityScale = 1f)
        controller.configure(96, 104)
        // Multi-monitor: negative coordinates are representable (no >= 0 clamp).
        controller.showAt(-500f, -300f)
        assertEquals(listOf(Pair(-500, -300)), fake.moves)
    }

    @Test
    fun jvmAvailabilityMapping() {
        // Pure OS-name mapping (no window creation).
        assertEquals(
            PetSystemOverlayAvailability.Available,
            jvmAvailabilityForOsName("Mac OS X"),
        )
        assertEquals(
            PetSystemOverlayAvailability.Available,
            jvmAvailabilityForOsName("Windows 11"),
        )
        assertEquals(
            PetSystemOverlayAvailability.BestEffort,
            jvmAvailabilityForOsName("Linux"),
        )
        assertEquals(
            PetSystemOverlayAvailability.BestEffort,
            jvmAvailabilityForOsName("SunOS"),
        )
    }

    @Test
    fun hostPlayMatchesPlayerSemantics() {
        // Host requested Idle -> same sample as Phase 3 player Idle.
        val definition = testDefinition()
        val host = PetHostState(animation = PetAnimations.Idle)
        val expectedIdle = samplePetAnimation(definition, PetAnimations.Idle, 0L)
        assertEquals(
            expectedIdle,
            samplePetAnimation(definition, host.requestedAnimation, 0L),
        )
        host.play(PetAnimations.Waving)
        assertEquals(
            samplePetAnimation(definition, PetAnimations.Waving, 0L),
            samplePetAnimation(definition, host.requestedAnimation, 0L),
        )
    }

    @Test
    fun hostMoveDoesNotRestartAnimation() {
        runComposeUiTest {
            val definition = testDefinition()
            val atlas = fullAtlasPng()
            val host = PetHostState(animation = PetAnimations.Waving)
            var observedAnimation: Any? = null
            setContent {
                val player = rememberPetPlayerState(definition, atlas)
                LaunchedEffect(host.requestedAnimation) {
                    player.play(host.requestedAnimation)
                }
                observedAnimation = player.currentSample.animation
                CodexPet(player)
            }
            waitForIdle()
            assertEquals(PetAnimations.Waving, observedAnimation)
            // Position-only update: animation intent untouched, no restart.
            host.moveTo(50f, 60f)
            waitForIdle()
            assertEquals(PetAnimations.Waving, host.requestedAnimation)
            assertEquals(PetAnimations.Waving, observedAnimation)
            assertEquals(50f, host.xDp)
            assertEquals(60f, host.yDp)
        }
    }

    @Test
    fun hostMoveDoesNotRedecodeAtlas() {
        runComposeUiTest {
            val definition = testDefinition()
            val atlas = fullAtlasPng()
            val host = PetHostState()
            var observed: PetAtlasState? = null
            setContent {
                val player = rememberPetPlayerState(definition, atlas)
                LaunchedEffect(host.requestedAnimation) {
                    player.play(host.requestedAnimation)
                }
                observed = player.atlasState
                CodexPet(player)
            }
            waitUntil(timeoutMillis = 10_000) { observed is PetAtlasState.Ready }
            assertIs<PetAtlasState.Ready>(observed)
            // Moving the host never touches definition/bytes instances, so the
            // player must stay Ready (never back to Loading = re-decode).
            host.moveTo(20f, 30f)
            mainClock.advanceTimeBy(500L)
            waitForIdle()
            assertIs<PetAtlasState.Ready>(observed)
            // Animation change also never re-decodes.
            host.play(PetAnimations.Jumping)
            mainClock.advanceTimeBy(500L)
            waitForIdle()
            assertIs<PetAtlasState.Ready>(observed)
        }
    }
}
