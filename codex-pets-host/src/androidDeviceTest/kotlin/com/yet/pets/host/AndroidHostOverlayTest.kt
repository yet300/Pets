package com.yet.pets.host

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yet.pets.core.PetAnimations
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * On-device host regressions (API 24 + modern):
 * - permission missing -> PermissionRequired, no window, no crash;
 * - permission granted/test-injected -> overlay can be shown/moved/hidden;
 * - overlay window is small (never fullscreen), correct type/flags;
 * - cleanup removes the view exactly once (no leak, no double-remove crash);
 * - InApp host composes on device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidHostOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun definition() = run {
        val outcome = PetPackageParser.parseTrustedMetadata(
            "{}",
            "test",
            SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
        )
        assertIs<PetParseOutcome.Success>(outcome, "expected parse success, got $outcome")
        outcome.definition
    }

    @Test
    fun permissionMissingReportsPermissionRequiredWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidOverlayPermission.overrideForTest = false
        try {
            assertFalse(AndroidOverlayPermission.canDrawOverlays(context))
            // Composable availability mirrors the same seam: PermissionRequired.
            // Single setContent (the rule allows one content per test): capture
            // availability and render SystemOverlay together. Missing permission
            // renders nothing and adds no window: no crash, no fallback.
            var availability: PetSystemOverlayAvailability? = null
            val state = PetHostState()
            composeRule.setContent {
                availability = platformSystemOverlayAvailability()
                PetHost(
                    definition = definition(),
                    spritesheetBytes = ByteArray(16),
                    state = state,
                    mode = PetHostMode.SystemOverlay,
                )
            }
            composeRule.waitForIdle()
            assertEquals(PetSystemOverlayAvailability.PermissionRequired, availability)
        } finally {
            AndroidOverlayPermission.overrideForTest = null
        }
    }

    @Test
    fun overlayTypeMatchesApiLevel() {
        val type = AndroidOverlayPermission.overlayType()
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, type)
        } else {
            @Suppress("DEPRECATION")
            assertEquals(WindowManager.LayoutParams.TYPE_PHONE, type)
        }
        println("ANDROID-HOST overlayType=$type api=${Build.VERSION.SDK_INT}")
    }

    @Test
    fun overlayFlagsDoNotStealFocusAndAllowTouch() {
        val flags = AndroidOverlayPermission.overlayFlags()
        assertTrue((flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        // Must NOT be fully click-through (dragging needs touch).
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // Must NOT be fullscreen.
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    @Test
    fun overlayLayoutParamsAreSmallNotFullscreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val density = context.resources.displayMetrics.density
        val widthPx = dpToPx(PetHostDefaultPetWidthDp, density)
        val heightPx = dpToPx(104f, density)
        val params = androidOverlayLayoutParams(widthPx, heightPx, 10, 20)
        // Approximately the pet size, not the screen size.
        assertEquals(widthPx, params.width)
        assertEquals(heightPx, params.height)
        assertTrue(params.width in 1..2_000)
        assertTrue(params.height in 1..2_000)
        assertEquals(10, params.x)
        assertEquals(20, params.y)
        // Gravity TOP|START so x/y are from the top-left.
        assertTrue((params.gravity and android.view.Gravity.TOP) != 0)
        assertTrue((params.gravity and android.view.Gravity.START) != 0)
    }

    private class FakeOps : OverlayWindowOps {
        val added = mutableListOf<View>()
        val updated = mutableListOf<View>()
        val removed = mutableListOf<View>()
        var removeThrowsOnce = false

        override fun addView(view: View, params: WindowManager.LayoutParams) {
            added.add(view)
        }

        override fun updateViewLayout(view: View, params: WindowManager.LayoutParams) {
            updated.add(view)
        }

        override fun removeView(view: View) {
            removed.add(view)
            if (removeThrowsOnce) {
                removeThrowsOnce = false
                throw IllegalArgumentException("View not attached to window manager")
            }
        }
    }

    @Test
    fun controllerShowMoveHideCleansUpExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ops = FakeOps()
        val controller = AndroidPetOverlayController(ops)
        assertFalse(controller.isShowing())

        val view = View(context)
        val params = androidOverlayLayoutParams(200, 220, 0, 0)
        controller.show(view, params)
        assertTrue(controller.isShowing())
        assertEquals(1, ops.added.size)

        // Second show is a no-op (no double-add).
        controller.show(View(context), params)
        assertEquals(1, ops.added.size)

        controller.moveTo(100, 200)
        assertEquals(1, ops.updated.size)
        assertEquals(100, controller.currentParams()?.x)
        assertEquals(200, controller.currentParams()?.y)

        controller.hide()
        assertFalse(controller.isShowing())
        assertEquals(1, ops.removed.size)

        // Double-hide is safe (no double-remove crash).
        controller.hide()
        assertEquals(1, ops.removed.size)
    }

    @Test
    fun controllerSurvivesViewNotAttachedRace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ops = FakeOps().apply { removeThrowsOnce = true }
        val controller = AndroidPetOverlayController(ops)
        controller.show(View(context), androidOverlayLayoutParams(200, 220, 0, 0))
        // "View not attached" during remove must not crash.
        controller.hide()
        assertFalse(controller.isShowing())
        assertEquals(1, ops.removed.size)
    }

    @Test
    fun dpPxConversionUsesDensity() {
        assertEquals(200, dpToPx(100f, 2f))
        assertEquals(50f, pxToDp(100f, 2f))
        // Host state stays in dp; params carry px.
        val state = PetHostState(xDp = 12f, yDp = 34f)
        state.moveTo(56f, 78f)
        assertEquals(56f, state.xDp)
        assertEquals(78f, state.yDp)
        assertEquals(PetAnimations.Idle, state.requestedAnimation)
    }

    @Test
    fun inAppHostComposesOnDevice() {
        val def = definition()
        // Small synthetic PNG bytes (content irrelevant: InApp must compose
        // without crash; decode outcome is Loading/Failed, never a crash).
        val bytes = ByteArray(64) { it.toByte() }
        val state = PetHostState()
        composeRule.setContent {
            PetHost(
                definition = def,
                spritesheetBytes = bytes,
                state = state,
                mode = PetHostMode.InApp,
            )
        }
        composeRule.waitForIdle()
        assertTrue(state.isVisible)
    }
}
