package com.yet.pets.host

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.yet.pets.compose.CodexPet
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetDefinition

/**
 * Test seam for the privileged overlay permission. Production uses
 * `Settings.canDrawOverlays`; device tests inject an override because
 * automation cannot reliably grant `SYSTEM_ALERT_WINDOW`. The production
 * check is never weakened: a null override always calls the platform API.
 */
internal object AndroidOverlayPermission {
    var overrideForTest: Boolean? = null

    fun canDrawOverlays(context: Context): Boolean =
        overrideForTest ?: Settings.canDrawOverlays(context)

    /** API >= 26 uses TYPE_APPLICATION_OVERLAY; API 24-25 uses TYPE_PHONE. */
    fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * Accepts touch for dragging, does not steal keyboard focus, and does not
     * create a click-through or fullscreen surface. Window bounds stay near
     * the pet size (see [AndroidPetOverlayController]).
     */
    fun overlayFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
}

/** Small window-ops seam so controller logic is unit-testable with a fake. */
internal interface OverlayWindowOps {
    fun addView(view: View, params: WindowManager.LayoutParams)
    fun updateViewLayout(view: View, params: WindowManager.LayoutParams)
    fun removeView(view: View)
}

internal class RealOverlayWindowOps(
    private val windowManager: WindowManager,
) : OverlayWindowOps {
    override fun addView(view: View, params: WindowManager.LayoutParams) {
        windowManager.addView(view, params)
    }

    override fun updateViewLayout(view: View, params: WindowManager.LayoutParams) {
        windowManager.updateViewLayout(view, params)
    }

    override fun removeView(view: View) {
        windowManager.removeView(view)
    }
}

/**
 * Android overlay controller: owns exactly one small overlay view sized near
 * the pet. Never fullscreen (avoids intercepting unrelated input and invisible
 * touch surfaces). Cleanup calls `removeView` exactly once per added view.
 */
internal class AndroidPetOverlayController(
    private val windowOps: OverlayWindowOps,
) {
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun isShowing(): Boolean = overlayView != null

    fun currentParams(): WindowManager.LayoutParams? = params

    fun show(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        if (overlayView != null) return
        overlayView = view
        this.params = params
        windowOps.addView(view, params)
    }

    fun moveTo(xPx: Int, yPx: Int) {
        val view = overlayView ?: return
        val current = params ?: return
        current.x = xPx
        current.y = yPx
        windowOps.updateViewLayout(view, current)
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        params = null
        try {
            windowOps.removeView(view)
        } catch (_: IllegalArgumentException) {
            // "View not attached to window manager": already removed; safe.
        }
    }
}

/**
 * Builds overlay [WindowManager.LayoutParams] sized near the pet (never
 * fullscreen). Only the pet area receives touch for dragging.
 */
internal fun androidOverlayLayoutParams(
    widthPx: Int,
    heightPx: Int,
    xPx: Int,
    yPx: Int,
): WindowManager.LayoutParams = WindowManager.LayoutParams(
    widthPx.coerceAtLeast(1),
    heightPx.coerceAtLeast(1),
    AndroidOverlayPermission.overlayType(),
    AndroidOverlayPermission.overlayFlags(),
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = xPx
    y = yPx
}

internal fun dpToPx(dp: Float, density: Float): Int = (dp * density).toInt()

internal fun pxToDp(px: Float, density: Float): Float = if (density > 0f) px / density else 0f

@Composable
internal actual fun platformSystemOverlayAvailability(): PetSystemOverlayAvailability {
    val context = LocalContext.current
    return if (AndroidOverlayPermission.canDrawOverlays(context)) {
        PetSystemOverlayAvailability.Available
    } else {
        PetSystemOverlayAvailability.PermissionRequired
    }
}

private class OverlayLifecycleOwner : androidx.lifecycle.LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override val lifecycle: Lifecycle get() = registry
}

/**
 * Attaches [owner] as the view-tree lifecycle owner without a compile-time
 * dependency on the Android-view `ViewTreeLifecycleOwner` class (which has no
 * KMP metadata and is therefore invisible to `androidMain` Kotlin
 * compilation). At runtime the `androidx.lifecycle` AAR is present (Compose
 * brings it transitively), so reflection succeeds on device.
 */
