package com.tucontrol.data.speech

import com.tucontrol.domain.repository.VoiceEvent
import com.tucontrol.domain.repository.VoiceFailure
import com.tucontrol.domain.repository.VoiceRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Prefers the platform recognizer (most accurate) and falls back to offline Vosk when the
 * watch has none, or when it fails before hearing anything (e.g. no internet for a cloud
 * recognizer). Either way the user just taps and speaks.
 */
class DeviceVoiceRecognizer(
    private val system: SystemVoiceRecognizer,
    private val offline: VoskVoiceRecognizer,
) : VoiceRecognizer {

    override fun listen(): Flow<VoiceEvent> = flow {
        if (!system.isAvailable) {
            emitAll(offline.listen())
            return@flow
        }
        var heardSomething = false
        var fallBack = false
        system.listen().collect { event ->
            when {
                event is VoiceEvent.Partial -> {
                    heardSomething = true
                    emit(event)
                }
                event is VoiceEvent.Failed && event.reason == VoiceFailure.ENGINE && !heardSomething -> fallBack = true
                else -> emit(event)
            }
        }
        if (fallBack) emitAll(offline.listen())
    }
}
