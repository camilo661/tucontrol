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
 */
class WifiNetworkProvider(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutex = Mutex()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null

    /** Returns a bound Wi‑Fi network, or `null` when Wi‑Fi is unavailable. */
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

        val acquired = withTimeoutOrNull(WIFI_TIMEOUT_MS) { available.await() }
        if (acquired == null) {
            releaseLocked()
            return null
        }
        connectivity.bindProcessToNetwork(acquired)
        network = acquired
        acquired
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
    }
}
