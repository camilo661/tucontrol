package com.tucontrol.data.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Persists remembered TVs and their pairing credentials in Preferences DataStore. */
class DataStoreDeviceRepository(
    private val store: DataStore<Preferences>,
) : DeviceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override val savedDevices: Flow<List<TvDevice>> = store.data.map { prefs -> prefs.decodeDevices() }

    override val activeDeviceId: Flow<String?> = store.data.map { it[ACTIVE_DEVICE] }

    override suspend fun save(device: TvDevice) {
        store.edit { prefs ->
            val devices = prefs.decodeDevices().filterNot { it.id == device.id } + device
            prefs[DEVICES] = json.encodeToString(devices)
        }
    }

    override suspend fun forget(deviceId: String) {
        store.edit { prefs ->
            prefs[DEVICES] = json.encodeToString(prefs.decodeDevices().filterNot { it.id == deviceId })
            prefs.remove(credentialKey(deviceId))
            prefs.remove(appsKey(deviceId))
            if (prefs[ACTIVE_DEVICE] == deviceId) prefs.remove(ACTIVE_DEVICE)
        }
    }

    override suspend fun setActive(deviceId: String?) {
        store.edit { prefs ->
            if (deviceId == null) prefs.remove(ACTIVE_DEVICE) else prefs[ACTIVE_DEVICE] = deviceId
        }
    }

    override suspend fun credential(deviceId: String): String? = store.data.first()[credentialKey(deviceId)]

    override suspend fun storeCredential(deviceId: String, value: String) {
        store.edit { it[credentialKey(deviceId)] = value }
    }

    override suspend fun learnedApps(deviceId: String): List<InstalledApp> =
        store.data.first()[appsKey(deviceId)]
            ?.let { runCatching { json.decodeFromString<List<InstalledApp>>(it) }.getOrNull() }
            .orEmpty()

    override suspend fun learnApp(deviceId: String, app: InstalledApp) {
        store.edit { prefs ->
            val known = prefs[appsKey(deviceId)]
                ?.let { runCatching { json.decodeFromString<List<InstalledApp>>(it) }.getOrNull() }
                .orEmpty()
            prefs[appsKey(deviceId)] = json.encodeToString(known.filterNot { it.id == app.id } + app)
        }
    }

    private fun Preferences.decodeDevices(): List<TvDevice> =
        this[DEVICES]?.let { runCatching { json.decodeFromString<List<TvDevice>>(it) }.getOrNull() }.orEmpty()

    private fun credentialKey(deviceId: String) = stringPreferencesKey("credential:$deviceId")

    private fun appsKey(deviceId: String) = stringPreferencesKey("apps:$deviceId")

    private companion object {
        val DEVICES = stringPreferencesKey("devices")
        val ACTIVE_DEVICE = stringPreferencesKey("active_device")
    }
}
