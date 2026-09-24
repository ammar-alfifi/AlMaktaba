package com.mylibrary.feature.library

import com.mylibrary.core.domain.model.Folder
import com.mylibrary.core.domain.model.FolderSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the set of folders marked unavailable.
 *
 * This exists because the set was once inverted: `filterNot` in place of `filter` marked every
 * *available* folder as unavailable, and the shelf believed it on every launch. The emulator found
 * it — a folder with a live persistable grant wearing the red badge — and this pins the fix.
 */
class UnavailableFolderIdsTest {

    private fun summary(id: Long) = FolderSummary(
        folder = Folder(id = id, uri = "content://tree/$id", name = "folder $id"),
        bookCount = 0,
    )

    @Test
    fun `only the folders without permission are marked`() {
        val folders = listOf(summary(1L), summary(2L), summary(3L))

        assertEquals(
            setOf(2L),
            unavailableFolderIds(folders) { summary -> summary.folder.id == 2L },
        )
    }

    @Test
    fun `an empty shelf marks nothing`() {
        assertEquals(emptySet<Long>(), unavailableFolderIds(emptyList()) { true })
    }

    @Test
    fun `when every grant is held nothing is marked`() {
        val folders = listOf(summary(1L), summary(2L))

        assertEquals(emptySet<Long>(), unavailableFolderIds(folders) { false })
    }
}
