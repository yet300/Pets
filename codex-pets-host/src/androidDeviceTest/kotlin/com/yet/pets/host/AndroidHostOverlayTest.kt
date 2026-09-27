package com.yet.pets.host

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yet.pets.core.PetAnimations
import com.yet.pets.compose.PetAtlasState
import androidx.lifecycle.Lifecycle
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
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
    fun failedAddDoesNotCreatePhantomAttachment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ops = FakeOps()
        val rejecting = object : OverlayWindowOps by ops {
            override fun addView(view: View, params: WindowManager.LayoutParams) {
                throw SecurityException("permission revoked")
            }
        }
        val controller = AndroidPetOverlayController(rejecting)
        try {
            controller.show(View(context), androidOverlayLayoutParams(96, 104, 0, 0))
            throw AssertionError("expected add rejection")
        } catch (_: SecurityException) {
            assertFalse(controller.isShowing())
            assertEquals(null, controller.currentParams())
            controller.hide()
            assertTrue(ops.removed.isEmpty())
        }
    }

    @Test
    fun hostReportsRejectedAddAndRecoversWithoutPhantomView() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(Settings.canDrawOverlays(context), "grant SYSTEM_ALERT_WINDOW app-op before this test")
        val state = PetHostState()
        val mode = mutableStateOf(PetHostMode.SystemOverlay)
        var reject = true
        var controller: AndroidPetOverlayController? = null
        AndroidOverlayTestProbe.windowOpsFactory = { manager ->
            val real = RealOverlayWindowOps(manager)
            object : OverlayWindowOps by real {
                override fun addView(view: View, params: WindowManager.LayoutParams) {
                    if (reject) throw SecurityException("permission revoked between check and add")
                    real.addView(view, params)
                }
            }
        }
        AndroidOverlayTestProbe.onViewCreated = { _, _, created -> controller = created }
        try {
            val def = definition()
            composeRule.setContent { PetHost(def, ByteArray(16), state, mode.value) }
            composeRule.waitUntil(5_000) { state.platformState == PetHostPlatformState.PermissionRequired }
            assertTrue(state.isVisible, "intent remains visible")
            assertFalse(controller!!.isShowing(), "failed add did not record attachment")
            assertEquals(null, controller!!.currentParams())
            reject = false
            composeRule.runOnIdle { state.moveTo(20f, 30f) }
            composeRule.waitUntil(5_000) { state.platformState == PetHostPlatformState.Showing }
            assertTrue(controller!!.isShowing())
            composeRule.runOnIdle { mode.value = PetHostMode.InApp }
            composeRule.waitUntil(5_000) { !controller!!.isShowing() }
        } finally {
            AndroidOverlayTestProbe.windowOpsFactory = null
            AndroidOverlayTestProbe.onViewCreated = null
        }
    }

    @Test
    fun realOverlayLifecycleWithGrantedAppOp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AndroidOverlayPermission.overrideForTest = null
        assertTrue(Settings.canDrawOverlays(context), "grant SYSTEM_ALERT_WINDOW app-op before this test")
        val bytes = ByteArrayOutputStream().also { stream ->
            val bitmap = Bitmap.createBitmap(1536, 1872, Bitmap.Config.ARGB_8888)
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            finally { bitmap.recycle() }
        }.toByteArray()
        val state = PetHostState()
        val stateRef = mutableStateOf(state)
        val mode = mutableStateOf(PetHostMode.SystemOverlay)
        val view = AtomicReference<androidx.compose.ui.platform.ComposeView?>()
        val owner = AtomicReference<OverlayLifecycleOwner?>()
        val controller = AtomicReference<AndroidPetOverlayController?>()
        val atlas = AtomicReference<PetAtlasState?>()
        AndroidOverlayTestProbe.onViewCreated = { created, lifecycle, controlled ->
            view.set(created); owner.set(lifecycle); controller.set(controlled)
        }
        AndroidOverlayTestProbe.onAtlasState = { atlas.set(it) }
        try {
            val definition = definition()
            composeRule.setContent {
                PetHost(definition, bytes, stateRef.value, mode.value)
            }
            composeRule.waitUntil(15_000) {
                state.platformState == PetHostPlatformState.Showing &&
                    view.get()?.isAttachedToWindow == true && atlas.get() is PetAtlasState.Ready
            }
            val firstView = view.get() ?: error("no ComposeView")
            val firstController = controller.get() ?: error("no controller")
            assertTrue(firstView.width in 1..2_000)
            assertTrue(firstView.height in 1..2_000)
            assertEquals(0, firstController.currentParams()!!.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(Lifecycle.State.RESUMED, owner.get()!!.lifecycle.currentState)

            composeRule.runOnIdle { state.moveTo(40f, 50f) }
            composeRule.waitUntil(5_000) {
                firstController.currentParams()?.x == dpToPx(40f, context.resources.displayMetrics.density)
            }
            assertEquals(dpToPx(50f, context.resources.displayMetrics.density), firstController.currentParams()!!.y)

            composeRule.runOnIdle { state.hide() }
            composeRule.waitUntil(5_000) { state.platformState == PetHostPlatformState.Hidden && !firstView.isAttachedToWindow }
            composeRule.runOnIdle { state.show() }
            composeRule.waitUntil(5_000) { state.platformState == PetHostPlatformState.Showing && firstView.isAttachedToWindow }
            assertTrue(firstView === view.get(), "show reused the ComposeView")

            composeRule.runOnIdle {
                val now = android.os.SystemClock.uptimeMillis()
                firstView.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0))
                firstView.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, 80f, 70f, 0))
                firstView.dispatchTouchEvent(MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_MOVE, 120f, 110f, 0))
                firstView.dispatchTouchEvent(MotionEvent.obtain(now, now + 120, MotionEvent.ACTION_UP, 120f, 110f, 0))
            }
            composeRule.waitUntil(5_000) { state.xDp > 40f && state.yDp > 50f }
            assertEquals(dpToPx(state.xDp, context.resources.displayMetrics.density), firstController.currentParams()!!.x)

            val oldOwner = owner.get()!!
            val replacement = PetHostState()
            composeRule.runOnIdle { stateRef.value = replacement }
            composeRule.waitUntil(5_000) {
                oldOwner.lifecycle.currentState == Lifecycle.State.DESTROYED &&
                    !firstView.isAttachedToWindow &&
                    replacement.platformState == PetHostPlatformState.Showing &&
                    view.get()?.isAttachedToWindow == true
            }
            assertFalse(firstView.isAttachedToWindow)
            assertTrue(firstView !== view.get())
            assertEquals(PetHostPlatformState.Hidden, state.platformState)
            composeRule.runOnIdle { replacement.hide() }
            composeRule.waitUntil(5_000) { replacement.platformState == PetHostPlatformState.Hidden && view.get()?.isAttachedToWindow == false }
            composeRule.runOnIdle { mode.value = PetHostMode.InApp }
            composeRule.waitUntil(5_000) { owner.get()!!.lifecycle.currentState == Lifecycle.State.DESTROYED }
            assertFalse(firstController.isShowing())
            assertFalse(firstView.isAttachedToWindow)
        } finally {
            AndroidOverlayTestProbe.onViewCreated = null
            AndroidOverlayTestProbe.onAtlasState = null
        }
    }

    @Test
    fun twoDifferentStatesOwnIndependentRealWindows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(Settings.canDrawOverlays(context), "grant SYSTEM_ALERT_WINDOW app-op before this test")
        val a = PetHostState(xDp = 10f)
        val b = PetHostState(xDp = 50f)
        val mode = mutableStateOf(PetHostMode.SystemOverlay)
        val views = mutableListOf<androidx.compose.ui.platform.ComposeView>()
        AndroidOverlayTestProbe.onViewCreated = { view, _, _ -> views.add(view) }
        try {
            val def = definition()
            val bytes = ByteArray(16)
            composeRule.setContent {
                if (mode.value == PetHostMode.SystemOverlay) {
                    PetHost(def, bytes, a, mode.value)
                    PetHost(def, bytes, b, mode.value)
                }
            }
            composeRule.waitUntil(5_000) {
                a.platformState == PetHostPlatformState.Showing &&
                    b.platformState == PetHostPlatformState.Showing && views.size == 2
            }
            assertTrue(views[0].isAttachedToWindow)
            assertTrue(views[1].isAttachedToWindow)
            composeRule.runOnIdle { a.hide(); b.moveTo(90f, 100f) }
            composeRule.waitUntil(5_000) { !views[0].isAttachedToWindow && views[1].isAttachedToWindow }
            assertEquals(PetHostPlatformState.Hidden, a.platformState)
            assertEquals(PetHostPlatformState.Showing, b.platformState)
            composeRule.runOnIdle { mode.value = PetHostMode.InApp }
            composeRule.waitUntil(5_000) { !views[1].isAttachedToWindow }
        } finally {
            AndroidOverlayTestProbe.onViewCreated = null
        }
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
