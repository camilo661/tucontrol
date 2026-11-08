package com.tucontrol.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.tucontrol.R
import com.tucontrol.data.discovery.TvProber
import com.tucontrol.data.network.LanAddress
import com.tucontrol.data.network.WifiNetworkProvider
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import com.tucontrol.domain.repository.TvDiscovery
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val saved: List<TvDevice> = emptyList(),
    /** Discovered TVs that are not saved yet. */
    val found: List<TvDevice> = emptyList(),
    val activeId: String? = null,
    val activeConnection: ConnectionState = ConnectionState.Disconnected,
    val scanning: Boolean = false,
    val wifiUnavailable: Boolean = false,
    /** A manual "add by IP" lookup is running. */
    val probing: Boolean = false,
    @StringRes val manualError: Int? = null,
)

class DevicesViewModel(
    private val discovery: TvDiscovery,
    private val devices: DeviceRepository,
    private val session: RemoteSession,
    private val wifi: WifiNetworkProvider,
    private val prober: TvProber,
) : ViewModel() {

    private val discovered = MutableStateFlow<List<TvDevice>>(emptyList())
    private val scanning = MutableStateFlow(false)
    private val wifiUnavailable = MutableStateFlow(false)
    private val manual = MutableStateFlow(ManualLookup())
    private var scanJob: Job? = null

    val uiState: StateFlow<DevicesUiState> = combine(
        devices.savedDevices,
        discovered,
        session.device,
        session.state,
        combine(scanning, wifiUnavailable, manual, ::Triple),
    ) { saved, found, active, connection, (isScanning, noWifi, lookup) ->
        val savedIds = saved.map { it.id }.toSet()
        val savedHosts = saved.map { it.host }.toSet()
        DevicesUiState(
            saved = saved.sortedBy { it.name.lowercase() },
            found = found.filterNot { it.id in savedIds || it.host in savedHosts }.sortedBy { it.name.lowercase() },
            activeId = active?.id,
            activeConnection = connection,
            scanning = isScanning,
            wifiUnavailable = noWifi,
            probing = lookup.running,
            manualError = lookup.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    init {
        scan()
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanning.value = true
            manual.value = ManualLookup()
            val hasNetwork = wifi.acquire() != null
            wifiUnavailable.value = !hasNetwork
            if (!hasNetwork) {
                scanning.value = false
                return@launch
            }
            discovery.discover()
                .onStart { scanning.value = true }
                .onCompletion { scanning.value = false }
                .collect { device ->
                    // A host may be re-emitted with a better protocol; replace it.
                    discovered.update { current -> current.filterNot { it.host == device.host } + device }
                }
        }
    }

    fun select(device: TvDevice) = session.launchConnect(device)

    /** Looks up a TV by the IP address the user typed and connects to it when found. */
    fun addByAddress(input: String, onConnecting: () -> Unit) {
        val host = LanAddress.parsePrivateIpv4(input)
        if (host == null) {
            manual.value = ManualLookup(error = R.string.devices_manual_invalid)
            return
        }
        viewModelScope.launch {
            manual.value = ManualLookup(running = true)
            val network = wifi.acquire()
            if (network == null) {
                manual.value = ManualLookup(error = R.string.error_wifi)
                return@launch
            }
            val device = prober.identify(host, network)
            if (device == null) {
                manual.value = ManualLookup(error = R.string.devices_manual_not_found)
                return@launch
            }
            manual.value = ManualLookup()
            discovered.update { current -> current.filterNot { it.host == device.host } + device }
            select(device)
            onConnecting()
        }
    }

    fun forget(device: TvDevice) {
        viewModelScope.launch { session.forget(device) }
    }

    companion object {
        val Factory = containerFactory { DevicesViewModel(discovery, deviceRepository, session, wifi, prober) }
    }
}

private data class ManualLookup(val running: Boolean = false, @StringRes val error: Int? = null)
