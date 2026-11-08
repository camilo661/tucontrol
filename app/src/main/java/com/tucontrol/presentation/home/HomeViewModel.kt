package com.tucontrol.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucontrol.data.ai.HybridCommandInterpreter
import com.tucontrol.data.session.RemoteSession
import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.data.speech.AudioPcm
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.domain.repository.VoiceEvent
import com.tucontrol.domain.repository.VoiceFailure
import com.tucontrol.domain.repository.VoiceRecognizer
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

    /** Setting up offline speech; [progress] is the download fraction, `null` while loading. */
    data class Preparing(val progress: Float?) : VoiceState

    /** The microphone is open; [heard] is the live transcript. */
    data class Listening(val heard: String = "") : VoiceState
    data class Thinking(val transcript: String) : VoiceState

    /** Executing a multi-step plan on its own; [step] is the action running now (0-based). */
    data class Running(val plan: CommandPlan, val step: Int) : VoiceState
    data class Executed(val plan: CommandPlan) : VoiceState
    data class NotUnderstood(val transcript: String) : VoiceState
    data class AppUnavailable(val app: StreamingApp) : VoiceState

    /** The request was handed, as spoken, to the TV's own assistant. */
    data class SentToTvAssistant(val transcript: String) : VoiceState

    /** "abre <name>" for an app the TV does not have, or that has not been learned yet. */
    data class AppNotFound(val name: String, val canLearn: Boolean) : VoiceState
    data object NotHeard : VoiceState
    data object SendFailed : VoiceState
    data object MicDenied : VoiceState
    data object ModelUnavailable : VoiceState
    data object Unavailable : VoiceState

    val isActive: Boolean get() = this is Preparing || this is Listening || this is Thinking || this is Running
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
    private val recognizer: VoiceRecognizer,
    settings: SettingsRepository,
) : ViewModel() {

    private val voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    private var resetJob: Job? = null
    private var listenJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        session.device,
        session.state,
        voice,
        settings.settings.map { it.hapticsEnabled },
    ) { device, connection, voiceState, haptics ->
        HomeUiState(device, connection, voiceState, haptics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Tap on the microphone: start listening, or stop if already busy. */
    fun toggleListening() {
        if (voice.value.isActive) {
            cancelListening()
            return
        }
        resetJob?.cancel()
        listenJob = viewModelScope.launch {
            voice.value = VoiceState.Listening()
            recognizer.listen().collect { event ->
                when (event) {
                    is VoiceEvent.Preparing -> voice.value = VoiceState.Preparing(event.progress)
                    VoiceEvent.Ready -> voice.value = VoiceState.Listening()
                    is VoiceEvent.Partial -> voice.value = VoiceState.Listening(event.text)
                    is VoiceEvent.Final -> runCommand(event.text, event.audio)
                    is VoiceEvent.Failed -> show(
                        when (event.reason) {
                            VoiceFailure.NO_SPEECH -> VoiceState.NotHeard
                            VoiceFailure.MODEL_UNAVAILABLE -> VoiceState.ModelUnavailable
                            VoiceFailure.ENGINE -> VoiceState.Unavailable
                        },
                    )
                }
            }
        }
    }

    fun cancelListening() {
        listenJob?.cancel()
        voice.value = VoiceState.Idle
    }

    fun onMicrophoneDenied() = show(VoiceState.MicDenied)

    /** A typed command (fallback when the microphone is not allowed). */
    fun onTypedCommand(text: String) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch { runCommand(text) }
    }

    private suspend fun runCommand(transcript: String, audio: ByteArray? = null) {
        voice.value = VoiceState.Thinking(transcript)
        val plan = interpreter.interpret(transcript)
        if (audio != null && session.hasTvAssistant && needsTvAssistant(plan)) {
            show(if (session.sendVoiceToTv(AudioPcm.downsampleForTv(audio))) VoiceState.SentToTvAssistant(transcript) else VoiceState.SendFailed)
            return
        }
        val missingApp = session.unsupportedApps(plan).firstOrNull()
        val unknownApp = session.unknownApps(plan).firstOrNull()
        val outcome = when {
            plan.isEmpty -> VoiceState.NotUnderstood(transcript)
            missingApp != null -> VoiceState.AppUnavailable(missingApp)
            unknownApp != null -> VoiceState.AppNotFound(unknownApp, canLearn = !session.listsAllApps)
            session.execute(plan) { step -> if (plan.actions.size > 1) voice.value = VoiceState.Running(plan, step) } ->
                VoiceState.Executed(plan)
            else -> VoiceState.SendFailed
        }
        show(outcome)
    }

    /**
     * Requests TuControl cannot complete on its own but the TV's assistant can, exactly like
     * the remote's microphone: anything not understood, and opening other apps (the TV only
     * lets TuControl open store pages).
     */
    private fun needsTvAssistant(plan: CommandPlan): Boolean =
        plan.isEmpty || plan.actions.any { it is TvAction.OpenApp }

    fun press(key: RemoteKey) {
        viewModelScope.launch { session.press(key) }
    }

    /** Shows a transient result, then returns to the idle prompt. */
    private fun show(state: VoiceState) {
        voice.value = state
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            delay(if (state is VoiceState.AppNotFound) LONG_FEEDBACK_DURATION_MS else FEEDBACK_DURATION_MS)
            voice.value = VoiceState.Idle
        }
    }

    companion object {
        private const val FEEDBACK_DURATION_MS = 3_500L
        private const val LONG_FEEDBACK_DURATION_MS = 7_000L

        val Factory = containerFactory { HomeViewModel(session, interpreter, voiceRecognizer, settingsRepository) }
    }
}
