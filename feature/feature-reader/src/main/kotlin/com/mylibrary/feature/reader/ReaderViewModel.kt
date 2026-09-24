package com.mylibrary.feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.core.domain.engine.LinkTarget
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.usecase.DeleteBookmarkUseCase
import com.mylibrary.core.domain.usecase.ObserveFolderSequenceUseCase
import com.mylibrary.core.domain.usecase.ObserveSettingsUseCase
import com.mylibrary.core.domain.usecase.OpenBookUseCase
import com.mylibrary.core.domain.usecase.ReadingProgressUseCase
import com.mylibrary.core.domain.usecase.RestoreReadingPositionUseCase
import com.mylibrary.core.domain.usecase.SaveReadingProgressUseCase
import com.mylibrary.core.domain.usecase.SearchInDocumentUseCase
import com.mylibrary.core.domain.usecase.ToggleBookmarkUseCase
import com.mylibrary.core.domain.usecase.UpdateSettingsUseCase
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the screen can draw for one page right now. */
sealed interface PageRenderState {
    data object Loading : PageRenderState
    data class Ready(val image: ImageBitmap) : PageRenderState
    data class Failed(val error: AppError) : PageRenderState
}

/**
 * A chapter ready to render.
 *
 * [title] comes from the document's own navigation, so a chapter announces itself the way the
 * publisher named it rather than by its position in the spine. [anchorBlocks] and [offsets] are what
 * let a link target and a stored highlight be turned into a place on the page.
 */
data class ChapterContent(
    val title: String?,
    val blocks: List<ContentBlock>,
    /** Element id → index into [blocks], for resolving link anchors. */
    val anchorBlocks: Map<String, Int> = emptyMap(),
    /** Block → character offset in the document's chapter text. */
    val offsets: ChapterTextMap = ChapterTextMap.Empty,
) {
    companion object {
        val Empty = ChapterContent(title = null, blocks = emptyList())
    }
}

