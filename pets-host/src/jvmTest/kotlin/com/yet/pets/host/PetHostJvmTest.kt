package com.yet.pets.host

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.yet.pets.compose.CodexPet
import com.yet.pets.compose.PetAtlasState
import com.yet.pets.compose.PetPlayerState
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import com.yet.pets.core.samplePetAnimation
import java.awt.image.BufferedImage
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.Rectangle
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
            // A fresh state expresses no explicit request (null), and binding
            // resolves it to the definition default without rewriting it.
            assertTrue(host.isVisible)
            assertNull(host.requestedAnimation)
            assertEquals(definition.defaultAnimationKey, host.effectiveAnimation(definition))
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
        val controller = JvmPetOverlayController(
            fake, densityScale = 1f,
            displays = { listOf(Rectangle(-1920, -1080, 1920, 1080), Rectangle(0, 0, 1920, 1080)) },
        )
        controller.configure(96, 104)
        // Multi-monitor: negative coordinates are representable (no >= 0 clamp).
        controller.showAt(-500f, -300f)
        assertEquals(listOf(Pair(-500, -300)), fake.moves)
    }

    @Test
    fun realWindowFollowsVisibilityPositionAndModeSwitch() {
        if (GraphicsEnvironment.isHeadless()) return
        runComposeUiTest {
            val prior = onSwingEdt { Window.getWindows().toSet() }
            val host = PetHostState()
            val hostRef = mutableStateOf(host)
            val mode = mutableStateOf(PetHostMode.SystemOverlay)
            val definition = testDefinition()
            val atlas = fullAtlasPng()
            setContent { PetHost(definition, atlas, hostRef.value, mode.value) }
            waitUntil(timeoutMillis = 10_000) { host.platformState == PetHostPlatformState.Showing }
            val window = onSwingEdt { (Window.getWindows().toSet() - prior).filterIsInstance<JWindow>().single() }
            assertTrue(onSwingEdt { window.isVisible && window.isDisplayable && window.isAlwaysOnTop })
            assertTrue(onSwingEdt { window.width in 1..2_000 && window.height in 1..2_000 })
            assertEquals(0, onSwingEdt { window.background.alpha })
            assertFalse(onSwingEdt { window.rootPane.isOpaque })
            assertFalse(onSwingEdt { (window.contentPane as javax.swing.JComponent).isOpaque })
            assertFalse(onSwingEdt { (window.contentPane.getComponent(0) as javax.swing.JComponent).isOpaque })

            host.moveTo(120f, 140f)
            waitForIdle()
            assertEquals(120, onSwingEdt { window.x })
            assertEquals(140, onSwingEdt { window.y })
            host.hide()
            waitUntil(timeoutMillis = 5_000) { onSwingEdt { !window.isVisible } }
            assertEquals(PetHostPlatformState.Hidden, host.platformState)
            host.show()
            waitUntil(timeoutMillis = 5_000) { onSwingEdt { window.isVisible } }
            assertEquals(PetHostPlatformState.Showing, host.platformState)
            assertTrue(onSwingEdt { window.isDisplayable })

            mode.value = PetHostMode.InApp
            waitUntil(timeoutMillis = 5_000) { onSwingEdt { !window.isDisplayable } }
            assertEquals(PetHostPlatformState.Hidden, host.platformState)

            mode.value = PetHostMode.SystemOverlay
            waitUntil(timeoutMillis = 5_000) { host.platformState == PetHostPlatformState.Showing }
            val reboundWindow = onSwingEdt { (Window.getWindows().toSet() - prior).filterIsInstance<JWindow>().filter { it.isDisplayable }.single() }
            val replacement = PetHostState(xDp = 80f, yDp = 90f)
            hostRef.value = replacement
            waitUntil(timeoutMillis = 5_000) {
                host.platformState == PetHostPlatformState.Hidden &&
                    replacement.platformState == PetHostPlatformState.Showing
            }
            assertFalse(onSwingEdt { reboundWindow.isDisplayable })
            val replacementWindow = onSwingEdt { (Window.getWindows().toSet() - prior).filterIsInstance<JWindow>().filter { it.isDisplayable }.single() }
            assertEquals(80, onSwingEdt { replacementWindow.x })
            assertEquals(90, onSwingEdt { replacementWindow.y })
            mode.value = PetHostMode.InApp
            waitUntil(timeoutMillis = 5_000) { onSwingEdt { !replacementWindow.isDisplayable } }
        }
    }

    @Test
    fun realWindowMutationsRunOnEdt() {
        if (GraphicsEnvironment.isHeadless()) return
        val window = RealJvmOverlayWindow()
        window.configure(96, 104)
        window.moveTo(25, 30)
        window.setVisible(true)
        assertTrue(onSwingEdt { window.windowRef()!!.isVisible })
        assertTrue(onSwingEdt { !window.windowRef()!!.rootPane.isOpaque })
        assertTrue(onSwingEdt { !(window.windowRef()!!.contentPane as javax.swing.JComponent).isOpaque })
        assertFalse(SwingUtilities.isEventDispatchThread())
        window.dispose()
        assertFalse(onSwingEdt { window.windowRef()!!.isDisplayable })
    }

    @Test
    fun hostPlayPinResumeUsesLatestIntentAcrossSameFrameChanges() {
        runComposeUiTest {
            val host = PetHostState()
            var player: PetPlayerState? = null
            PetHostPlayerTestProbe.onPlayer = { player = it }
            try {
                val definition = testDefinition()
                setContent { PetHost(definition, tinyPngBytes, host, PetHostMode.InApp) }
                waitForIdle()

                host.play(PetAnimations.Waving)
                host.pinToIdle()
                waitForIdle()
                assertTrue(player!!.isPinned)
                assertEquals(PetAnimations.Idle, player!!.currentSample.animation)

                host.play(PetAnimations.Running)
                host.play(PetAnimations.Jumping)
                host.play(PetAnimations.Waving)
                host.resume()
                waitForIdle()
                assertFalse(player!!.isPinned)
                assertEquals(PetAnimations.Waving, player!!.currentSample.animation)

                host.play(PetAnimations.Running)
                host.pinToIdle()
                waitForIdle()
                assertTrue(player!!.isPinned)
                assertEquals(PetAnimations.Idle, player!!.currentSample.animation)
                host.resume()
                waitForIdle()
                assertEquals(PetAnimations.Running, player!!.currentSample.animation)
            } finally {
                PetHostPlayerTestProbe.onPlayer = null
            }
        }
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
        assertEquals(
            PetSystemOverlayAvailability.Unsupported,
            jvmAvailabilityForOsName("Mac OS X", headless = true),
        )
    }

    @Test
    fun hostNullIntentResolvesToDefinitionDefault() {
        // Null (no explicit request) resolves to the definition default;
        // explicit play is preserved verbatim.
        val definition = testDefinition()
        val host = PetHostState()
        assertNull(host.requestedAnimation)
        assertEquals(
            samplePetAnimation(definition, definition.defaultAnimationKey, 0L),
            samplePetAnimation(definition, host.effectiveAnimation(definition), 0L),
        )
        host.play(PetAnimations.Waving)
        assertEquals(
            samplePetAnimation(definition, PetAnimations.Waving, 0L),
            samplePetAnimation(definition, host.effectiveAnimation(definition), 0L),
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
                    player.play(host.effectiveAnimation(definition))
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
                    player.play(host.effectiveAnimation(definition))
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
