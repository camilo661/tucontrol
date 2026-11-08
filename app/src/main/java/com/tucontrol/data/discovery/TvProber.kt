package com.tucontrol.data.discovery

import android.net.Network
import com.tucontrol.domain.model.TvBrand
import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Identifies the TV (if any) at a single IP address by checking which control ports are
 * open and reading the platform's identity endpoint. Unlike SSDP and mDNS this needs no
 * multicast, so it also works on routers that filter it.
 */
class TvProber(http: OkHttpClient) {

    private val http = http.newBuilder()
        .connectTimeout(IDENTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(IDENTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Cheap liveness check (ICMP echo, falling back to TCP port 7 where a refusal still
     * proves the host exists). One socket per host keeps a /24 sweep light on the network.
     */
    suspend fun isAlive(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getByName(host).isReachable(ALIVE_TIMEOUT_MS) }.getOrDefault(false)
    }

    suspend fun identify(host: String, network: Network?): TvDevice? = coroutineScope {
        val open = PROBE_PORTS
            .map { port -> async { port.takeIf { isOpen(host, it, network) } } }
            .awaitAll()
            .filterNotNull()
            .toSet()

        withContext(Dispatchers.IO) {
            when {
                ANDROID_TV_REMOTE in open && ANDROID_TV_PAIRING in open -> androidTv(host)
                ROKU in open -> roku(host)
                SAMSUNG in open || SAMSUNG_TLS in open -> samsung(host)
                LG in open && acceptsWebSocket(host, LG, network) -> lg(host)
                HTTP in open -> sony(host)
                else -> null
            }
        }
    }

    private fun androidTv(host: String): TvDevice {
        val identity = get("http://$host:$CAST/setup/eureka_info?params=name")?.let(TvFingerprints::parseCastInfo)
        val name = identity?.name
        // Same id scheme as AndroidTvMdnsDiscovery (the Cast name is the mDNS service name).
        return TvDevice(
            id = name?.let { "androidtv:$it" } ?: "androidtv:$host",
            name = name ?: TvBrand.ANDROID_TV.displayName,
            brand = TvBrand.ANDROID_TV,
            host = host,
        )
    }

    private fun roku(host: String): TvDevice? {
        val identity = get("http://$host:$ROKU/query/device-info")?.let(TvFingerprints::parseRokuDeviceInfo) ?: return null
        return TvDevice(
            id = identity.uniqueId?.let { "roku:$it" } ?: "roku:$host",
            name = identity.name ?: TvBrand.ROKU.displayName,
            brand = TvBrand.ROKU,
            host = host,
            model = identity.model,
        )
    }

    private fun samsung(host: String): TvDevice {
        val identity = get("http://$host:$SAMSUNG/api/v2/")?.let(TvFingerprints::parseSamsungInfo)
        return TvDevice(
            id = identity?.uniqueId ?: "samsung:$host",
            name = identity?.name ?: TvBrand.SAMSUNG.displayName,
            brand = TvBrand.SAMSUNG,
            host = host,
            model = identity?.model,
        )
    }

    private fun lg(host: String) = TvDevice(
        id = "lg:$host",
        name = "${TvBrand.LG.displayName} webOS",
        brand = TvBrand.LG,
        host = host,
    )

    private fun sony(host: String): TvDevice? {
        val body = """{"method":"getInterfaceInformation","id":1,"params":[],"version":"1.0"}"""
        val request = Request.Builder()
            .url("http://$host/sony/system")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val identity = execute(request)?.let(TvFingerprints::parseSonyInterfaceInfo) ?: return null
        return TvDevice(
            id = "sony:$host",
            name = listOfNotNull("Sony", identity.name).distinct().joinToString(" "),
            brand = TvBrand.SONY,
            host = host,
            model = identity.model,
        )
    }

    /**
     * webOS answers a WebSocket upgrade on port 3000. Checking the handshake avoids
     * mistaking a computer running a web server on that common port for an LG TV.
     */
    private fun acceptsWebSocket(host: String, port: Int, network: Network?): Boolean = runCatching {
        socketFactory(network).createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IDENTIFY_TIMEOUT_MS.toInt()
            val handshake = "GET / HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply { write(handshake.toByteArray()); flush() }
            val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            statusLine.split(' ').getOrNull(1) == "101"
        }
    }.getOrDefault(false)

    private suspend fun isOpen(host: String, port: Int, network: Network?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            socketFactory(network).createSocket().use { socket: Socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)
    }

    private fun get(url: String): String? = execute(Request.Builder().url(url).build())

    private fun execute(request: Request): String? = runCatching {
        http.newCall(request).execute().use { response -> if (response.isSuccessful) response.body.string() else null }
    }.getOrNull()

    private fun socketFactory(network: Network?): SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()

    private companion object {
        const val ANDROID_TV_REMOTE = 6466
        const val ANDROID_TV_PAIRING = 6467
        const val CAST = 8008
        const val ROKU = 8060
        const val SAMSUNG = 8001
        const val SAMSUNG_TLS = 8002
        const val LG = 3000
        const val HTTP = 80

        val PROBE_PORTS = listOf(ANDROID_TV_REMOTE, ANDROID_TV_PAIRING, ROKU, SAMSUNG, SAMSUNG_TLS, LG, HTTP)

        const val ALIVE_TIMEOUT_MS = 1_000
        const val CONNECT_TIMEOUT_MS = 1_200
        const val IDENTIFY_TIMEOUT_MS = 2_500L
    }
}
