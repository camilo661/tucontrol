package com.tucontrol.data.tv.roku

import com.tucontrol.data.tv.BaseTvController
import com.tucontrol.data.tv.TvRejectedException
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Roku TVs and sticks via the External Control Protocol (HTTP, port 8060). */
class RokuController(
    override val device: TvDevice,
    private val http: OkHttpClient,
) : BaseTvController() {

    private val baseUrl = "http://${device.host}:$PORT"

    override suspend fun connect() {
        mutableState.value = ConnectionState.Connecting
        runCatching { get("/query/device-info") }
            .onSuccess { mutableState.value = ConnectionState.Connected }
            .onFailure { fail(it.toConnectionError()) }
    }

    override suspend fun press(key: RemoteKey) {
        ensureConnected()
        val code = KEY_CODES[key] ?: return
        post("/keypress/$code")
    }

    override suspend fun launch(app: StreamingApp) {
        ensureConnected()
        val channelId = APP_IDS[app] ?: return
        post("/launch/$channelId")
    }

    override fun canLaunch(app: StreamingApp) = app in APP_IDS

    /** Roku lists every installed channel with its name. */
    override val listsAllApps = true

    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/query/apps").build()
        val body = http.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else "" }
        APP_ENTRY.findAll(body).map { InstalledApp(it.groupValues[1], it.groupValues[2].trim()) }.toList()
    }

    override suspend fun open(app: InstalledApp) {
        ensureConnected()
        post("/launch/${app.id}")
    }

    /** Roku's universal search; with a known app it jumps straight into that app's results. */
    override suspend fun search(query: String, app: StreamingApp?) {
        ensureConnected()
        val url = (baseUrl + "/search/browse").toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .apply {
                app?.let(APP_IDS::get)?.let { providerId ->
                    addQueryParameter("provider-id", providerId)
                    addQueryParameter("launch", "true")
                }
            }
            .build()
        execute(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build())
    }

    override suspend fun disconnect() {
        mutableState.value = ConnectionState.Disconnected
    }

    private suspend fun get(path: String) = execute(Request.Builder().url(baseUrl + path).build())

    private suspend fun post(path: String) =
        execute(Request.Builder().url(baseUrl + path).post(ByteArray(0).toRequestBody()).build())

    private suspend fun execute(request: Request) = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            when {
                response.code == 403 -> throw TvRejectedException("Roku blocks network control")
                !response.isSuccessful -> throw IOException("Roku answered ${response.code}")
            }
        }
    }

    private companion object {
        const val PORT = 8060

        /** `<app id="12" type="appl" version="5.1">Netflix</app>` */
        val APP_ENTRY = Regex("<app id=\"([^\"]+)\"[^>]*>([^<]+)</app>")

        val KEY_CODES = mapOf(
            RemoteKey.POWER to "Power",
            RemoteKey.VOLUME_UP to "VolumeUp",
            RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "VolumeMute",
            RemoteKey.CHANNEL_UP to "ChannelUp",
            RemoteKey.CHANNEL_DOWN to "ChannelDown",
            RemoteKey.UP to "Up",
            RemoteKey.DOWN to "Down",
            RemoteKey.LEFT to "Left",
            RemoteKey.RIGHT to "Right",
            RemoteKey.OK to "Select",
            RemoteKey.BACK to "Back",
            RemoteKey.HOME to "Home",
            RemoteKey.MENU to "Info",
            RemoteKey.INPUT to "InputHDMI1",
            RemoteKey.PLAY_PAUSE to "Play",
            RemoteKey.PLAY to "Play",
            RemoteKey.PAUSE to "Play",
            RemoteKey.REWIND to "Rev",
            RemoteKey.FAST_FORWARD to "Fwd",
            RemoteKey.INFO to "Info",
            RemoteKey.SEARCH to "Search",
        )

        val APP_IDS = mapOf(
            StreamingApp.NETFLIX to "12",
            StreamingApp.YOUTUBE to "837",
            StreamingApp.PRIME_VIDEO to "13",
            StreamingApp.DISNEY_PLUS to "291097",
            StreamingApp.SPOTIFY to "22297",
            StreamingApp.MAX to "61322",
            StreamingApp.APPLE_TV to "551012",
            StreamingApp.PARAMOUNT_PLUS to "31440",
            StreamingApp.CRUNCHYROLL to "2595",
            StreamingApp.PLUTO_TV to "74519",
            StreamingApp.PLEX to "13535",
        )
    }
}
