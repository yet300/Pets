package com.yet.pets.host

import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.yet.pets.compose.PetAtlasState
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

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
}
