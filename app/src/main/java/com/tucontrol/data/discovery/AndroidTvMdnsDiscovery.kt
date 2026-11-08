package com.tucontrol.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.tucontrol.data.network.WifiNetworkProvider
import com.tucontrol.domain.model.TvBrand
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.TvDiscovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/**
 * Finds Android TV / Google TV devices, which advertise the Android TV Remote v2
 * service over mDNS (`_androidtvremote2._tcp`).
 */
class AndroidTvMdnsDiscovery(
    context: Context,
    private val wifi: WifiNetworkProvider,
) : TvDiscovery {

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    /** NsdManager rejects concurrent resolutions, so they are serialized. */
    private val resolveMutex = Mutex()

    override fun discover(): Flow<TvDevice> = channelFlow {
        if (wifi.acquire() == null) return@channelFlow

        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                found.trySend(service)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                found.close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            withTimeoutOrNull(SCAN_WINDOW_MS) {
                for (service in found) {
                    launch { resolve(service)?.let { send(it) } }
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun resolve(service: NsdServiceInfo): TvDevice? = resolveMutex.withLock {
        val result = CompletableDeferred<NsdServiceInfo?>()
        @Suppress("DEPRECATION") // registerServiceInfoCallback requires API 34; minSdk is 30.
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    result.complete(info)
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    result.complete(null)
                }
            },
        )
        val info = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { result.await() } ?: return@withLock null

        @Suppress("DEPRECATION")
        val address = info.host as? Inet4Address ?: return@withLock null
        val host = address.hostAddress ?: return@withLock null
        TvDevice(
            id = "androidtv:${info.serviceName}",
            name = info.serviceName,
            brand = TvBrand.ANDROID_TV,
            host = host,
        )
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
        const val SCAN_WINDOW_MS = 5_000L
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}
