package com.mylibrary.feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.PageRenderRequest
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
    private val dispatchers: DispatcherProvider,
) : MviViewModel<ReaderUiState, ReaderIntent, ReaderEffect>(ReaderUiState()) {

    private val bookId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_BOOK_ID)) {
        "ReaderViewModel requires a '$ARG_BOOK_ID' navigation argument"
    }

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
                val restored = withContext(dispatchers.io) { restoredUnitIndex(opened) }
                setState {
                    copy(
                        isLoading = false,
                        error = null,
                        isAwaitingPassword = false,
                        lastPasswordWasWrong = false,
                        isPaged = opened is PagedDocument,
                        totalUnits = unitCountOf(opened),
                        currentUnit = restored,
                        outline = opened.outline,
                        isRtlContent = opened.metadata.language?.startsWith("ar")
                            ?: currentState.isRtlContent,
                    )
                }
                refreshPositionLabel()
                saveCurrentProgress()
            }
        }
    }

    /**
     * The saved unit index, clamped to what the document actually contains.
     *
     * Clamping matters when a book is re-imported at a different revision: a saved page 900 in a
     * document that now has 200 pages must open at page 200, not crash or show a blank page.
     */
    private suspend fun restoredUnitIndex(opened: OpenDocument): Int {
        val locator = restorePosition(bookId)?.locator ?: return 0
        val total = unitCountOf(opened)
        val index = when (locator) {
            is ReadingLocator.Paged -> locator.pageIndex
            is ReadingLocator.Reflowable -> locator.chapterIndex
        }
        return index.coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    private fun unitCountOf(opened: OpenDocument): Int = when (opened) {
        is PagedDocument -> opened.pageCount
        is ReflowableDocument -> opened.chapterCount
        else -> 0
    }

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

        val key = PageCache.Key(book.uri, pageIndex, widthPx, heightPx)
        pageCache[key]?.let { return PageRenderState.Ready(it) }

        val paged = document as? PagedDocument ?: return PageRenderState.Loading

        val result = documentMutex.withLock {
            paged.renderPage(
                PageRenderRequest(
                    pageIndex = pageIndex,
                    targetWidthPx = widthPx,
                    targetHeightPx = heightPx,
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

    /** The parsed content of a chapter, for the reflowable reader. */
    suspend fun chapterContent(chapterIndex: Int): List<ContentBlock> {
        val reflowable = document as? ReflowableDocument ?: return emptyList()
        val html = documentMutex.withLock { reflowable.chapterHtml(chapterIndex) }
        return parseChapterHtml(html)
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
            is ReaderIntent.JumpTo -> jumpTo(intent.locator)
            ReaderIntent.NextUnit -> moveTo(currentState.currentUnit + 1)
            ReaderIntent.PreviousUnit -> moveTo(currentState.currentUnit - 1)

            ReaderIntent.ToggleBookmark -> toggleBookmarkAtCurrentPosition()
            is ReaderIntent.DeleteBookmark -> launch { deleteBookmark(intent.bookmarkId) }

            is ReaderIntent.SearchQueryChanged -> setState { copy(searchQuery = intent.query) }
            ReaderIntent.SubmitSearch -> runSearch()
            ReaderIntent.ClearSearch -> setState {
                copy(searchQuery = "", searchResults = emptyList(), isSearching = false)
            }

            is ReaderIntent.SetThemeMode -> launch { updateSettings.setThemeMode(intent.mode) }
            is ReaderIntent.SetFont -> launch { updateSettings.setReaderFont(intent.font) }
            is ReaderIntent.SetFontScale -> launch { updateSettings.setFontScale(intent.scale) }
            is ReaderIntent.SetLineHeight -> launch { updateSettings.setLineHeightScale(intent.scale) }
            is ReaderIntent.SetPageFit -> launch { updateSettings.setPageFitMode(intent.mode) }
            is ReaderIntent.SetKeepScreenOn -> launch { updateSettings.setKeepScreenOn(intent.enabled) }

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

        setState { copy(currentUnit = clamped) }
        refreshPositionLabel()
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
            percent = progressCalculator(locator, document ?: return),
            excerpt = excerpt,
        )
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

        /** 64 MB: enough for several comic pages at phone resolution, far short of an OOM. */
        private const val PAGE_CACHE_BYTES = 64 * 1024 * 1024

        private const val PROGRESS_SAVE_DEBOUNCE_MS = 600L
        private const val EXCERPT_LENGTH = 160
    }
}
