package com.tucontrol.domain.repository

import kotlinx.coroutines.flow.Flow

/** Turns the user's speech into text, live. */
interface VoiceRecognizer {
    /**
     * Listens for one utterance. The flow ends after [VoiceEvent.Final] or
     * [VoiceEvent.Failed]; cancelling the collection stops the microphone.
     */
    fun listen(): Flow<VoiceEvent>
}

sealed interface VoiceEvent {
    /** The speech engine is being set up (e.g. downloading the offline model). */
    data class Preparing(val progress: Float?) : VoiceEvent

    /** The microphone is open. */
    data object Ready : VoiceEvent

    /** What has been heard so far; may still change. */
    data class Partial(val text: String) : VoiceEvent

    data class Final(val text: String) : VoiceEvent

    data class Failed(val reason: VoiceFailure) : VoiceEvent
}

enum class VoiceFailure {
    /** Nothing intelligible was said before the engine gave up. */
    NO_SPEECH,

    /** The offline speech model could not be downloaded. */
    MODEL_UNAVAILABLE,

    /** The engine failed (busy, no network for a cloud recognizer, …). */
    ENGINE,
}
