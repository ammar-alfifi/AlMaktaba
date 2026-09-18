package com.mylibrary.feature.reader

/**
 * A rectangle in page-bitmap pixels. [right] and [bottom] are exclusive, so width and height are
 * plain subtractions and a rectangle of no size is expressible without a sentinel.
 */
internal data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * An enclosed region of light pixels under a tap: the inside of a speech bubble, or of a panel.
 *
 * [bounds] is the interior — the ink outline around a bubble belongs to the page, not to the region,
 * and including it would frame the zoom on a black line.
 */
internal data class BubbleRegion(
    val bounds: PixelRect,
    /** Light pixels inside [bounds], which is smaller than its area wherever the region has ink in it. */
    val areaPx: Int,
)

/**
 * Finds the enclosed light region containing the pixel at ([x], [y]) — a comic's speech bubble, or
 * the panel it sits in — so the reader can zoom into it.
 *
 * **What it is.** A flood fill from the tapped pixel, bounded by ink, with a set of shape gates that
 * decide whether what it found is a region worth zooming into at all. It is a heuristic: it reads
 * the *picture*, and pictures do not come with a schema. What it is not is a guess — every failure
 * below returns `null`, and `null` simply means the reader zooms the ordinary way instead. A miss
 * costs the user a different zoom; only a *wrong* answer would cost them the interaction, which is
 * why the gates are conservative.
 *
 * **How it works.** The seed pixel's own brightness is the reference, and the fill accepts pixels
 * within [tolerance] of it (and not darker than [minRegionLuma]). Comparing against the seed rather
 * than against a fixed threshold is what makes it work on a scan: paper that has yellowed, a bubble
 * filled with pale grey, a screentone panel whose dots average out to something mid-grey — each
 * region defines its own "light" and the ink around it still stops the fill.
 *
 * **Why the fill cannot squeeze through a gap.** A bubble whose outline thins to nothing at one
 * point — a hand-inked break, or a couple of outline pixels a lossy encoder left lighter than the
 * paper around them — used to leak, and a leaked fill does not touch the page's edge when the bubble
 * sits inside a panel: it finds the *panel*, which is most of the page and is not worth zooming
 * into, so the reader got the ordinary zoom and reasonably concluded the feature did not work. The
 * region is therefore grown through *open* space rather than merely through light pixels — see
 * [openSpace] — which seals a break of a couple of pixels without needing to see it.
 *
 * **Why it cannot go badly wrong.** A leak that survives the sealing is wider than the seal, and a
 * fill through a wide break reaches the page's own edge, so the border test still catches exactly
 * that case and turns it into the ordinary zoom. There is no arrangement of pixels that produces a
 * plausible-looking but wrong rectangle: either the fill is closed, or it touches the border, or it
 * covers too much of the page.
 *
 * @param pixels ARGB_8888, row-major, top-down, `width * height` entries.
 * @return the region, or `null` when there is nothing here worth framing.
 */
internal fun findBubbleRegion(
    pixels: IntArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    tolerance: Int = BUBBLE_TOLERANCE,
): BubbleRegion? {
    if (width <= 0 || height <= 0) return null
    if (pixels.size < width * height) return null
    if (x !in 0 until width || y !in 0 until height) return null

    // Aiming at a bubble often lands on its outline, which is a few pixels wide and exactly the part
    // the reader's eye is following. So the seed is not simply the tapped pixel: candidates are the
    // light pixels nearest to it, tried in order of distance until one of them turns out to be
    // enclosed. A tap just inside an outline finds the interior immediately; a tap *on* the outline
    // tries the page margin first, which leaks to the border and is refused, and then finds the
    // interior on the very next attempt.
    val candidates = seedCandidates(pixels, width, height, x, y)
    if (candidates.isEmpty()) return null

    // One pair of working arrays for every attempt, rather than one per attempt: a page's worth of
    // booleans is megabytes, and a double-tap in a busy panel could otherwise allocate a dozen of
    // them in a row for the collector to chase. `stamp` replaces clearing the visited flags between
    // attempts — a pixel counts as seen when its stamp matches the current attempt's number, so the
    // old marks cost nothing.
    val stamp = IntArray(width * height)
    var queue = IntArray(INITIAL_QUEUE)

    // Built once for every attempt, because it does not depend on the seed. This is the expensive
    // part of the whole search — two passes over the page — and it is still far cheaper than the
    // flood it protects, which without it can walk a whole panel.
    val open = openSpace(pixels, width, height, sealRadius(width, height))

    var generation = 0
    for (seedIndex in candidates) {
        // A seed that is itself inside the sealed band cannot start a fill; the next one will do.
        if (!open[seedIndex]) continue

        generation++
        val region = floodRegion(
            pixels = pixels,
            width = width,
            height = height,
            seedIndex = seedIndex,
            tolerance = tolerance,
            open = open,
            stamp = stamp,
            generation = generation,
            queue = queue,
        )
        if (region != null) return region
    }
    return null
}

