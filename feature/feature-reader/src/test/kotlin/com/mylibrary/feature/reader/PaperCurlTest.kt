package com.mylibrary.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mylibrary.core.domain.model.PageTurnEffect
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the curl to being a curl.
 *
 * The curl is the one turn effect that is not a transform of the page, so nothing else in the reader
 * can fail when it flattens back into the card it replaced: it still draws, still moves, and still
 * lands on the next page. These assertions are what would notice — that the fold runs diagonally
 * across a corner, that the sheet sweeps the whole page over a turn, that it foreshortens, and that
 * it rolls far enough to turn its back to the reader.
 */
class PaperCurlTest {

    private val frame = Rect(0f, 0f, 1000f, 1400f)

    private fun curl(roll: Float, hingeAtLeft: Boolean = true) =
        paperCurl(roll, frame, hingeAtLeft)

    @Test
    fun `a settled page has no roll and is not owned by the curl`() {
        assertEquals(0f, paperCurlRoll(0f), 0f)
        assertFalse(paperCurlOwns(PageTurnEffect.CURL, 0f))
        assertFalse(curl(paperCurlRoll(0f)).rolled)
    }

    @Test
    fun `roll is how far the page is from settled, in either direction`() {
        assertEquals(0.4f, paperCurlRoll(-0.4f), 1e-6f)
        assertEquals(0.4f, paperCurlRoll(0.4f), 1e-6f)
        // The pager cannot report more than a page away, but a fling can for a frame, and a roll
        // past one would turn the sheet inside out.
        assertEquals(1f, paperCurlRoll(-3.5f), 0f)
    }

    @Test
    fun `only a curl in motion bends the sheet`() {
        assertTrue(paperCurlOwns(PageTurnEffect.CURL, 0.2f))
        assertTrue(paperCurlOwns(PageTurnEffect.CURL, -0.2f))
        assertFalse(paperCurlOwns(PageTurnEffect.SLIDE, 0.2f))
        assertFalse(paperCurlOwns(PageTurnEffect.FADE, 0.2f))
    }

    @Test
    fun `the fold runs diagonally, not along an edge`() {
        // This is the difference between a page curl and a page rolled up like a poster, and it is
        // the single thing a later simplification is most likely to spoil.
        val square = Rect(0f, 0f, 1000f, 1000f)
        val diagonal = paperCurl(0.4f, square, hingeAtLeft = true)
        assertEquals(0.7071f, diagonal.normal.x, 1e-3f)
        // Negative y is upwards on a canvas: the fold runs from a lifted *top* corner down across
        // the page, not up from the bottom one.
        assertEquals(-0.7071f, diagonal.normal.y, 1e-3f)
        assertEquals(1f, diagonal.normal.getDistance(), 1e-5f)
        // And the fold crosses it, so the two are perpendicular.
        val crossing = diagonal.normal.x * diagonal.along.x + diagonal.normal.y * diagonal.along.y
        assertEquals(0f, crossing, 1e-5f)
    }

    @Test
    fun `the lifted corner is the top one of the free edge, and mirrors with the hinge`() {
        val right = curl(0.4f, hingeAtLeft = true)
        val left = curl(0.4f, hingeAtLeft = false)
        // On a page hinged at its left the free edge is the right one, so the corner that lifts is
        // the top right — half a page along from where the mirrored page lifts its own.
        assertTrue(right.hinge.x > left.hinge.x)
        assertEquals(right.normal.x, -left.normal.x, 1e-6f)
        assertEquals(right.normal.y, left.normal.y, 1e-6f)

        // And it is the *top* of the page that lifts, not the bottom. Distance from the fold is the
        // one number the whole effect turns on, so its sign at the two corners settles it: the top
        // of the free edge is past the fold and already rolling, the opposite corner is still flat.
        val bend = curl(0.3f)
        fun acrossFromFold(point: Offset) =
            (point.x - bend.hinge.x) * bend.normal.x + (point.y - bend.hinge.y) * bend.normal.y

        assertTrue("the top of the free edge lifts", acrossFromFold(Offset(frame.right, frame.top)) > 0f)
        assertTrue("the bottom of the free edge does not", acrossFromFold(Offset(frame.right, frame.bottom)) < 0f)
        assertTrue("the far corner stays flat", acrossFromFold(Offset(frame.left, frame.bottom)) < 0f)
    }

    @Test
    fun `the fold sweeps from the lifted corner across to the far one`() {
        // At the start of a turn the fold passes through the lifted corner, so no sheet has rolled
        // and all of it is still lying flat; at the end it has swept to the corner opposite, so all
        // of it has rolled and none of it is.
        val start = curl(0f)
        assertFalse(start.rolled)
        assertEquals(0f, start.farEdge, 1e-4f)
        assertTrue("the whole page is still flat", start.nearEdge < 0f)

        val end = curl(1f)
        assertEquals(0f, end.nearEdge, 1e-3f)
        assertTrue(end.farEdge > 0f)
    }

