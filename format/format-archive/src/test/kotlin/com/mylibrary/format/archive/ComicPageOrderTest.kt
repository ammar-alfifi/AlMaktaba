package com.mylibrary.format.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The page-ordering rules, tested on names rather than on archives: this is the one part of the
 * engine whose failure is silent — a book whose pages are out of order still opens and still
 * renders — so it gets the closest scrutiny.
 */
class ComicPageOrderTest {

    @Test
    fun `orders page numbers numerically, not as text`() {
        val names = listOf("page10.png", "page2.png", "page1.png")

        val ordered = names.sortedWith(ComicPageOrder.comparator)

        assertThat(ordered).containsExactly("page1.png", "page2.png", "page10.png").inOrder()
    }

    @Test
    fun `keeps a cover first and page ten last`() {
        val names = listOf("page10.jpg", "page2.jpg", "cover.jpg", "page1.jpg", "page11.jpg")

        val ordered = names.sortedWith(ComicPageOrder.comparator)

        assertThat(ordered).containsExactly("cover.jpg", "page1.jpg", "page2.jpg", "page10.jpg", "page11.jpg")
            .inOrder()
    }

    @Test
    fun `orders pages inside subdirectories by their whole path`() {
        val names = listOf("chapter2/page1.jpg", "chapter1/page10.jpg", "chapter1/page2.jpg")

        val ordered = names.sortedWith(ComicPageOrder.comparator)

        assertThat(ordered).containsExactly("chapter1/page2.jpg", "chapter1/page10.jpg", "chapter2/page1.jpg")
            .inOrder()
    }

    @Test
    fun `accepts every image extension, whatever its case`() {
        val names = listOf(
            "page.JPG", "page.jpeg", "page.PNG", "page.gif", "page.BMP", "page.webp",
        )

        assertThat(names.filter { ComicPageOrder.isPage(it, isDirectory = false) }).hasSize(names.size)
    }

    @Test
    fun `rejects directories`() {
        assertThat(ComicPageOrder.isPage("pages/", isDirectory = true)).isFalse()
        assertThat(ComicPageOrder.isPage("__MACOSX/", isDirectory = true)).isFalse()
    }

    @Test
    fun `rejects the cruft a macOS zip carries`() {
        val cruft = listOf(
            "__MACOSX/._page1.png",
            "__MACOSX/page1.png",
            "._page1.png",
            ".DS_Store",
            "Thumbs.db",
        )

        cruft.forEach { name ->
            assertThat(ComicPageOrder.isPage(name, isDirectory = false)).isFalse()
        }
    }

    @Test
    fun `rejects files that are not images`() {
        val notPages = listOf("ComicInfo.xml", "readme.txt", "page1.png.bak", "noextension")

        notPages.forEach { name ->
            assertThat(ComicPageOrder.isPage(name, isDirectory = false)).isFalse()
        }
    }

    @Test
    fun `normalises windows separators and dot prefixes`() {
        assertThat(ComicPageOrder.isPage("pages\\page1.png", isDirectory = false)).isTrue()
        assertThat(ComicPageOrder.isPage("./page1.png", isDirectory = false)).isTrue()
        // The same page written two ways must sort to the same key, or a zip packed on Windows and
        // one packed on Linux would order differently.
        assertThat(ComicPageOrder.sortKey("pages\\page2.png"))
            .isEqualTo(ComicPageOrder.sortKey("./pages/page2.png"))
    }
}
