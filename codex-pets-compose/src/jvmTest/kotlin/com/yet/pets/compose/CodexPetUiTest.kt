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

    @Test
    fun rendererEntersComposition() {
        runComposeUiTest {
            val definition = testDefinition()
            val decoded = decodeAtlasBytes(tinyPngBytes)
            setContent {
                CodexPet(
                    state = rememberPetPlayerState(definition, tinyPngBytes),
                    animation = PetAnimations.Idle,
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
            var animation by mutableStateOf(PetAnimations.Idle)
            setContent {
                CodexPet(state = player, animation = animation)
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
            animation = PetAnimations.Waving
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
                        animation = PetAnimations.Running,
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
}
