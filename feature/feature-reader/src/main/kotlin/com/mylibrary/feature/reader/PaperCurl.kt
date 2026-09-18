package com.mylibrary.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mylibrary.core.domain.model.PageTurnEffect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The page curl: a corner of the sheet lifted and rolled over on a diagonal fold.
 *
 * **What it is not.** Every other turn effect is a transform of the page — a scale, a fade, a
 * rotation about its binding edge — and an earlier curl was one too: a 70° `rotationY` with a
 * gradient rectangle standing in for the shadow. It reads as a card pivoting, because that is what
 * it is. A plane cannot bend, and the free edge of a turning page is the one part of it that never
 * stays straight.
 *
 * **The fold is diagonal, and that is most of the effect.** A page lifted at its corner bends along
 * a line running from one edge to another across that corner, not along a line parallel to the
 * spine; and the sheet past the fold wraps around itself until it is standing edge-on and beyond
 * that turns its *back* to the reader. Both of those are the shape people recognise, and neither is
 * reachable by rotating anything.
 *
 * **Why it is cheap.** The wrap is one-dimensional: a point's whole journey depends on nothing but
 * its distance from the fold, measured across it. So the sheet is cut into bands parallel to the
 * fold and each band is drawn with a single `drawImage` under an affine transform — no mesh, no
 * vertex buffer, no work per pixel on the main thread. Bands past a quarter turn come out mirrored
 * on their own, because the cosine that foreshortens them has gone negative by then, and that
 * mirror image is the sheet's back.
 *
 * **What it needs, and why every sheet is a bitmap.** Bending the sheet means re-drawing its pixels
 * at a different width, and the bend draws the sheet once per band — some fifty draws a frame. A
 * page that costs anything to draw therefore costs fifty times that, and the only cheap thing to
 * draw fifty times a frame is a bitmap. A PDF's page and a comic's are pixels already; a reflowable
 * page is live text with no pixels to slice, so the paged reader gives it pixels before it gets here
 * — rasterised once, as its turn begins, and dropped when it settles.
 *
 * That is a measurement rather than a preference. Bending the live display list, which is what 1.5.0
 * did, put every one of those fifty draws through a page's worth of glyph runs: the reader's own
 * frame stats on the API 35 emulator read 150 ms for the median frame against 16 ms for the same
 * page slid, and 85% of frames janky against 6%. A text page turned that way in every text file,
 * under the default turn effect, which is exactly the stutter this was reported as.
 */

/** Roll of a page-turn, 0 (lying flat) to 1 (fully turned), from a page's distance in pages. */
internal fun paperCurlRoll(offsetFraction: Float): Float =
    if (offsetFraction == 0f) 0f else abs(offsetFraction).coerceIn(0f, 1f)

/**
 * Whether the page at [offsetFraction] bends itself rather than being transformed by its slot.
 *
 * A settled page is never a curl, and this is what guarantees the property that matters most: a page
 * nobody is touching is drawn by exactly one code path, the one that has always drawn it.
 */
internal fun paperCurlOwns(effect: PageTurnEffect, offsetFraction: Float): Boolean =
    effect == PageTurnEffect.CURL && offsetFraction != 0f

/**
 * Where the sheet bends and how far it has rolled, in the two axes the fold defines.
 *
 * Everything is expressed against the fold line: [hinge] is a point on it, [normal] crosses it
 * towards the corner being lifted, and [along] runs up it. A point's distance from the fold measured
 * along [normal] is the *only* number that decides what happens to it, which is what makes the whole
 * effect one-dimensional and therefore cheap.
 */
internal data class PaperCurl(
    /** A point on the fold line. */
    val hinge: Offset,
    /** Unit vector across the fold, towards the corner that lifts. */
    val normal: Offset,
    /** Unit vector along the fold. */
    val along: Offset,
    /** Radius the sheet wraps at. */
    val radius: Float,
    /**
     * How far the flat side of the sheet reaches from the fold, as a negative distance.
     *
     * Negative because it is measured against [normal]: the flat side is behind the fold, the rolled
     * side in front of it. It is the whole page when a turn starts and nothing when it ends.
     */
    val nearEdge: Float,
    /** How much sheet there is to roll: the distance from the fold out to the far corner. */
    val farEdge: Float,
) {
    /**
     * Where on screen the sheet ends up at [distance] from the fold.
     *
     * The sheet wraps a cylinder, so a distance travelled *along the sheet* becomes a chord of the
     * circle it wrapped: `R·sin(d/R)`. Past a quarter turn this comes back towards the fold, which is
     * the sheet turning its back to the reader, and it keeps the whole band walk unambiguous — two
     * screen positions share one distance, and the far one is the one in front.
     */
    fun screenAt(distance: Float): Float = radius * sin(distance / radius)

    /** How many bands the roll is cut into. Finer where the sheet has further to curve. */
    val bandCount: Int
        get() = ((farEdge / radius) / BAND_ANGLE).roundToInt().coerceIn(1, MAX_BANDS)

    val rolled: Boolean get() = farEdge > 0f && radius > 0f
}

