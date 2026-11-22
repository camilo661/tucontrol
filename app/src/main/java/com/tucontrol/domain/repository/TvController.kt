package com.tucontrol.domain.repository

import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.MediaQueueItem
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.SettingsSection
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvDevice
import kotlinx.coroutines.flow.StateFlow

/** Drives one TV through its native network protocol. */
/** Sample rate of the audio passed to [TvController.sendVoice]. */
const val VOICE_SAMPLE_RATE = 8_000

interface TvController {
    val device: TvDevice
    val state: StateFlow<ConnectionState>

    /** Opens (and if needed pairs) the connection. Progress is reported through [state]. */
    suspend fun connect()

    suspend fun press(key: RemoteKey)

    suspend fun launch(app: StreamingApp)

    /** Whether [launch] can open [app] on this TV platform. */
    fun canLaunch(app: StreamingApp): Boolean

    /**
     * Searches for [query], inside [app] when given (YouTube otherwise). Platforms without a
     * search API open the app instead, so the user lands one step away from the result.
     */
    suspend fun search(query: String, app: StreamingApp?) {
        (app ?: StreamingApp.YOUTUBE).takeIf(::canLaunch)?.let { launch(it) }
    }

    suspend fun disconnect()

    /**
     * Sets the volume to [percent] of the maximum. Returns `false` when the TV neither accepts
     * an absolute level nor reports its current one (so the right number of presses is unknown).
     */
    suspend fun setVolume(percent: Int): Boolean = false

    /**
     * Moves the video playing to [seconds] (or by [seconds] when [relative]) and keeps it
     * playing. Returns `false` when the TV gives no way to jump to an exact time.
     */
    suspend fun seek(seconds: Int, relative: Boolean): Boolean = false

    /** The play queue of the app playing; `null` when the TV cannot share it. */
    suspend fun mediaQueue(): List<MediaQueueItem>? = null

    suspend fun playQueueItem(id: Long): Boolean = false

    /**
     * Opens the TV settings. Most platforms expose no deep links into them, so the default is
     * the settings key; the user continues by voice ("baja", "selecciona").
     */
    suspend fun openSettings(section: SettingsSection) = press(RemoteKey.SETTINGS)

    /** Whether the TV has its own voice assistant that [sendVoice] can talk to. */
    val hasVoiceAssistant: Boolean get() = false

    /**
     * Hands a spoken request to the TV's own assistant, as the microphone button of its remote
     * does. [pcm] is 16-bit mono PCM at [VOICE_SAMPLE_RATE] Hz. Returns `false` if unsupported.
     */
    suspend fun sendVoice(pcm: ByteArray): Boolean = false

    /**
     * Highlights the on-screen item named [target] and presses OK ("selecciona Kalley").
     * Only TVs whose screen TuControl can read support it.
     */
    suspend fun select(target: String): Boolean = false

    /** Whether any installed app can be opened by name right now. */
    val canLaunchAnyApp: Boolean get() = listsAllApps

    /** Whether [installedApps] is the TV's full list (otherwise apps are learned as they are used). */
    val listsAllApps: Boolean get() = false

    /** Apps that can be opened by name: listed by the TV, or learned while the user uses it. */
    suspend fun installedApps(): List<InstalledApp> = emptyList()

    suspend fun open(app: InstalledApp) = Unit

    /**
     * For TVs that only reveal the app in use: label the next newly opened app as [name], so
     * "abre <name>" works after the user opens it once with the TV's own remote.
     */
    fun learnNextAppAs(name: String) = Unit
}

/** Implemented by controllers whose pairing requires a PIN shown on the TV. */
interface PinPairing {
    /** @return  when the code is malformed or does not match the one on screen. */
    suspend fun submitPin(pin: String): Boolean
}
