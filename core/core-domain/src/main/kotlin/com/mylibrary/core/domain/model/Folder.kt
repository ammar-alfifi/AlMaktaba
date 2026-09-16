package com.mylibrary.core.domain.model

/**
 * A folder on the device that the user has given MyLibrary access to.
 *
 * Not a folder *in* the library: nothing is copied, nothing is moved, and the books keep living
 * wherever they were. This is a record that the user pointed at a directory once — a series of
 * novels, a run of a manga — so the library can show that run as one shelf and can go back and pick
 * up whatever was added to it since.
 *
 * [uri] is the Storage Access Framework *tree* URI, and it is the folder's identity: picking the same
 * directory twice must find the same row rather than create a second one, exactly as a book's own
 * URI identifies it.
 */
data class Folder(
    val id: Long = NO_ID,
    val uri: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    /** When the folder was last enumerated, or `null` if it never has been. */
    val lastScannedAt: Long? = null,
) {
    companion object {
        const val NO_ID: Long = 0L
    }
}

/**
 * A folder with how many of its books are in the library.
 *
 * The count is what makes the folder chips worth having: a shelf that says "12" is a series the user
 * recognises, where a bare name is one they have to open to identify.
 */
data class FolderSummary(
    val folder: Folder,
    val bookCount: Int,
)