/**
 * Which pixels a fill may travel through: paper that is not within [radius] of ink.
 *
 * **This is what closes a broken outline**, and it is worth being precise about why it is a
 * *dilation of the ink* rather than a smarter test on the outline. A break is not something the
 * sander can see: it is an absence, and the pixels around it look exactly like the paper on either
 * side of it. What can be seen is the ink, and growing it by a pixel or two makes it meet across a
 * gap the way the pen meant it to. The cost is that the region is eroded by the same couple of
 * pixels on every side, which the margin [inset] adds back is there to absorb.
 *
 * The radius scales with the render rather than being a constant in pixels, because a break is a
 * property of the *page* and a page is drawn at whatever size the screen asks for: a scan whose
 * outline is two pixels thin at 1400 wide is one pixel thin at 700, and a fixed radius would seal it
 * on a tablet and miss it on a phone.
 *
 * Separable — a horizontal pass then a vertical one — so the whole page costs two linear scans
 * rather than one pass per pixel per neighbour.
 */
private fun openSpace(pixels: IntArray, width: Int, height: Int, radius: Int): BooleanArray {
    val total = width * height
    val nearInk = BooleanArray(total)
    for (index in 0 until total) {
        nearInk[index] = lumaOf(pixels[index]) < INK_LUMA
    }
    if (radius <= 0) {
        for (index in 0 until total) nearInk[index] = !nearInk[index]
        return nearInk
    }

    // Horizontal: does this pixel have ink within `radius` along its own row?
    val rows = BooleanArray(total)
    for (y in 0 until height) {
        val rowStart = y * width
        var inkInWindow = 0
        for (dx in 0..minOf(radius, width - 1)) {
            if (nearInk[rowStart + dx]) inkInWindow++
        }
        for (x in 0 until width) {
            if (x > 0) {
                if (nearInk[rowStart + x - 1]) inkInWindow--
                val entering = x + radius
                if (entering < width && nearInk[rowStart + entering]) inkInWindow++
            }
            rows[rowStart + x] = inkInWindow > 0
        }
    }

    // Vertical, over the horizontal result: the two together are a square window, which is all a
    // seal needs. Returned inverted, because the caller wants to know where it *may* go.
    val open = BooleanArray(total)
    for (x in 0 until width) {
        var inkInWindow = 0
        for (dy in 0..minOf(radius, height - 1)) {
            if (rows[dy * width + x]) inkInWindow++
        }
        for (y in 0 until height) {
            if (y > 0) {
                if (rows[(y - 1) * width + x]) inkInWindow--
                val entering = y + radius
                if (entering < height && rows[entering * width + x]) inkInWindow++
            }
            open[y * width + x] = inkInWindow == 0
        }
    }
    return open
}

/**
 * How far the ink is grown to seal a break, in page pixels.
 *
 * Four pixels is the ceiling because the seal erodes the bubble it is protecting by the same amount
 * at every edge, and a small balloon can only afford so much. The floor of one keeps the seal doing
 * something on a page rendered small enough — a phone showing a whole comic page — that a one-pixel
 * break is the only kind that fits.
 */
private fun sealRadius(width: Int, height: Int): Int =
    (minOf(width, height) / SEAL_DIVISOR).coerceIn(1, MAX_SEAL_RADIUS)

/**
 * The light pixels near ([x], [y]), nearest first — the seed candidates for [findBubbleRegion].
 *
 * A tap on solid ink has no candidates at all, which is what makes "tapping a black area does
 * nothing" fall out of the search rather than needing a rule of its own.
 */
