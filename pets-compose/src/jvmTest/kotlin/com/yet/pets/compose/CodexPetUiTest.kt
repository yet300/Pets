package com.yet.pets.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.yet.pets.core.PetAnimations
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.yet.pets.core.PetInputLimits
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Compose UI behavior tests (JVM): composition enter/update/leave and the
 * remember/decode ownership model. Playback math itself is covered by the
 * pure state tests; these prove the renderer integration.
 *
 * Frame timing is fully deterministic: the player state is constructed with
 * a virtual clock that the test advances in lockstep with the test main
 * clock, so no real frame durations are ever slept.
 */
@OptIn(ExperimentalTestApi::class)
class CodexPetUiTest {

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), "png", out))
        return out.toByteArray()
    }

    @Test
    fun rendererEntersComposition() {
        runComposeUiTest {
            val definition = testDefinition()
            val decoded = decodeAtlasBytes(tinyPngBytes)
            setContent {
                CodexPet(
                    state = rememberPetPlayerState(definition, tinyPngBytes),
                )
            }
            onNodeWithTag(CodexPetTag).assertExists()
            assertNotNull(decoded.bitmap)
        }
    }

    @Test
    fun animationChangeUpdatesRenderedFrame() {
        runComposeUiTest {
            mainClock.autoAdvance = false
            var virtualNow = 0L
            val player = PetPlayerState(testDefinition(), decodedTinyPng()) { virtualNow }
            setContent {
                CodexPet(state = player)
            }
            // Pump the first frame: scheduler samples idle at elapsed zero.
            mainClock.advanceTimeBy(100L)
            assertEquals(0, player.currentSample.spriteIndex)

            // Move the virtual clock past idle frame 0 (1_680 ms) and let the
            // scheduled wake-up resample: second frame, no restart involved.
            virtualNow += 2_000_000_000L
            mainClock.advanceTimeBy(2_000L)
            runOnIdle {
                assertEquals(1, player.currentSample.spriteIndex)
            }

            // Changing the intent restarts elapsed time from zero on the new track:
            // waving frame 0 is sprite 24 (row 3 of the CLI V1 table).
            runOnIdle { player.play(PetAnimations.Waving) }
            mainClock.advanceTimeBy(100L)
            runOnIdle {
                assertEquals(PetAnimations.Waving, player.currentSample.animation)
                assertEquals(24, player.currentSample.spriteIndex)
            }
        }
    }

    @Test
    fun leavingCompositionStopsSchedulerCleanly() {
        runComposeUiTest {
            var show by mutableStateOf(true)
            setContent {
                if (show) {
                    CodexPet(
                        state = rememberPetPlayerState(testDefinition(), tinyPngBytes),
                    )
                }
            }
            onNodeWithTag(CodexPetTag).assertExists()
            mainClock.advanceTimeBy(500L)
            show = false
            waitForIdle()
            onNodeWithTag(CodexPetTag).assertDoesNotExist()
            // Advancing further must not reschedule or crash: the frame loop is
            // a child of the composition and is cancelled when it leaves.
            mainClock.advanceTimeBy(5_000L)
            waitForIdle()
        }
    }

    @Test
    fun replacingImageBytesCausesExactlyOneRedeode() {
        runComposeUiTest {
            var current by mutableStateOf(tinyPngBytes)
            var decodes = 0
            var captured: PetPlayerState? = null
            setContent {
                captured = rememberPetPlayerState(testDefinition(), current) { bytes ->
                    decodes++
                    decodeAtlasBytes(bytes)
                }
            }
            waitForIdle()
            assertNotNull(captured)
            assertEquals(1, decodes)

            // Pumping frames without touching inputs never re-decodes.
            mainClock.advanceTimeBy(1_000L)
            waitForIdle()
            assertEquals(1, decodes)

            // A new array instance — even with identical content — is new image
            // content (ByteArray equality is referential) and decodes exactly once.
            current = current.copyOf()
            waitForIdle()
            assertEquals(2, decodes)
            assertNotNull(captured)
        }
    }

    @Test
    fun publicStateRejectsMismatchedAtlasAndOversizedBytes() {
        runComposeUiTest {
            val definition = testDefinition()
            var state: PetPlayerState? = null
            setContent { state = rememberPetPlayerState(definition, tinyPngBytes) }
            waitUntil(timeoutMillis = 5_000) { state?.atlasState is PetAtlasState.Failed }
            assertIs<PetAtlasState.Failed>(state?.atlasState)
            assertEquals(null, state?.drawParamsFor(0))
        }
        for ((width, height) in listOf(1535 to 1872, 1536 to 1871)) {
            runComposeUiTest {
                val input = png(width, height)
                var state: PetPlayerState? = null
                setContent { state = rememberPetPlayerState(testDefinition(), input) }
                waitUntil(timeoutMillis = 5_000) { state?.atlasState is PetAtlasState.Failed }
                assertEquals(null, state?.drawParamsFor(0))
            }
        }
        runComposeUiTest {
            val definition = testDefinition()
            var decoderCalls = 0
            var state: PetPlayerState? = null
            val overLimit = ByteArray(PetInputLimits.MAX_SPRITESHEET_BYTES + 1)
            setContent {
                state = rememberPetPlayerState(definition, overLimit) {
                    decoderCalls++
                    decodedTinyPng()
                }
            }
            waitUntil(timeoutMillis = 5_000) { state?.atlasState is PetAtlasState.Failed }
            assertEquals(0, decoderCalls)
        }
        for (size in listOf(PetInputLimits.MAX_SPRITESHEET_BYTES - 1, PetInputLimits.MAX_SPRITESHEET_BYTES)) {
            runComposeUiTest {
                val input = ByteArray(size)
                var decoderCalls = 0
                var state: PetPlayerState? = null
                setContent {
                    state = rememberPetPlayerState(testDefinition(), input) {
                        decoderCalls++
                        DecodedAtlas(null, PetAtlasState.Failed("test decoder"))
                    }
                }
                waitUntil(timeoutMillis = 5_000) { state?.atlasState is PetAtlasState.Failed }
                assertEquals(1, decoderCalls, "size $size should reach decoder")
            }
        }
    }

    @Test
    fun lateDecodeFromReplacedInputCannotPublish() {
        runComposeUiTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var bytes by mutableStateOf(tinyPngBytes)
            val replacement = tinyPngBytes + byteArrayOf(0)
            var state: PetPlayerState? = null
            setContent {
                state = rememberPetPlayerState(testDefinition(), bytes) { encoded ->
                    if (encoded.size == tinyPngBytes.size) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        DecodedAtlas(null, PetAtlasState.Failed("A"))
                    } else {
                        DecodedAtlas(null, PetAtlasState.Failed("B"))
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { entered.count == 0L }
            bytes = replacement
            waitUntil(timeoutMillis = 5_000) { state?.atlasState is PetAtlasState.Failed }
            val current = state
            assertEquals(PetAtlasState.Failed("B"), current?.atlasState)
            release.countDown()
            mainClock.advanceTimeBy(100L)
            assertEquals(current, state)
            assertEquals(PetAtlasState.Failed("B"), state?.atlasState)
        }
    }

    @Test
    fun twoRenderersObserveOneExplicitPlaybackIntent() {
        runComposeUiTest {
            val player = PetPlayerState(testDefinition(), decodedTinyPng())
            setContent {
                CodexPet(player)
                CodexPet(player)
            }
            runOnIdle { player.play(PetAnimations.Waving) }
            waitForIdle()
            assertEquals(PetAnimations.Waving, player.currentSample.animation)
            assertEquals(1, player.animationEpoch)
        }
    }
}
