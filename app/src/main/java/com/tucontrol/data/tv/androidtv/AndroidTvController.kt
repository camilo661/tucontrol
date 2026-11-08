package com.tucontrol.data.tv.androidtv

import com.tucontrol.data.tv.BaseTvController
import com.tucontrol.data.tv.TvRejectedException
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import com.tucontrol.domain.repository.PinPairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Android TV / Google TV (including Chromecast with Google TV, Sony, TCL, Hisense, Philips
 * Google TV models) via the Android TV Remote protocol v2.
 *
 * - Port 6467: one-time pairing. The TV shows a 6-character code typed on the watch.
 * - Port 6466: remote session authenticated with the paired client certificate.
 */
class AndroidTvController(
    override val device: TvDevice,
    private val devices: DeviceRepository,
    private val certificates: AndroidTvCertificateStore,
) : BaseTvController(), PinPairing {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var pairingSocket: SSLSocket? = null
    private var serverKey: RSAPublicKey? = null
    private var remoteSocket: SSLSocket? = null
    private var reader: Job? = null

    override suspend fun connect() {
        mutableState.value = ConnectionState.Connecting
        try {
            if (devices.credential(device.id) == PAIRED) {
                val connected = runCatching { openRemoteSession() }.isSuccess
                if (connected) {
                    mutableState.value = ConnectionState.Connected
                    return
                }
            }
            startPairing()
            mutableState.value = ConnectionState.AwaitingPin
        } catch (error: Throwable) {
            closeAll()
            fail(error.toConnectionError())
        }
    }

    override suspend fun submitPin(pin: String): Boolean {
        val socket = pairingSocket ?: return false
        val server = serverKey ?: return false
        val secret = PairingSecret.compute(certificates.publicKey, server, pin) ?: return false

        mutableState.value = ConnectionState.Connecting
        return try {
            withContext(Dispatchers.IO) {
                socket.sendPairing { message(PAIRING_SECRET) { bytes(1, secret) } }
                socket.expectPairingAck(PAIRING_SECRET_ACK)
                socket.close()
            }
            pairingSocket = null
            devices.storeCredential(device.id, PAIRED)
            openRemoteSession()
            mutableState.value = ConnectionState.Connected
            true
        } catch (error: Throwable) {
            closeAll()
            fail(error.toConnectionError())
            false
        }
    }

    override suspend fun press(key: RemoteKey) {
        ensureConnected()
        val code = KEY_CODES[key] ?: return
        sendRemote {
            message(REMOTE_KEY_INJECT) {
                int(1, code)
                int(2, DIRECTION_SHORT)
            }
        }
    }

    override suspend fun launch(app: StreamingApp) {
        ensureConnected()
        val link = APP_LINKS[app] ?: return
        sendRemote { message(REMOTE_APP_LINK_LAUNCH) { string(1, link) } }
    }

    override suspend fun disconnect() {
        closeAll()
        mutableState.value = ConnectionState.Disconnected
    }

    // region Pairing (port 6467)

    private suspend fun startPairing() = withContext(Dispatchers.IO) {
        val socket = openTls(PAIRING_PORT)
        pairingSocket = socket
        serverKey = socket.session.peerCertificates.first().publicKey as RSAPublicKey

        socket.sendPairing {
            message(PAIRING_REQUEST) {
                string(1, SERVICE_NAME)
                string(2, CLIENT_NAME)
            }
        }
        socket.expectPairingAck(PAIRING_REQUEST_ACK)

        socket.sendPairing {
            message(PAIRING_OPTION) {
                message(1) {
                    int(1, ENCODING_HEXADECIMAL)
                    int(2, PairingSecret.CODE_LENGTH)
                }
                int(3, ROLE_INPUT)
            }
        }
        socket.expectPairingAck(PAIRING_OPTION)

        socket.sendPairing {
            message(PAIRING_CONFIGURATION) {
                message(1) {
                    int(1, ENCODING_HEXADECIMAL)
                    int(2, PairingSecret.CODE_LENGTH)
                }
                int(2, ROLE_INPUT)
            }
        }
        socket.expectPairingAck(PAIRING_CONFIGURATION_ACK)
    }

    private fun SSLSocket.sendPairing(body: ProtoWriter.() -> Unit) {
        val payload = ProtoWriter()
            .int(1, PROTOCOL_VERSION)
            .int(2, STATUS_OK)
            .apply(body)
            .toByteArray()
        ProtoFraming.write(outputStream, payload)
    }

    private fun SSLSocket.expectPairingAck(field: Int) {
        val response = ProtoFraming.read(inputStream)
        if (response.long(2) != STATUS_OK.toLong()) throw TvRejectedException("Pairing refused by the TV")
        if (!response.has(field)) throw IOException("Unexpected pairing message")
    }

    // endregion

    // region Remote session (port 6466)

    private suspend fun openRemoteSession() {
        val socket = withContext(Dispatchers.IO) { openTls(REMOTE_PORT) }
        remoteSocket = socket
        val ready = CompletableDeferred<Unit>()
        reader = scope.launch { readRemote(socket, ready) }
        try {
            withTimeout(REMOTE_READY_TIMEOUT_MS) { ready.await() }
        } catch (error: Throwable) {
            closeRemote()
            throw error
        }
    }

    private suspend fun readRemote(socket: SSLSocket, ready: CompletableDeferred<Unit>) {
        try {
            while (true) {
                val message = ProtoFraming.read(socket.inputStream)
                when {
                    message.has(REMOTE_CONFIGURE) -> sendRemote {
                        message(REMOTE_CONFIGURE) {
                            int(1, FEATURES)
                            message(2) {
                                string(1, "Wear OS")
                                string(2, "TuControl")
                                int(3, 1)
                                string(4, "1")
                                string(5, "com.tucontrol.app")
                                string(6, "1.0.0")
                            }
                        }
                    }
                    message.has(REMOTE_SET_ACTIVE) -> {
                        sendRemote { message(REMOTE_SET_ACTIVE) { int(1, FEATURES) } }
                        ready.complete(Unit)
                    }
                    message.has(REMOTE_PING_REQUEST) -> {
                        val value = message.message(REMOTE_PING_REQUEST)?.long(1) ?: 0L
                        sendRemote { message(REMOTE_PING_RESPONSE) { int(1, value) } }
                    }
                    message.has(REMOTE_START) -> ready.complete(Unit)
                }
            }
        } catch (error: Throwable) {
            ready.completeExceptionally(error)
            if (remoteSocket === socket && isConnected) mutableState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun sendRemote(body: ProtoWriter.() -> Unit) {
        val socket = remoteSocket ?: throw IOException("Android TV session closed")
        val payload = ProtoWriter().apply(body).toByteArray()
        writeLock.withLock {
            withContext(Dispatchers.IO) { ProtoFraming.write(socket.outputStream, payload) }
        }
    }

    // endregion

    private fun openTls(port: Int): SSLSocket {
        val socket = certificates.socketFactory().createSocket() as SSLSocket
        socket.connect(InetSocketAddress(device.host, port), CONNECT_TIMEOUT_MS)
        socket.startHandshake()
        return socket
    }

    private fun closeRemote() {
        reader?.cancel()
        reader = null
        runCatching { remoteSocket?.close() }
        remoteSocket = null
    }

    private fun closeAll() {
        closeRemote()
        runCatching { pairingSocket?.close() }
        pairingSocket = null
        serverKey = null
    }

    private companion object {
        const val PAIRED = "paired"
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        const val CONNECT_TIMEOUT_MS = 4_000
        const val REMOTE_READY_TIMEOUT_MS = 8_000L

        const val SERVICE_NAME = "tucontrol"
        const val CLIENT_NAME = "TuControl"
        const val PROTOCOL_VERSION = 2
        const val STATUS_OK = 200
        const val ENCODING_HEXADECIMAL = 3
        const val ROLE_INPUT = 1

        // PairingMessage fields
        const val PAIRING_REQUEST = 10
        const val PAIRING_REQUEST_ACK = 11
        const val PAIRING_OPTION = 20
        const val PAIRING_CONFIGURATION = 30
        const val PAIRING_CONFIGURATION_ACK = 31
        const val PAIRING_SECRET = 40
        const val PAIRING_SECRET_ACK = 41

        // RemoteMessage fields
        const val REMOTE_CONFIGURE = 1
        const val REMOTE_SET_ACTIVE = 2
        const val REMOTE_PING_REQUEST = 8
        const val REMOTE_PING_RESPONSE = 9
        const val REMOTE_KEY_INJECT = 10
        const val REMOTE_START = 40
        const val REMOTE_APP_LINK_LAUNCH = 90

        /** Feature bitmask advertised by official remotes (ping, key, IME, power, volume, app link). */
        const val FEATURES = 622
        const val DIRECTION_SHORT = 3

        /** Android `KeyEvent` codes. */
        val KEY_CODES = mapOf(
            RemoteKey.POWER to 26,
            RemoteKey.VOLUME_UP to 24,
            RemoteKey.VOLUME_DOWN to 25,
            RemoteKey.MUTE to 164,
            RemoteKey.CHANNEL_UP to 166,
            RemoteKey.CHANNEL_DOWN to 167,
            RemoteKey.UP to 19,
            RemoteKey.DOWN to 20,
            RemoteKey.LEFT to 21,
            RemoteKey.RIGHT to 22,
            RemoteKey.OK to 23,
            RemoteKey.BACK to 4,
            RemoteKey.HOME to 3,
            RemoteKey.MENU to 82,
            RemoteKey.INPUT to 178,
            RemoteKey.PLAY_PAUSE to 85,
            RemoteKey.PLAY to 126,
            RemoteKey.PAUSE to 127,
            RemoteKey.STOP to 86,
            RemoteKey.REWIND to 89,
            RemoteKey.FAST_FORWARD to 90,
        )

        val APP_LINKS = mapOf(
            StreamingApp.NETFLIX to "https://www.netflix.com/title",
            StreamingApp.YOUTUBE to "https://www.youtube.com",
            StreamingApp.PRIME_VIDEO to "https://app.primevideo.com",
            StreamingApp.DISNEY_PLUS to "https://www.disneyplus.com",
            StreamingApp.SPOTIFY to "spotify://",
            StreamingApp.MAX to "https://play.max.com",
        )
    }
}
