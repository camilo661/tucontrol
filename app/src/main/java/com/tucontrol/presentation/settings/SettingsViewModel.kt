package com.tucontrol.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.domain.repository.AppSettings
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val aiConfigured: Boolean = false,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    aiConfigured: Boolean,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(it, aiConfigured) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(aiConfigured = aiConfigured))

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAiEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setHapticsEnabled(enabled) }
    }

    fun setSonyPreSharedKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch { repository.setSonyPreSharedKey(key) }
    }

    companion object {
        val Factory = containerFactory { SettingsViewModel(settingsRepository, interpreter.isAiConfigured) }
    }
}
