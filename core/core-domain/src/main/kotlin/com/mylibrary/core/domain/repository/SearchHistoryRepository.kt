package com.mylibrary.core.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The queries the reader has explicitly submitted, most recent first.
 *
 * Persisted rather than session-scoped. The search screen used to keep this list in its ViewModel,
 * which meant a reader who found a useful query lost it the moment they left the tab — and a history
 * that disappears is a history nobody learns to rely on. Storing it costs one DataStore key.
 *
 * [save] takes the whole list rather than an "add" so that the trimming and de-duplication rules
 * stay in one place: the search feature owns what a useful history looks like (`addRecentQuery`),
 * and this interface only owns remembering it.
 */
interface SearchHistoryRepository {

    /** The saved queries, most recent first. Emits on every change. */
    val recentQueries: Flow<List<String>>

    /** Replaces the stored history with [queries]. */
    suspend fun save(queries: List<String>)
}
