package com.mylibrary.core.domain.repository

import com.mylibrary.core.domain.model.ReaderSettings
import kotlinx.coroutines.flow.Flow

/**
 * User preferences, backed by DataStore.
 *
 * [update] takes a transform rather than a whole new value so that two screens changing different
 * settings at the same time cannot clobber each other's edit.
 */
interface SettingsRepository {

    val settings: Flow<ReaderSettings>

    /** A one-shot read, for callers that cannot await a `Flow` (e.g. an Activity's `onCreate`). */
    suspend fun currentSettings(): ReaderSettings

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings)
}
