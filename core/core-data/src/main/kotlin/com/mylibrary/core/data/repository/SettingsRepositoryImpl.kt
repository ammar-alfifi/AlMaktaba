package com.mylibrary.core.data.repository

import com.mylibrary.core.data.datastore.SettingsDataStore
import com.mylibrary.core.domain.model.ReaderSettings
import com.mylibrary.core.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore,
) : SettingsRepository {

    override val settings: Flow<ReaderSettings> = dataStore.settings

    override suspend fun currentSettings(): ReaderSettings = dataStore.settings.first()

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) =
        dataStore.update(transform)
}
