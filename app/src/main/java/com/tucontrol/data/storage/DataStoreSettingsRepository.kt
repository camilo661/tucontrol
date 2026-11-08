package com.tucontrol.data.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tucontrol.domain.repository.AppSettings
import com.tucontrol.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            aiEnabled = prefs[AI_ENABLED] ?: true,
            hapticsEnabled = prefs[HAPTICS_ENABLED] ?: true,
            sonyPreSharedKey = prefs[SONY_PSK] ?: AppSettings.DEFAULT_SONY_PSK,
        )
    }

    override suspend fun setAiEnabled(enabled: Boolean) {
        store.edit { it[AI_ENABLED] = enabled }
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        store.edit { it[HAPTICS_ENABLED] = enabled }
    }

    override suspend fun setSonyPreSharedKey(key: String) {
        store.edit { it[SONY_PSK] = key.trim() }
    }

    private companion object {
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val SONY_PSK = stringPreferencesKey("sony_psk")
    }
}