private fun seedCandidates(
    pixels: IntArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
): List<Int> {
    val candidates = ArrayList<Pair<Int, Int>>(MAX_SEED_CANDIDATES * 2)

    for (dy in -SEED_SEARCH_RADIUS..SEED_SEARCH_RADIUS) {
        val ny = y + dy
        if (ny < 0 || ny >= height) continue
        for (dx in -SEED_SEARCH_RADIUS..SEED_SEARCH_RADIUS) {
            val nx = x + dx
            if (nx < 0 || nx >= width) continue
            val index = ny * width + nx
            if (lumaOf(pixels[index]) < MIN_SEED_LUMA) continue
            candidates += (dy * dy + dx * dx) to index
        }
    }

    return candidates
        .sortedBy { (distance, _) -> distance }
        .take(MAX_SEED_CANDIDATES)
        .map { (_, index) -> index }
}

/**
 * Floods the region of light pixels reachable from [seedIndex].
 *
 * Returns `null` — never a partial or approximate answer — for every way this can fail to be a
 * region: a leave that reaches the page's border, a fill larger than [MAX_REGION_AREA_FRACTION] of
 * the page, dust, a sliver, or a shape that does not fill its own bounding box.
 */
private fun floodRegion(
    pixels: IntArray,
    width: Int,
    height: Int,
    seedIndex: Int,
    tolerance: Int,
    open: BooleanArray,
    stamp: IntArray,
    generation: Int,
    queue: IntArray,
): BubbleRegion? {
    val total = width * height
    val seedLuma = lumaOf(pixels[seedIndex])

    // A seed this dark is ink, not a place to read: black outlines, panel borders, lettering. The
    // fill would otherwise crawl along a glyph and return a rectangle that is a line of drawing.
    if (seedLuma < MIN_SEED_LUMA) return null

    val maxArea = (total * MAX_REGION_AREA_FRACTION).toInt()
    // Indices rather than coordinates: one Int per entry instead of two, on a queue that can hold a
    // whole page.
    var work = queue
    var head = 0
    var tail = 0

    fun push(index: Int) {
        if (tail == work.size) {
            work = work.copyOf(work.size * 2)
        }
        work[tail++] = index
    }

    val seedX = seedIndex % width
    val seedY = seedIndex / width
    var left = seedX
    var top = seedY
    var right = seedX
    var bottom = seedY
    var area = 0

    stamp[seedIndex] = generation
    push(seedIndex)

    while (head < tail) {
        val index = work[head++]
        val px = index % width
        val py = index / width

        area++
        if (area > maxArea) return null
        if (px < left) left = px
        if (px > right) right = px
        if (py < top) top = py
        if (py > bottom) bottom = py

        // A region that reaches the page's edge is not enclosed: it is the page margin, or the fill
        // escaped through a break in the outline. Either way it is not something to zoom into.
        if (px == 0 || py == 0 || px == width - 1 || py == height - 1) return null

        for (neighbour in 0 until 4) {
            val nx = if (neighbour == 0) px - 1 else if (neighbour == 1) px + 1 else px
            val ny = if (neighbour == 2) py - 1 else if (neighbour == 3) py + 1 else py
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue

            val neighbourIndex = ny * width + nx
            if (stamp[neighbourIndex] == generation) continue
            // Marked whether or not it is accepted, so a rejected pixel is tested once rather than
            // once per neighbour. That is what keeps the whole scan linear.
            stamp[neighbourIndex] = generation

            // Sealed against the ink around it before the pixel's own colour is considered: a
            // hairline break is a way through made of paper, so no test on the pixel itself can
            // refuse it.
            if (!open[neighbourIndex]) continue

            val luma = lumaOf(pixels[neighbourIndex])
            if (luma < MIN_REGION_LUMA) continue
            if (kotlin.math.abs(luma - seedLuma) > tolerance) continue
            push(neighbourIndex)
        }
    }

    val bounds = PixelRect(left, top, right + 1, bottom + 1)

    // A speck of dust or a single white pixel in a halftone.
    if (area < total * MIN_REGION_AREA_FRACTION) return null

    // A sliver: a seam between panels is wide enough to pass the area gate on a large page but is
    // not a region — zooming into it would show a strip of white.
    if (kotlin.math.min(bounds.width, bounds.height) <
        kotlin.math.min(width, height) * MIN_REGION_SIDE_FRACTION
    ) {
        return null
    }

    // A region whose bounding box is mostly ink: a lattice of screentone, or a texture. Its
    // bounding box says "the whole panel" while its pixels say "nothing like it".
    if (area < bounds.area * MIN_REGION_FILL) return null

    return BubbleRegion(bounds = inset(bounds, width, height), areaPx = area)
}

