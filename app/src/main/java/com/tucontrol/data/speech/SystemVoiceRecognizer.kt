package com.tucontrol.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.tucontrol.domain.repository.VoiceEvent
import com.tucontrol.domain.repository.VoiceFailure
import com.tucontrol.domain.repository.VoiceRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * The platform recognizer (Google speech services on most watches), driven in-app so the
 * user never leaves TuControl and sees words appear as they speak.
 */
class SystemVoiceRecognizer(context: Context) : VoiceRecognizer {

    private val context = context.applicationContext

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(): Flow<VoiceEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    trySend(VoiceEvent.Ready)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.bestMatch()?.let { trySend(VoiceEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    val text = results.bestMatch()
                    trySend(if (text != null) VoiceEvent.Final(text) else VoiceEvent.Failed(VoiceFailure.NO_SPEECH))
                    close()
                }

                override fun onError(error: Int) {
                    val reason = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NO_SPEECH
                        else -> VoiceFailure.ENGINE
                    }
                    trySend(VoiceEvent.Failed(reason))
                    close()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, spanishLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer must be used from the main thread.

    private fun Bundle?.bestMatch(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** Keeps the user's regional Spanish (es-MX, es-CO, …) when the device already uses it. */
    private fun spanishLanguageTag(): String {
        val locale = Locale.getDefault()
        return if (locale.language == "es") locale.toLanguageTag() else "es-419"
    }
}
