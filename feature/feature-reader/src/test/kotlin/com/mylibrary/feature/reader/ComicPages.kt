package com.mylibrary.feature.reader

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Comic pages drawn for the tests to read, rather than read from a file.
 *
 * **Why not a fixture.** The bubble detector is a heuristic over pixels, and a heuristic is only as
 * good as the pictures it has been shown. A single checked-in page would pin the one case that was
 * in front of whoever drew it; a page *built* to a description can be varied — thinner outlines,
 * denser lettering, a coarser render — and each variation says which property the detector actually
 * depends on. It also keeps the corpus honest: `test-books/comic.cbz` was, until recently, twelve
 * solid pages with a numeral on each and no speech bubble anywhere, so nothing had ever exercised
 * this at all.
 *
 * The pages are real PNGs rasterised by `java.awt` — the same technique
 * `:format:format-archive`'s `TestComics` uses — and are then converted to the ARGB layout the
 * detector works in, so what is tested is the same array of pixels a decoded page produces.
 */
internal object ComicPages {

    const val WIDTH = 1400
    const val HEIGHT = 2000

    /**
     * A page's pixels as the reader hands them to [findBubbleRegion]: ARGB_8888, row-major, top-down.
     *
     * [scale] renders the page into a bitmap of that fraction of its drawn size, which is what
     * happens on a device — a comic scan is far larger than a phone screen, and the detector only
     * ever sees the *rendered* page, not the file's own resolution.
     */
    fun pixels(page: BufferedImage, scale: Float = 1f): IntArray {
        val rendered = if (scale == 1f) {
            page
        } else {
            val scaled = BufferedImage(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                BufferedImage.TYPE_INT_RGB,
            )
            val g = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(page, AffineTransform.getScaleInstance(scale.toDouble(), scale.toDouble()), null)
            g.dispose()
            scaled
        }

        val out = IntArray(rendered.width * rendered.height)
        rendered.getRGB(0, 0, rendered.width, rendered.height, out, 0, rendered.width)
        return out
    }

    /**
     * A comic page: four panels, two speech bubbles, and a caption box.
     *
     * The bubbles are the two shapes a reader would double-tap, and they are deliberately different
     * from each other. The top one is the well-behaved case — a clear outline and roomy lettering.
     * The bottom one is a page's worth of dialogue crammed into a small balloon, which is the case
     * that decides whether the detector is measuring enclosures or counting paper.
     *
     * [outlineWidth] is how thick the bubbles' outlines are drawn, and exists to be turned *down*:
     * a scan of a cheap print, or a page that has been through JPEG, has outlines that are a grey
     * pixel or two rather than a black line.
     */
    fun page(outlineWidth: Float = 3f): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        g.color = Color.WHITE
        g.fillRect(0, 0, WIDTH, HEIGHT)

        // Panel borders. Ink, and thick enough that the fill cannot cross one.
        g.color = Color.BLACK
        g.stroke = BasicStroke(6f)
        g.drawRect(40, 40, WIDTH - 80, 900)
        g.drawRect(40, 980, WIDTH - 80, 980)

        // The roomy bubble, in the top panel.
        bubble(
            g = g,
            bounds = Ellipse2D.Float(140f, 140f, 620f, 380f),
            outlineWidth = outlineWidth,
            lines = listOf("I THINK", "WE ARE LOST"),
            fontSize = 64,
        )

        // The cramped one: the same width, half the height, twice the dialogue.
        bubble(
            g = g,
            bounds = Ellipse2D.Float(760f, 1180f, 520f, 300f),
            outlineWidth = outlineWidth,
            lines = listOf("BUT THE MAP", "SAYS THE RIVER", "IS THE OTHER", "WAY, CAPTAIN"),
            fontSize = 52,
        )

