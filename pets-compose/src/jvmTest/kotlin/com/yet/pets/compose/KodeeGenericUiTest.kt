package com.yet.pets.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpParseOutcome
import com.yet.pets.core.PetAnimationKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun repoFile(name: String): File {
    var dir = File(System.getProperty("user.dir"))
    repeat(8) {
        val candidate = File(dir, "assets/kodee/$name")
        if (candidate.exists()) return candidate
        dir = dir.parentFile ?: return@repeat
    }
    return File("assets/kodee/$name").let {
        if (it.exists()) it else File("../assets/kodee/$name")
    }
}

/**
 * Kodee generic integration through PUBLIC APIs: the EXACT supplied
 * spritesheet plus the SEPARATE generic manifest must reach Compose Ready,
 * render idle, and change visible frame on wave. Says nothing about Codex V2.
 */
@OptIn(ExperimentalTestApi::class)
class KodeeGenericUiTest {

    @Test
    fun kodeeGenericReachesReadyAndRendersIdleThenWave() {
        val manifestBytes = repoFile("pet.pets-kmp.json").readBytes()
        val sheetBytes = repoFile("spritesheet.webp").readBytes()
        val parsed = PetsKmpPackageParser.parse(manifestBytes, sheetBytes)
        val success = assertIs<PetsKmpParseOutcome.Success>(parsed, "generic Kodee must parse, got $parsed")
        val definition = success.definition
        assertEquals(1536, definition.geometry.atlasWidth)
        assertEquals(2288, definition.geometry.atlasHeight)
        assertEquals(8, definition.geometry.columns)
        assertEquals(11, definition.geometry.rows)
        assertEquals(88, definition.frameCount)

        // Direct decode proves the exact bytes are a valid static atlas.
        val decoded = decodeAtlasBytes(sheetBytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(1536, bitmap.width)
        assertEquals(2288, bitmap.height)

        runComposeUiTest {
            var state: PetPlayerState? = null
            setContent {
                val player = rememberPetPlayerState(definition, sheetBytes)
                state = player
                Pet(player)
            }
            waitUntil(timeoutMillis = 15_000) { state?.atlasState == PetAtlasState.Ready }
            onNodeWithTag(CodexPetTag).assertExists()
            // Idle frame renders (first idle sprite).
            val idleIndex = state?.currentSample?.spriteIndex
            assertEquals(0, idleIndex)
            val idleParams = assertNotNull(state?.drawParamsFor(idleIndex!!))
            assertEquals(192, idleParams.srcWidth)
            assertEquals(208, idleParams.srcHeight)

            // Wave/action animation changes the visible frame.
            runOnIdle { state?.play(PetAnimationKey("wave")) }
            waitUntil(timeoutMillis = 5_000) { state?.currentSample?.spriteIndex == 24 }
            assertEquals(24, state?.currentSample?.spriteIndex)
            val waveParams = assertNotNull(state?.drawParamsFor(24))
            assertTrue(waveParams.srcTop != idleParams.srcTop || waveParams.srcLeft != idleParams.srcLeft)
        }
    }
}
