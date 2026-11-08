package com.tucontrol.domain.repository

import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.flow.Flow

/** Finds TVs on the same Wi‑Fi network. */
interface TvDiscovery {
    /** Emits each TV as soon as it answers; completes when the scan window ends. */
    fun discover(): Flow<TvDevice>
}
