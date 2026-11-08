package com.tucontrol.data.tv.lg

import com.tucontrol.data.network.WebSocketSession
import com.tucontrol.data.tv.BaseTvController
import com.tucontrol.data.tv.TvRejectedException
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS TVs via the SSAP WebSocket API (port 3001 TLS, legacy 3000).
 * The first registration shows a prompt on the TV; the returned client key is stored.
 * Navigation keys travel through a secondary "pointer input" socket.
 */
class LgWebOsController(
    override val device: TvDevice,
    private val http: OkHttpClient,
    private val devices: DeviceRepository,
) : BaseTvController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicInteger()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var session: WebSocketSession? = null
    private var pointer: WebSocketSession? = null
    private var reader: Job? = null
    private var muted = false

    override suspend fun connect() {
        mutableState.value = ConnectionState.Connecting
        try {
            val socket = openSocket()
            session = socket
            val clientKey = devices.credential(device.id)
            if (clientKey == null) mutableState.value = ConnectionState.AwaitingApproval
            withTimeout(APPROVAL_TIMEOUT_MS) { register(socket, clientKey) }
            reader = scope.launch { readResponses(socket) }
            mutableState.value = ConnectionState.Connected
        } catch (error: Throwable) {
            close()
            fail(error.toConnectionError())
        }
    }

    override suspend fun press(key: RemoteKey) {
        ensureConnected()
        when (key) {
            RemoteKey.POWER -> request("ssap://system/turnOff")
            RemoteKey.VOLUME_UP -> request("ssap://audio/volumeUp")
            RemoteKey.VOLUME_DOWN -> request("ssap://audio/volumeDown")
            RemoteKey.MUTE -> {
                muted = !muted
                request("ssap://audio/setMute", buildJsonObject { put("mute", muted) })
            }
            RemoteKey.CHANNEL_UP -> request("ssap://tv/channelUp")
            RemoteKey.CHANNEL_DOWN -> request("ssap://tv/channelDown")
            RemoteKey.PLAY, RemoteKey.PLAY_PAUSE -> request("ssap://media.controls/play")
            RemoteKey.PAUSE -> request("ssap://media.controls/pause")
            RemoteKey.STOP -> request("ssap://media.controls/stop")
            RemoteKey.REWIND -> request("ssap://media.controls/rewind")
            RemoteKey.FAST_FORWARD -> request("ssap://media.controls/fastForward")
            else -> POINTER_BUTTONS[key]?.let { sendPointerButton(it) }
        }
    }

    override fun canLaunch(app: StreamingApp) = app in APP_IDS

    override suspend fun launch(app: StreamingApp) {
        ensureConnected()
        val appId = APP_IDS[app] ?: return
        request("ssap://system.launcher/launch", buildJsonObject { put("id", appId) })
    }

    override suspend fun disconnect() {
        close()
        mutableState.value = ConnectionState.Disconnected
    }

    private suspend fun openSocket(): WebSocketSession = runCatching {
        WebSocketSession.open(http, "wss://${device.host}:3001")
    }.getOrElse {
        WebSocketSession.open(http, "ws://${device.host}:3000")
    }

    private suspend fun register(socket: WebSocketSession, clientKey: String?) {
        socket.send(registrationPayload(clientKey).toString())
        for (raw in socket.incoming) {
            val message = parse(raw) ?: continue
            when (message["type"]?.jsonPrimitive?.contentOrNull) {
                "registered" -> {
                    message["payload"]?.jsonObject?.get("client-key")?.jsonPrimitive?.contentOrNull
                        ?.let { devices.storeCredential(device.id, it) }
                    return
                }
                "error" -> throw TvRejectedException("LG TV rejected registration")
            }
        }
        throw IOException("LG TV closed the connection")
    }

    private suspend fun readResponses(socket: WebSocketSession) {
        try {
            for (raw in socket.incoming) {
                val message = parse(raw) ?: continue
                val id = message["id"]?.jsonPrimitive?.contentOrNull ?: continue
                pending.remove(id)?.complete(message)
            }
        } finally {
            if (session === socket) mutableState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun request(uri: String, payload: JsonObject? = null): JsonObject {
        val socket = session ?: throw IOException("LG socket closed")
        val id = "req_${requestIds.incrementAndGet()}"
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        val message = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }
        if (!socket.send(message.toString())) {
            pending.remove(id)
            throw IOException("LG socket closed")
        }
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { response.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun sendPointerButton(name: String) {
        val socket = pointer ?: openPointerSocket().also { pointer = it }
        if (!socket.send("type:button\nname:$name\n\n")) {
            pointer = null
            openPointerSocket().also { pointer = it }.send("type:button\nname:$name\n\n")
        }
    }

    private suspend fun openPointerSocket(): WebSocketSession {
        val response = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val path = response["payload"]?.jsonObject?.get("socketPath")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("LG TV did not return a pointer socket")
        return WebSocketSession.open(http, path)
    }

    private fun close() {
        reader?.cancel()
        reader = null
        pointer?.close()
        pointer = null
        session?.close()
        session = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun parse(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun registrationPayload(clientKey: String?) = buildJsonObject {
        put("type", "register")
        put("id", "register_0")
        putJsonObject("payload") {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (clientKey != null) put("client-key", clientKey)
            putJsonObject("manifest") {
                put("manifestVersion", 1)
                put("appVersion", "1.0")
                putJsonArray("permissions") { PERMISSIONS.forEach { add(it) } }
            }
        }
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 5_000L

        val PERMISSIONS = listOf(
            "LAUNCH",
            "CONTROL_AUDIO",
            "CONTROL_DISPLAY",
            "CONTROL_INPUT_TV",
            "CONTROL_INPUT_MEDIA_PLAYBACK",
            "CONTROL_MOUSE_AND_KEYBOARD",
            "CONTROL_POWER",
            "READ_INSTALLED_APPS",
            "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST",
            "READ_TV_CHANNEL_LIST",
        )

        val POINTER_BUTTONS = mapOf(
            RemoteKey.UP to "UP",
            RemoteKey.DOWN to "DOWN",
            RemoteKey.LEFT to "LEFT",
            RemoteKey.RIGHT to "RIGHT",
            RemoteKey.OK to "ENTER",
            RemoteKey.BACK to "BACK",
            RemoteKey.HOME to "HOME",
            RemoteKey.MENU to "MENU",
            RemoteKey.INPUT to "INPUT_HUB",
            RemoteKey.INFO to "INFO",
            *RemoteKey.entries.filter { it.name.startsWith("NUM_") }
                .map { it to it.name.removePrefix("NUM_") }
                .toTypedArray(),
        )

        val APP_IDS = mapOf(
            StreamingApp.NETFLIX to "netflix",
            StreamingApp.YOUTUBE to "youtube.leanback.v4",
            StreamingApp.PRIME_VIDEO to "amazon",
            StreamingApp.DISNEY_PLUS to "com.disney.disneyplus-prod",
            StreamingApp.SPOTIFY to "spotify-beehive",
            StreamingApp.MAX to "com.wbd.stream",
        )
    }
}
