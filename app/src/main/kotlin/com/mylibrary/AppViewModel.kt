package com.mylibrary

import android.content.Intent
import androidx.compose.runtime.Immutable
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.usecase.ImportBooksUseCase
import com.mylibrary.core.domain.usecase.ImportCandidate
import com.mylibrary.core.domain.usecase.ObserveSettingsUseCase
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch

/** App-wide state: the settings that decide the theme and the language. */
@Immutable
data class AppUiState(
    val settings: ReaderSettings = ReaderSettings.Default,
    /**
     * True once the persisted settings have been read.
     *
     * The UI waits for this before its first frame because painting with the default theme and then
     * switching to the user's dark theme is a visible flash on every cold start — and with a dark
     * theme the flash is a white screen, which is the worst version of it.
     */
    val isReady: Boolean = false,

    /**
     * The book the rest of the system asked MyLibrary to open, awaiting navigation.
     *
     * Set when a VIEW or SEND intent lands a file in the library; the navigation host consumes it
     * by opening the reader and clearing it, so the same intent is never followed twice across a
     * configuration change. `null` means nothing is pending.
     */
    val externalBookId: Long? = null,
)

/** Every action the app-level layer can be asked to perform. */
sealed interface AppIntent {

    /**
     * A file another app handed over — opened from a file manager or shared into MyLibrary.
     * [candidate] is already resolved from the intent's URI by the Activity.
     */
    data class OpenExternalFile(val candidate: ImportCandidate) : AppIntent

    /** The pending external book has been navigated to and can be forgotten. */
    data object ExternalBookConsumed : AppIntent
}

/** One-shot app-level events. */
sealed interface AppEffect {

    /**
     * A file handed over by another app could not be added: it is either not a format MyLibrary
     * reads or its provider would not open. The UI reports it rather than swallowing the tap.
     */
    data object ExternalImportFailed : AppEffect
}

/** Holds the settings every screen is themed and translated by, and opens books sent from outside. */
@HiltViewModel
class AppViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val importBooks: ImportBooksUseCase,
    private val libraryRepository: LibraryRepository,
) : MviViewModel<AppUiState, AppIntent, AppEffect>(AppUiState()) {

    init {
        launch {
            observeSettings()
                // If settings cannot be read the app still has to start; defaults are a far better
                // outcome than a blank window.
                .catch { setState { copy(isReady = true) } }
                .collect { settings -> setState { copy(settings = settings, isReady = true) } }
        }
    }

    override fun onIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.OpenExternalFile -> launch { openExternal(intent.candidate) }
            AppIntent.ExternalBookConsumed -> setState { copy(externalBookId = null) }
        }
    }

    /**
     * Imports a file the system handed over and marks it for the reader to open.
     *
     * Import, not "open once": the book joins the shelf like any other, which is what a reader
     * double-tapping a PDF in a file manager expects to happen. A file already in the library is
     * not added twice — the import summary's dedup means the existing row is found and opened, so
     * re-opening a book from a file manager re-opens the shelf's copy.
     */
    private suspend fun openExternal(candidate: ImportCandidate) {
        importBooks(listOf(candidate))
        val bookId = libraryRepository.findBookByUri(candidate.uri)?.id
        if (bookId != null) {
            setState { copy(externalBookId = bookId) }
        } else {
            sendEffect(AppEffect.ExternalImportFailed)
        }
    }
}