/**
 * Grows a region's box by a small margin, clamped to the page.
 *
 * The interior of a bubble is what the fill finds, and a zoom framed exactly on the interior puts the
 * outline — and any text that reaches it — hard against the edge of the screen. A little air around
 * it is what makes the result look deliberate.
 */
private fun inset(bounds: PixelRect, width: Int, height: Int): PixelRect {
    val marginX = (bounds.width * REGION_MARGIN_FRACTION).toInt().coerceAtLeast(1)
    val marginY = (bounds.height * REGION_MARGIN_FRACTION).toInt().coerceAtLeast(1)
    return PixelRect(
        left = (bounds.left - marginX).coerceAtLeast(0),
        top = (bounds.top - marginY).coerceAtLeast(0),
        right = (bounds.right + marginX).coerceAtMost(width),
        bottom = (bounds.bottom + marginY).coerceAtMost(height),
    )
}

/**
 * Perceived brightness, 0..255, weighted for the eye's own sensitivity (Rec. 601).
 *
 * Not the mean of the channels: pure blue and pure green have the same average and are nowhere near
 * as bright as each other, which matters here because the threshold decides whether a pixel counts
 * as paper.
 */
internal fun lumaOf(argb: Int): Int {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return (red * 299 + green * 587 + blue * 114) / 1000
}

/** How far from the tap a seed may be looked for. Wide enough to clear a drawn outline. */
private const val SEED_SEARCH_RADIUS = 4

/**
 * How many seeds are tried before giving up.
 *
 * Each attempt is a flood fill, and a leaked fill is abandoned the moment it reaches the border, so
 * an attempt is cheap — but a tap in the middle of a large black area would otherwise try every
 * pixel around it. A handful covers the case this exists for, which is a tap that landed on an
 * outline two or three pixels wide.
 */
private const val MAX_SEED_CANDIDATES = 12

/** A seed this dark is ink, not a place to read: black outlines, panel borders, lettering. */
private const val MIN_SEED_LUMA = 96

/** Nothing darker than this joins a region, however close it is to the seed's own brightness. */
private const val MIN_REGION_LUMA = 64

/**
 * Below this a pixel counts as *ink* when deciding where a fill may travel — see [openSpace].
 *
 * Higher than [MIN_SEED_LUMA], and deliberately: a seed has to be somewhere a reader could put their
 * eye, while ink only has to be something a fill must not cross. The anti-aliased shoulder either
 * side of a drawn line sits in the gap between the two, and counting it as ink is what lets the
 * seal close a break that the strict fill would walk straight through.
 */
private const val INK_LUMA = 128

/**
 * How far a pixel's brightness may differ from the seed's and still count as the same surface.
 *
 * About 11% of the range: wide enough for JPEG ringing around lettering, the anti-aliased edge of an
 * outline, and the grain of a scanned page — narrow enough that a bubble interior and the grey
 * screentone beside it do not merge.
 */
private const val BUBBLE_TOLERANCE = 28

/**
 * A region covering more than this share of the page is not a region.
 *
 * It is the page's own background, or a fill that escaped. Either way, framing it would zoom to
 * roughly where the reader already is.
 */
private const val MAX_REGION_AREA_FRACTION = 0.55f

/** Below this share of the page, a region is dust rather than a place to read. */
private const val MIN_REGION_AREA_FRACTION = 0.0004f

/** A region narrower than this share of the page's shorter side is a seam, not a region. */
private const val MIN_REGION_SIDE_FRACTION = 0.015f

/** How much of its own bounding box a region must actually fill to be a region. */
private const val MIN_REGION_FILL = 0.30f

/** How far the framing box is grown beyond the interior it was measured from. */
private const val REGION_MARGIN_FRACTION = 0.06f

/** Four entries per pixel on a page is enough for any queue of pixel indices; this is the seed. */
private const val INITIAL_QUEUE = 1024

/**
 * The page's shorter side is divided by this to get the seal radius.
 *
 * Four hundred puts a three-pixel seal on a comic page rendered 1080 wide, which is about the
 * widest break a lossy encoder leaves in an outline.
 */
private const val SEAL_DIVISOR = 400

/** The seal erodes what it protects, so it is never allowed to grow past a few pixels. */
private const val MAX_SEAL_RADIUS = 4
