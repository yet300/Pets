package com.yet.pets.host

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yet.pets.compose.Pet
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetDefinition
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Pure OS-name mapping for JVM availability (testable without a window).
 * macOS/Windows -> Available; Linux/other -> BestEffort; headless -> Unsupported.
 */
internal fun jvmAvailabilityForOsName(osName: String, headless: Boolean = false): PetSystemOverlayAvailability = when {
    headless -> PetSystemOverlayAvailability.Unsupported
    osName.contains("mac", ignoreCase = true) -> PetSystemOverlayAvailability.Available
    osName.contains("windows", ignoreCase = true) -> PetSystemOverlayAvailability.Available
    osName.contains("linux", ignoreCase = true) -> PetSystemOverlayAvailability.BestEffort
    else -> PetSystemOverlayAvailability.BestEffort
}

/**
 * JVM availability: macOS and Windows support the transparent floating window;
 * Linux window managers/Wayland may not guarantee always-on-top/positioning,
 * so Linux is BestEffort. Headless environments are Unsupported.
 */
@Composable
internal actual fun platformSystemOverlayAvailability(): PetSystemOverlayAvailability {
    val os = try {
        System.getProperty("os.name") ?: ""
    } catch (_: SecurityException) {
        ""
    }
    return jvmAvailabilityForOsName(os, GraphicsEnvironment.isHeadless())
}

internal fun <T> onSwingEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
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
        } else onSwingEdt {
            JWindow().apply {
                // No visible frame/title/background: visually only the pet exists.
                // A desktop pet cannot exist without an OS window; this window
                // has no decorations so only the pet is visible, floating above
                // other application windows.
                background = Color(0, 0, 0, 0)
                rootPane.isOpaque = false
                rootPane.background = Color(0, 0, 0, 0)
                contentPane.background = Color(0, 0, 0, 0)
                (contentPane as? javax.swing.JComponent)?.isOpaque = false
                layeredPane.isOpaque = false
                glassPane.background = Color(0, 0, 0, 0)
                (glassPane as? javax.swing.JComponent)?.isOpaque = false
                isAlwaysOnTop = true
                // JWindow is undecorated and non-resizable by construction.
                focusableWindowState = false
            }
        }
    private var showing = false

    internal fun windowRef(): JWindow? = window

    override fun configure(widthPx: Int, heightPx: Int) {
        onSwingEdt { window?.setSize(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1)) }
    }

    override fun moveTo(xPx: Int, yPx: Int) {
        // Uses direct location so negative desktop coordinates (secondary
        // monitors via the active graphics configuration) remain
        // representable. No global >= 0 clamp.
        onSwingEdt { window?.setLocation(xPx, yPx) }
    }

    override fun setVisible(visible: Boolean) {
        onSwingEdt {
            showing = visible
            window?.isVisible = visible
        }
    }

    override fun dispose() {
        onSwingEdt {
            showing = false
            window?.dispose()
        }
    }

    override val isShowing: Boolean get() = onSwingEdt { showing && window?.isVisible == true }
}

/** Clamp against individual displays, preserving negative virtual coordinates. */
internal fun recoverableOverlayLocation(
    requested: Point,
    width: Int,
    height: Int,
    displays: List<Rectangle>,
): Point {
    if (displays.isEmpty()) return requested
    val reachableWidth = width.coerceAtMost(48).coerceAtLeast(1)
    val reachableHeight = height.coerceAtMost(48).coerceAtLeast(1)
    return displays.filter { it.width > 0 && it.height > 0 }.map { bounds ->
        Point(
            requested.x.coerceIn(bounds.x - width + reachableWidth, bounds.x + bounds.width - reachableWidth),
            requested.y.coerceIn(bounds.y - height + reachableHeight, bounds.y + bounds.height - reachableHeight),
        )
    }.minByOrNull { candidate ->
        val dx = candidate.x.toDouble() - requested.x
        val dy = candidate.y.toDouble() - requested.y
        dx * dx + dy * dy
    } ?: requested
}

internal fun usableDesktopBounds(): List<Rectangle> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    val toolkit = Toolkit.getDefaultToolkit()
    return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device: GraphicsDevice ->
        val configuration = device.defaultConfiguration
        val bounds = Rectangle(configuration.bounds)
        val insets = toolkit.getScreenInsets(configuration)
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
        bounds
    }
}

