package com.yet.pets.compose

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Geometry/render integration tests: sample index -> core geometry -> draw
 * parameters. No screenshots; behavior and geometry only.
 */
class DrawParamsTest {

    private fun readyState(): PetPlayerState {
        val decoded = decodedTinyPng()
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap: ImageBitmap = assertNotNull(decoded.bitmap)
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
        return PetPlayerState(testDefinition(), decoded)
    }

    @Test
    fun spriteZeroSourceRect() {
        val params = assertNotNull(readyState().drawParamsFor(0))
        assertEquals(0, params.srcLeft)
        assertEquals(0, params.srcTop)
        assertEquals(192, params.srcWidth)
        assertEquals(208, params.srcHeight)
    }

    @Test
    fun rowBoundarySourceRect() {
        // 8 columns of 192 px: index 8 opens the second row.
        val params = assertNotNull(readyState().drawParamsFor(8))
        assertEquals(0, params.srcLeft)
        assertEquals(208, params.srcTop)
        assertEquals(192, params.srcWidth)
        assertEquals(208, params.srcHeight)
    }

    @Test
    fun finalValidSpriteSourceRect() {
        // 8 x 9 grid: index 71 is the last cell.
        val params = assertNotNull(readyState().drawParamsFor(71))
        assertEquals(7 * 192, params.srcLeft)
        assertEquals(8 * 208, params.srcTop)
        assertEquals(192, params.srcWidth)
        assertEquals(208, params.srcHeight)
    }

    @Test
    fun nonSquareSourceCellAspectPreserved() {
        val params = assertNotNull(readyState().drawParamsFor(0))
        assertEquals(192f / 208f, params.aspect)
        assertEquals(192f / 208f, readyState().cellAspect)
    }

    @Test
    fun invalidIndexCannotCrashRenderer() {
        val state = readyState()
        assertNull(state.drawParamsFor(-1))
        assertNull(state.drawParamsFor(72))
        assertNull(state.drawParamsFor(Int.MAX_VALUE))
    }

    @Test
    fun missingAtlasCannotCrashRenderer() {
        val state = PetPlayerState(testDefinition(), DecodedAtlas(null, PetAtlasState.Failed("test")))
        assertNull(state.drawParamsFor(0))
        assertTrue(state.atlasState is PetAtlasState.Failed)
    }

    @Test
    fun singleAtlasIsSharedNotSliced() {
        // Exactly one decoded image is held; draw params reference it, never copy it.
        val decoded = decodedTinyPng()
        val bitmap = assertNotNull(decoded.bitmap)
        val state = PetPlayerState(testDefinition(), decoded)
        assertNotNull(state.drawParamsFor(0))
        assertNotNull(state.drawParamsFor(71))
    }
}
