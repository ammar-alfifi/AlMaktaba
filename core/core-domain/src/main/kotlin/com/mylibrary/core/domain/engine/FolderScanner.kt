package com.mylibrary.core.domain.engine

/**
 * One file found inside a folder the user granted access to.
 *
 * Deliberately the same shape as the file picker's `ImportCandidate` minus the folder: what the
 * scanner knows about a file is what the picker knows about a file, and the importer should not have
 * to care which of the two produced it.
 */
data class FolderEntry(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    /** The entry's path relative to the folder, for reporting and for stable ordering. */
    val relativePath: String,
)

/** What enumerating a folder found, including what it could not do. */
data class FolderScan(
    val entries: List<FolderEntry>,
    /** True when the scan stopped at [FolderScanner.MAX_ENTRIES] rather than running out of files. */
    val truncated: Boolean = false,
    /** Directories that could not be listed — a revoked grant, or a provider that failed. */
    val failedDirectories: Int = 0,
)

/**
 * Finds the documents inside a folder the user granted access to.
 *
 * This is the same seam as [DocumentSource], one level up: `DocumentSource` says *read these bytes*,
 * and this says *tell me what is in here*. It exists so that `:core:core-domain` — a plain JVM module
 * with no Android dependency — can hold the import rules without ever naming a `Uri`, a
 * `DocumentFile` or a content resolver. The one implementation lives in `:core:core-data`, over the
 * Storage Access Framework.
 *
 * The grant's lifecycle is part of this contract rather than the UI's because a folder is a *tree*:
 * its permission is taken once, kept across restarts, and released when the user removes the folder.
 * Splitting that across two layers is how an app ends up accumulating grants for folders it no
 * longer knows about, which Android charges for.
 */
interface FolderScanner {

    /** Takes a persistable read grant for [treeUri]. False when the provider offers none. */
    fun takePermission(treeUri: String): Boolean

    /** Releases the grant for [treeUri], when the folder is removed from the library. */
    fun releasePermission(treeUri: String)

    /** True when a persisted grant for [treeUri] is still held. */
    fun hasPermission(treeUri: String): Boolean

    /** The folder's own name, or `null` when the provider cannot say. */
    fun displayName(treeUri: String): String?

    /** Enumerates every supported document in the tree, depth first. */
    suspend fun scan(treeUri: String): FolderScan

    companion object {
        /**
         * The most documents one folder may contribute.
         *
         * A folder of twenty thousand files is a library dump rather than a series, and importing one
         * would take minutes while inserting a database row per file. The cap is reported rather than
         * applied quietly — a reader who is told the scan stopped can point at a smaller folder.
         */
        const val MAX_ENTRIES = 2000

        /**
         * How deep the walk goes.
         *
         * Deep enough for the way series are actually filed — `Author/Series/Volume/` — and shallow
         * enough that a symlinked or misreported tree cannot turn into a cycle.
         */
        const val MAX_DEPTH = 8
    }
}
