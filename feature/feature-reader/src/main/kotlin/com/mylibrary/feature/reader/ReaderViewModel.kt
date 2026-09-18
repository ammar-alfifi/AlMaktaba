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
import com.mylibrary.core.domain.model.Chapter
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.repository.BookmarkRepository
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.usecase.DeleteBookmarkUseCase
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Three things here are worth reading before changing anything:
 *
 *  **1. The document is opened once and held.** Decoders hold native state — a pdfium document, an
 *  open archive — so the document is opened on the IO dispatcher, kept in a field, and closed in
 *  [onCleared]. Re-opening per page would be catastrophic for a PDF.
 *
 *  **2. All document access is serialised.** pdfium is not thread-safe on a single document, and
 *  opening a second stream into a RAR mid-extract is not either. A [Mutex] around every call into
 *  the document is cheaper than reasoning about which decoders happen to tolerate concurrency.
 *
 *  **3. Rendered pages live in [PageCache], not in [ReaderUiState].** Putting bitmaps in the state
 *  object would make every state comparison walk megabytes of pixels, and would make `StateFlow`
 *  conflation compare images by identity on every recomposition. The state holds *where* the reader
 *  is; the cache holds the pixels.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
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
    private val fontLoader: DocumentFontLoader,
    private val dispatchers: DispatcherProvider,
) : MviViewModel<ReaderUiState, ReaderIntent, ReaderEffect>(ReaderUiState()) {

    private val bookId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_BOOK_ID)) {
        "ReaderViewModel requires a '$ARG_BOOK_ID' navigation argument"
    }

    /**
     * A place to open at, when the caller named one — a search hit, a bookmark tap.
     *
     * When present it wins over the saved reading position: the reader asked for *this* spot, and
     * landing where they last were would make the request a lie. Absent, the ordinary case, means
     * the saved position decides. Parsed once here rather than in [openDocument] so a malformed
     * argument fails the same way every launch does — to `null` — and the saved position takes over.
     */
    private val startLocator: ReadingLocator? =
        savedStateHandle.get<String>(ARG_LOCATOR)?.let(ReadingLocator::parse)

    private val documentMutex = Mutex()
    private var document: OpenDocument? = null

    /**
     * Rendered pages, bounded in bytes rather than in page count — see [PageCache] for why.
     */
    private val pageCache = PageCache(maxBytes = PAGE_CACHE_BYTES)

    /** Debounces progress writes so that a fast scroll does not write on every frame. */
    private var progressJob: Job? = null

    init {
        collectSettings()
        observeBookmarks()
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
            val book = withContext(dispatchers.io) { libraryRepository.getBook(bookId) }
            if (book == null) {
                setState { copy(isLoading = false, error = AppError.FileAccess("Book $bookId is not in the library")) }
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
                document = opened
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
        val locator = startLocator ?: restorePosition(bookId)?.locator ?: return 0 to 0
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
     * Renders a page at the requested size, using the cache where possible.
     *
     * Called from the UI with `produceState`, so it may be cancelled mid-render when the user
     * scrolls past — which is safe, because the mutex is released by the cancellation and nothing
     * partial is cached.
     */
    suspend fun renderPage(pageIndex: Int, widthPx: Int, heightPx: Int): PageRenderState {
        val book = currentState.book ?: return PageRenderState.Loading
        if (widthPx <= 0 || heightPx <= 0) return PageRenderState.Loading

        val key = PageCache.Key(
            documentId = book.uri,
            pageIndex = pageIndex,
            widthPx = widthPx,
            heightPx = heightPx,
            backgroundColorArgb = PAGE_BACKGROUND_ARGB,
        )
        pageCache[key]?.let { return PageRenderState.Ready(it) }

        val paged = document as? PagedDocument ?: return PageRenderState.Loading

        val result = documentMutex.withLock {
            paged.renderPage(
                PageRenderRequest(
                    pageIndex = pageIndex,
                    targetWidthPx = widthPx,
                    targetHeightPx = heightPx,
                    backgroundColorArgb = PAGE_BACKGROUND_ARGB,
                ),
            )
        }

        return when (result) {
            is AppResult.Failure -> PageRenderState.Failed(result.error)
            is AppResult.Success -> {
                val image = result.data.toImageBitmap()
                pageCache.put(key, image)
                PageRenderState.Ready(image)
            }
        }
    }

    /**
     * The page's intrinsic size, or `null` when the document has no pages or will not report one.
     *
     * The reader needs the page's proportions *before* it can ask for pixels: a width-fitted page is
     * as tall as its own proportions make it, and an actual-size page is rendered at its own
     * dimensions. A document that cannot answer — a closed handle, a page whose entry will not
     * decode — reports `null`, and the reader falls back to fitting the viewport, which is what it
     * did before [ReaderUiState.settings]' fit mode existed.
     */
    suspend fun pageSize(pageIndex: Int): PageSize? {
        val paged = document as? PagedDocument ?: return null
        return withContext(dispatchers.io) {
            documentMutex.withLock {
                runCatching { paged.pageSize(pageIndex) }
                    .getOrNull()
                    ?.takeIf { it.width > 0 && it.height > 0 }
            }
        }
    }

    /**
     * A parsed chapter: its title, its blocks, where its anchors are, and where its blocks sit in
     * the chapter text.
     *
     * All four are produced under one lock acquisition because they are derived from the same
     * chapter and must agree with each other — building them from separate reads would let a
     * document that changed underneath produce an anchor map that points into a different chapter's
     * text.
     */
    suspend fun chapterContent(chapterIndex: Int, links: LinkStyling? = null): ChapterContent {
        val reflowable = document as? ReflowableDocument ?: return ChapterContent.Empty

        val loaded = documentMutex.withLock {
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
    suspend fun chapterImage(path: String, targetWidthPx: Int): ImageBitmap? {
        val reflowable = document as? ReflowableDocument ?: return null
        val bytes = documentMutex.withLock { reflowable.resource(path) } ?: return null

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

            is ReaderIntent.FollowLink -> followLink(intent.href)
            ReaderIntent.ReturnFromLink -> returnFromLink()
            ReaderIntent.AnchorReached -> setState { copy(pendingAnchor = null) }

            is ReaderIntent.SearchQueryChanged -> setState { copy(searchQuery = intent.query) }
            ReaderIntent.SubmitSearch -> runSearch()

            is ReaderIntent.SetThemeMode -> launch { updateSettings.setThemeMode(intent.mode) }
            is ReaderIntent.SetFont -> launch { updateSettings.setReaderFont(intent.font) }
            is ReaderIntent.SetFontScale -> launch { updateSettings.setFontScale(intent.scale) }
            is ReaderIntent.SetLineHeight -> launch { updateSettings.setLineHeightScale(intent.scale) }
            is ReaderIntent.SetPageFit -> launch { updateSettings.setPageFitMode(intent.mode) }
            is ReaderIntent.SetKeepScreenOn -> launch { updateSettings.setKeepScreenOn(intent.enabled) }
            is ReaderIntent.SetShowProgressIndicator -> launch {
                updateSettings.setShowProgressIndicator(intent.enabled)
            }
            is ReaderIntent.SetLayout -> launch { updateSettings.setLayout(intent.layout) }
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
     * Moves to [unit], clamped to the document.
     *
     * Progress is saved through a debounce: dragging a page slider across a 900-page PDF would
     * otherwise issue a database write per page crossed.
     */
    private fun moveTo(unit: Int) {
        val total = currentState.totalUnits
        val clamped = unit.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (clamped == currentState.currentUnit && !currentState.isLoading) return

        setState {
            copy(
                currentUnit = clamped,
                // A chapter that has not been laid out yet has no pages and no offset. Carrying the
                // previous chapter's across would put the reader's saved position — and the page
                // count in the toolbar — inside a chapter they have only just arrived in.
                reflowPage = 0,
                reflowPageCount = 0,
                reflowOffset = 0,
            )
        }
        refreshPositionLabel()
        scheduleProgressSave()
    }

    /**
     * Takes the paged view's word for where the reader is.
     *
     * The offset is stored, not just displayed, because it is what the position is saved as: a page
     * number means nothing once the font size changes, while the character the page began at is
     * still in the same place.
     */
    private fun reportReflowPosition(intent: ReaderIntent.ReflowPositionChanged) {
        setState {
            copy(
                reflowPage = intent.pageIndex.coerceAtLeast(0),
                reflowPageCount = intent.pageCount.coerceAtLeast(0),
                reflowOffset = intent.offset.coerceAtLeast(0),
            )
        }
        scheduleProgressSave()
    }

    private fun jumpTo(locator: ReadingLocator) {
        val index = when (locator) {
            is ReadingLocator.Paged -> locator.pageIndex
            is ReadingLocator.Reflowable -> locator.chapterIndex
        }
        setState { copy(openPanel = null) }
        moveTo(index)
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
            bookId = bookId,
            locator = locator,
            percent = progressCalculator(locator, document ?: return, state.chapterFraction),
            excerpt = excerpt,
        )
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
        val reflowable = document as? ReflowableDocument ?: return
        val fromChapter = currentState.currentUnit

        launch {
            val target = documentMutex.withLock { reflowable.resolveLink(fromChapter, href) }

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
     * Restores the chapter, not the exact scroll position within it: the back stack holds a
     * [ReadingLocator] whose offset is not yet meaningful in scroll mode. Once pagination lands, the
     * stored offset will place the reader back on the exact page they left.
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
                bookId = bookId,
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
     * Keeps the bookmark list live.
     *
     * Collected once rather than re-read after each mutation, so a bookmark added here and one added
     * elsewhere — or a highlight deleted from the details screen — both show up without the reader
     * having to guess when its copy went stale.
     */
    private fun observeBookmarks() {
        launch {
            bookmarkRepository.observeBookmarks(bookId).collect { bookmarks ->
                setState { copy(bookmarks = bookmarks) }
            }
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
     * Releases the document and everything cached from it.
     *
     * This runs when the reader leaves the back stack. Without it a pdfium document and its native
     * buffers would stay alive for the life of the process, and opening half a dozen books in a
     * session would exhaust memory.
     */
    override fun onCleared() {
        pageCache.clear()
        val open = document
        document = null
        // Closing touches native state, so it must not run on the main thread — but `onCleared`
        // cannot suspend. A detached write to the IO dispatcher is the correct trade: the process
        // is not going to reuse this handle, and blocking the main thread here would be worse.
        kotlinx.coroutines.CoroutineScope(dispatchers.io).launch { open?.close() }
        super.onCleared()
    }

    companion object {
        const val ARG_BOOK_ID = "bookId"

        /** Optional start position, encoded by [ReadingLocator.encoded]. Matches `Routes.LOCATOR_ARG`. */
        const val ARG_LOCATOR = "locator"

        /** 64 MB: enough for several comic pages at phone resolution, far short of an OOM. */
        private const val PAGE_CACHE_BYTES = 64 * 1024 * 1024

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
    }
}
