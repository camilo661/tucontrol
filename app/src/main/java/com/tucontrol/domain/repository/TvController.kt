package com.tucontrol.domain.repository

import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.SettingsSection
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.flow.StateFlow

/** Drives one TV through its native network protocol. */
interface TvController {
    val device: TvDevice
    val state: StateFlow<ConnectionState>

    /** Opens (and if needed pairs) the connection. Progress is reported through [state]. */
    suspend fun connect()

    suspend fun press(key: RemoteKey)

    suspend fun launch(app: StreamingApp)

    /** Whether [launch] can open [app] on this TV platform. */
    fun canLaunch(app: StreamingApp): Boolean

    /**
     * Searches for [query], inside [app] when given (YouTube otherwise). Platforms without a
     * search API open the app instead, so the user lands one step away from the result.
     */
    suspend fun search(query: String, app: StreamingApp?) {
        (app ?: StreamingApp.YOUTUBE).takeIf(::canLaunch)?.let { launch(it) }
    }

    suspend fun disconnect()

    /**
     * Opens the TV settings. Most platforms expose no deep links into them, so the default is
     * the settings key; the user continues by voice ("baja", "selecciona").
     */
    suspend fun openSettings(section: SettingsSection) = press(RemoteKey.SETTINGS)

    /** Whether [installedApps] is the TV's full list (otherwise apps are learned as they are used). */
    val listsAllApps: Boolean get() = false

    /** Apps that can be opened by name: listed by the TV, or learned while the user uses it. */
    suspend fun installedApps(): List<InstalledApp> = emptyList()

    suspend fun open(app: InstalledApp) = Unit

    /**
     * For TVs that only reveal the app in use: label the next newly opened app as [name], so
     * "abre <name>" works after the user opens it once with the TV's own remote.
     */
    fun learnNextAppAs(name: String) = Unit
}

/** Implemented by controllers whose pairing requires a PIN shown on the TV. */
interface PinPairing {
    /** @return  when the code is malformed or does not match the one on screen. */
    suspend fun submitPin(pin: String): Boolean
}
