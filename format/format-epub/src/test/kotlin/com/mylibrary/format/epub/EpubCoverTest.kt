package com.mylibrary.format.epub

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.common.errorOrNull
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.OpenDocument
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The declared cover, which is what the library shelf draws instead of a placeholder.
 *
 * Three ways of declaring one are exercised, because all three are in the wild and only the first is
 * what the specification prefers: the EPUB 3 manifest property, the EPUB 2 metadata indirection, and
 * a book that names its cover only by calling the file one. The negative cases matter as much: a book
 * with no cover, and a manifest that names a cover the archive does not contain, must both answer
 * `null` so the shelf falls back to its stand-in rather than to an empty frame.
 */
class EpubCoverTest {

    private val engine = EpubEngine()

    private fun open(bytes: ByteArray): OpenDocument {
        val result = runBlocking { engine.open(FakeDocumentSource(bytes)) }
        assertThat(result.errorOrNull()).isNull()
        return result.getOrNull()!!
    }

    private fun coverOf(opf: String): ByteArray? =
        runBlocking { open(EpubBuilder.standard().file("OEBPS/content.opf", opf).build()).coverImage() }

    @Test
    fun `reads the cover the manifest marks as one`() {
        val opf = OPF_XML.replace(
            """<item id="plate" href="Images/plate.png" media-type="image/png"/>""",
            """<item id="plate" href="Images/plate.png" media-type="image/png" properties="cover-image"/>""",
        )

        assertThat(coverOf(opf)).isEqualTo(PLATE_PNG)
    }

    @Test
    fun `reads the cover an epub 2 meta element names`() {
        val opf = OPF_XML
            .replace("<dc:description>A book about sand.</dc:description>",
                "<dc:description>A book about sand.</dc:description>\n    <meta name=\"cover\" content=\"plate\"/>")

        assertThat(coverOf(opf)).isEqualTo(PLATE_PNG)
    }

    @Test
    fun `finds a cover that is only named as one`() {
        val opf = OPF_XML
            .replace("""<item id="plate" href="Images/plate.png"""", """<item id="cover" href="Images/plate.png"""")

        assertThat(coverOf(opf)).isEqualTo(PLATE_PNG)
    }

    @Test
    fun `a book that declares no cover has none`() {
        assertThat(coverOf(OPF_XML)).isNull()
    }

    @Test
    fun `a cover the archive does not contain is no cover`() {
        val opf = OPF_XML.replace(
            """<item id="plate" href="Images/plate.png" media-type="image/png"/>""",
            """<item id="plate" href="Images/plate.png" media-type="image/png" properties="cover-image"/>""",
        )
        val bytes = EpubBuilder.standard().without("OEBPS/Images/plate.png").file("OEBPS/content.opf", opf).build()

        assertThat(runBlocking { open(bytes).coverImage() }).isNull()
    }
}
