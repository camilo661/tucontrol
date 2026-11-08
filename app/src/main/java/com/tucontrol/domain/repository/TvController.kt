package com.tucontrol.domain.repository

import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
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
}

/** Implemented by controllers whose pairing requires a PIN shown on the TV. */
interface PinPairing {
    /** @return  when the code is malformed or does not match the one on screen. */
    suspend fun submitPin(pin: String): Boolean
}
