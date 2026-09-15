package com.mylibrary.format.pdf

import com.mylibrary.core.domain.model.PageSize
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The size a page should actually be rendered at: its own aspect ratio, fitted inside a box.
 *
 * pdfium does not letterbox. `FPDF_RenderPageBitmap` maps the page's box *onto* the rectangle it is
 * given, so handing it a box of a different shape stretches the page — text comes out skewed rather
 * than centred with margins. The reader passes the viewport it wants filled and relies on the engine
 * to work out the rest, so the fit has to happen here, before the bitmap exists. Doing it first also
 * guarantees the allocation is never bigger than the page needs.
 *
 * Rounding is per axis, so the result can differ from the exact ratio by up to a pixel; the box is
 * then clamped to, which matters because rounding up would otherwise allocate a bitmap one pixel
 * wider than the caller's viewport.
 *
 * @return the largest size with the page's aspect ratio that fits in the box, or `PageSize(0, 0)`
 *   when the page or the box has no area. That is not a size anyone can render: a zero-sized
 *   `Bitmap` cannot be allocated, and a page with no intrinsic size is a broken page rather than a
 *   tiny one — so the caller decides how to report it instead of getting a silently wrong page.
 */
internal fun fitPageInBox(pageWidth: Int, pageHeight: Int, boxWidth: Int, boxHeight: Int): PageSize {
    if (pageWidth <= 0 || pageHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return PageSize(0, 0)

    val scale = min(boxWidth.toDouble() / pageWidth, boxHeight.toDouble() / pageHeight)
    return PageSize(
        width = (pageWidth * scale).roundToInt().coerceIn(1, boxWidth),
        height = (pageHeight * scale).roundToInt().coerceIn(1, boxHeight),
    )
}