/**
 * Drives the reader.
 *
 * Four things here are worth reading before changing anything:
 *
 *  **1. A document is opened once and held.** Decoders hold native state — a pdfium document, an
 *  open archive — so a document is opened on the IO dispatcher, kept in a field, and closed in
 *  [onCleared]. Re-opening per page would be catastrophic for a PDF.
 *
 *  **2. All access to one document is serialised.** pdfium is not thread-safe on a single document,
 *  and opening a second stream into a RAR mid-extract is not either. A [Mutex] around every call
 *  into a document is cheaper than reasoning about which decoders happen to tolerate concurrency.
 *  The lock belongs to the *document* rather than to the reader, because the reader now holds more
 *  than one: a folder is a series, and the volume either side of the open one is opened before the
 *  reader reaches it. One lock over all of them would make a prefetch block the page on screen.
 *
 *  **3. The open book moves.** [currentBookId] is where the reader is, and it follows them across
 *  the seam into the next volume of the folder — which is why nothing may capture it, and why
 *  anything that observes the library (bookmarks, the sequence of neighbours, progress) observes it
 *  through that flow rather than as a value read at construction time.
 *
 *  **4. Rendered pages live in [PageCache], not in [ReaderUiState].** Putting bitmaps in the state
 *  object would make every state comparison walk megabytes of pixels, and would make `StateFlow`
 *  conflation compare images by identity on every recomposition. The state holds *where* the reader
 *  is; the cache holds the pixels.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val documentRepository: DocumentRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val openBook: OpenBookUseCase,
    private val restorePosition: RestoreReadingPositionUseCase,
    private val saveProgress: SaveReadingProgressUseCase,
    private val toggleBookmark: ToggleBookmarkUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    private val progressCalculator: ReadingProgressUseCase,
    private val observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val searchInDocument: SearchInDocumentUseCase,
    private val observeSequence: ObserveFolderSequenceUseCase,
    private val fontLoader: DocumentFontLoader,
    private val dispatchers: DispatcherProvider,
) : MviViewModel<ReaderUiState, ReaderIntent, ReaderEffect>(ReaderUiState()) {

    private val initialBookId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_BOOK_ID)) {
        "ReaderViewModel requires a '$ARG_BOOK_ID' navigation argument"
    }

    /**
     * The book being read, which is not the same book for the whole life of this ViewModel.
     *
     * The reader carries on into the next volume of the folder without leaving the screen, so the
     * document it is drawing changes underneath it — and everything that is *about* the open book
     * (its bookmarks, its neighbours, the position written for it) has to follow that change rather
     * than read a value captured when the screen was created.
     */
    private val currentBookId = MutableStateFlow(initialBookId)

    /**
     * A place to open at, when the caller named one — a search hit, a bookmark tap.
     *
     * When present it wins over the saved reading position: the reader asked for *this* spot, and
     * landing where they last were would make the request a lie. Absent, the ordinary case, means
     * the saved position decides. Parsed once here rather than in [openDocument] so a malformed
     * argument fails the same way every launch does — to `null` — and the saved position takes over.
     * It applies to the book the reader *arrived* at, which is the only one a caller can have named.
     */
    private val startLocator: ReadingLocator? =
        savedStateHandle.get<String>(ARG_LOCATOR)?.let(ReadingLocator::parse)

    /**
     * One open book of the sequence, with the lock that serialises access to it.
     *
     * Holds the book rather than only the handle because everything read out of a document — its unit
     * count, its outline, whether it is made of pages — is needed beside the identity of the book it
     * came from, and a cache key is the book's own `uri`.
     */
    private class OpenSegment(
        val book: Book,
        val document: OpenDocument,
        val mutex: Mutex = Mutex(),
    )

    /**
     * The open documents, by book id.
     *
     * At most three: the open book and the volume on either side of it, and the two neighbours only
     * while the reader is near enough to a seam to reach one — see [closeDetachedSegments]. Every one
     * of them holds native memory, and a reader working through ten volumes while keeping all ten
     * open is how a process runs out of it.
     *
     * Touched only from the main dispatcher. Opening happens on IO, but the result is published back
     * here and the map is not read from anywhere else.
     */
    private val segments = mutableMapOf<Long, OpenSegment>()

    /**
     * Books whose document is being opened right now.
     *
     * What keeps the prefetch from being asked for the same volume on every page turned near the end
     * of a book: opening a 900-page PDF is not free, and the request arrives once per position
     * change.
     */
    private val pendingOpens = mutableSetOf<Long>()

    /** The open book, or `null` before it opens and after the reader leaves. */
    private val primary: OpenSegment? get() = segments[currentBookId.value]

    /** The open document, which every call that reads from one goes through. */
    private val document: OpenDocument? get() = primary?.document

    /**
     * Rendered pages, bounded in bytes rather than in page count — see [PageCache] for why.
     */
    private val pageCache = PageCache(maxBytes = PAGE_CACHE_BYTES)

    /** Debounces progress writes so that a fast scroll does not write on every frame. */
    private var progressJob: Job? = null

    /** Set in [onCleared], so an open that lands afterwards is closed rather than kept alive. */
    private var isCleared = false

    init {
        collectSettings()
        observeBookmarks()
        observeSequenceOfBook()
        loadBook()
    }

    /**
     * Mirrors the settings store into state.
     *
     * Named distinctly from the injected `observeSettings` use case it calls: two members with the
     * same name in one class is how a call silently resolves to the wrong one.
     */
    private fun collectSettings() {
        launch {
            observeSettings().collect { settings -> setState { copy(settings = settings) } }
        }
    }

    private fun loadBook() {
        launch {
            val book = withContext(dispatchers.io) { libraryRepository.getBook(currentBookId.value) }
            if (book == null) {
                setState {
                    copy(
                        isLoading = false,
                        error = AppError.FileAccess("Book ${currentBookId.value} is not in the library"),
                    )
                }
                return@launch
            }
            setState { copy(book = book, isRtlContent = book.language?.startsWith("ar") == true) }
            openDocument(password = null)
        }
    }

    /**
     * Opens the document and restores the saved reading position.
     *
     * A password-protected document does not set [ReaderUiState.error] — it is not a failure, it is
     * a question, and the screen answers it with a dialog rather than an error page.
     */
    private suspend fun openDocument(password: String?) {
        val book = currentState.book ?: return
        setState { copy(isLoading = true, error = null) }

        val result = withContext(dispatchers.io) { openBook(book, password) }

        when (result) {
            is AppResult.Failure -> when (val error = result.error) {
                is AppError.PasswordRequired -> setState {
                    copy(
                        isLoading = false,
                        isAwaitingPassword = true,
                        lastPasswordWasWrong = error.wrongPassword,
                    )
                }

                else -> setState { copy(isLoading = false, error = error) }
            }

            is AppResult.Success -> {
                val opened = result.data
                segments[book.id] = OpenSegment(book, opened)
                val (restored, restoredOffset) = withContext(dispatchers.io) { restoredPosition(opened) }
                setState {
                    copy(
                        isLoading = false,
                        error = null,
                        isAwaitingPassword = false,
                        lastPasswordWasWrong = false,
                        capabilities = opened.capabilities,
                        isPageImages = opened is PagedDocument,
                        totalUnits = unitCountOf(opened),
                        currentUnit = restored,
                        reflowOffset = restoredOffset,
                        // The previous book's measurement, if one was open. Its chapter count and its
                        // layout are not this book's, and nothing would ever overwrite it: the paged
                        // view clears it before measuring, but only once it is composed — a scrolling
                        // reader would carry the old book's page count into the new one.
                        bookIndex = null,
                        outline = opened.outline,
                        isRtlContent = opened.metadata.language?.startsWith("ar")
                            ?: currentState.isRtlContent,
                    )
                }
                refreshPositionLabel()
                saveCurrentProgress()

                // The document's own typeface, if it carries one. Loaded after the state update so
                // the first frame is not delayed by reading a font out of the archive: the reader
                // starts in the fallback face and switches when the face arrives, which is far less
                // noticeable than a blank screen.
                if (opened is ReflowableDocument) {
                    val font = fontLoader.load(opened)
                    if (font != null) setState { copy(documentFont = font) }
                }

                // The volume either side of this one, opened now rather than on the frame the reader
                // reaches the seam. A book of one page is already at its seam here, which is why this
                // runs on opening rather than only on moving.
                openNeighbours()
            }
        }
    }

    /**
     * The position to open at — an explicit jump target when one was passed in, the saved position
     * otherwise — clamped to what the document actually contains.
     *
     * Clamping matters when a book is re-imported at a different revision: a saved page 900 in a
     * document that now has 200 pages must open at page 200, not crash or show a blank page. The
     * offset comes back with it because it is what lets a *paginated* book reopen on the page the
     * reader left, rather than at the top of the chapter — the chapter is the same either way, and
     * the page inside it is not.
     */
    private suspend fun restoredPosition(opened: OpenDocument): Pair<Int, Int> {
        val locator = startLocator ?: restorePosition(currentBookId.value)?.locator ?: return 0 to 0
        val total = unitCountOf(opened)
        val index = when (locator) {
            is ReadingLocator.Paged -> locator.pageIndex
            is ReadingLocator.Reflowable -> locator.chapterIndex
        }
        val offset = (locator as? ReadingLocator.Reflowable)?.charOffset ?: 0
        return index.coerceIn(0, (total - 1).coerceAtLeast(0)) to offset.coerceAtLeast(0)
    }

    private fun unitCountOf(opened: OpenDocument): Int = when (opened) {
        is PagedDocument -> opened.pageCount
        is ReflowableDocument -> opened.chapterCount
        else -> 0
    }

    /** What [chapterContent] reads from a decoder in one pass. */
    private data class LoadedChapter(
        val chapter: Chapter,
        val parsed: ParsedChapter,
        val text: String,
    )

    /**
     * Renders a page of [bookId] at the requested size, using the cache where possible.
     *
     * The book is named by the caller rather than taken from the state, because the reader draws the
     * volumes either side of the open one as well: a page of the next book is a page of *that* book,
     * and asking the open one for it would render whatever page happens to share its number.
     *
     * Called from the UI with `produceState`, so it may be cancelled mid-render when the user
     * scrolls past — which is safe, because the lock is released by the cancellation and nothing
     * partial is cached. The lock is the book's own, so a page being prefetched for the next volume
     * never blocks the page in front of the reader.
     */
    suspend fun renderPage(bookId: Long, pageIndex: Int, widthPx: Int, heightPx: Int): PageRenderState {
        val segment = segments[bookId] ?: return PageRenderState.Loading
        if (widthPx <= 0 || heightPx <= 0) return PageRenderState.Loading

        val key = PageCache.Key(
            documentId = segment.book.uri,
            pageIndex = pageIndex,
            widthPx = widthPx,
            heightPx = heightPx,
            backgroundColorArgb = PAGE_BACKGROUND_ARGB,
        )
        pageCache[key]?.let { return PageRenderState.Ready(it) }

        val paged = segment.document as? PagedDocument ?: return PageRenderState.Loading

        // A page whose number is no longer in the document, or a render that cannot get the memory
        // it needs, must not take the reader down with it. Both are reachable by dragging the
        // progress bar quickly: the order underneath the pager is rebuilt as the volume either side
        // opens and closes, and a scrub across a comic queues decodes faster than the cache frees
        // them. The page is reported as still loading, or as an out-of-memory the reader can retry.
        return try {
            val result = segment.mutex.withLock {
                paged.renderPage(
                    PageRenderRequest(
                        pageIndex = pageIndex,
                        targetWidthPx = widthPx,
                        targetHeightPx = heightPx,
                        backgroundColorArgb = PAGE_BACKGROUND_ARGB,
                    ),
                )
            }

            when (result) {
                is AppResult.Failure -> PageRenderState.Failed(result.error)
                is AppResult.Success -> {
                    val image = result.data.toImageBitmap()
                    pageCache.put(key, image)
                    PageRenderState.Ready(image)
                }
            }
        } catch (outOfMemory: OutOfMemoryError) {
            PageRenderState.Failed(AppError.OutOfMemory)
        } catch (staleIndex: IndexOutOfBoundsException) {
            PageRenderState.Loading
        }
    }

    /**
     * The page's intrinsic size in [bookId], or `null` when it has no pages or will not report one.
     *
     * The reader needs the page's proportions *before* it can ask for pixels: a width-fitted page is
     * as tall as its own proportions make it, and an actual-size page is rendered at its own
     * dimensions. A document that cannot answer — a closed handle, a page whose entry will not
     * decode — reports `null`, and the reader falls back to fitting the viewport, which is what it
     * did before [ReaderUiState.settings]' fit mode existed.
     */
    suspend fun pageSize(bookId: Long, pageIndex: Int): PageSize? {
        val segment = segments[bookId] ?: return null
        val paged = segment.document as? PagedDocument ?: return null
        return withContext(dispatchers.io) {
            segment.mutex.withLock {
                runCatching { paged.pageSize(pageIndex) }
                    .getOrNull()
                    ?.takeIf { it.width > 0 && it.height > 0 }
            }
        }
    }

    /**
     * A parsed chapter of [bookId]: its title, its blocks, where its anchors are, and where its
     * blocks sit in the chapter text.
     *
     * The book is named for the same reason [renderPage]'s is: the reader lays out the neighbour's
     * chapters too, so that the turn across the seam lands on a page that already exists.
     *
     * All four are produced under one lock acquisition because they are derived from the same
     * chapter and must agree with each other — building them from separate reads would let a
     * document that changed underneath produce an anchor map that points into a different chapter's
     * text.
     */
    suspend fun chapterContent(bookId: Long, chapterIndex: Int, links: LinkStyling? = null): ChapterContent {
        val segment = segments[bookId] ?: return ChapterContent.Empty
        val reflowable = segment.document as? ReflowableDocument ?: return ChapterContent.Empty

        val loaded = segment.mutex.withLock {
            LoadedChapter(
                chapter = reflowable.chapter(chapterIndex),
                parsed = parseChapterHtml(reflowable.chapterHtml(chapterIndex), links),
                text = reflowable.chapterText(chapterIndex),
            )
        }

        return ChapterContent(
            title = loaded.chapter.title?.takeIf { it.isNotBlank() },
            blocks = loaded.parsed.blocks,
            anchorBlocks = loaded.parsed.anchorBlocks,
            offsets = buildChapterTextMap(loaded.parsed.blocks, loaded.text),
        )
    }

    /**
     * Decodes an image referenced by a chapter.
     *
     * Downsampled to roughly [targetWidthPx] before it is handed to the UI: an EPUB's illustrations
     * are often 2000px wide, and decoding a screenful of them at full size while scrolling is how a
     * reading app runs out of memory on a mid-range phone.
     *
     * Returns `null` — rather than throwing — for a missing or undecodable image, because a broken
     * illustration must not take down the chapter around it.
     */
    suspend fun chapterImage(bookId: Long, path: String, targetWidthPx: Int): ImageBitmap? {
        val segment = segments[bookId] ?: return null
        val reflowable = segment.document as? ReflowableDocument ?: return null
        val bytes = segment.mutex.withLock { reflowable.resource(path) } ?: return null

        return withContext(dispatchers.io) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return@runCatching null

                val options = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx)
                }
                android.graphics.BitmapFactory
                    .decodeByteArray(bytes, 0, bytes.size, options)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }

    /**
     * The speech bubble or panel under a point on the page, for the reader's double-tap zoom.
     *
     * The pixels are read back out of the page that is *already on screen* rather than kept in a
     * cache beside it. Holding a rendered page's raw `IntArray` alive would add up to 32 MB of
     * resident memory for as long as the reader is open — precisely what [PageCache] exists to
     * bound — and a pixel cache would also have to miss whenever the pager had pre-rendered a
     * neighbouring page instead. Paying one `getPixels` copy per double-tap, off the main thread,
     * costs nothing at all when the user is not zooming.
     *
     * Failing is a legitimate outcome, not an error: the caller falls back to the ordinary zoom, so
     * a bitmap that cannot be read (or that is not backed by an Android bitmap at all) simply means
     * this feature does not apply to it.
     */
    internal suspend fun bubbleRegionAt(image: ImageBitmap, xPx: Int, yPx: Int): BubbleRegion? {
        if (xPx !in 0 until image.width || yPx !in 0 until image.height) return null

        return withContext(dispatchers.default) {
            runCatching {
                val width = image.width
                val height = image.height
                val pixels = IntArray(width * height)
                image.asAndroidBitmap().getPixels(pixels, 0, width, 0, 0, width, height)
                findBubbleRegion(pixels, width, height, xPx, yPx)
            }.getOrNull()
        }
    }

    /** The largest power of two that keeps the image at least [targetWidth] wide. */
    private fun sampleSizeFor(width: Int, targetWidth: Int): Int {
        var sampleSize = 1
        var current = width
        while (current / 2 >= targetWidth) {
            current /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    /** The document's text at the reader's current position, used as a bookmark's excerpt. */
    private suspend fun excerptAt(locator: ReadingLocator): String? = when (val doc = document) {
        is PagedDocument -> (locator as? ReadingLocator.Paged)
            ?.let { doc.pageText(it.pageIndex) }
            ?.take(EXCERPT_LENGTH)
            ?.trim()

        is ReflowableDocument -> (locator as? ReadingLocator.Reflowable)?.let {
            doc.chapterText(it.chapterIndex).take(EXCERPT_LENGTH).trim()
        }

        else -> null
    }

    override fun onIntent(intent: ReaderIntent) {
        when (intent) {
            ReaderIntent.ToggleChrome -> setState { copy(isChromeVisible = !isChromeVisible) }
            is ReaderIntent.OpenPanel -> setState { copy(openPanel = intent.panel, isChromeVisible = true) }
            ReaderIntent.ClosePanel -> setState { copy(openPanel = null) }

            is ReaderIntent.PageChanged -> moveTo(intent.pageIndex)
            is ReaderIntent.ChapterChanged -> moveTo(intent.chapterIndex)
            is ReaderIntent.ReflowPositionChanged -> reportReflowPosition(intent)
            is ReaderIntent.JumpTo -> jumpTo(intent.locator)
            ReaderIntent.NextUnit -> moveTo(currentState.currentUnit + 1)
            ReaderIntent.PreviousUnit -> moveTo(currentState.currentUnit - 1)
            is ReaderIntent.EnteredBook -> enterBook(intent.bookId, intent.locator)
            is ReaderIntent.OpenNeighbour -> openNeighbour(intent.bookId)

            ReaderIntent.ToggleBookmark -> toggleBookmarkAtCurrentPosition()
            is ReaderIntent.DeleteBookmark -> launch {
                // The whole bookmark is captured before deleting, because undo re-adds it — an id
                // alone cannot bring back a label, a colour or an excerpt.
                val bookmark = currentState.bookmarks.firstOrNull { it.id == intent.bookmarkId }
                    ?: return@launch
                deleteBookmark(bookmark.id)
                sendEffect(ReaderEffect.ShowMessage(ReaderMessage.BookmarkDeleted(bookmark)))
            }

            is ReaderIntent.UndoDeleteBookmark -> launch {
                bookmarkRepository.addBookmark(intent.bookmark)
            }

            is ReaderIntent.SaveBookmark -> launch {
                bookmarkRepository.updateBookmark(intent.bookmark)
                sendEffect(ReaderEffect.ShowMessage(ReaderMessage.BookmarkSaved))
            }

            is ReaderIntent.FollowLink -> followLink(intent.href)
            ReaderIntent.ReturnFromLink -> returnFromLink()
            ReaderIntent.AnchorReached -> setState { copy(pendingAnchor = null) }

            is ReaderIntent.SearchQueryChanged -> setState { copy(searchQuery = intent.query) }
            ReaderIntent.SubmitSearch -> runSearch()

            ReaderIntent.ReadAloud -> launch { readAloud() }
            ReaderIntent.StopReadingAloud -> launch { sendEffect(ReaderEffect.StopSpeaking) }
            ReaderIntent.ShareQuote -> launch { shareQuote() }

            is ReaderIntent.SetThemeMode -> launch { updateSettings.setThemeMode(intent.mode) }
            is ReaderIntent.SetFont -> launch { updateSettings.setReaderFont(intent.font) }
            is ReaderIntent.SetFontScale -> launch { updateSettings.setFontScale(intent.scale) }
            is ReaderIntent.SetLineHeight -> launch { updateSettings.setLineHeightScale(intent.scale) }
            is ReaderIntent.SetMargins -> launch { updateSettings.setMarginScale(intent.scale) }
            is ReaderIntent.SetParagraphSpacing -> launch {
                updateSettings.setParagraphSpacingScale(intent.scale)
            }
            is ReaderIntent.SetFirstLineIndent -> launch {
                updateSettings.setFirstLineIndent(intent.enabled)
            }
            is ReaderIntent.SetTextAlignment -> launch {
                updateSettings.setTextAlignment(intent.alignment)
            }
            is ReaderIntent.SetPageFit -> launch { updateSettings.setPageFitMode(intent.mode) }
            is ReaderIntent.SetKeepScreenOn -> launch { updateSettings.setKeepScreenOn(intent.enabled) }
            is ReaderIntent.SetShowProgressIndicator -> launch {
                updateSettings.setShowProgressIndicator(intent.enabled)
            }
            is ReaderIntent.SetReaderPaper -> launch { updateSettings.setReaderPaper(intent.paper) }
            is ReaderIntent.SetLayout -> launch { updateSettings.setLayout(intent.layout) }
            is ReaderIntent.SetProgressScope -> launch { updateSettings.setProgressScope(intent.scope) }
            is ReaderIntent.SetTapToTurnPages -> launch { updateSettings.setTapToTurnPages(intent.enabled) }
            is ReaderIntent.SetReadingDirection -> launch {
                updateSettings.setReadingDirection(intent.direction)
            }
            is ReaderIntent.SetReverseTapZones -> launch {
                updateSettings.setReverseTapZones(intent.enabled)
            }
            is ReaderIntent.SetPageTurnEffect -> launch { updateSettings.setPageTurnEffect(intent.effect) }
            is ReaderIntent.SetHapticsEnabled -> launch { updateSettings.setHapticsEnabled(intent.enabled) }
            is ReaderIntent.SetBubbleZoom -> launch { updateSettings.setBubbleZoom(intent.enabled) }

            // The book's own length, from the one place that can measure it. Not persisted: every
            // number in it belongs to the layout it was measured at, so a stored one would be wrong
            // the moment the reader changed the font size or turned the phone.
            ReaderIntent.BookPageIndexCleared -> setState { copy(bookIndex = null) }
            is ReaderIntent.BookPageIndexReady -> setState { copy(bookIndex = intent.index) }

            ReaderIntent.RequestResetSettings -> launch {
                updateSettings.resetReaderDefaults()
                // Said out loud because most of what changed is behind the sheet that is still open:
                // without it the only evidence is the controls moving back on their own.
                sendEffect(ReaderEffect.ShowMessage(ReaderMessage.SettingsReset))
            }

            is ReaderIntent.PasswordSubmitted -> launch {
                setState { copy(isAwaitingPassword = false, lastPasswordWasWrong = false) }
                openDocument(intent.password)
            }

            ReaderIntent.PasswordDismissed -> launch { sendEffect(ReaderEffect.NavigateBack) }
            ReaderIntent.Retry -> launch { openDocument(password = null) }
        }
    }

    /**
     * Keeps the reader's sequence of neighbours current.
     *
     * Collected rather than resolved once when the document opens, because the library is live: a
     * volume imported, a book moved into or out of the folder while this one is being read changes
     * the answer, and the reader has to carry on into what is true when they reach the seam. It
     * follows [currentBookId], so crossing into a volume re-resolves the sequence *of that volume* —
     * which is how a reader works through a series rather than through two books.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSequenceOfBook() {
        launch {
            currentBookId
                .flatMapLatest { observeSequence(it) }
                .collect { sequence ->
                    setState { copy(sequence = sequence) }
                    openNeighbours()
                }
        }
    }

    /**
     * Opens the volume either side of the open one, before the reader reaches it.
     *
     * The point is the seam: a document opened on the frame the reader crosses into it is a stall in
     * the middle of the one gesture this exists to make continuous. The cost is a second native
     * handle while the reader is near an end of the book, which is why the trigger is proximity
     * rather than the open itself — see [PREFETCH_LEAD_UNITS] and [closeDetachedSegments], which
     * gives the two ends of the reader's position in a book its whole lifetime.
     */
    private fun openNeighbours() {
        val state = currentState
        val sequence = state.sequence ?: return

        // How far the reader is from each end of the open book, in the units it counts in: pages for
        // a PDF or a comic, chapters for a reflowed book. One expression for both because a book is
        // read forwards from wherever the reader is, and the last few units of either are the ones
        // that look ahead.
        val toEnd = (state.totalUnits - 1 - state.currentUnit).coerceAtLeast(0)
        val toStart = state.currentUnit.coerceAtLeast(0)

        sequence.next?.let { if (toEnd <= PREFETCH_LEAD_UNITS) openSegment(it, isNext = true) }
        sequence.previous?.let { if (toStart <= PREFETCH_LEAD_UNITS) openSegment(it, isNext = false) }
        closeDetachedSegments(toStart = toStart, toEnd = toEnd)
    }

    /**
     * Opens [book] and publishes it as the neighbour on one side.
     *
     * A book that cannot be carried on into is not an error the reader is told about: the seam
     * between the two books simply offers to open it instead of drawing it, which is what the reader
     * would have done anyway. Three ways that happens —
     *
     *  - it is protected by a password, which the reader cannot supply on its behalf;
     *  - its file will not open, or opens to nothing;
     *  - it is drawn by the other family of reader. A reflowed book is a column of chapters and a
     *    comic is a column of pages, and the reader is *one* of those at a time rather than a
     *    container for both: continuing into one from the other would mean a column that changes
     *    what it is halfway down, which no presentation here can draw.
     */
    private fun openSegment(book: Book, isNext: Boolean) {
        val id = book.id
        if (segments.containsKey(id) || !pendingOpens.add(id)) return

        // The family and the book being read *now*: a prefetch outlives the position it was started
        // from, and one that lands after the reader has crossed into another volume is a document
        // nobody is going to draw.
        val readingAtStart = currentBookId.value

        launch {
            val opened = withContext(dispatchers.io) { openBook(book, password = null) }
            pendingOpens.remove(id)

            val segment = when (opened) {
                is AppResult.Success -> ReaderSegment(
                    book = book,
                    unitCount = unitCountOf(opened.data),
                    isPageImages = opened.data is PagedDocument,
                )

                is AppResult.Failure -> null
            }

            val usable = segment != null &&
                segment.unitCount > 0 &&
                segment.isPageImages == currentState.isPageImages &&
                !isCleared &&
                currentBookId.value == readingAtStart

            if (!usable) {
                // Closed here rather than kept for later: a document nobody can draw is native
                // memory held for the lifetime of the reader.
                if (opened is AppResult.Success) {
                    val handle = opened.data
                    withContext(dispatchers.io) { handle.close() }
                }
                // Only a failure that is still *this* book's to report. One that landed after the
                // reader crossed into another volume says nothing about the neighbour, and marking
                // it unavailable would put a button on a seam the reader is about to continue
                // through.
                if (!isCleared && currentBookId.value == readingAtStart) {
                    setState { copy(unavailableNeighbours = unavailableNeighbours + id) }
                }
                return@launch
            }

            segments[id] = OpenSegment(book, (opened as AppResult.Success).data)
            setState {
                if (isNext) copy(nextSegment = segment) else copy(previousSegment = segment)
            }
        }
    }

    /**
     * Closes the documents the reader is nowhere near.
     *
     * One rule with two distances rather than two rules: a neighbour is *opened* within
     * [PREFETCH_LEAD_UNITS] of the seam and *kept* within [KEEP_LEAD_UNITS] of it, and the gap
     * between the two is what stops a reader sitting on the boundary from opening and closing the
     * same volume as they cross one page back and forth. Anything left over — the volume before the
     * one before this one, after the reader has crossed a seam — is closed by the same pass, because
     * what is kept is decided by the position rather than by what happened to be open.
     */
    private fun closeDetachedSegments(toStart: Int, toEnd: Int) {
        val state = currentState
        val keepPrevious = state.previousSegment?.book?.id?.takeIf { toStart <= KEEP_LEAD_UNITS }
        val keepNext = state.nextSegment?.book?.id?.takeIf { toEnd <= KEEP_LEAD_UNITS }

        val keep = setOfNotNull(currentBookId.value, keepPrevious, keepNext)
        segments.keys.filterNot { it in keep }.forEach { closeSegment(it) }

        // A neighbour dropped for being far away is dropped from the state too, or the reader would
        // keep drawing pages of a document that is no longer open. Reopening is one prefetch away,
        // and the gap between the two distances is what keeps that from happening on every turn.
        if (state.previousSegment != null && keepPrevious == null) {
            setState { copy(previousSegment = null) }
        }
        if (state.nextSegment != null && keepNext == null) {
            setState { copy(nextSegment = null) }
        }
    }

    /**
     * Closes one document and drops everything cached from it.
     *
     * The cache is keyed by the book's `uri`, so its pages go with it: a volume of a series is not
     * going to be asked for the same page again soon, and leaving them in the budget would evict the
     * pages of the book the reader *is* reading.
     */
    private fun closeSegment(bookId: Long) {
        val segment = segments.remove(bookId) ?: return
        pageCache.evict(segment.book.uri)
        // Closing touches native state, so it must not run on the main thread. A detached write is
        // the same trade `onCleared` makes: nothing is going to use this handle again.
        CoroutineScope(dispatchers.io).launch { segment.document.close() }
    }

    /**
     * Becomes [bookId], with the reader at [locator] in it.
     *
     * This is the seam being crossed: the volume that was open becomes the neighbour behind the
     * reader, the neighbour they have walked into becomes the open book, and the reader keeps the
     * page they were looking at — which is what makes the crossing a change of document rather than
     * a second reader being started. The screen is not told to navigate anywhere: the presentations
     * are already drawing this book's pages, and the only thing that changes is which of them the
     * state describes.
     */
    private fun enterBook(bookId: Long, locator: ReadingLocator) {
        if (bookId == currentBookId.value) return
        val segment = segments[bookId] ?: return
        val opened = segment.document
        val arrivingFromNext = currentState.nextSegment?.book?.id == bookId

        // The book being left, as the neighbour it becomes. Read before the state is overwritten,
        // because the next frame draws it from these three facts.
        val leavingSegment = currentState.let { state ->
            state.book?.let {
                ReaderSegment(
                    book = it,
                    unitCount = state.totalUnits,
                    isPageImages = state.isPageImages,
                )
            }
        }

        // Written before the state stops describing the book being left, and written directly rather
        // than through `scheduleProgressSave`: the position at the end of a volume is exactly the one
        // worth keeping, since it is what says the volume was finished.
        progressJob?.cancel()
        launch {
            saveCurrentProgress()

            val index = when (locator) {
                is ReadingLocator.Paged -> locator.pageIndex
                is ReadingLocator.Reflowable -> locator.chapterIndex
            }
            val offset = (locator as? ReadingLocator.Reflowable)?.charOffset ?: 0
            val total = unitCountOf(opened)

            currentBookId.value = bookId
            // The navigation argument too, so a process death in the middle of a series restores
            // the volume the reader is actually in rather than the one they started from.
            savedStateHandle[ARG_BOOK_ID] = bookId

            setState {
                copy(
                    book = segment.book,
                    error = null,
                    capabilities = opened.capabilities,
                    isPageImages = opened is PagedDocument,
                    totalUnits = total,
                    currentUnit = index.coerceIn(0, (total - 1).coerceAtLeast(0)),
                    reflowOffset = offset.coerceAtLeast(0),
                    reflowPage = 0,
                    reflowPageCount = 0,
                    // The measurement belonged to the book being left, and the paged view replaces it
                    // once it has laid the new one out.
                    bookIndex = null,
                    outline = opened.outline,
                    isRtlContent = opened.metadata.language?.startsWith("ar") ?: isRtlContent,
                    documentFont = null,
                    // Everything that was about the book being left.
                    bookmarks = emptyList(),
                    pendingAnchor = null,
                    linkBackStack = emptyList(),
                    // The volume just finished is behind the reader and is drawn above it; whatever
                    // was open beyond it belongs to a book two seams away, and the pass below closes
                    // it. The sequence is re-resolved for the new book by its own observer.
                    previousSegment = if (arrivingFromNext) leavingSegment else null,
                    nextSegment = if (arrivingFromNext) null else leavingSegment,
                    unavailableNeighbours = emptySet(),
                    sequence = null,
                )
            }
            refreshPositionLabel()
            saveCurrentProgress()

            if (opened is ReflowableDocument) {
                val font = fontLoader.load(opened)
                if (font != null) setState { copy(documentFont = font) }
            }

            // The book left behind is kept open while the reader is near the seam — that is what
            // makes scrolling back into it work — and closed by this same call once they are not.
            openNeighbours()
        }
    }

    /**
     * Opens a neighbouring book that could not be continued into, as a book of its own.
     *
     * The position in the book being left is written *before* the effect that leaves it, and written
     * directly rather than through [scheduleProgressSave]: the reader's entry is popped as soon as
     * the navigation happens, which clears this ViewModel and cancels everything it was still
     * waiting to do — including a save that was still inside the debounce.
     */
    private fun openNeighbour(bookId: Long) {
        progressJob?.cancel()
        launch {
            saveCurrentProgress()
            sendEffect(ReaderEffect.OpenBook(bookId))
        }
    }

    /**
     * Moves to [unit], clamped to the document, and to [offset] within it where one is known.
     *
     * Progress is saved through a debounce: dragging a page slider across a 900-page PDF would
     * otherwise issue a database write per page crossed.
     *
     * [offset] is what makes a jump land where it was aimed rather than at the beginning of a
     * chapter. Most callers have no better aim than the chapter — an outline entry names a chapter
     * and nothing finer — but a bookmark, a search hit and the progress bar all name a *position*,
     * and going through this with only the chapter would put the reader at the top of it every time.
     * The paged view is what turns the offset into a page: it is the only side that knows how the
     * chapter was laid out, and it finds the page the offset falls in.
     */
    private fun moveTo(unit: Int, offset: Int = 0) {
        val total = currentState.totalUnits
        val clamped = unit.coerceIn(0, (total - 1).coerceAtLeast(0))
        // Both have to match to be a no-op. A jump within the chapter the reader is already in is
        // still a move — it is how a search hit on this page is followed — and comparing the chapter
        // alone would swallow it.
        val alreadyThere = clamped == currentState.currentUnit && offset == currentState.reflowOffset
        if (alreadyThere && !currentState.isLoading) return

        setState {
            copy(
                currentUnit = clamped,
                // A chapter that has not been laid out yet has no pages. Carrying the previous
                // chapter's count across would put the page count in the toolbar inside a chapter the
                // reader has only just arrived in; the offset is the opposite case, and is the one
                // thing here that is known before the chapter is laid out.
                reflowPage = 0,
                reflowPageCount = 0,
                reflowOffset = offset,
            )
        }
        refreshPositionLabel()
        scheduleProgressSave()
        // Every move is a chance to be near an end of the book, which is where the volume either
        // side of it has to be open before the reader gets there.
        openNeighbours()
    }

    /**
     * Takes the paged view's word for where the reader is — chapter included.
     *
     * The chapter comes from the report rather than from the position slider, because a page turn
     * now crosses from one chapter into the next: the pager is the authority on which chapter the
     * reader is in, and it says so from the page it settled on, so the chapter and the page number
     * inside it always agree.
     *
     * Deliberately not [moveTo], which clears the page fields — correct for a jump *into* a chapter,
     * and it would erase the very position being reported here.
     *
     * The offset is stored, not just displayed, because it is what the position is saved as: a page
     * number means nothing once the font size changes, while the character the page began at is
     * still in the same place.
     */
    private fun reportReflowPosition(intent: ReaderIntent.ReflowPositionChanged) {
        val chapter = intent.chapterIndex.coerceIn(0, (currentState.totalUnits - 1).coerceAtLeast(0))
        val enteredChapter = chapter != currentState.currentUnit

        setState {
            copy(
                currentUnit = chapter,
                reflowPage = intent.pageIndex.coerceAtLeast(0),
                reflowPageCount = intent.pageCount.coerceAtLeast(0),
                reflowOffset = intent.offset.coerceAtLeast(0),
            )
        }
        if (enteredChapter) refreshPositionLabel()
        scheduleProgressSave()
        openNeighbours()
    }

    /**
     * Goes where a locator points.
     *
     * A reflowable locator names a chapter *and* a character in it, and both are used: the chapter is
     * where the reader goes and the offset is where in it they arrive. Dropping the offset — which is
     * what this did — meant every jump landed at the top of a chapter, so a bookmark opened its
     * chapter rather than the bookmarked passage, a search result opened the chapter it was found in
     * rather than the hit, and a hit inside the chapter already being read did not move at all.
     */
    private fun jumpTo(locator: ReadingLocator) {
        setState { copy(openPanel = null) }
        when (locator) {
            is ReadingLocator.Paged -> moveTo(locator.pageIndex)
            is ReadingLocator.Reflowable -> moveTo(locator.chapterIndex, locator.charOffset)
        }
    }

    private fun scheduleProgressSave() {
        progressJob?.cancel()
        progressJob = launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            saveCurrentProgress()
        }
    }

    private suspend fun saveCurrentProgress() {
        val state = currentState
        val locator = state.currentLocator ?: return
        val excerpt = excerptAt(locator)
        saveProgress(
            bookId = currentBookId.value,
            locator = locator,
            percent = percentOf(state, locator),
            excerpt = excerpt,
        )
    }

    /**
     * Where the reader is, as a percentage of the book.
     *
     * The number the bar is showing, and deliberately the same one: `ReaderUiState.progress` mirrors
     * this arithmetic the way it has always mirrored [ReadingProgressUseCase]'s, so the shelf cannot
     * report a position the reader was never at. Once the book has been measured its *pages* are
     * counted, which is [ReadingProgressUseCase.fromPage] and not the chapter's share — the two
     * differ, and a saved percentage computed from the chapter while the bar showed the page would be
     * exactly the drift the mirroring exists to prevent.
     */
    private fun percentOf(state: ReaderUiState, locator: ReadingLocator): Float {
        val page = state.bookPageNumber
        val total = state.bookPageCount
        if (page != null && total != null) return progressCalculator.fromPage(page - 1, total)
        return progressCalculator(locator, document ?: return 0f, state.chapterFraction)
    }

    /**
     * Follows a link the user tapped.
     *
     * The engine decides *where* it goes; this decides what the reader does about it. An external
     * target becomes an effect rather than an action, because opening a URL needs a platform
     * context the ViewModel deliberately has no access to.
     *
     * The current position is pushed onto a back stack first, which is what makes a footnote a
     * round trip instead of a one-way jump to the end of the book.
     */
    private fun followLink(href: String) {
        val open = primary ?: return
        val reflowable = open.document as? ReflowableDocument ?: return
        val fromChapter = currentState.currentUnit

        launch {
            val target = open.mutex.withLock { reflowable.resolveLink(fromChapter, href) }

            when (target) {
                null -> sendEffect(ReaderEffect.ShowMessage(ReaderMessage.LinkUnavailable))

                is LinkTarget.External -> sendEffect(ReaderEffect.OpenExternalUrl(target.url))

                is LinkTarget.Internal -> {
                    val chapterIndex = when (val locator = target.locator) {
                        is ReadingLocator.Paged -> locator.pageIndex
                        is ReadingLocator.Reflowable -> locator.chapterIndex
                    }
                    val origin = currentState.currentLocator
                    val clamped = chapterIndex.coerceIn(0, (currentState.totalUnits - 1).coerceAtLeast(0))

                    setState {
                        copy(
                            currentUnit = clamped,
                            pendingAnchor = target.anchor,
                            linkBackStack = if (origin != null) linkBackStack + origin else linkBackStack,
                        )
                    }
                    refreshPositionLabel()
                    scheduleProgressSave()
                }
            }
        }
    }

    /**
     * Returns to where the reader was before following a link.
     *
     * Restores the chapter *and* the offset within it, so a footnote is a round trip in the paged
     * reader rather than a one-way jump to the top of the page the note was on — the same chapter
     * included, which is where most notes are and where the offset is the only thing that moves. The
     * scroll reader ignores the offset: it restores the chapter and the reader finds their place on
     * it.
     *
     * The page number and its count are deliberately left as they are. They are chapter-relative, so
     * the paged view replaces them the moment it settles where this sends it — and zeroing them
     * instead would hide the page indicator until the reader turned a page, while saying nothing
     * truer in the meantime.
     */
    private fun returnFromLink() {
        val stack = currentState.linkBackStack
        val target = stack.lastOrNull() ?: return

        val chapterIndex = when (target) {
            is ReadingLocator.Paged -> target.pageIndex
            is ReadingLocator.Reflowable -> target.chapterIndex
        }
        val clamped = chapterIndex.coerceIn(0, (currentState.totalUnits - 1).coerceAtLeast(0))

        setState {
            copy(
                currentUnit = clamped,
                reflowOffset = (target as? ReadingLocator.Reflowable)?.charOffset ?: 0,
                linkBackStack = stack.dropLast(1),
                pendingAnchor = null,
            )
        }
        refreshPositionLabel()
        scheduleProgressSave()
    }

    private fun toggleBookmarkAtCurrentPosition() {
        val locator = currentState.currentLocator ?: return
        launch {
            val excerpt = excerptAt(locator)
            val label = currentState.positionLabel
            val added = toggleBookmark(
                bookId = currentBookId.value,
                locator = locator,
                label = label,
                excerpt = excerpt,
            )
            sendEffect(
                ReaderEffect.ShowMessage(
                    if (added) ReaderMessage.BookmarkAdded else ReaderMessage.BookmarkRemoved,
                ),
            )
        }
    }

    /**
     * Keeps the bookmark list live, for whichever book is open.
     *
     * Collected once rather than re-read after each mutation, so a bookmark added here and one added
     * elsewhere — or a highlight deleted from the details screen — both show up without the reader
     * having to guess when its copy went stale. It follows [currentBookId] for the same reason the
     * sequence does: the list belongs to the book being read, and crossing a seam changes which book
     * that is.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeBookmarks() {
        launch {
            currentBookId
                .flatMapLatest { bookmarkRepository.observeBookmarks(it) }
                .collect { bookmarks -> setState { copy(bookmarks = bookmarks) } }
        }
    }

    /**
     * Searches inside the document.
     *
     * A search that the format cannot support is reported rather than silently returning nothing,
     * because "no results" and "this file has no text layer" look identical to a user who has not
     * been told which one happened.
     */
    private fun runSearch() {
        val query = currentState.searchQuery.trim()
        val document = document
        if (query.isEmpty() || document == null) return

        if (!document.capabilities.canSearch) {
            launch { sendEffect(ReaderEffect.ShowMessage(ReaderMessage.SearchUnavailable)) }
            return
        }

        setState { copy(isSearching = true, searchResults = emptyList()) }
        launch {
            val hits = searchInDocument(document, query)
            setState { copy(isSearching = false, searchResults = hits) }
            if (hits.isEmpty()) {
                sendEffect(ReaderEffect.ShowMessage(ReaderMessage.NoSearchResults))
            }
        }
    }

    private fun refreshPositionLabel() {
        val state = currentState
        val label = when (val doc = document) {
            is PagedDocument -> (state.currentUnit + 1).toString()
            is ReflowableDocument -> doc.chapter(state.currentUnit).title
            else -> null
        }
        setState { copy(positionLabel = label) }
    }

    /**
     * Speaks the text from the reader's position to the end of the current unit.
     *
     * A reflowable book speaks its chapter *from the reader's own character offset*, so "read aloud"
     * carries on from where they are rather than restarting the chapter; a page-image document with a
     * text layer speaks the page. A comic has no text at all and says so, rather than appearing to
     * start and going silent — the same honesty the search action follows.
     *
     * The text is produced as an effect, never held in state: a chapter is large, and the platform
     * engine that speaks it belongs to the screen.
     */
    private suspend fun readAloud() {
        val open = document ?: return
        val text = when (open) {
            is ReflowableDocument -> open.chapterText(currentState.currentUnit)
                .drop(currentState.reflowOffset)

            is PagedDocument -> open.pageText(currentState.currentUnit).orEmpty()
            else -> ""
        }.trim()

        if (text.isBlank()) {
            sendEffect(ReaderEffect.ShowMessage(ReaderMessage.ReadAloudUnavailable))
        } else {
            sendEffect(ReaderEffect.Speak(text))
        }
    }

    /**
     * Shares a short quote from where the reader is.
     *
     * The quote is the text from the reader's own position, capped so a share never carries a whole
     * chapter — a share sheet is for a sentence or two — and prefixed with the book and the position,
     * because a quote with neither means nothing to whoever receives it.
     */
    private suspend fun shareQuote() {
        val book = currentState.book ?: return
        val open = document

        val excerpt = when (open) {
            is ReflowableDocument -> open.chapterText(currentState.currentUnit)
                .drop(currentState.reflowOffset)

            is PagedDocument -> open.pageText(currentState.currentUnit).orEmpty()
            else -> ""
        }.replace(SHARE_WHITESPACE, " ").trim().take(SHARE_EXCERPT_LIMIT)

        val text = buildString {
            append(book.title)
            book.author?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            currentState.positionLabel?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
            if (excerpt.isNotBlank()) append("\n\n«").append(excerpt).append("»")
        }
        sendEffect(ReaderEffect.ShareText(text))
    }

    /**
     * Releases every open document and everything cached from them.
     *
     * This runs when the reader leaves the back stack. Without it a pdfium document and its native
     * buffers would stay alive for the life of the process, and opening half a dozen books in a
     * session would exhaust memory — which is why the prefetched neighbours are closed here too
     * rather than only the book on screen.
     */
    override fun onCleared() {
        isCleared = true
        pageCache.clear()
        val open = segments.values.map { it.document }
        segments.clear()
        pendingOpens.clear()
        // Closing touches native state, so it must not run on the main thread — but `onCleared`
        // cannot suspend. A detached write to the IO dispatcher is the correct trade: the process
        // is not going to reuse these handles, and blocking the main thread here would be worse.
        CoroutineScope(dispatchers.io).launch { open.forEach { it.close() } }
        super.onCleared()
    }

    companion object {
        const val ARG_BOOK_ID = "bookId"

        /** Optional start position, encoded by [ReadingLocator.encoded]. Matches `Routes.LOCATOR_ARG`. */
        const val ARG_LOCATOR = "locator"

        /** 64 MB: enough for several comic pages at phone resolution, far short of an OOM. */
        private const val PAGE_CACHE_BYTES = 64 * 1024 * 1024

        /**
         * How near the end of a book a reader has to be for the volume beside it to be opened.
         *
         * In the book's own units, so it is three pages of a comic and three chapters of an EPUB —
         * deliberately the same distance, because what the distance buys is the same in both: enough
         * time for a decoder to be opened off the main thread before the seam arrives. Opening a
         * 900-page PDF is fast enough that one unit would do; parsing the chapters of an EPUB is
         * not, which is why this is not one.
         */
        private const val PREFETCH_LEAD_UNITS = 3

        /**
         * How far past its end a neighbour stays open, before it is closed again.
         *
         * Eight against [PREFETCH_LEAD_UNITS]'s three, and the gap between them is the whole point:
         * with one distance, a reader sitting on the boundary and turning one page back and forth
         * would open and close a document on every turn. Wide enough to cover a reader reading on
         * past the seam, and narrow enough that walking away from it gives the memory back.
         */
        private const val KEEP_LEAD_UNITS = 8

        /**
         * White, because a PDF or comic page *is* white paper.
         *
         * This is deliberately not derived from the reading theme. Inverting or tinting a rendered
         * page would change the artwork and, for a PDF, could render black text invisible on a
         * black background. A night-reading mode, if added, belongs on the chrome around the page
         * rather than baked into the pixels — which is also why the value is a named constant here
         * rather than a literal, so the decision is visible and can be varied per book later
         * without the cache serving a stale image (see `PageCache.Key`).
         */
        private const val PAGE_BACKGROUND_ARGB = 0xFFFFFFFF.toInt()

        private const val PROGRESS_SAVE_DEBOUNCE_MS = 600L
        private const val EXCERPT_LENGTH = 160

        /** How long a shared quote may be, in characters. A share sheet is for a sentence or two. */
        private const val SHARE_EXCERPT_LIMIT = 280
    }
}

/** Collapses runs of whitespace so a shared quote reads as prose rather than as laid-out text. */
private val SHARE_WHITESPACE = Regex("\\s+")
