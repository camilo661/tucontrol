package com.tucontrol.presentation.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RemoteUiState(
    val deviceName: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val hapticsEnabled: Boolean = true,
)

class RemoteViewModel(
    private val session: RemoteSession,
    settings: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<RemoteUiState> = combine(
        session.device,
        session.state,
        settings.settings,
    ) { device, connection, prefs ->
        RemoteUiState(device?.name, connection, prefs.hapticsEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    fun press(key: RemoteKey) {
        viewModelScope.launch { session.press(key) }
    }

    companion object {
        val Factory = containerFactory { RemoteViewModel(session, settingsRepository) }
    }
}
