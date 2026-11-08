package com.tucontrol.presentation.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val deviceName: String = "",
    val connection: ConnectionState = ConnectionState.Connecting,
    val invalidPin: Boolean = false,
)

class PairingViewModel(private val session: RemoteSession) : ViewModel() {

    private val invalidPin = MutableStateFlow(false)

    val uiState: StateFlow<PairingUiState> = combine(
        session.device,
        session.state,
        invalidPin,
    ) { device, connection, invalid ->
        PairingUiState(device?.name.orEmpty(), connection, invalid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    fun submitPin(pin: String) {
        val pairing = session.pinPairing ?: return
        viewModelScope.launch {
            invalidPin.value = false
            invalidPin.value = !pairing.submitPin(pin)
        }
    }

    fun retry() {
        invalidPin.value = false
        viewModelScope.launch { session.retry() }
    }

    companion object {
        val Factory = containerFactory { PairingViewModel(session) }
    }
}
