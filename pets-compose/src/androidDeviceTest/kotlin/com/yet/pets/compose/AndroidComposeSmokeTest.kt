package com.yet.pets.compose

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.PetAnimations
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Minimal on-device Compose integration smoke for the Phase 3 gate: encoded
 * atlas bytes -> [rememberPetPlayerState] -> [PetAtlasState.Ready] ->
 * [CodexPet], composed with the real public/state path. Asserts composition
 * does not crash and the renderer node exists. No screenshot/golden
 * comparison.
 */
@RunWith(AndroidJUnit4::class)
class AndroidComposeSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun petComposesToReadyAtlasWithoutCrash() {
        val outcome = PetPackageParser.parseTrustedMetadata(
            "{}",
            "test",
            SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        assertIs<PetParseOutcome.Success>(outcome, "expected parse success, got $outcome")
        val bitmap = Bitmap.createBitmap(1536, 1872, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val bytes = out.toByteArray()

        var observed: PetAtlasState? = null
        var decodedOffMain = false
        composeRule.setContent {
            val state = rememberPetPlayerState(outcome.definition, bytes) { encoded ->
                decodedOffMain = Looper.myLooper() != Looper.getMainLooper()
                decodeAtlasBytes(encoded)
            }
            observed = state.atlasState
            CodexPet(state)
        }
        composeRule.waitUntil(10_000) { observed is PetAtlasState.Ready }
        composeRule.onNodeWithTag(CodexPetTag).assertExists()
        println("ANDROID-SMOKE atlasState=$observed")
        assertIs<PetAtlasState.Ready>(observed, "expected Ready, got $observed")
        kotlin.test.assertTrue(decodedOffMain, "atlas decode must run off Android main")
    }
}
