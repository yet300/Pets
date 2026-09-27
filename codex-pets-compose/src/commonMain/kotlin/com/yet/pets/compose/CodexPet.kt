package com.yet.pets.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Resolved draw parameters for one frame: the atlas plus the source region
 * for [com.yet.pets.core.PetPlaybackSample.spriteIndex] and the destination
 * aspect ratio.
 *
 * Internal: the atlas never crosses a public boundary.
 */
internal data class PetDrawParams(
    val atlas: ImageBitmap,
    val srcLeft: Int,
    val srcTop: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    val aspect: Float,
)

/**
 * Renders one pet frame.
 *
 * The pet uses exactly one decoded atlas image: frame rendering draws a
 * subregion of it (no per-frame slicing, no per-frame decode, no pixel
 * copies). The source region comes from the single geometry source of truth,
 * [com.yet.pets.core.AtlasGeometry.sourceRectForOrNull]; an invalid index
 * (impossible for core-validated samples — defensive only) or a failed atlas
 * decode renders an empty placeholder instead of crashing, still preserving
 * the sprite aspect ratio.
 *
 * Scaling: the renderer preserves the sprite aspect ratio via
 * [aspectRatio] (non-square cells such as 192x208 stay proportional); the
 * destination rectangle is the full laid-out size. An explicit
 * non-proportional size forced through [modifier] fills that size — pass no
 * fixed size (or a proportional one) to keep the pet undistorted.
 *
 * Filtering: the draw uses the Compose default sampling
 * ([DefaultFilterQuality][androidx.compose.ui.graphics.DefaultFilterQuality],
 * bilinear). Codex pet sprites are smooth illustrations, not pixel art, so no
 * filtering configuration is exposed in v1.
 *
 * @param state player state owning the definition and decoded atlas.
 * Animation intent belongs to [PetPlayerState.play]. Renderers sharing a state
 * observe the same timeline; use separate states for independent timelines.
 * @param modifier applied after the aspect-ratio sizing.
 */
@Composable
public fun CodexPet(
    state: PetPlayerState,
    modifier: Modifier = Modifier,
) {
    val epoch = state.animationEpoch
    val pinned = state.isPinned
    LaunchedEffect(state, epoch, pinned) {
        if (!pinned) {
            while (true) {
                state.refresh(state.nowNanos())
                val waitMillis = delayMillisForNextFrame(state.currentSample) ?: break
                delay(waitMillis)
            }
        }
        // Static sample (pinned, or an animation that settled): park until the
        // intent changes or the composition leaves. No polling loop.
        awaitCancellation()
    }

    val params = state.drawParamsFor(state.currentSample.spriteIndex)
    if (params != null) {
        Canvas(
            modifier = Modifier.aspectRatio(params.aspect).then(modifier).testTag(CodexPetTag),
        ) {
            drawImage(
                image = params.atlas,
                srcOffset = IntOffset(params.srcLeft, params.srcTop),
                srcSize = IntSize(params.srcWidth, params.srcHeight),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    } else {
        Spacer(
            modifier = Modifier.aspectRatio(state.cellAspect).then(modifier).testTag(CodexPetTag),
        )
    }
}

/** Test tag applied to the renderer node (both the canvas and the empty placeholder). */
internal const val CodexPetTag: String = "CodexPet"
