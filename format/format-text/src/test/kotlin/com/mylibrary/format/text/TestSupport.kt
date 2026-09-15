package com.mylibrary.format.text

import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.runBlocking

/**
 * A [DocumentSource] over bytes already in memory.
 *
 * The engine only ever sees this interface, so no test needs a file, a `Uri` or a `ContentResolver`.
 */
internal class ByteArrayDocumentSource(
    private val bytes: ByteArray,
    override val displayName: String = "book.txt",
    override val format: BookFormat = BookFormat.TXT,
    override val mimeType: String = "text/plain",
) : DocumentSource {

    override val id: String get() = "test:$displayName"

    override val sizeBytes: Long get() = bytes.size.toLong()

    override fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun openChannel(): SeekableByteChannel = ByteArraySeekableChannel(bytes)
}

/**
 * A [SeekableByteChannel] over bytes already in memory.
 *
 * `Channels.newChannel` only offers a *readable* channel, and the engine is written against the
 * interface rather than against a file, so the test source supplies the few methods the contract
 * names. The engine itself reads through [DocumentSource.openStream], so nothing here is exercised
 * beyond the type check the compiler performs.
 */
internal class ByteArraySeekableChannel(private val bytes: ByteArray) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun read(destination: ByteBuffer): Int {
        check(open) { "This channel is closed" }
        if (position >= bytes.size) return -1
        val count = minOf(destination.remaining(), bytes.size - position.toInt())
        destination.put(bytes, position.toInt(), count)
        position += count
        return count
    }

    override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0) { "negative position: $newPosition" }
        position = newPosition
        return this
    }

    override fun size(): Long = bytes.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }
}

/** A source that fails the way a revoked URI grant does, to exercise the error mapping. */
internal class FailingDocumentSource(private val failure: IOException) : DocumentSource {
    override val id: String = "test:failing"
    override val displayName: String = "gone.txt"
    override val mimeType: String = "text/plain"
    override val sizeBytes: Long = 0L
    override val format: BookFormat = BookFormat.TXT

    override fun openStream(): InputStream = throw failure
    override fun openChannel(): SeekableByteChannel = throw failure
}

internal val engine = TextEngine()

/** Opens [bytes] and asserts it succeeds, so the test that follows can work with the document. */
internal fun open(
    bytes: ByteArray,
    displayName: String = "book.txt",
    format: BookFormat = BookFormat.TXT,
): PlainTextDocument = runBlocking {
    when (val result = engine.open(ByteArrayDocumentSource(bytes, displayName, format))) {
        is AppResult.Success -> result.data as PlainTextDocument
        is AppResult.Failure -> throw AssertionError("Expected the document to open, got ${result.error}")
    }
}

internal fun open(text: String): PlainTextDocument = open(text.toByteArray(Charsets.UTF_8))

/** Opens [bytes] expecting a failure, and hands back the error for the test to identify. */
internal fun openError(bytes: ByteArray): AppError = runBlocking {
    when (val result = engine.open(ByteArrayDocumentSource(bytes))) {
        is AppResult.Success -> throw AssertionError("Expected the document to fail, but it opened")
        is AppResult.Failure -> result.error
    }
}

internal fun textOf(document: PlainTextDocument, index: Int): String = runBlocking {
    document.chapterText(index)
}

internal fun htmlOf(document: PlainTextDocument, index: Int): String = runBlocking {
    document.chapterHtml(index)
}

internal fun resourceOf(document: PlainTextDocument, path: String): ByteArray? = runBlocking {
    document.resource(path)
}

internal fun searchIn(
    document: PlainTextDocument,
    query: String,
    limit: Int = OpenDocument.DEFAULT_SEARCH_LIMIT,
): List<SearchHit> = runBlocking { document.search(query, limit) }

/** The typing of `SearchHit.locator` is `ReadingLocator`; every TXT hit is a reflowable one. */
internal fun locatorOf(hit: SearchHit): ReadingLocator.Reflowable =
    hit.locator as ReadingLocator.Reflowable

/**
 * A page of Arabic prose, the shape a real book arrives in: several sentences, a comma, and enough
 * letters that a byte-distribution test has something to measure. Deliberately long — deciding an
 * encoding from a handful of bytes is the mistake this module exists to avoid.
 */
internal val ARABIC_SAMPLE: String = """
    كان يا ما كان في قديم الزمان وسالف العصر والأوان، ملك عظيم يحكم بلاداً واسعة، وكان له ثلاثة أبناء،
    أكبرهم شجاع مقدام، وأوسطهم حكيم عاقل، وأصغرهم طيب القلب محبوب من الناس.
    وفي يوم من الأيام خرج الملك إلى الصيد مع حاشيته، فابتعد عنهم حتى ضل الطريق،
    فوجد قصراً مهجوراً في وسط الغابة، فدخله فوجد فيه كنزاً عظيماً وكتاباً قديماً مكتوباً فيه أسرار الملك.
    ثم عاد الملك إلى مدينته، ودعا أبناءه وأخبرهم بما رأى، وأمرهم أن يحكموا بالعدل بين الناس،
    وأن يتعلموا من الكتاب كل ما فيه من حكمة وعلم، وأن يحرصوا على رعاية الرعية والرفق بالضعفاء.
    وتمر الأيام وتتوالى السنين، ويكبر الأبناء، ويصبح كل واحد منهم ملكاً على إقليم من أقاليم المملكة،
    ويعم الرخاء والسلام في البلاد، ويعيش الناس في أمن وأمان ورغد من العيش، ويذكرون الملك الحكيم بخير.
""".trimIndent()

/** A page of English prose, to prove a Latin file reads left to right inside an RTL interface. */
internal val ENGLISH_SAMPLE: String = """
    It was a bright cold day in April, and the clocks were striking thirteen. The wind that swept
    through the streets was cold and sharp, and the people hurried along with their collars turned
    up against it. Nobody paid much attention to the notices on the walls, though the notices had
    been there for as long as anyone could remember.
""".trimIndent()

/**
 * Accented Latin prose, long enough for the detector to be consulted about it, and written with the
 * characters that ISO-8859-1 and Windows-1252 agree on — so a test can assert the decoded text
 * whichever of the two the detector names.
 */
internal val SPANISH_SAMPLE: String = """
    Muchos años después, frente al pelotón de fusilamiento, el coronel Aureliano Buendía había de
    recordar aquella tarde remota en que su padre lo llevó a conocer el hielo. Macondo era entonces
    una aldea de veinte casas de barro y cañabrava construidas a la orilla de un río de aguas
    diáfanas que se precipitaban por un lecho de piedras pulidas, blancas y enormes como huevos
    prehistóricos. El mundo era tan reciente, que muchas cosas carecían de nombre, y para
    mencionarlas había que señalarlas con el dedo. Todos los años, por el mes de marzo, una familia
    de gitanos desarrapados plantaba su carpa cerca de la aldea, y con un grande alboroto de pitos y
    timbales daban a conocer los nuevos inventos. Primero llevaron el imán. Un gitano corpulento, de
    barba montaraz y manos de gorrión, que se presentó con el nombre de Melquíades, hizo una
    truculenta demostración pública de lo que él mismo llamaba la octava maravilla de los sabios
    alquimistas de Macedonia. Fue de casa en casa arrastrando dos lingotes metálicos, y todo el mundo
    se espantó al ver que los calderos, las pailas, las tenazas y los anafes se caían de su sitio, y
    las maderas crujían por la desesperación de los clavos y los tornillos tratando de desenclavarse.
""".trimIndent()
