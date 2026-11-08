package com.tucontrol.data.tv.androidtv

import android.util.Log
import com.tucontrol.BuildConfig
import com.tucontrol.data.tv.BaseTvController
import com.tucontrol.data.tv.TvRejectedException
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.SettingsSection
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import com.tucontrol.domain.repository.PinPairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLEncoder
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
    private val phrases: AssistantPhrases,
    private val agent: AdbScreenAgent,
) : BaseTvController(), PinPairing {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var pairingSocket: SSLSocket? = null
    private var serverKey: RSAPublicKey? = null
    private var remoteSocket: SSLSocket? = null
    private var reader: Job? = null
    /** A name the user asked for that is not known yet; given to the next newly opened app. */
    @Volatile private var nameForNextApp: String? = null
    private val foreground = MutableStateFlow<String?>(null)
    @Volatile private var isGoogleTv = false
    private var voiceBegin: CompletableDeferred<Long>? = null
    @Volatile private var nextAgentAttempt = 0L
    private val voiceLock = Mutex()

    override suspend fun connect() {
        mutableState.value = ConnectionState.Connecting
        try {
            if (devices.credential(device.id) == PAIRED) {
                val connected = runCatching { openRemoteSession() }.isSuccess
                if (connected) {
                    mutableState.value = ConnectionState.Connected
                    scope.launch { agentReady() } // Screen access, when the TV allows it.
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
        openLink(APP_LINKS[app] ?: return)
    }

    override fun canLaunch(app: StreamingApp) = app in APP_LINKS

    override suspend fun search(query: String, app: StreamingApp?) {
        ensureConnected()
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        when (app) {
            null, StreamingApp.YOUTUBE -> openLink("https://www.youtube.com/results?search_query=$encoded")
            StreamingApp.NETFLIX -> openLink("https://www.netflix.com/search?q=$encoded")
            else -> launch(app)
        }
    }

    /** Opens a web deep link through the remote service; the TV routes it to the matching app. */
    private suspend fun openLink(link: String) {
        sendRemote { message(REMOTE_APP_LINK_LAUNCH) { string(1, link) } }
    }

    override val hasVoiceAssistant = true

    /**
     * Same flow as the official remote's microphone: the search key makes the TV open its
     * assistant and offer a voice session, which is accepted by echoing it, fed with audio
     * and closed. The TV ignores chunks under 8 KB.
     */
    override suspend fun sendVoice(pcm: ByteArray): Boolean = voiceLock.withLock {
        ensureConnected()
        val begin = CompletableDeferred<Long>().also { voiceBegin = it }
        try {
            sendRemote { message(REMOTE_KEY_INJECT) { int(1, KEYCODE_SEARCH); int(2, DIRECTION_SHORT) } }
            val session = withTimeout(VOICE_BEGIN_TIMEOUT_MS) { begin.await() }
            sendRemote { message(REMOTE_VOICE_BEGIN) { int(1, session) } }
            pcm.asSequence().chunked(VOICE_CHUNK_BYTES).forEach { chunk ->
                val samples = chunk.toByteArray().copyOf(maxOf(chunk.size, VOICE_MIN_CHUNK_BYTES))
                sendRemote { message(REMOTE_VOICE_PAYLOAD) { int(1, session); bytes(2, samples) } }
            }
            sendRemote { message(REMOTE_VOICE_END) { int(1, session) } }
            true
        } finally {
            voiceBegin = null
        }
    }

    /**
     * This remote protocol has no settings deep links and many TVs ignore the settings key, so
     * TuControl does what a person does: asks the TV's assistant to open the settings, waits for
     * the TV to confirm it, then walks the menu with the D-pad to the requested section.
     */
    override suspend fun openSettings(section: SettingsSection) {
        ensureConnected()
        if (agentReady() && runCatching { agent.openSettings(section) }.getOrDefault(false)) return
        // Start from the home screen: a known state, and it reveals a Google TV launcher.
        pressKey(KEY_CODES.getValue(RemoteKey.HOME))
        withTimeoutOrNull(MENU_DELAY_MS) { foreground.first { it != SETTINGS_PACKAGE } }
        delay(MENU_DELAY_MS)
        val opened = phrases.openSettings.any { phrase ->
            sendVoice(phrase)
            withTimeoutOrNull(SETTINGS_OPEN_TIMEOUT_MS) { foreground.first { it == SETTINGS_PACKAGE } } != null
        }
        if (!opened) throw IOException("The TV did not open its settings")
        delay(MENU_DELAY_MS)

        val path = (if (isGoogleTv) GOOGLE_TV_SETTINGS else ANDROID_TV_SETTINGS)[section].orEmpty()
        if (path.isEmpty()) return
        repeat(MENU_TOP_PRESSES) { pressKey(KEY_CODES.getValue(RemoteKey.UP)) }
        for (downs in path) {
            repeat(downs) { pressKey(KEY_CODES.getValue(RemoteKey.DOWN)) }
            pressKey(KEY_CODES.getValue(RemoteKey.OK))
            delay(MENU_DELAY_MS)
        }
    }

    private suspend fun pressKey(code: Int) {
        sendRemote { message(REMOTE_KEY_INJECT) { int(1, code); int(2, DIRECTION_SHORT) } }
        delay(KEY_DELAY_MS)
    }

    override val canLaunchAnyApp: Boolean get() = agent.isConnected

    override suspend fun select(target: String): Boolean =
        agentReady() && runCatching { agent.select(target) }.getOrDefault(false)

    /**
     * Screen access, reconnecting when needed (e.g. after the user approved the watch on the
     * TV). A failed attempt is not repeated for a while, so TVs without debugging stay fast.
     */
    private suspend fun agentReady(): Boolean {
        if (agent.isConnected) return true
        if (System.currentTimeMillis() < nextAgentAttempt) return false
        return agent.connect(device.host).also { ok ->
            if (!ok) nextAgentAttempt = System.currentTimeMillis() + AGENT_RETRY_MS
        }
    }

    override suspend fun installedApps(): List<InstalledApp> {
        val learned = devices.learnedApps(device.id)
        if (agentReady()) {
            // The TV's full list, keeping names learned or known for the same packages.
            val names = (learned + KNOWN_APPS).associate { it.id to it.name }
            runCatching { agent.launchableApps() }.getOrNull()?.let { apps ->
                return apps.map { app -> InstalledApp(app.id, names[app.id] ?: app.id) }
            }
        }
        return learned + KNOWN_APPS.filterNot { known -> learned.any { it.id == known.id } }
    }

    override suspend fun open(app: InstalledApp) {
        ensureConnected()
        if (agentReady() && runCatching { agent.launch(app.id) }.isSuccess) return
        // The remote service only follows web links (market:// and intent: links are ignored or
        // drop the session), so the app opens through its Play Store page, one OK from "Abrir".
        openLink("https://play.google.com/store/apps/details?id=${app.id}")
    }

    override fun learnNextAppAs(name: String) {
        nameForNextApp = name
    }

    private suspend fun onForegroundApp(packageName: String) {
        if (packageName == foreground.value) return
        foreground.value = packageName
        if (packageName == GOOGLE_TV_LAUNCHER) isGoogleTv = true
        if (BuildConfig.DEBUG) Log.d(TAG, "Foreground app on ${device.name}: $packageName")
        if (packageName in SYSTEM_PACKAGES) return
        val known = installedApps().firstOrNull { it.id == packageName }
        val requested = nameForNextApp
        when {
            requested != null && known == null -> {
                devices.learnApp(device.id, InstalledApp(packageName, requested))
                nameForNextApp = null
            }
            known == null -> devices.learnApp(device.id, InstalledApp(packageName, nameFromPackage(packageName)))
        }
    }

    /** "com.xupertv.app" → "xupertv": the most descriptive segment of a package name. */
    private fun nameFromPackage(packageName: String): String =
        packageName.split('.')
            .filterNot { it in GENERIC_PACKAGE_SEGMENTS }
            .maxByOrNull { it.length }
            ?: packageName

    override suspend fun disconnect() {
        agent.close()
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
        closeRemote() // Never keep a previous session alive alongside the new one.
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
                    message.has(REMOTE_VOICE_BEGIN) ->
                        voiceBegin?.complete(message.message(REMOTE_VOICE_BEGIN)?.long(1) ?: 0L)
                    message.has(REMOTE_SET_VOLUME_LEVEL) && BuildConfig.DEBUG -> {
                        val volume = message.message(REMOTE_SET_VOLUME_LEVEL)
                        Log.d(TAG, "Volume on ${device.name}: ${volume?.long(7)}/${volume?.long(6)} muted=${volume?.long(8) == 1L}")
                    }
                    message.has(REMOTE_IME_KEY_INJECT) -> {
                        // The TV reports the app in the foreground; learn it to open it by name later.
                        message.message(REMOTE_IME_KEY_INJECT)?.message(1)?.string(APP_PACKAGE)?.let { onForegroundApp(it) }
                    }
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
        const val TAG = "AndroidTv"
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
        const val REMOTE_IME_KEY_INJECT = 20
        const val REMOTE_START = 40
        const val REMOTE_SET_VOLUME_LEVEL = 50
        const val REMOTE_VOICE_BEGIN = 30
        const val REMOTE_VOICE_PAYLOAD = 31
        const val REMOTE_VOICE_END = 32

        const val KEYCODE_SEARCH = 84

        const val AGENT_RETRY_MS = 60_000L
        const val SETTINGS_PACKAGE = "com.android.tv.settings"
        const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
        const val SETTINGS_OPEN_TIMEOUT_MS = 7_000L
        const val MENU_DELAY_MS = 1_200L
        const val KEY_DELAY_MS = 180L
        const val MENU_TOP_PRESSES = 12

        /**
         * D-pad paths through the settings menu: for each level, how many rows down to move
         * before OK. Google TV: Configurar Google TV · Canales y entradas · Pantalla y sonido ·
         * Red e Internet · Cuentas y perfiles · Privacidad · Apps · Sistema · Accesibilidad ·
         * Controles remotos y accesorios · Ayuda. Inside "Remotes & accessories", "Pair
         * accessory" comes first; inside "Display & sound", "Picture" comes first.
         */
        val GOOGLE_TV_SETTINGS = mapOf(
            SettingsSection.PICTURE to listOf(2, 0),
            SettingsSection.SOUND to listOf(2),
            SettingsSection.NETWORK to listOf(3),
            SettingsSection.BLUETOOTH to listOf(9),
            SettingsSection.BLUETOOTH_PAIRING to listOf(9, 0),
        )

        /** Classic Android TV: Red e Internet · Cuentas · Apps · Preferencias · Controles remotos y accesorios. */
        val ANDROID_TV_SETTINGS = mapOf(
            SettingsSection.PICTURE to listOf(3),
            SettingsSection.SOUND to listOf(3),
            SettingsSection.NETWORK to listOf(0),
            SettingsSection.BLUETOOTH to listOf(4),
            SettingsSection.BLUETOOTH_PAIRING to listOf(4, 0),
        )
        const val VOICE_BEGIN_TIMEOUT_MS = 3_000L
        const val VOICE_CHUNK_BYTES = 20 * 1024
        const val VOICE_MIN_CHUNK_BYTES = 8 * 1024

        /** RemoteAppInfo.app_package inside RemoteImeKeyInject.app_info. */
        const val APP_PACKAGE = 12

        /** Launchers, the assistant and system UI are not apps the user asks for by name. */
        val SYSTEM_PACKAGES = setOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.katniss",
            "com.google.android.apps.tv.dreamx", // Ambient mode / screensaver
            "com.android.systemui",
            "com.google.android.tv.remote.service",
        )
        val GENERIC_PACKAGE_SEGMENTS = setOf("com", "org", "net", "tv", "android", "app", "apps", "google", "leanback", "atv", "androidtv")

        /** Common Android TV apps, so they open by name before ever being learned. */
        val KNOWN_APPS = listOf(
            InstalledApp("com.google.android.youtube.tv", "YouTube"),
            InstalledApp("com.google.android.youtube.tvmusic", "YouTube Music"),
            InstalledApp("com.google.android.youtube.tvkids", "YouTube Kids"),
            InstalledApp("com.netflix.ninja", "Netflix"),
            InstalledApp("com.amazon.amazonvideo.livingroom", "Prime Video"),
            InstalledApp("com.disney.disneyplus", "Disney+"),
            InstalledApp("com.spotify.tv.android", "Spotify"),
            InstalledApp("com.android.vending", "Play Store"),
            InstalledApp("com.google.android.videos", "Google TV"),
            InstalledApp("com.apple.atve.androidtv.appletv", "Apple TV"),
            InstalledApp("com.cbs.ott", "Paramount+"),
            InstalledApp("com.crunchyroll.crunchyroid", "Crunchyroll"),
            InstalledApp("tv.pluto.android", "Pluto TV"),
            InstalledApp("com.plexapp.android", "Plex"),
            InstalledApp("com.univision.prendetv", "ViX"),
            InstalledApp("tv.twitch.android.app", "Twitch"),
            InstalledApp("org.xbmc.kodi", "Kodi"),
            InstalledApp("org.videolan.vlc", "VLC"),
            InstalledApp("com.tubitv", "Tubi"),
            InstalledApp("deezer.android.tv", "Deezer"),
        )
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
            RemoteKey.NEXT to 87,
            RemoteKey.PREVIOUS to 88,
            RemoteKey.INFO to 165,
            RemoteKey.GUIDE to 172,
            RemoteKey.CAPTIONS to 175,
            RemoteKey.SETTINGS to 176,
            RemoteKey.SEARCH to 84,
            RemoteKey.BRIGHTNESS_DOWN to 220,
            RemoteKey.BRIGHTNESS_UP to 221,
            // KEYCODE_0..KEYCODE_9 are consecutive (7..16).
            *RemoteKey.entries.filter { it.name.startsWith("NUM_") }
                .map { it to 7 + it.name.removePrefix("NUM_").toInt() }
                .toTypedArray(),
        )

        val APP_LINKS = mapOf(
            StreamingApp.NETFLIX to "https://www.netflix.com/title",
            StreamingApp.YOUTUBE to "https://www.youtube.com",
            StreamingApp.PRIME_VIDEO to "https://app.primevideo.com",
            StreamingApp.DISNEY_PLUS to "https://www.disneyplus.com",
            StreamingApp.SPOTIFY to "https://open.spotify.com",
            StreamingApp.MAX to "https://play.max.com",
            StreamingApp.APPLE_TV to "https://play.google.com/store/apps/details?id=com.apple.atve.androidtv.appletv",
            StreamingApp.PARAMOUNT_PLUS to "https://play.google.com/store/apps/details?id=com.cbs.ott",
            StreamingApp.CRUNCHYROLL to "https://play.google.com/store/apps/details?id=com.crunchyroll.crunchyroid",
            StreamingApp.PLUTO_TV to "https://play.google.com/store/apps/details?id=tv.pluto.android",
            StreamingApp.PLEX to "https://play.google.com/store/apps/details?id=com.plexapp.android",
            StreamingApp.VIX to "https://play.google.com/store/apps/details?id=com.univision.prendetv",
        )
    }
}
