package com.tucontrol.domain.repository

import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.flow.Flow

/** Remembered TVs and the per-device pairing credentials. */
interface DeviceRepository {
    val savedDevices: Flow<List<TvDevice>>
    val activeDeviceId: Flow<String?>

    suspend fun save(device: TvDevice)
    suspend fun forget(deviceId: String)
    suspend fun setActive(deviceId: String?)

    suspend fun credential(deviceId: String): String?
    suspend fun storeCredential(deviceId: String, value: String)

    /** Apps learned for a TV while the user used it (for platforms that cannot list them). */
    suspend fun learnedApps(deviceId: String): List<InstalledApp>
    suspend fun learnApp(deviceId: String, app: InstalledApp)
}
