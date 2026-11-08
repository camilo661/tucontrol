package com.tucontrol.data.tv.sony

import com.tucontrol.data.tv.BaseTvController
import com.tucontrol.data.tv.TvRejectedException
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Sony Bravia TVs via the IRCC-IP SOAP endpoint.
 * Requires "Remote start" and a pre-shared key (Settings → Network → IP control) on the TV.
 */
class SonyBraviaController(
    override val device: TvDevice,
    private val http: OkHttpClient,
    private val preSharedKey: suspend () -> String,
) : BaseTvController() {

    override suspend fun connect() {
        mutableState.value = ConnectionState.Connecting
        val body = """{"method":"getPowerStatus","id":1,"params":[],"version":"1.0"}"""
            .toRequestBody(JSON)
        runCatching {
            execute(
                Request.Builder()
                    .url("http://${device.host}/sony/system")
                    .header(PSK_HEADER, preSharedKey())
                    .post(body)
                    .build(),
            )
        }
            .onSuccess { mutableState.value = ConnectionState.Connected }
            .onFailure { fail(it.toConnectionError()) }
    }

    override suspend fun press(key: RemoteKey) {
        ensureConnected()
        IRCC_CODES[key]?.let { sendIrcc(it) }
    }

    override suspend fun launch(app: StreamingApp) {
        ensureConnected()
        APP_CODES[app]?.let { sendIrcc(it) }
    }

    override suspend fun disconnect() {
        mutableState.value = ConnectionState.Disconnected
    }

    private suspend fun sendIrcc(code: String) {
        val envelope = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                  <IRCCCode>$code</IRCCCode>
                </u:X_SendIRCC>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        execute(
            Request.Builder()
                .url("http://${device.host}/sony/IRCC")
                .header(PSK_HEADER, preSharedKey())
                .header("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                .post(envelope.toRequestBody(XML))
                .build(),
        )
    }

    private suspend fun execute(request: Request) = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            when {
                response.code == 403 -> throw TvRejectedException("Sony TV rejected the pre-shared key")
                !response.isSuccessful -> throw IOException("Sony TV answered ${response.code}")
            }
        }
    }

    private companion object {
        const val PSK_HEADER = "X-Auth-PSK"
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val XML = "text/xml; charset=UTF-8".toMediaType()

        val IRCC_CODES = mapOf(
            RemoteKey.POWER to "AAAAAQAAAAEAAAAVAw==",
            RemoteKey.VOLUME_UP to "AAAAAQAAAAEAAAASAw==",
            RemoteKey.VOLUME_DOWN to "AAAAAQAAAAEAAAATAw==",
            RemoteKey.MUTE to "AAAAAQAAAAEAAAAUAw==",
            RemoteKey.CHANNEL_UP to "AAAAAQAAAAEAAAAQAw==",
            RemoteKey.CHANNEL_DOWN to "AAAAAQAAAAEAAAARAw==",
            RemoteKey.UP to "AAAAAQAAAAEAAAB0Aw==",
            RemoteKey.DOWN to "AAAAAQAAAAEAAAB1Aw==",
            RemoteKey.LEFT to "AAAAAQAAAAEAAAA0Aw==",
            RemoteKey.RIGHT to "AAAAAQAAAAEAAAAzAw==",
            RemoteKey.OK to "AAAAAQAAAAEAAABlAw==",
            RemoteKey.BACK to "AAAAAgAAAJcAAAAjAw==",
            RemoteKey.HOME to "AAAAAQAAAAEAAABgAw==",
            RemoteKey.MENU to "AAAAAgAAAJcAAAA2Aw==",
            RemoteKey.INPUT to "AAAAAQAAAAEAAAAlAw==",
            RemoteKey.PLAY_PAUSE to "AAAAAgAAAJcAAAAaAw==",
            RemoteKey.PLAY to "AAAAAgAAAJcAAAAaAw==",
            RemoteKey.PAUSE to "AAAAAgAAAJcAAAAZAw==",
            RemoteKey.STOP to "AAAAAgAAAJcAAAAYAw==",
            RemoteKey.REWIND to "AAAAAgAAAJcAAAAbAw==",
            RemoteKey.FAST_FORWARD to "AAAAAgAAAJcAAAAcAw==",
        )

        val APP_CODES = mapOf(
            StreamingApp.NETFLIX to "AAAAAgAAABoAAAB8Aw==",
            StreamingApp.YOUTUBE to "AAAAAgAAAMQAAABHAw==",
        )
    }
}