/**
 * How one band of sheet is placed: the width it is squeezed to, and where it starts.
 *
 * The wrap is a rotation of the sheet about the fold, so a band's slice of it is foreshortened by
 * the cosine of the angle it has turned through and moved to the chord that angle subtends. Both
 * numbers are what the band's transform is built from, and pinning them here keeps the one piece of
 * arithmetic the drawing cannot show a mistake in testable.
 *
 * [scale] goes negative once the sheet is past edge-on, which is exactly the moment it starts
 * showing its back, and is what mirrors the band rather than a special case for it.
 */
internal data class BandPlacement(val scale: Float, val shift: Float) {
    /** Where the sheet at [distance] from the fold ends up. */
    fun place(distance: Float): Float = scale * distance + shift
}

/** The placement of the band of sheet between [previous] and [edge], for [curl]. */
internal fun bandPlacement(curl: PaperCurl, previous: Float, edge: Float): BandPlacement {
    val from = curl.screenAt(previous)
    val to = curl.screenAt(edge)
    val scale = (to - from) / (edge - previous)
    return BandPlacement(scale = scale, shift = from - scale * previous)
}

/**
 * The bend for a page [roll] of the way through a turn, inside [frame].
 *
 * [hingeAtLeft] is the whole of the mirroring: it says which edge the page is hinged on, and so which
 * corner is the one being lifted. An Arabic page hinged on its right lifts its top-left corner, which
 * is the same arithmetic with the diagonal reflected.
 */
internal fun paperCurl(roll: Float, frame: Rect, hingeAtLeft: Boolean): PaperCurl {
    val turned = roll.coerceIn(0f, 1f)
    val diagonal = DIAGONAL
    // Up and towards the free edge: the corner that lifts is the *top* one, and the fold runs down
    // and across the page away from it. Screen y grows downwards, hence the negated term.
    val normal = if (hingeAtLeft) Offset(diagonal, -diagonal) else Offset(-diagonal, -diagonal)
    val along = Offset(-normal.y, normal.x)

    val corner = if (hingeAtLeft) {
        Offset(frame.right, frame.top)
    } else {
        Offset(frame.left, frame.top)
    }

    // The fold sweeps from the lifted corner to the corner opposite it, so a turn of 1 has rolled
    // the whole sheet up and a turn of 0 has not started.
    val reach = listOf(
        Offset(frame.left, frame.top),
        Offset(frame.right, frame.top),
        Offset(frame.left, frame.bottom),
        Offset(frame.right, frame.bottom),
    ).maxOf { far ->
        val dx = far.x - corner.x
        val dy = far.y - corner.y
        -(dx * normal.x + dy * normal.y)
    }

    val distance = turned * reach
    val radius = BEND_RADIUS * min(frame.width, frame.height)

    return PaperCurl(
        hinge = corner - normal * distance,
        normal = normal,
        along = along,
        radius = radius,
        nearEdge = distance - reach,
        // Past half a turn the sheet has rolled onto itself and the rest of it is inside the roll.
        farEdge = min(distance, PI.toFloat() * radius),
    )
}

/**
 * Draws the page lying flat — the single call a settled page has always been.
 *
 * The frame is the page's own rectangle, centred in the slot, at the size `drawnPageSize` worked out
 * for the fit mode; the three fit modes differ only in that rectangle, so there is nothing here that
 * knows which one is in force.
 */
internal fun DrawScope.drawSettledPage(page: ImageBitmap, frame: Rect) {
    if (frame.width <= 0f || frame.height <= 0f) return
    drawImage(
        image = page,
        dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
        dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
    )
}

/**
 * Draws [page] with its corner lifted into [curl].
 *
 * Every format comes through here, and every one of them as a bitmap — see the note at the top of
 * this file for why that is not a limitation but the whole of the design.
 *
 * The page is drawn in three pieces that between them are the whole of it: the part still lying
 * flat, the bands it wraps through, and the shading each band turns away from the light. Every band
 * is the same `drawImage` under a different affine transform, clipped to the strip of screen it
 * occupies — which is the entire renderer.
 *
 * The page is drawn at the origin of [frame]: [frame]'s position is baked into the destination rect
 * the bitmap is drawn to.
 */
