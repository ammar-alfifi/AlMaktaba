package com.mylibrary.format.archive

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.domain.model.BookFormat
import org.junit.Test

/** The CBZ container: what it lists, in what order, and what it hands back for one page. */
class ZipArchiveSourceTest {

    private val source = InMemoryDocumentSource(TestComics.cbzWithCruft(), "book.cbz", BookFormat.CBZ)

    @Test
    fun `lists the ten pages and nothing else`() {
        ZipArchiveSource.open(source).use { archive ->
            assertThat(archive.pageNames).hasSize(10)
        }
    }

    @Test
    fun `lists the pages in reading order, not in archive order`() {
        ZipArchiveSource.open(source).use { archive ->
            // The fixture stores the pages scrambled, and `containsExactly(...).inOrder()` is what
            // proves page 10 comes after page 2 rather than after page 1.
            assertThat(archive.pageNames).containsExactly(*TestComics.PAGE_NAMES.toTypedArray()).inOrder()
        }
    }

    @Test
    fun `reads one page's bytes on demand, and reads the right one`() {
        ZipArchiveSource.open(source).use { archive ->
            assertThat(archive.readPage(0)).isEqualTo(TestComics.PAGES.getValue("page1.png"))
            assertThat(archive.readPage(9)).isEqualTo(TestComics.PAGES.getValue("page10.png"))
        }
    }

    @Test
    fun `reports a page whose entry is missing as unreadable, not as an empty page`() {
        ZipArchiveSource.open(source).use { archive ->
            val failure = runCatching { archive.readPage(10) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(PageReadException::class.java)
        }
    }

    @Test
    fun `closing twice is harmless`() {
        val archive = ZipArchiveSource.open(source)

        archive.close()
        archive.close()
    }
}
