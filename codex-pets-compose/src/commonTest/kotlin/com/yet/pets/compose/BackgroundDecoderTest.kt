package com.yet.pets.compose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundDecoderTest {
    @Test
    fun platformDecoderWorksOnDefaultDispatcher() = runBlocking {
        val result = withContext(Dispatchers.Default) { decodeAtlasBytes(tinyPngBytes.copyOf()) }
        assertEquals(PetAtlasState.Ready, result.state)
    }
}