    @Test
    fun `more of the page rolls as the turn goes on, and less of it stays flat`() {
        var previousFlat = 0f
        for (step in 0..20) {
            val bend = curl(step / 20f)
            val flat = -bend.nearEdge
            if (step > 0) assertTrue("step $step", flat < previousFlat + 1e-3f)
            previousFlat = flat
        }
    }

    @Test
    fun `the sheet never wraps more than half a turn`() {
        // Past it the sheet has rolled onto itself, and drawing those bands would lay the page's
        // front over its own back.
        for (step in 0..100) {
            val bend = curl(step / 100f)
            assertTrue("step $step", bend.farEdge <= PI.toFloat() * bend.radius + 1e-3f)
        }
    }

    @Test
    fun `the wrap turns the sheet's back to the reader`() {
        // The point of the effect: somewhere in a turn the sheet has to get far enough round that
        // the cosine foreshortening it goes negative, which is the sheet showing its back.
        val bend = curl(1f)
        val pastEdgeOn = PI.toFloat() / 2f * bend.radius
        assertTrue(bend.farEdge > pastEdgeOn)
        assertTrue(bend.screenAt(bend.farEdge) < bend.radius)
    }

    @Test
    fun `the wrap foreshortens the sheet it was made from`() {
        // The property that makes it paper: no band of sheet is ever as wide on screen as the
        // material it took to make it.
        for (step in 1..16) {
            val roll = step / 16f
            val bend = curl(roll)
            val bands = bend.bandCount
            var previousEdge = 0f
            var previousScreen = 0f
            for (band in 1..bands) {
                val edge = bend.farEdge * band / bands
                val screen = bend.screenAt(edge)
                val sheet = edge - previousEdge
                val shown = abs(screen - previousScreen)
                assertTrue("roll $roll band $band", shown <= sheet + 1e-3f)
                previousEdge = edge
                previousScreen = screen
            }
        }
    }

    @Test
    fun `the wrap keeps its shape whatever the page size`() {
        // The radius is a share of the page rather than a length in pixels, so a tablet's page
        // curls by the same shape as a phone's instead of wrapping into a scroll.
        val phone = paperCurl(0.4f, Rect(0f, 0f, 400f, 600f), hingeAtLeft = true)
        val tablet = paperCurl(0.4f, Rect(0f, 0f, 1200f, 1800f), hingeAtLeft = true)
        assertEquals(3f, tablet.radius / phone.radius, 1e-3f)
        assertEquals(3f, tablet.farEdge / phone.farEdge, 1e-3f)
    }

    @Test
    fun `every band is a slice of the sheet, with none doubled or missed`() {
        // Bands are walked along the sheet and placed on screen; a walk that skipped or repeated
        // would tear the wrap or fold the page over itself.
        val bend = curl(0.7f)
        val bands = bend.bandCount
        assertTrue(bands > 1)
        var covered = 0f
        for (band in 1..bands) {
            val from = bend.farEdge * (band - 1) / bands
            val to = bend.farEdge * band / bands
            assertTrue("band $band", to > from)
            covered += to - from
        }
        assertEquals(bend.farEdge, covered, 1e-3f)
    }

    @Test
    fun `a page that is not turning has nothing to draw`() {
        // The guard that keeps the common case on the one path that has always drawn it.
        assertFalse(curl(0f).rolled)
        assertFalse(paperCurl(paperCurlRoll(0f), frame, hingeAtLeft = false).rolled)
    }

    @Test
    fun `a band is placed exactly where the wrap puts it`() {
        // The arithmetic the drawing cannot show a mistake in: a band's slice of sheet has to land
        // on the chord its angle subtends, and nothing about that is visible in a picture of it.
        val bend = curl(0.6f)
        val bands = bend.bandCount
        var previous = 0f
        for (band in 1..bands) {
            val edge = bend.farEdge * band / bands
            val placement = bandPlacement(bend, previous, edge)
            assertEquals(
                "band $band start",
                bend.screenAt(previous),
                placement.place(previous),
                1e-2f,
            )
            assertEquals(
                "band $band end",
                bend.screenAt(edge),
                placement.place(edge),
                1e-2f,
            )
            previous = edge
        }
    }

    @Test
    fun `a band past edge-on is mirrored onto the sheet's back`() {
        // The sign flip is the whole of "the reader is now looking at the back of the page"; with it
        // forced positive the wrap would paint the page's front over its own back.
        val bend = curl(1f)
        val edgeOn = PI.toFloat() / 2f * bend.radius
        assertTrue(bend.farEdge > edgeOn)
        val front = bandPlacement(bend, 0f, edgeOn / 2f)
        val back = bandPlacement(bend, edgeOn, bend.farEdge)
        assertTrue("the front of the sheet is not mirrored", front.scale > 0f)
        assertTrue("the back of the sheet is mirrored", back.scale < 0f)
    }
}
