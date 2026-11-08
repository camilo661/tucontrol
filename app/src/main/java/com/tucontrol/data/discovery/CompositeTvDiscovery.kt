package com.tucontrol.data.discovery

import com.tucontrol.domain.model.TvBrand
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.TvDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Runs every discovery strategy in parallel and de-duplicates by IP address.
 *
 * A TV may answer more than one protocol (e.g. a Sony Bravia with Google TV answers SSDP
 * and mDNS). The most capable protocol wins, and an upgraded entry is re-emitted so the
 * UI can replace the previous one.
 */
class CompositeTvDiscovery(
    private val strategies: List<TvDiscovery>,
) : TvDiscovery {

    override fun discover(): Flow<TvDevice> = flow {
        val byHost = mutableMapOf<String, TvDevice>()
        merge(*strategies.map { it.discover() }.toTypedArray()).collect { candidate ->
            val current = byHost[candidate.host]
            val merged = when {
                current == null -> candidate
                priority(candidate) > priority(current) ->
                    if (candidate.hasGenericName && !current.hasGenericName) candidate.copy(name = current.name) else candidate
                // Same protocol found twice: keep whichever answer carries the TV's real name.
                priority(candidate) == priority(current) && current.hasGenericName && !candidate.hasGenericName -> candidate
                else -> return@collect
            }
            byHost[candidate.host] = merged
            emit(merged)
        }
    }

    private val TvDevice.hasGenericName: Boolean get() = name == brand.displayName

    private fun priority(device: TvDevice): Int = when (device.brand) {
        // Android TV Remote works out of the box (PIN pairing) on Google TV models,
        // whereas Sony IRCC requires enabling a pre-shared key in the TV settings.
        TvBrand.ANDROID_TV -> 3
        TvBrand.SAMSUNG, TvBrand.LG, TvBrand.ROKU -> 2
        TvBrand.SONY -> 1
        TvBrand.UNKNOWN -> 0
    }
}