        g.dispose()
        return image
    }

    /** Where a double-tap at the centre of the roomy bubble lands, in fractions of the page. */
    val ROOMY_BUBBLE_CENTRE: Pair<Float, Float> = (140f + 310f) / WIDTH to (140f + 190f) / HEIGHT

    /** ...and of the cramped one. */
    val CRAMPED_BUBBLE_CENTRE: Pair<Float, Float> = (760f + 260f) / WIDTH to (1180f + 150f) / HEIGHT

    /**
     * Where a reader actually double-taps: **on the words**.
     *
     * This is the difference between a bubble detector tested on paper and one tested on a reader.
     * The centre of a balloon is empty paper only when it holds a single short line; the moment
     * there are two lines of dialogue the middle of the bubble is a line of lettering, and a reader
     * aiming at "the balloon" has no reason to thread the gap between them.
     */
    val ROOMY_BUBBLE_TEXT: Pair<Float, Float> = (140f + 310f) / WIDTH to (140f + 175f) / HEIGHT

    val CRAMPED_BUBBLE_TEXT: Pair<Float, Float> = (760f + 260f) / WIDTH to (1180f + 160f) / HEIGHT

    /**
     * A page that has been through a lossy encoder, or scanned off paper that was never white.
     *
     * Real comics reach the reader as JPEG inside a CBZ or as a photograph of ink on newsprint;
     * neither has the crisp black outline that `java.awt` draws. Wrapping the raster in a JPEG
     * round trip is a cheap, deterministic way to put the ringing and the soft edges back.
     */
    fun degraded(page: BufferedImage, quality: Float = 0.3f): BufferedImage {
        val buffer = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        ImageIO.createImageOutputStream(buffer).use { out ->
            writer.output = out
            writer.write(null, IIOImage(page, null, null), param)
        }
        writer.dispose()
        return ImageIO.read(ByteArrayInputStream(buffer.toByteArray()))
    }

    /** The same page with its paper off-white and its ink grey, as a scan of cheap print looks. */
    fun scanned(page: BufferedImage): BufferedImage {
        val scanned = BufferedImage(page.width, page.height, BufferedImage.TYPE_INT_RGB)
        val g = scanned.createGraphics()
        g.drawImage(page, 0, 0, null)
        g.dispose()
        for (y in 0 until scanned.height) {
            for (x in 0 until scanned.width) {
                val argb = scanned.getRGB(x, y)
                // Paper at 214 rather than 255, ink at 45 rather than 0: the contrast is halved, and
                // the detector's thresholds are all absolute.
                val r = 45 + (argb shr 16 and 0xFF) * 169 / 255
                val gr = 45 + (argb shr 8 and 0xFF) * 169 / 255
                val b = 45 + (argb and 0xFF) * 169 / 255
                scanned.setRGB(x, y, (r shl 16) or (gr shl 8) or b)
            }
        }
        return scanned
    }

    /**
     * The roomy bubble drawn with a break in its outline.
     *
     * The classic real-world miss, and the one the detector's own documentation calls out: a
     * hand-inked balloon whose line thins to nothing at one point, or a scan where a couple of
     * outline pixels came out lighter than the paper around them. The fill then escapes the balloon
     * and finds the panel it sits in, and a *panel* is not worth zooming into — it is most of the
     * page — so the reader gets the ordinary zoom and reasonably reads it as the feature not working.
     *
     * [gapPx] is how wide the break is, in page pixels — a hairline, not a doorway. Nothing else
     * about the page changes.
     */
    fun pageWithBrokenOutline(gapPx: Float = 4f): BufferedImage {
        val image = page()
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Repaint the outline as an arc that stops short, leaving the break at the top of the ball.
        val bounds = Ellipse2D.Float(140f, 140f, 620f, 380f)
        g.color = Color.WHITE
        g.stroke = BasicStroke(14f)
        g.draw(bounds)
        g.color = Color.BLACK
        g.stroke = BasicStroke(3f)
        // The gap is at the top of the balloon, where a tail would leave it. Converted from pixels
        // to degrees against the balloon's own radius, because that is the size a break actually is.
        val gapDegrees = gapPx / 310f * (180f / Math.PI.toFloat())
        g.drawArc(
            bounds.x.toInt(),
            bounds.y.toInt(),
            bounds.width.toInt(),
            bounds.height.toInt(),
            (90f + gapDegrees / 2f).toInt(),
            Math.round(360f - gapDegrees),
        )
        g.dispose()
        return image
    }

    private fun bubble(
        g: Graphics2D,
        bounds: Ellipse2D.Float,
        outlineWidth: Float,
        lines: List<String>,
        fontSize: Int,
    ) {
        g.color = Color.WHITE
        g.fill(bounds)

        g.color = Color.BLACK
        g.stroke = BasicStroke(outlineWidth)
        g.draw(bounds)

        g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
        val metrics = g.fontMetrics
        val lineHeight = metrics.height
        var y = (bounds.centerY - (lines.size * lineHeight) / 2.0 + metrics.ascent).toInt()
        lines.forEach { line ->
            val width = metrics.stringWidth(line)
            g.drawString(line, (bounds.centerX - width / 2.0).toInt(), y)
            y += lineHeight
        }
    }
}
