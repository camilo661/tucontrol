package com.tucontrol.data.session

import com.tucontrol.data.network.WifiNetworkProvider
import com.tucontrol.data.tv.TvControllerFactory
import com.tucontrol.domain.model.AppNames
import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.ConnectionError
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import com.tucontrol.domain.repository.PinPairing
import com.tucontrol.domain.repository.TvController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-wide owner of the active TV connection. Screens observe [device] and [state];
 * every command goes through here so key presses are serialized and paced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSession(
    private val factory: TvControllerFactory,
    private val devices: DeviceRepository,
    private val wifi: WifiNetworkProvider,
    private val scope: CoroutineScope,
) {
    private val controller = MutableStateFlow<TvController?>(null)
    private val sessionError = MutableStateFlow<ConnectionError?>(null)
    private val commandLock = Mutex()

    /**
     * Every connect/disconnect goes through this lock. Returning from another screen (e.g.
     * the keyboard) triggers a reconnect while a command may reconnect too; without it the
     * TV could end up with two sessions from this watch.
     */
    private val connectionLock = Mutex()

    private val mutableDevice = MutableStateFlow<TvDevice?>(null)
    val device: StateFlow<TvDevice?> = mutableDevice.asStateFlow()

    val state: StateFlow<ConnectionState> = combine(
        controller.flatMapLatest { it?.state ?: flowOf(ConnectionState.Disconnected) },
        sessionError,
    ) { controllerState, error ->
        error?.let { ConnectionState.Failed(it) } ?: controllerState
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val pinPairing: PinPairing? get() = controller.value as? PinPairing

    /** Starts connecting in the app scope so it survives screen changes. */
    fun launchConnect(target: TvDevice) {
        scope.launch { connect(target) }
    }

    /** Called when the app returns to the foreground: re-link the current or last TV. */
    fun onForeground() {
        scope.launch {
            val current = mutableDevice.value
            if (current == null) {
                restore()
            } else if (state.value !is ConnectionState.AwaitingPin) {
                connect(current)
            }
        }
    }

    /** Called when the app leaves the foreground: free sockets and the Wi‑Fi radio. */
    fun onBackground() {
        scope.launch {
            commandLock.withLock {
                connectionLock.withLock {
                    if (state.value !is ConnectionState.AwaitingPin) controller.value?.disconnect()
                    wifi.release()
                }
            }
        }
    }

    /** Reconnects to the last used TV, if any. */
    suspend fun restore() {
        if (controller.value != null) return
        val activeId = devices.activeDeviceId.first() ?: return
        val saved = devices.savedDevices.first().firstOrNull { it.id == activeId } ?: return
        connect(saved)
    }

    suspend fun connect(target: TvDevice) = connectionLock.withLock {
        val current = controller.value
        // Already linked to this TV (e.g. a command reconnected first): nothing to do.
        if (current?.device?.id == target.id && current.state.value == ConnectionState.Connected) return@withLock
        sessionError.value = null
        if (current != null && current.device.id != target.id) current.disconnect()

        mutableDevice.value = target
        val next = current?.takeIf { it.device.id == target.id } ?: factory.create(target)
        controller.value = next
        if (next == null) {
            sessionError.value = ConnectionError.UNSUPPORTED
            return@withLock
        }
        if (wifi.acquire() == null) {
            sessionError.value = ConnectionError.NO_WIFI
            return@withLock
        }

        next.connect()
        if (next.state.value == ConnectionState.Connected || next.state.value == ConnectionState.AwaitingPin) {
            devices.save(target)
            devices.setActive(target.id)
        }
    }

    suspend fun retry() {
        mutableDevice.value?.let { connect(it) }
    }

    suspend fun disconnect() {
        controller.value?.disconnect()
        controller.value = null
        mutableDevice.value = null
        sessionError.value = null
    }

    suspend fun forget(target: TvDevice) {
        if (mutableDevice.value?.id == target.id) disconnect()
        devices.forget(target.id)
    }

    suspend fun press(key: RemoteKey): Boolean = execute(CommandPlan(listOf(TvAction.Press(key))))

    /** Apps in [plan] that the connected TV platform cannot open. */
    fun unsupportedApps(plan: CommandPlan): List<StreamingApp> {
        val active = controller.value ?: return emptyList()
        return plan.actions.mapNotNull { action ->
            when (action) {
                is TvAction.LaunchApp -> action.app
                is TvAction.Search -> action.app
                else -> null
            }
        }.filterNot(active::canLaunch).distinct()
    }

    /**
     * Names of apps in [plan] that the TV does not have (or has not been seen using yet).
     * On TVs that learn apps, the first one is armed to be learned the next time it opens.
     */
    suspend fun unknownApps(plan: CommandPlan): List<String> {
        val active = controller.value ?: return emptyList()
        val names = plan.actions.filterIsInstance<TvAction.OpenApp>().map { it.name }
        if (names.isEmpty()) return emptyList()
        val apps = runCatching { active.installedApps() }.getOrDefault(emptyList())
        val unknown = names.filter { AppNames.find(it, apps) == null }
        unknown.firstOrNull()?.let(active::learnNextAppAs)
        return unknown
    }

    /** Whether the connected TV has an assistant that can take the user's voice as-is. */
    val hasTvAssistant: Boolean get() = controller.value?.hasVoiceAssistant == true

    /** Hands [pcm] (16-bit mono, 8 kHz) to the TV's own assistant. */
    suspend fun sendVoiceToTv(pcm: ByteArray): Boolean = commandLock.withLock {
        val active = controller.value ?: return false
        if (active.state.value != ConnectionState.Connected) connect(active.device)
        runCatching { active.sendVoice(pcm) }.getOrDefault(false)
    }

    /** Whether the connected TV lists all its apps, rather than learning them as they are used. */
    val listsAllApps: Boolean get() = controller.value?.listsAllApps == true

    /**
     * Runs every action in order, autonomously, reporting each step as it starts through
     * [onStep] (0-based). Returns `false` when there is no usable connection.
     */
    suspend fun execute(plan: CommandPlan, onStep: (Int) -> Unit = {}): Boolean = commandLock.withLock {
        val active = controller.value ?: return false
        if (active.state.value != ConnectionState.Connected) {
            connect(active.device)
            if (active.state.value != ConnectionState.Connected) return false
        }
        try {
            plan.actions.forEachIndexed { step, action ->
                if (step > 0) {
                    // Apps and search results need a moment to load before they react to keys.
                    delay(
                        when (plan.actions[step - 1]) {
                            is TvAction.Search -> SEARCH_RESULTS_DELAY_MS
                            is TvAction.LaunchApp, is TvAction.OpenApp -> APP_OPEN_DELAY_MS
                            else -> STEP_DELAY_MS
                        },
                    )
                }
                onStep(step)
                when (action) {
                    is TvAction.Press -> repeat(action.times) { index ->
                        if (index > 0) delay(REPEAT_DELAY_MS)
                        active.press(action.key)
                    }
                    is TvAction.LaunchApp -> active.launch(action.app)
                    is TvAction.OpenApp -> AppNames.find(action.name, active.installedApps())?.let { active.open(it) }
                    is TvAction.OpenSettings -> active.openSettings(action.section)
                    is TvAction.TuneChannel -> RemoteKey.digitsOf(action.number).forEach { digit ->
                        active.press(digit)
                        delay(REPEAT_DELAY_MS)
                    }
                    is TvAction.Search -> active.search(action.query, action.app)
                }
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val REPEAT_DELAY_MS = 120L
        const val STEP_DELAY_MS = 400L
        const val APP_OPEN_DELAY_MS = 3_000L
        const val SEARCH_RESULTS_DELAY_MS = 4_500L
    }
}
