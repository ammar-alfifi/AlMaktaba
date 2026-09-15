package com.mylibrary.format.archive

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Builds real archives and real images for the tests.
 *
 * The pages are genuine PNGs produced by [ImageIO] rather than random bytes, so a test that ever
 * decodes them (on a device, or under Robolectric) decodes something that is actually an image, and
 * so the page bytes differ from one another in a way that proves extraction returned the *right*
 * entry.
 */
internal object TestComics {

    /** Page names exactly as a downloaded comic names them — including the page 1/page 10 trap. */
    val PAGE_NAMES: List<String> = (1..10).map { "page$it.png" }

    /** The bytes of page *n*, keyed by the same names, each visually distinct and a distinct size. */
    val PAGES: Map<String, ByteArray> = PAGE_NAMES.associateWith { name ->
        val number = name.removePrefix("page").removeSuffix(".png").toInt()
        png(width = 10 + number, height = 20 + number, rgb = Color.HSBtoRGB(number / 12f, 1f, 1f))
    }

    /**
     * A CBZ that looks like the real thing: ten pages plus the cruft a macOS user's zip carries —
     * the `__MACOSX` directory, its AppleDouble sidecars, and a `.DS_Store` — and a `ComicInfo.xml`,
     * which is metadata rather than a page.
     */
    fun cbzWithCruft(): ByteArray = cbz(
        buildList {
            add("__MACOSX/" to ByteArray(0))
            add("__MACOSX/._page1.png" to ByteArray(16))
            add(".DS_Store" to ByteArray(8))
            add("ComicInfo.xml" to "<ComicInfo/>".toByteArray())
            shuffledPages().forEach { add(it) }
        }
    )

    /** A CBZ of the ten pages and nothing else. */
    fun cbz(): ByteArray = cbz(shuffledPages())

    /**
     * The pages in a fixed but scrambled order.
     *
     * Deliberately not the reading order: a fixture that already happened to be in order would pass
     * even if the engine never sorted at all. A fixed seed keeps the test reproducible.
     */
    private fun shuffledPages(): List<Pair<String, ByteArray>> =
        PAGE_NAMES.shuffled(Random(7)).map { it to PAGES.getValue(it) }

    fun cbz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A real, decodable PNG of the given size and colour. */
    fun png(width: Int, height: Int, rgb: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(rgb, false)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