internal fun attachViewTreeLifecycleOwner(view: View, owner: androidx.lifecycle.LifecycleOwner) {
    try {
        val clazz = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
        val set = clazz.getMethod("set", View::class.java, androidx.lifecycle.LifecycleOwner::class.java)
        set.invoke(null, view, owner)
    } catch (_: Exception) {
        // Fallback: tag-based attach using the runtime resource identifier.
        val id = view.resources.getIdentifier(
            "view_tree_lifecycle_owner",
            "id",
            "androidx.lifecycle",
        )
        if (id != 0) {
            view.setTag(id, owner)
        }
    }
}

/**
 * Real Android application overlay using `WindowManager`.
 *
 * - Permission `SYSTEM_ALERT_WINDOW` declared in the host library manifest
 *   (opt-in: only consumers of `:codex-pets-host` inherit it).
 * - Missing permission: renders nothing, adds no window, never crashes, never
 *   silently falls back to in-app (future sample owns the Settings flow).
 * - Lifetime: exists outside any Activity while the process lives; NOT
 *   guaranteed across process death/force-stop/reboot (no boot persistence,
 *   no foreground service in Phase 4).
 * - Dragging updates both `LayoutParams.x/y` and [PetHostState] (dp/px via
 *   current display density); state remains the source of desired position.
 */
@Composable
internal actual fun PlatformPetOverlayHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val density = LocalDensity.current.density

    if (!AndroidOverlayPermission.canDrawOverlays(context)) {
        // Permission missing: typed non-crashing empty host, no window added.
        return
    }

    val cellAspect =
        definition.geometry.cellWidth.toFloat() / definition.geometry.cellHeight.toFloat()
    val petWidthDp = PetHostDefaultPetWidthDp
    val petHeightDp = if (cellAspect.isFinite() && cellAspect > 0f) petWidthDp / cellAspect else petWidthDp
    val widthPx = dpToPx(petWidthDp, density)
    val heightPx = dpToPx(petHeightDp, density)

    DisposableEffect(definition, spritesheetBytes) {
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ops = RealOverlayWindowOps(windowManager)
        val controller = AndroidPetOverlayController(ops)
        val lifecycleOwner = OverlayLifecycleOwner()

        val composeView = ComposeView(applicationContext).apply {
            attachViewTreeLifecycleOwner(this, lifecycleOwner)
        }
        lifecycleOwner.resume()
        composeView.setContent {
            val player = rememberPetPlayerState(definition, spritesheetBytes)
            LaunchedEffect(state.requestedAnimation) {
                player.play(state.requestedAnimation)
            }
            LaunchedEffect(state.isPinned) {
                if (state.isPinned) player.pinToIdle() else if (player.isPinned) player.resume()
            }
            if (state.isVisible) {
                val viewDensity = LocalDensity.current.density
                Box(
                    modifier = Modifier.size(petWidthDp.dp, petHeightDp.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dxDp = pxToDp(dragAmount.x, viewDensity)
                                val dyDp = pxToDp(dragAmount.y, viewDensity)
                                val nextX = state.xDp + dxDp
                                val nextY = state.yDp + dyDp
                                state.moveTo(nextX, nextY)
                                controller.moveTo(
                                    dpToPx(nextX, viewDensity),
                                    dpToPx(nextY, viewDensity),
                                )
                            }
                        },
                ) {
                    CodexPet(player)
                }
            }
        }

        if (state.isVisible) {
            val params = androidOverlayLayoutParams(
                widthPx = widthPx,
                heightPx = heightPx,
                xPx = dpToPx(state.xDp, density),
                yPx = dpToPx(state.yDp, density),
            )
            try {
                controller.show(composeView, params)
            } catch (_: SecurityException) {
                // Permission revoked between check and add: typed empty host.
            } catch (_: WindowManager.BadTokenException) {
                // Window manager rejected the token: no crash, no window.
            }
        }

        onDispose {
            controller.hide()
            lifecycleOwner.destroy()
        }
    }
}
