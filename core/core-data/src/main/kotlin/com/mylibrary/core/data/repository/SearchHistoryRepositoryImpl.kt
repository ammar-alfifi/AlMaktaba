package com.mylibrary.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mylibrary.core.domain.repository.SearchHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Search history over the same preferences DataStore the settings use.
 *
 * It shares the file rather than opening a second one: the history is a handful of short strings, a
 * second DataStore would mean a second file, a second corruption handler and a second write scope
 * for no benefit, and the settings store's own `update` never touches this key so the two cannot
 * clobber each other.
 *
 * The list is stored as one delimited string rather than a `stringSetPreferencesKey`, because a set
 * has no order and "most recent first" is the whole point of a history. The separator is a NUL
 * character, which a search field cannot produce.
 */
@Singleton
class SearchHistoryRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SearchHistoryRepository {

    override val recentQueries: Flow<List<String>> = dataStore.data
        // The same failure the settings store tolerates: a corrupted preferences file throws on
        // every read. Losing the history is a survivable outcome; crashing on launch is not.
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> decodeQueries(preferences[KEY]) }

    override suspend fun save(queries: List<String>) {
        dataStore.edit { preferences -> preferences[KEY] = encodeQueries(queries) }
    }

    private companion object {
        val KEY = stringPreferencesKey("search_recent_queries")
    }
}

/** Joins queries into their stored form. An empty list stores the empty string. */
internal fun encodeQueries(queries: List<String>): String = queries.joinToString(SEPARATOR)

/** Splits a stored value back into queries, dropping the blanks a malformed value could produce. */
internal fun decodeQueries(raw: String?): List<String> =
    raw?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

/**
 * The delimiter between stored queries.
 *
 * NUL, because it cannot be typed into the search field, so a query can never contain it and split
 * on it can never cut a query in half. A newline or a comma would.
 */
private const val SEPARATOR = "\u0000"
