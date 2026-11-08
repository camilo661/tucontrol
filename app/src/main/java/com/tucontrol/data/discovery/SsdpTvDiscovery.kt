package com.tucontrol.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.tucontrol.data.network.WifiNetworkProvider
import com.tucontrol.domain.model.TvBrand
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.TvDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.DatagramSocket

/**
 * Finds Samsung, LG, Sony and Roku TVs (plus any DIAL-capable TV) using SSDP multicast,
 * then reads each device's UPnP description to identify brand and name.
 */
class SsdpTvDiscovery(
    context: Context,
    private val wifi: WifiNetworkProvider,
    private val http: OkHttpClient,
) : TvDiscovery {

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    override fun discover(): Flow<TvDevice> = channelFlow {
        val network = wifi.acquire() ?: return@channelFlow
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        val seenHosts = mutableSetOf<String>()

        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                network.bindSocket(socket)
                socket.soTimeout = RECEIVE_TIMEOUT_MS

                val group = InetAddress.getByName(SSDP_ADDRESS)
                repeat(SEARCH_ROUNDS) {
                    SEARCH_TARGETS.forEach { target ->
                        val payload = SsdpMessage.searchRequest(target).toByteArray()
                        socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + SCAN_WINDOW_MS
                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive && System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val message = SsdpMessage.parse(String(packet.data, 0, packet.length)) ?: continue
                    val host = packet.address.hostAddress ?: continue
                    if (!seenHosts.add(host)) continue

                    launch { resolve(host, message)?.let { send(it) } }
                }
            }
        } finally {
            lock.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun resolve(host: String, message: SsdpMessage): TvDevice? {
        val description = fetchDescription(message.location, host)
        val brand = BrandClassifier.classify(message, description)
        if (brand == TvBrand.UNKNOWN && !BrandClassifier.looksLikeTv(description)) return null

        return TvDevice(
            id = description.udn ?: message.usn.substringBefore("::").ifBlank { host },
            name = description.friendlyName ?: brand.displayName,
            brand = brand,
            host = host,
            model = description.modelName,
        )
    }

    private fun fetchDescription(location: String, host: String): DeviceDescription = runCatching {
        // Only follow description URLs that point back to the LAN host that answered.
        val uri = URI(location)
        require(uri.scheme in setOf("http", "https") && uri.host == host)
        http.newCall(Request.Builder().url(location).build()).execute().use { response ->
            if (response.isSuccessful) DeviceDescription.parse(response.body.string()) else DeviceDescription.EMPTY
        }
    }.getOrDefault(DeviceDescription.EMPTY)

    private companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val SEARCH_ROUNDS = 2
        const val SCAN_WINDOW_MS = 4_000L
        const val RECEIVE_TIMEOUT_MS = 500
        const val BUFFER_SIZE = 2048
        const val MULTICAST_LOCK_TAG = "tucontrol:ssdp"

        val SEARCH_TARGETS = listOf(
            "roku:ecp",
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:lge-com:service:webos-second-screen:1",
            "urn:schemas-sony-com:service:ScalarWebAPI:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
        )
    }
}
