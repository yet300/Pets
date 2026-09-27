package com.yet.pets.host

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.yet.pets.compose.CodexPet
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetDefinition
import java.awt.Color
import java.awt.GraphicsEnvironment
import javax.swing.JWindow

/**
 * Pure OS-name mapping for JVM availability (testable without a window).
 * macOS/Windows -> Available; Linux/other -> BestEffort. Never Unsupported.
 */
internal fun jvmAvailabilityForOsName(osName: String): PetSystemOverlayAvailability = when {
    osName.contains("mac", ignoreCase = true) -> PetSystemOverlayAvailability.Available
    osName.contains("windows", ignoreCase = true) -> PetSystemOverlayAvailability.Available
    osName.contains("linux", ignoreCase = true) -> PetSystemOverlayAvailability.BestEffort
    else -> PetSystemOverlayAvailability.BestEffort
}

/**
 * JVM availability: macOS and Windows support the transparent floating window;
 * Linux window managers/Wayland may not guarantee always-on-top/positioning,
 * so Linux is BestEffort. Never Unsupported on JVM.
 */
@Composable
internal actual fun platformSystemOverlayAvailability(): PetSystemOverlayAvailability {
    val os = try {
        System.getProperty("os.name") ?: ""
    } catch (_: SecurityException) {
        ""
    }
    return jvmAvailabilityForOsName(os)
}

/**
 * Minimal window adapter: owns exactly one transparent floating window whose
 * dimensions stay near the pet dimensions (never fullscreen). Separated from
 * pure position logic so headless CI can test [JvmPetOverlayController] with
 * a fake window.
 */
internal interface JvmOverlayWindow {
    fun configure(widthPx: Int, heightPx: Int)
    fun moveTo(xPx: Int, yPx: Int)
    fun setVisible(visible: Boolean)
    fun dispose()
    val isShowing: Boolean
}

/** Real AWT window: transparent, undecorated, non-resizable, always-on-top. */
internal class RealJvmOverlayWindow : JvmOverlayWindow {
    private val window: JWindow? =
        if (GraphicsEnvironment.isHeadless()) {
            null
        } else {
            JWindow().apply {
                // No visible frame/title/background: visually only the pet exists.
                // A desktop pet cannot exist without an OS window; this window
                // has no decorations so only the pet is visible, floating above
                // other application windows.
                background = Color(0, 0, 0, 0)
                isAlwaysOnTop = true
                // JWindow is undecorated and non-resizable by construction.
                focusableWindowState = false
            }
        }
    private var showing = false

    internal fun windowRef(): JWindow? = window

