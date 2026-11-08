package com.tucontrol.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tucontrol.data.network.WifiNetworkProvider
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.TvDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address

/**
 * Sweeps every address of the local /24 subnet for TV control ports. Slower than
 * multicast discovery but independent of it: many routers drop SSDP/mDNS between Wi‑Fi
 * bands, and some TVs stop answering multicast while still accepting connections.
 *
 * @param extraSubnets "a.b.c" prefixes to sweep instead of the own subnet when not on Wi‑Fi. Debug builds
 * pass the development machine's LAN so the emulator (behind NAT) can find real TVs.
 */
class SubnetScanDiscovery(
    context: Context,
    private val wifi: WifiNetworkProvider,
    private val prober: TvProber,
    private val extraSubnets: List<String> = emptyList(),
) : TvDiscovery {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun discover(): Flow<TvDevice> = channelFlow {
        val network = wifi.acquire() ?: return@channelFlow
        val (ownAddresses, subnets) = subnetsOf(network)
        val hosts = subnets.flatMap { prefix -> (1..254).map { "$prefix.$it" } }.filterNot { it in ownAddresses }

        // Two stages so that at most a few dozen sockets are open at once: a light liveness
        // sweep over every address, then a full port probe of the hosts that answered.
        val sweep = Semaphore(MAX_CONCURRENT_PINGS)
        val probe = Semaphore(MAX_CONCURRENT_PROBES)
        hosts.forEach { host ->
            launch {
                if (sweep.withPermit { prober.isAlive(host) }) {
                    probe.withPermit { prober.identify(host, network) }?.let { send(it) }
                }
            }
        }
    }

    private fun subnetsOf(network: Network): Pair<Set<String>, List<String>> {
        val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .mapNotNull { (it.address as? Inet4Address)?.takeIf(Inet4Address::isSiteLocalAddress)?.hostAddress }
        val onWifi = connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val own = addresses.map { it.substringBeforeLast('.') }
        // Off Wi‑Fi (the emulator's NAT link) the own subnet holds no TVs; sweep the extras instead.
        val subnets = if (onWifi || extraSubnets.isEmpty()) own else extraSubnets
        return addresses.toSet() to subnets.distinct()
    }

    private companion object {
        const val MAX_CONCURRENT_PINGS = 48

        /** Each probe opens one socket per control port (seven). */
        const val MAX_CONCURRENT_PROBES = 4
    }
}
