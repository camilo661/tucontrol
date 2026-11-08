package com.tucontrol.domain.repository

import kotlinx.coroutines.flow.Flow

data class AppSettings(
    val aiEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val sonyPreSharedKey: String = DEFAULT_SONY_PSK,
    /** The welcome guide was shown and dismissed. */
    val welcomeSeen: Boolean = false,
) {
    companion object {
        const val DEFAULT_SONY_PSK = "0000"
    }
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setAiEnabled(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setSonyPreSharedKey(key: String)
    suspend fun setWelcomeSeen()
}
