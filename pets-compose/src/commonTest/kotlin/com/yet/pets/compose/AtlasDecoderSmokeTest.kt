package com.yet.pets.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoder smoke coverage for every leg with a real platform decoder
 * (JVM + iOS simulator in CI): a tiny PNG decodes, foreign bytes fail safely.
 * The full four-format matrix runs on JVM (see `AtlasDecoderFormatTest`).
 */
class AtlasDecoderSmokeTest {

    @Test
    fun tinyPngDecodesToReadyAtlas() {
        val decoded = decodeAtlasBytes(tinyPngBytes)
        assertEquals(PetAtlasState.Ready, decoded.state)
        val bitmap = assertNotNull(decoded.bitmap)
        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
    }

    @Test
    fun malformedBytesFailSafely() {
        val garbage = ByteArray(256) { (it * 31 + 7).toByte() }
        val decoded = decodeAtlasBytes(garbage)
        assertTrue(decoded.state is PetAtlasState.Failed)
        assertTrue((decoded.state as PetAtlasState.Failed).reason.isNotBlank())
        assertNull(decoded.bitmap)
    }

    @Test
    fun emptyBytesFailSafely() {
        val decoded = decodeAtlasBytes(ByteArray(0))
        assertTrue(decoded.state is PetAtlasState.Failed)
        assertNull(decoded.bitmap)
    }

    @Test
    fun truncatedPngFailsSafely() {
        val truncated = tinyPngBytes.copyOf(tinyPngBytes.size / 2)
        val decoded = decodeAtlasBytes(truncated)
        assertTrue(decoded.state is PetAtlasState.Failed, "expected Failed, got ${decoded.state}")
        assertNull(decoded.bitmap)
    }
}