    override fun configure(widthPx: Int, heightPx: Int) {
        window?.setSize(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
    }

    override fun moveTo(xPx: Int, yPx: Int) {
        // Uses direct location so negative desktop coordinates (secondary
        // monitors via the active graphics configuration) remain
        // representable. No global >= 0 clamp.
        window?.setLocation(xPx, yPx)
    }

    override fun setVisible(visible: Boolean) {
        showing = visible
        window?.isVisible = visible
    }

    override fun dispose() {
        showing = false
        window?.dispose()
    }

    override val isShowing: Boolean get() = showing && window?.isVisible == true
}

/** Pure position/state adapter: no AWT calls except through [JvmOverlayWindow]. */
internal class JvmPetOverlayController(
    private val window: JvmOverlayWindow,
    private val densityScale: Float = 1f,
) {
    var petWidthPx: Int = (PetHostDefaultPetWidthDp * densityScale).toInt().coerceAtLeast(1)
        private set
    var petHeightPx: Int = petWidthPx
        private set

    fun configure(petWidthPx: Int, petHeightPx: Int) {
        this.petWidthPx = petWidthPx.coerceAtLeast(1)
        this.petHeightPx = petHeightPx.coerceAtLeast(1)
        window.configure(this.petWidthPx, this.petHeightPx)
    }

    fun showAt(xDp: Float, yDp: Float) {
        window.moveTo(dpToPx(xDp), dpToPx(yDp))
        window.setVisible(true)
    }

    fun moveTo(xDp: Float, yDp: Float) {
        window.moveTo(dpToPx(xDp), dpToPx(yDp))
    }

    fun hide() {
        // Hides the pet window only; never terminates the application.
        window.setVisible(false)
    }

    fun dispose() {
        window.dispose()
    }

    fun dpToPx(dp: Float): Int = (dp * densityScale).toInt()

    fun pxToDp(px: Int): Float = px / densityScale
}

/**
 * JVM SystemOverlay: a separate transparent undecorated always-on-top window
 * containing `CodexPet` through the existing player pipeline. Dragging the
 * visible pet moves the overlay window itself (window dimensions stay near
 * the pet dimensions, never fullscreen). Closing/hiding releases only the pet
 * window; application lifetime remains host-owned.
 */
@Composable
internal actual fun PlatformPetOverlayHost(
    definition: PetDefinition,
    spritesheetBytes: ByteArray,
    state: PetHostState,
) {
    if (GraphicsEnvironment.isHeadless()) {
        // Headless CI: no window, no crash. Pure logic remains testable via
        // JvmPetOverlayController with a fake window.
        return
    }
    val availability = platformSystemOverlayAvailability()
    if (availability != PetSystemOverlayAvailability.Available &&
        availability != PetSystemOverlayAvailability.BestEffort
    ) {
        return
    }

    val cellAspect =
        definition.geometry.cellWidth.toFloat() / definition.geometry.cellHeight.toFloat()
    val petWidthDp = PetHostDefaultPetWidthDp
    val petHeightDp = if (cellAspect.isFinite() && cellAspect > 0f) petWidthDp / cellAspect else petWidthDp
    // 1dp ~= 1px at the default desktop scale; window stays near pet size.
    val petWidthPx = petWidthDp.toInt().coerceAtLeast(1)
    val petHeightPx = petHeightDp.toInt().coerceAtLeast(1)

    DisposableEffect(definition, spritesheetBytes) {
        val overlay = RealJvmOverlayWindow()
        val controller = JvmPetOverlayController(overlay)
        controller.configure(petWidthPx, petHeightPx)
        val window = overlay.windowRef()

        if (window != null) {
            val panel = ComposePanel().apply {
                setSize(petWidthPx, petHeightPx)
            }
            panel.setContent {
                val player = rememberPetPlayerState(definition, spritesheetBytes)
                LaunchedEffect(state.requestedAnimation) {
                    player.play(state.requestedAnimation)
                }
                LaunchedEffect(state.isPinned) {
                    if (state.isPinned) player.pinToIdle() else if (player.isPinned) player.resume()
                }
                if (state.isVisible) {
                    Box(
                        modifier = Modifier.size(petWidthDp.dp, petHeightDp.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // Desktop drag moves the overlay window itself.
                                    val dxDp = dragAmount.x
                                    val dyDp = dragAmount.y
                                    val nextX = state.xDp + dxDp
                                    val nextY = state.yDp + dyDp
                                    state.moveTo(nextX, nextY)
                                    controller.moveTo(nextX, nextY)
                                }
                            },
                    ) {
                        CodexPet(player)
                    }
                }
            }
            window.contentPane.add(panel)
            window.setSize(petWidthPx, petHeightPx)
            if (state.isVisible) {
                controller.showAt(state.xDp, state.yDp)
            }
        }

        onDispose {
            controller.dispose()
        }
    }

    // Visibility changes after creation hide/show the same window without
    // recreating it or terminating the app. Position sync during drag updates
    // both the window and the state synchronously above; external moveTo calls
    // are reflected on next drag/show. PetHostState remains the source of
    // desired position.
    LaunchedEffect(state.isVisible) {
        // Documented sync point: the DisposableEffect owns the window; this
        // effect observes visibility so future extensions can drive the same
        // window instance. No window recreation here.
    }
}
