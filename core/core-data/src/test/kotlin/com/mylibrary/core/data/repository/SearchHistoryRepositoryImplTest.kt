package com.mylibrary.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The search history store.
 *
 * The point of these tests is the part the search feature cannot check for itself: that a history
 * written in one session is still there in the next. The trimming rules (`addRecentQuery`) live and
 * are tested in `:feature:feature-search`; this only has to prove it remembers what it is handed.
 */
class SearchHistoryRepositoryImplTest {

    private val directory = File(System.getProperty("java.io.tmpdir"), "search-history-test-${hashCode()}")

    /**
     * A store over a real file, on the test's own background scope — the same shape as
     * `SettingsDataStoreTest`, and for the same reason: DataStore keeps a coroutine alive for as
     * long as it exists, and on the foreground scope `runTest` would wait for it forever.
     */
    private fun TestScope.repository(): SearchHistoryRepositoryImpl {
        val file = File(directory, "history.preferences_pb").also { it.parentFile?.mkdirs() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        return SearchHistoryRepositoryImpl(store)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a fresh store has no history`() = runTest {
        assertEquals(emptyList<String>(), repository().recentQueries.first())
    }

    @Test
    fun `saved queries come back most recent first`() = runTest {
        val repository = repository()
        repository.save(listOf("dune", "herbert", "arrakis"))

        assertEquals(listOf("dune", "herbert", "arrakis"), repository.recentQueries.first())
    }

    @Test
    fun `saving an empty list forgets everything`() = runTest {
        val repository = repository()
        repository.save(listOf("dune"))
        repository.save(emptyList())

        assertEquals(emptyList<String>(), repository.recentQueries.first())
    }

    /**
     * The list is stored as one delimited string, so a query containing the characters a naive
     * delimiter would use — a comma, a space, a colon — must not be cut in half on the way back.
     */
    @Test
    fun `a query with spaces and punctuation survives the round trip`() = runTest {
        val repository = repository()
        val queries = listOf("محمد عبد الله", "Dune: Book 1", "a,b;c")

        repository.save(queries)

        assertEquals(queries, repository.recentQueries.first())
    }

    /** A stored value that is empty or blank decodes to no history rather than to a blank entry. */
    @Test
    fun `blank stored values decode to nothing`() {
        assertEquals(emptyList<String>(), decodeQueries(null))
        assertEquals(emptyList<String>(), decodeQueries(""))
    }
}
