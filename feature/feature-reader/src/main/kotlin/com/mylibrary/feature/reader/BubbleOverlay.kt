package com.mylibrary.feature.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What overwrites the page when a double-tap lands on a speech bubble: that bubble's pixels on
 * their own, drawn over where the bubble already is, just bigger.
 *
 * Deliberately not a camera zoom. The previous behaviour moved the *page* into the bubble, which
 * moved everything else in the frame with it — the reader asked for a bigger speech bubble and got
 * a bigger page. Here the page stays exactly where and how big it is, and the bubble is repainted
 * above it, cropped to the region [findBubbleRegion] measured. The artwork around the bubble does
 * not move one pixel, which is the entire point.
 */
internal data class BubbleOverlay(
    /** The page pixels of the bubble, at whatever resolution the page was rendered at. */
    val image: ImageBitmap,
    /** Where on screen to paint it, in the reader viewport's own coordinates. */
    val targetOnScreen: Rect,
)

/**
 * Builds the overlay for a detected [region], or `null` when there is nothing sensible to draw.
 *
 * The crop is the region's bounds grown by a small margin — the fill stops at the ink outline, and
 * without margin the outline itself would be shaved off the crop and the blown-up bubble would look
 * trimmed of its own border. The size on screen is the region's own drawn size grown until the
 * longer of its two axes reaches a share of the viewport, clamped to what fits and anchored at the
 * bubble's own centre, so it appears where it always was and not somewhere it no longer belongs.
 */
internal fun bubbleOverlayFor(
    bitmap: ImageBitmap,
    region: BubbleRegion,
    container: IntSize,
    drawn: Size,
): BubbleOverlay? {
    val bounds = region.bounds
    val bitmapWidth = bitmap.width
    val bitmapHeight = bitmap.height
    if (bounds.width <= 0 || bounds.height <= 0) return null
    if (drawn.width <= 0f || drawn.height <= 0f) return null
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || container.width <= 0 || container.height <= 0) {
        return null
    }

    val identity = PageTransform.Identity
    val topLeftOnScreen = pixelToViewPoint(
        pixel = Offset(bounds.left.toFloat(), bounds.top.toFloat()),
        container = container,
        drawn = drawn,
        transform = identity,
        bitmapWidth = bitmapWidth,
        bitmapHeight = bitmapHeight,
    )
    val bottomRightOnScreen = pixelToViewPoint(
        pixel = Offset(bounds.right.toFloat(), bounds.bottom.toFloat()),
        container = container,
        drawn = drawn,
        transform = identity,
        bitmapWidth = bitmapWidth,
        bitmapHeight = bitmapHeight,
    )
    val original = Rect(
        topLeft = topLeftOnScreen,
        bottomRight = Offset(bottomRightOnScreen.x, bottomRightOnScreen.y),
    )
    val longest = max(original.width, original.height)
    if (longest <= 0f) return null

    // Grown to [BUBBLE_ENLARGE_FILL] of the shorter viewport axis at most, bounded so that a
    // bubble already covering much of the page grows by less than one covering a corner would.
    val enlarge = (BUBBLE_ENLARGE_FILL * min(container.width, container.height) / longest)
        .coerceIn(BUBBLE_ENLARGE_MIN, BUBBLE_ENLARGE_MAX)

    val centre = original.center
    val enlarged = Size(
        width = max(original.width * enlarge, BUBBLE_MIN_ON_SCREEN),
        height = max(original.height * enlarge, BUBBLE_MIN_ON_SCREEN),
    )
    val left = (centre.x - enlarged.width / 2f)
        .coerceIn(BUBBLE_SCREEN_MARGIN, container.width - enlarged.width - BUBBLE_SCREEN_MARGIN)
        .coerceAtLeast(0f)
    val top = (centre.y - enlarged.height / 2f)
        .coerceIn(BUBBLE_SCREEN_MARGIN, container.height - enlarged.height - BUBBLE_SCREEN_MARGIN)
        .coerceAtLeast(0f)

    // A crop can fall off the bitmap's edge by a couple of pixels of lossy slack; clamping the
    // crop's own coordinates is the answer to every one of them, and a cheap one.
    val cropLeft = (bounds.left - cropMarginPx(bounds.width)).coerceIn(0, bitmapWidth - 1)
    val cropTop = (bounds.top - cropMarginPx(bounds.height)).coerceIn(0, bitmapHeight - 1)
    val cropRight = (bounds.right + cropMarginPx(bounds.width)).coerceIn(cropLeft + 1, bitmapWidth)
    val cropBottom = (bounds.bottom + cropMarginPx(bounds.height)).coerceIn(cropTop + 1, bitmapHeight)

    val crop = runCatching {
        Bitmap.createBitmap(
            bitmap.asAndroidBitmap(),
            cropLeft,
            cropTop,
            cropRight - cropLeft,
            cropBottom - cropTop,
        )
    }.getOrNull() ?: return null

    return BubbleOverlay(
        image = crop.asImageBitmap(),
        targetOnScreen = Rect(left, top, left + enlarged.width, top + enlarged.height),
    )
}

/** As many pixels as [length] allows under [BUBBLE_CROP_MARGIN], so the outline stays in the crop. */
private fun cropMarginPx(length: Int): Int = (length * BUBBLE_CROP_MARGIN).toInt().coerceAtLeast(4)

/**
 * The overlay itself: a scrim over the page, the bubble floating above it.
 *
 * A tap anywhere dismisses it, handled here rather than left to the page below — a tap that closes
 * a looked-at overlay must not also turn the page underneath it. The system back does the same,
 * which is what a reader who reached for the screen's edge will try first.
 */
@Composable
internal fun BubbleOverlayLayer(
    overlay: BubbleOverlay,
    onDismiss: () -> Unit,
) {
    val currentDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(BUBBLE_CORNER_PERCENT)
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = BUBBLE_SCRIM_ALPHA)

    BackHandler(onBack = currentDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { currentDismiss() },
                    onTap = { currentDismiss() },
                )
            },
    ) {
        val rect = overlay.targetOnScreen
        Image(
            bitmap = overlay.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .width(with(density) { rect.width.coerceAtLeast(1f).toDp() })
                .height(with(density) { rect.height.coerceAtLeast(1f).toDp() })
                .clip(shape),
        )
    }
}

/** Bubbles are rounded things; a mild corner keeps the crop from reading as a screenshot. */
internal const val BUBBLE_CORNER_PERCENT = 14

/** How dark the page sits under the floating bubble. */
internal const val BUBBLE_SCRIM_ALPHA = 0.25f

/** The share of the tighter viewport axis the blown-up bubble's longer side may reach for. */
internal const val BUBBLE_ENLARGE_FILL = 0.62f

/** How little a bubble may grow: below this the feature reads as a page zoom doing nothing. */
internal const val BUBBLE_ENLARGE_MIN = 1.6f

/** How much a bubble may grow before its crop goes softer than the page's own render could. */
internal const val BUBBLE_ENLARGE_MAX = 2.5f

/** A fraction of the bubble's own size added around it when cropping. */
internal const val BUBBLE_CROP_MARGIN = 0.06f

/** The smallest an enlarged bubble may be drawn, in view px, so tiny bubbles still read as "bigger". */
internal const val BUBBLE_MIN_ON_SCREEN = 180f

/** How close to the viewport's edge the drawn bubble may sit. */
internal const val BUBBLE_SCREEN_MARGIN = 12f

/** A detected region above this share of the page is a panel, not a bubble, and gets no overlay. */
internal const val OVERLAY_MAX_PAGE_SHARE = 0.45f
