package com.tucontrol.data.tv.samsung

import android.util.Base64
import com.tucontrol.data.network.WebSocketSession
import com.tucontrol.data.tv.BaseTvController
import com.tucontrol.data.tv.TvRejectedException
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Samsung Tizen TVs (2016+) via the `samsung.remote.control` WebSocket channel.
 * The first connection shows an "Allow" prompt on the TV; the returned token is stored
 * so later connections are silent.
 */
class SamsungTizenController(
    override val device: TvDevice,
    private val http: OkHttpClient,
    private val devices: DeviceRepository,
) : BaseTvController() {

    private var session: WebSocketSession? = null

    override suspend fun connect() {
        mutableState.value = ConnectionState.Connecting
        try {
            val token = devices.credential(device.id)
            val socket = openSocket(token)
            if (token == null) mutableState.value = ConnectionState.AwaitingApproval
            withTimeout(APPROVAL_TIMEOUT_MS) { awaitAuthorization(socket) }
            session = socket
            mutableState.value = ConnectionState.Connected
        } catch (error: Throwable) {
            session?.close()
            session = null
            fail(error.toConnectionError())
        }
    }

    override suspend fun press(key: RemoteKey) {
        ensureConnected()
        val code = KEY_CODES[key] ?: return
        send(
            buildJsonObject {
                put("method", "ms.remote.control")
                putJsonObject("params") {
                    put("Cmd", "Click")
                    put("DataOfCmd", code)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                }
            }.toString(),
        )
    }

    override fun canLaunch(app: StreamingApp) = app in APP_IDS

    override suspend fun launch(app: StreamingApp) {
        ensureConnected()
        val appId = APP_IDS[app] ?: return
        send(
            buildJsonObject {
                put("method", "ms.channel.emit")
                putJsonObject("params") {
                    put("event", "ed.apps.launch")
                    put("to", "host")
                    putJsonObject("data") {
                        put("appId", appId)
                        put("action_type", "DEEP_LINK")
                    }
                }
            }.toString(),
        )
    }

    override suspend fun disconnect() {
        session?.close()
        session = null
        mutableState.value = ConnectionState.Disconnected
    }

    /** Tries the secure endpoint (2018+ models) first, then the legacy one. */
    private suspend fun openSocket(token: String?): WebSocketSession {
        val endpoints = listOf("wss://${device.host}:8002", "ws://${device.host}:8001")
        var lastError: Throwable = IOException("No Samsung endpoint answered")
        for (base in endpoints) {
            val url = "$base/api/v2/channels/samsung.remote.control".toHttpUrl().newBuilder()
                .addQueryParameter("name", CLIENT_NAME_B64)
                .apply { if (token != null) addQueryParameter("token", token) }
                .build()
                .toString()
            try {
                return WebSocketSession.open(http, url)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError
    }

    private suspend fun awaitAuthorization(socket: WebSocketSession) {
        for (raw in socket.incoming) {
            val message = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            when (message["event"]?.jsonPrimitive?.contentOrNull) {
                "ms.channel.connect" -> {
                    message["data"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNull
                        ?.let { devices.storeCredential(device.id, it) }
                    return
                }
                "ms.channel.unauthorized" -> throw TvRejectedException("Samsung TV denied access")
            }
        }
        throw IOException("Samsung TV closed the connection")
    }

    private fun send(payload: String) {
        val socket = session ?: throw IOException("Samsung socket closed")
        if (!socket.send(payload)) {
            mutableState.value = ConnectionState.Disconnected
            throw IOException("Samsung socket closed")
        }
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 30_000L
        val CLIENT_NAME_B64: String = Base64.encodeToString("TuControl".toByteArray(), Base64.NO_WRAP)

        val KEY_CODES = mapOf(
            RemoteKey.POWER to "KEY_POWER",
            RemoteKey.VOLUME_UP to "KEY_VOLUP",
            RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN",
            RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.CHANNEL_UP to "KEY_CHUP",
            RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN",
            RemoteKey.UP to "KEY_UP",
            RemoteKey.DOWN to "KEY_DOWN",
            RemoteKey.LEFT to "KEY_LEFT",
            RemoteKey.RIGHT to "KEY_RIGHT",
            RemoteKey.OK to "KEY_ENTER",
            RemoteKey.BACK to "KEY_RETURN",
            RemoteKey.HOME to "KEY_HOME",
            RemoteKey.MENU to "KEY_MENU",
            RemoteKey.INPUT to "KEY_SOURCE",
            RemoteKey.PLAY_PAUSE to "KEY_PLAY",
            RemoteKey.PLAY to "KEY_PLAY",
            RemoteKey.PAUSE to "KEY_PAUSE",
            RemoteKey.STOP to "KEY_STOP",
            RemoteKey.REWIND to "KEY_REWIND",
            RemoteKey.FAST_FORWARD to "KEY_FF",
            RemoteKey.INFO to "KEY_INFO",
            RemoteKey.GUIDE to "KEY_GUIDE",
            RemoteKey.CAPTIONS to "KEY_CAPTION",
            *RemoteKey.entries.filter { it.name.startsWith("NUM_") }
                .map { it to "KEY_" + it.name.removePrefix("NUM_") }
                .toTypedArray(),
        )

        val APP_IDS = mapOf(
            StreamingApp.NETFLIX to "3201907018807",
            StreamingApp.YOUTUBE to "111299001912",
            StreamingApp.PRIME_VIDEO to "3201910019365",
            StreamingApp.DISNEY_PLUS to "3201901017640",
            StreamingApp.SPOTIFY to "3201606009684",
            StreamingApp.MAX to "3202301029760",
        )
    }
}