internal fun DrawScope.drawPaperCurl(page: ImageBitmap, frame: Rect, curl: PaperCurl) {
    if (frame.width <= 0f || frame.height <= 0f) return
    if (!curl.rolled) {
        drawSettledPage(page, frame)
        return
    }

    val strip = Path()
    // The fold's own angle, which every band's transform is built around.
    val foldDegrees = atan2(curl.normal.y, curl.normal.x) * 180f / PI.toFloat()
    // Long enough to cut the canvas in two whichever way the fold runs across it.
    val reach = (frame.width + frame.height) * 2f

    // 1. the sheet still lying flat, on the far side of the fold. One draw call.
    stripTo(strip, curl, from = curl.nearEdge, to = 0f, reach = reach)
    clipPath(strip) {
        drawSettledPage(page, frame)
    }

    // 2. the sheet wrapping, a band at a time. Walked along the *sheet* rather than across the
    //    screen, because the screen is ambiguous once the wrap passes a quarter turn and the sheet
    //    is coming back towards the fold; walking the sheet also crowds the bands together exactly
    //    where the sheet is most compressed, which is where they need to be closest.
    val bands = curl.bandCount
    var previousEdge = 0f
    for (band in 1..bands) {
        val edge = curl.farEdge * band / bands
        val from = curl.screenAt(previousEdge)
        val to = curl.screenAt(edge)

        // The one affine transform that carries this band's slice of the sheet to where the wrap
        // puts it: a scale across the fold, being the foreshortening, and a shift along it being
        // how far the band has travelled. A band past a quarter turn comes out with a negative
        // scale, which mirrors it — that mirror image is the back of the sheet.
        val placement = bandPlacement(curl, previousEdge, edge)

        stripTo(strip, curl, from = min(from, to), to = maxOf(from, to), reach = reach)
        clipPath(strip) {
            // Built from `rotate`/`scale` rather than one matrix, and both rotations are about the
            // origin: a scale *across a diagonal* is what carries the sheet to its new width, and
            // `DrawTransform.transform` takes its matrix in a storage order that is not the order
            // `Matrix` indexes by, which silently threw the page off the canvas.
            withTransform({
                translate(curl.hinge.x + curl.normal.x * placement.shift, curl.hinge.y + curl.normal.y * placement.shift)
                rotate(foldDegrees, pivot = Offset.Zero)
                scale(placement.scale, 1f, pivot = Offset.Zero)
                rotate(-foldDegrees, pivot = Offset.Zero)
                translate(-curl.hinge.x, -curl.hinge.y)
            }) {
                drawSettledPage(page, frame)
            }
            // 3. the light this band of the sheet turns away from. Darkest where the sheet is
            //    edge-on, which is what draws the crease the eye reads as paper rather than paint.
            val angle = ((previousEdge + edge) / 2f) / curl.radius
            val shade = MAX_SHADE * (1f - abs(cos(angle)))
            if (shade > 0f) {
                drawRect(
                    color = Color.Black.copy(alpha = shade),
                    topLeft = Offset.Zero,
                    size = size,
                )
            }
        }
        previousEdge = edge
    }
}

/**
 * Fills [path] with the strip of screen between two distances from the fold.
 *
 * A parallelogram rather than a rectangle, because the fold runs diagonally; it is cut far past the
 * canvas on both ends, so clipping to it is clipping to the strip and nothing more.
 */
private fun stripTo(path: Path, curl: PaperCurl, from: Float, to: Float, reach: Float) {
    val end = curl.along * reach
    val a = curl.hinge - end + curl.normal * from
    val b = curl.hinge + end + curl.normal * from
    val c = curl.hinge + end + curl.normal * to
    val d = curl.hinge - end + curl.normal * to
    path.rewind()
    path.moveTo(a.x, a.y)
    path.lineTo(b.x, b.y)
    path.lineTo(c.x, c.y)
    path.lineTo(d.x, d.y)
    path.close()
}

/**
 * Radius of the wrap, as a fraction of the page's shorter side — the tightness of the curl.
 *
 * A fraction rather than a length in pixels so the same page curls by the same *shape* on a phone and
 * on a tablet. Too small and the corner snaps over into a scroll; too large and it barely curves,
 * which is the flat plane this exists to replace.
 */
private const val BEND_RADIUS = 0.42f

/**
 * How dark the sheet goes as it turns edge-on.
 *
 * Short of opaque, because paper is thin and light gets through it. Deep enough to matter, though:
 * the crease is what the eye reads as a fold rather than as a shadow someone painted on.
 */
private const val MAX_SHADE = 0.62f

/** Angle of sheet one band covers. Small enough that the wrap reads as a curve, not a facet. */
private const val BAND_ANGLE = 0.06f

/** However long the sheet is, the bands are never allowed to become the expensive part. */
private const val MAX_BANDS = 72

/** `sin(45°)` — the fold runs at forty-five degrees to the page's own edges. */
private val DIAGONAL = 1f / sqrt(2f)
