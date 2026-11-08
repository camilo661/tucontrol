package com.tucontrol.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wear OS routes traffic through the paired phone over Bluetooth by default, which cannot
 * reach devices on the home LAN. This class explicitly requests the Wi‑Fi radio and binds
 * the process to it while TuControl is in the foreground.
 *
 * @param allowLanFallback when Wi‑Fi cannot be obtained, fall back to the active network
 * unless it is the Bluetooth proxy. Enabled in debug builds for the emulator, whose only
 * network is a virtual Ethernet link that still reaches the host's LAN through NAT.
 */
class WifiNetworkProvider(context: Context, private val allowLanFallback: Boolean = false) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutex = Mutex()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null

    /** Returns the bound LAN network (Wi‑Fi, or the debug fallback), or `null` when there is none. */
    suspend fun acquire(): Network? = mutex.withLock {
        network?.let { return it }

        val available = CompletableDeferred<Network>()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }

            override fun onLost(lost: Network) {
                if (lost == network) {
                    network = null
                    connectivity.bindProcessToNetwork(null)
                }
            }
        }
        connectivity.requestNetwork(request, networkCallback)
        callback = networkCallback

        val fallback = fallbackNetwork()
        val timeout = if (fallback != null) FALLBACK_WIFI_TIMEOUT_MS else WIFI_TIMEOUT_MS
        val acquired = withTimeoutOrNull(timeout) { available.await() }
        if (acquired == null) releaseLocked()
        val bound = acquired ?: fallback ?: return null

        connectivity.bindProcessToNetwork(bound)
        network = bound
        bound
    }

    private fun fallbackNetwork(): Network? {
        if (!allowLanFallback) return null
        val active = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return null
        return active.takeUnless { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) }
    }

    /** Hands the Wi‑Fi radio back to the system so the watch can save battery. */
    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        connectivity.bindProcessToNetwork(null)
    }

    private companion object {
        const val WIFI_TIMEOUT_MS = 10_000L
        const val FALLBACK_WIFI_TIMEOUT_MS = 3_000L
    }
}
