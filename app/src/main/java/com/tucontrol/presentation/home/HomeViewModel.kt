package com.tucontrol.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.data.ai.HybridCommandInterpreter
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.presentation.common.containerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the voice assistant is doing right now. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    data object Thinking : VoiceState
    data class Executed(val plan: CommandPlan) : VoiceState
    data class NotUnderstood(val transcript: String) : VoiceState
    data object SendFailed : VoiceState
    data object Unavailable : VoiceState
}

data class HomeUiState(
    val device: TvDevice? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val voice: VoiceState = VoiceState.Idle,
    val hapticsEnabled: Boolean = true,
)

class HomeViewModel(
    private val session: RemoteSession,
    private val interpreter: HybridCommandInterpreter,
    settings: SettingsRepository,
) : ViewModel() {

    private val voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    private var resetJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        session.device,
        session.state,
        voice,
        settings.settings.map { it.hapticsEnabled },
    ) { device, connection, voiceState, haptics ->
        HomeUiState(device, connection, voiceState, haptics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onListeningStarted() {
        resetJob?.cancel()
        voice.value = VoiceState.Listening
    }

    fun onListeningCancelled() {
        voice.value = VoiceState.Idle
    }

    fun onVoiceUnavailable() = show(VoiceState.Unavailable)

    fun onTranscript(transcript: String) {
        voice.value = VoiceState.Thinking
        viewModelScope.launch {
            val plan = interpreter.interpret(transcript)
            val outcome = when {
                plan.isEmpty -> VoiceState.NotUnderstood(transcript)
                session.execute(plan) -> VoiceState.Executed(plan)
                else -> VoiceState.SendFailed
            }
            show(outcome)
        }
    }

    fun press(key: RemoteKey) {
        viewModelScope.launch { session.press(key) }
    }

    /** Shows a transient result, then returns to the idle prompt. */
    private fun show(state: VoiceState) {
        voice.value = state
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            delay(FEEDBACK_DURATION_MS)
            voice.value = VoiceState.Idle
        }
    }

    companion object {
        private const val FEEDBACK_DURATION_MS = 3_500L

        val Factory = containerFactory { HomeViewModel(session, interpreter, settingsRepository) }
    }
}
