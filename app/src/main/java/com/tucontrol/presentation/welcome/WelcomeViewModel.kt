package com.tucontrol.presentation.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WelcomeViewModel(private val settings: SettingsRepository) : ViewModel() {

    /** `null` while loading, so the first screen is only chosen once it is known. */
    val welcomeSeen: StateFlow<Boolean?> = settings.settings
        .map { it.welcomeSeen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markSeen() {
        viewModelScope.launch { settings.setWelcomeSeen() }
    }

    companion object {
        val Factory = containerFactory { WelcomeViewModel(settingsRepository) }
    }
}
