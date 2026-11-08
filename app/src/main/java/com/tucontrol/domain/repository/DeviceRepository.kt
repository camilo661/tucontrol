package com.tucontrol.domain.repository

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
}
