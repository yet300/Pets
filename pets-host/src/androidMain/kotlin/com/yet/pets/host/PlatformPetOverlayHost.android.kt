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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.yet.pets.compose.Pet
import com.yet.pets.compose.PetAtlasState
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetDefinition

/**
 * Test seam for the privileged overlay permission. Production uses
 * `Settings.canDrawOverlays`; device tests inject an override for permission
 * transition and add-race cases. The production
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

/** Internal observation seam for real device regressions; null in production. */
internal object AndroidOverlayTestProbe {
    var windowOpsFactory: ((WindowManager) -> OverlayWindowOps)? = null
    var onViewCreated: ((ComposeView, OverlayLifecycleOwner, AndroidPetOverlayController) -> Unit)? = null
    var onAtlasState: ((PetAtlasState) -> Unit)? = null
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
        windowOps.addView(view, params)
        overlayView = view
        this.params = params
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

internal fun dpToPx(dp: Float, density: Float): Int {
    val scaled = dp.toDouble() * density.toDouble()
    return when {
        scaled.isNaN() -> 0
        scaled >= Int.MAX_VALUE -> Int.MAX_VALUE
        scaled <= Int.MIN_VALUE -> Int.MIN_VALUE
        else -> scaled.toInt()
    }
}

internal fun pxToDp(px: Float, density: Float): Float = if (density > 0f) px / density else 0f

@Composable
internal actual fun platformSystemOverlayAvailability(): PetSystemOverlayAvailability {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeGeneration by remember(lifecycleOwner) { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // ON_RESUME is the invalidation signal after returning from Settings.
    @Suppress("UNUSED_VARIABLE") val generation = resumeGeneration
    return if (AndroidOverlayPermission.canDrawOverlays(context)) {
        PetSystemOverlayAvailability.Available
    } else {
        PetSystemOverlayAvailability.PermissionRequired
    }
}

internal class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun destroy() {
        stop()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    override val lifecycle: Lifecycle get() = registry
}

/**
 * Real Android application overlay using `WindowManager`.
 *
 * - Permission `SYSTEM_ALERT_WINDOW` declared in the host library manifest
 *   (opt-in: only consumers of `:pets-host` inherit it).
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
    val availability = platformSystemOverlayAvailability()

    val cellAspect =
        definition.geometry.cellWidth.toFloat() / definition.geometry.cellHeight.toFloat()
    val petWidthDp = PetHostDefaultPetWidthDp
    val petHeightDp = if (cellAspect.isFinite() && cellAspect > 0f) petWidthDp / cellAspect else petWidthDp
    val widthPx = dpToPx(petWidthDp, density)
    val heightPx = dpToPx(petHeightDp, density)

    var resource by remember(state, definition, spritesheetBytes) {
        mutableStateOf<AndroidOverlayResource?>(null)
    }
    DisposableEffect(state, definition, spritesheetBytes) {
        val ownerToken = Any()
        state.claimOverlay(ownerToken)
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ops = AndroidOverlayTestProbe.windowOpsFactory?.invoke(windowManager) ?: RealOverlayWindowOps(windowManager)
        val controller = AndroidPetOverlayController(ops)
        val lifecycleOwner = OverlayLifecycleOwner()

        val composeView = ComposeView(applicationContext).apply {
            OverlayViewTreeOwners.attach(this, lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        composeView.setContent {
            val player = rememberPetPlayerState(definition, spritesheetBytes)
            AndroidOverlayTestProbe.onAtlasState?.invoke(player.atlasState)
            LaunchedEffect(player, state.requestedAnimation, state.isPinned) {
                player.play(state.effectiveAnimation(definition))
                if (state.isPinned) player.pinToDefault() else if (player.isPinned) player.resume()
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
                                controller.moveTo(dpToPx(nextX, viewDensity), dpToPx(nextY, viewDensity))
                            }
                        },
                ) {
                    Pet(player)
                }
            }
        }

        AndroidOverlayTestProbe.onViewCreated?.invoke(composeView, lifecycleOwner, controller)
        resource = AndroidOverlayResource(controller, composeView, lifecycleOwner, ownerToken)

        onDispose {
            resource = null
            controller.hide()
            composeView.disposeComposition()
            lifecycleOwner.destroy()
            state.releaseOverlay(ownerToken)
        }
    }

    val desiredVisible = state.isVisible
    val desiredX = state.xDp
    val desiredY = state.yDp
    SideEffect {
        val current = resource ?: return@SideEffect
        val controller = current.controller
        if (!desiredVisible) {
            controller.hide()
            current.lifecycleOwner.stop()
            state.reportOverlay(current.ownerToken, PetHostPlatformState.Hidden)
        } else if (availability != PetSystemOverlayAvailability.Available) {
            controller.hide()
            current.lifecycleOwner.stop()
            state.reportOverlay(current.ownerToken, PetHostPlatformState.PermissionRequired)
        } else {
            val xPx = dpToPx(desiredX, density)
            val yPx = dpToPx(desiredY, density)
            if (controller.isShowing()) {
                val params = controller.currentParams()
                if (params?.x != xPx || params.y != yPx) controller.moveTo(xPx, yPx)
            } else {
                val params = androidOverlayLayoutParams(widthPx, heightPx, xPx, yPx)
                try {
                    controller.show(current.composeView, params)
                    current.lifecycleOwner.resume()
                    state.reportOverlay(current.ownerToken, PetHostPlatformState.Showing)
                } catch (_: SecurityException) {
                    state.reportOverlay(current.ownerToken, PetHostPlatformState.PermissionRequired)
                } catch (_: WindowManager.BadTokenException) {
                    state.reportOverlay(current.ownerToken, PetHostPlatformState.PlatformRejected)
                }
            }
        }
    }
}

private class AndroidOverlayResource(
    val controller: AndroidPetOverlayController,
    val composeView: ComposeView,
    val lifecycleOwner: OverlayLifecycleOwner,
    val ownerToken: Any,
)
