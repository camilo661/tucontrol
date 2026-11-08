package com.tucontrol.data.tv.androidtv

import android.util.Log
import com.tucontrol.BuildConfig
import com.tucontrol.domain.model.InstalledApp
import com.tucontrol.domain.model.SettingsSection
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Sees and drives an Android TV the way a person with the remote does, through the TV's
 * network debugging (ADB). It reads the screen, opens any installed app or settings page and
 * moves the highlight to whatever the user names ("conéctate al Kalley") before pressing OK.
 *
 * It needs "Depuración por USB" enabled on the TV and the watch approved once on the TV's
 * prompt. Without it, the controller falls back to the TV's assistant and fixed menu paths.
 */
class AdbScreenAgent(private val keyDirectory: File) {

    private val mutex = Mutex()
    @Volatile private var dadb: Dadb? = null

    val isConnected: Boolean get() = dadb != null

    /** Connects to [host]; the first time, the TV asks the user to allow this watch. */
    suspend fun connect(host: String): Boolean = mutex.withLock {
        if (dadb != null) return true
        withContext(Dispatchers.IO) {
            runCatching {
                Dadb.create(host, ADB_PORT, keyPair(), CONNECT_TIMEOUT_MS, APPROVAL_TIMEOUT_MS)
                    .also { check(it.shell("echo ok").output.trim() == "ok") }
            }.onFailure { log("ADB unavailable on $host: ${it.message}") }.getOrNull()
        }?.also { dadb = it } != null
    }

    fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }

    /** Every app with a TV launcher entry, named after its package ("com.xuper.crystalclear"). */
    suspend fun launchableApps(): List<InstalledApp> =
        shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
            .lineSequence()
            .mapNotNull { line -> line.trim().substringBefore('/', "").takeIf { '/' in line && it.isNotEmpty() } }
            .distinct()
            .map { InstalledApp(it, it) }
            .toList()

    suspend fun launch(packageName: String) {
        shell("monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1")
    }

    /** Opens a settings page directly; `false` if this TV has no such page. */
    suspend fun openSettings(section: SettingsSection): Boolean {
        val action = SETTINGS_ACTIONS.getValue(section)
        // -S restarts the settings app, so the page opens at its top rather than wherever the
        // user left it (e.g. inside a device's page).
        val output = shell("am start -S -a $action")
        if ("Error" in output) return false
        if (section == SettingsSection.BLUETOOTH_PAIRING) {
            delay(SCREEN_SETTLE_MS)
            return PAIRING_LABELS.any { select(it) }
        }
        return true
    }

    /** The UI hierarchy, dumped to a temporary file (there is no terminal over this connection). */
    suspend fun snapshot(): ScreenSnapshot =
        ScreenSnapshot.parse(shell("uiautomator dump $DUMP_FILE >/dev/null 2>&1; cat $DUMP_FILE; rm -f $DUMP_FILE"))

    /**
     * Highlights the on-screen item named [target] with the D-pad, then presses OK. Returns
     * `false` when nothing on screen matches.
     */
    suspend fun select(target: String): Boolean {
        repeat(SELECT_ATTEMPTS) {
            var screen = snapshot()
            log("Screen: ${screen.texts.take(12)} items=${screen.items.size} focused=${screen.focusedItem?.bounds}")
            if (screen.find(target) == null) screen = explore(screen, target) ?: return false
            val wanted = screen.find(target) ?: return false
            val item = screen.itemOf(wanted) ?: wanted
            var focused = screen.focusedItem
            if (focused == null) {
                // After a touch the TV hides the highlight; any D-pad key brings it back.
                keys(KEY_DOWN)
                screen = snapshot()
                focused = screen.focusedItem ?: return false
            }
            if (focused.bounds.contains(item.bounds.centerX, item.bounds.centerY)) {
                keys(KEY_CENTER)
                log("Selected \"${wanted.text}\" for \"$target\"")
                return true
            }
            val steps = stepsBetween(screen, focused, item)
            log("Moving ${steps.size} × ${steps.firstOrNull()} toward \"${wanted.text}\"")
            keys(*steps)
        }
        return false
    }

    /**
     * Scrolls the list in focus looking for [target], the way a person scans a menu: a few
     * steps one way, then the other. Returns the screen where it appeared, or `null`.
     */
    private suspend fun explore(start: ScreenSnapshot, target: String): ScreenSnapshot? {
        val focused = start.focusedItem ?: return null
        val sameRow = start.items.count { abs(it.bounds.centerY - focused.bounds.centerY) < (focused.bounds.bottom - focused.bounds.top) / 2 }
        val directions = if (sameRow > 1) intArrayOf(KEY_LEFT, KEY_RIGHT) else intArrayOf(KEY_UP, KEY_DOWN)
        for (direction in directions) {
            var previous = start.texts
            for (batch in 1..EXPLORE_BATCHES) {
                keys(*IntArray(EXPLORE_STEP) { direction })
                val screen = snapshot()
                if (screen.find(target) != null) return screen
                if (screen.texts == previous) break // End of the list this way.
                previous = screen.texts
            }
        }
        return null
    }

    /** D-pad presses from [from] to [to]: by row count in a list, by direction otherwise. */
    private fun stepsBetween(screen: ScreenSnapshot, from: ScreenSnapshot.Node, to: ScreenSnapshot.Node): IntArray {
        val dx = to.bounds.centerX - from.bounds.centerX
        val dy = to.bounds.centerY - from.bounds.centerY
        if (abs(dy) >= abs(dx)) {
            val range = minOf(from.bounds.centerY, to.bounds.centerY) + 1..maxOf(from.bounds.centerY, to.bounds.centerY)
            val rows = screen.rowsBetween(range).coerceIn(1, MAX_STEPS)
            return IntArray(rows) { if (dy > 0) KEY_DOWN else KEY_UP }
        }
        val columns = screen.items.count { it.bounds.centerX in minOf(from.bounds.centerX, to.bounds.centerX) + 1..maxOf(from.bounds.centerX, to.bounds.centerX) && abs(it.bounds.centerY - from.bounds.centerY) < from.bounds.bottom - from.bounds.top }
        return IntArray(columns.coerceIn(1, MAX_STEPS)) { if (dx > 0) KEY_RIGHT else KEY_LEFT }
    }

    private suspend fun keys(vararg codes: Int) {
        shell("input keyevent ${codes.joinToString(" ")}")
        delay(KEY_SETTLE_MS)
    }

    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        val connection = dadb ?: error("ADB not connected")
        try {
            connection.shell(command).output
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun keyPair(): AdbKeyPair {
        keyDirectory.mkdirs()
        val private = File(keyDirectory, "adbkey")
        val public = File(keyDirectory, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "TvAgent"
        const val ADB_PORT = 5555
        const val DUMP_FILE = "/data/local/tmp/tucontrol_screen.xml"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val APPROVAL_TIMEOUT_MS = 30_000
        const val SELECT_ATTEMPTS = 4
        const val MAX_STEPS = 30
        const val EXPLORE_STEP = 3
        const val EXPLORE_BATCHES = 5
        const val KEY_SETTLE_MS = 400L
        const val SCREEN_SETTLE_MS = 1_500L

        const val KEY_UP = 19
        const val KEY_DOWN = 20
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22
        const val KEY_CENTER = 23

        val SETTINGS_ACTIONS = mapOf(
            SettingsSection.GENERAL to "android.settings.SETTINGS",
            SettingsSection.NETWORK to "android.settings.WIFI_SETTINGS",
            SettingsSection.PICTURE to "android.settings.DISPLAY_SETTINGS",
            SettingsSection.SOUND to "android.settings.SOUND_SETTINGS",
            SettingsSection.BLUETOOTH to "com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES",
            SettingsSection.BLUETOOTH_PAIRING to "com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES",
        )

        /** The "pair accessory" entry, as TVs label it. */
        val PAIRING_LABELS = listOf("accesorio de emparejamiento", "vincular accesorio", "pair accessory")
    }
}