/** Pure position/state adapter: no AWT calls except through [JvmOverlayWindow]. */
internal class JvmPetOverlayController(
    private val window: JvmOverlayWindow,
    private var densityScale: Float = 1f,
    private val displays: () -> List<Rectangle> = ::usableDesktopBounds,
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

    fun updateScale(scale: Float) {
        if (scale.isFinite() && scale > 0f) densityScale = scale
    }

    fun showAt(xDp: Float, yDp: Float): Pair<Float, Float> {
        val actual = moveTo(xDp, yDp)
        window.setVisible(true)
        return actual
    }

    fun moveTo(xDp: Float, yDp: Float): Pair<Float, Float> {
        val safe = recoverableOverlayLocation(
            Point(dpToPx(xDp), dpToPx(yDp)), petWidthPx, petHeightPx, displays(),
        )
        window.moveTo(safe.x, safe.y)
        return pxToDp(safe.x) to pxToDp(safe.y)
    }

    fun hide() {
        // Hides the pet window only; never terminates the application.
        window.setVisible(false)
    }

    fun dispose() {
        window.dispose()
    }

    fun dpToPx(dp: Float): Int {
        val scaled = dp.toDouble() * densityScale.toDouble()
        return when {
            scaled.isNaN() -> 0
            scaled >= Int.MAX_VALUE -> Int.MAX_VALUE
            scaled <= Int.MIN_VALUE -> Int.MIN_VALUE
            else -> scaled.toInt()
        }
    }

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
    val composeDensity = LocalDensity.current.density
    var resource by remember(state, definition, spritesheetBytes) { mutableStateOf<JvmOverlayResource?>(null) }

    DisposableEffect(state, definition, spritesheetBytes) {
        val ownerToken = Any()
        state.claimOverlay(ownerToken)
        val overlay = onSwingEdt { RealJvmOverlayWindow() }
        val window = overlay.windowRef() ?: error("Graphical overlay window unavailable")
        val controller = JvmPetOverlayController(overlay)
        // Compose desktop density is expressed in AWT logical units already.
        // AWT setLocation/setSize also take logical units; dividing by the
        // GraphicsConfiguration device transform double-scales on Retina.
        controller.updateScale(composeDensity)
        val petWidthPx = controller.dpToPx(petWidthDp).coerceAtLeast(1)
        val petHeightPx = controller.dpToPx(petHeightDp).coerceAtLeast(1)
        val panel = onSwingEdt {
            ComposePanel().apply {
                isOpaque = false
                background = Color(0, 0, 0, 0)
                setSize(petWidthPx, petHeightPx)
            }
        }
        onSwingEdt {
            panel.setContent {
                val player = rememberPetPlayerState(definition, spritesheetBytes)
                LaunchedEffect(player, state.requestedAnimation, state.isPinned) {
                    player.play(state.effectiveAnimation(definition))
                    if (state.isPinned) player.pinToDefault() else if (player.isPinned) player.resume()
                }
                if (state.isVisible) {
                    Box(
                        modifier = Modifier.size(petWidthDp.dp, petHeightDp.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // Desktop drag moves the overlay window itself.
                                    val dxDp = dragAmount.x / composeDensity
                                    val dyDp = dragAmount.y / composeDensity
                                    val nextX = state.xDp + dxDp
                                    val nextY = state.yDp + dyDp
                                    val (actualX, actualY) = controller.moveTo(nextX, nextY)
                                    state.moveTo(actualX, actualY)
                                }
                            },
                    ) {
                        Pet(player)
                    }
                }
            }
            window.contentPane.add(panel)
            window.setSize(petWidthPx, petHeightPx)
        }
        resource = JvmOverlayResource(controller, overlay, panel, ownerToken)

        onDispose {
            resource = null
            controller.dispose()
            state.releaseOverlay(ownerToken)
        }
    }

    val desiredVisible = state.isVisible
    val desiredX = state.xDp
    val desiredY = state.yDp
    SideEffect {
        val current = resource ?: return@SideEffect
        val window = current.overlay.windowRef() ?: return@SideEffect
        current.controller.updateScale(composeDensity)
        if (desiredVisible) {
            val (actualX, actualY) = if (current.overlay.isShowing)
                current.controller.moveTo(desiredX, desiredY)
            else current.controller.showAt(desiredX, desiredY)
            if (actualX != desiredX || actualY != desiredY) state.moveTo(actualX, actualY)
            state.reportOverlay(current.ownerToken, PetHostPlatformState.Showing)
        } else {
            current.controller.hide()
            state.reportOverlay(current.ownerToken, PetHostPlatformState.Hidden)
        }
    }
}

private class JvmOverlayResource(
    val controller: JvmPetOverlayController,
    val overlay: RealJvmOverlayWindow,
    val panel: ComposePanel,
    val ownerToken: Any,
)
