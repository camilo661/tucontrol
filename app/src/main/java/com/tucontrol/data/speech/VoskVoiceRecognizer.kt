package com.tucontrol.data.speech

import com.tucontrol.domain.repository.VoiceEvent
import com.tucontrol.domain.repository.VoiceFailure
import com.tucontrol.domain.repository.VoiceRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Fully offline Spanish recognition with Vosk, for watches without a system recognizer
 * (and the emulator). The model is downloaded on first use and kept in memory afterwards.
 */
class VoskVoiceRecognizer(private val models: VoskModelStore) : VoiceRecognizer {

    private val loadMutex = Mutex()
    private var model: Model? = null

    override fun listen(): Flow<VoiceEvent> = callbackFlow {
        val loaded = try {
            loadModel { progress -> trySend(VoiceEvent.Preparing(progress)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            send(VoiceEvent.Failed(VoiceFailure.MODEL_UNAVAILABLE))
            close()
            return@callbackFlow
        }

        // Vosk closes a segment at every short pause. Multi-step requests ("entra a YouTube…
        // y busca…") span several, so segments are joined until the user stays silent.
        val segments = mutableListOf<String>()
        var heard = ""
        var settle: Job? = null
        fun transcript() = (segments + heard).filter { it.isNotEmpty() }.joinToString(" ")
        val recognizer = Recognizer(loaded, SAMPLE_RATE)
        val service = try {
            SpeechService(recognizer, SAMPLE_RATE)
        } catch (_: Exception) {
            recognizer.close()
            send(VoiceEvent.Failed(VoiceFailure.ENGINE))
            close()
            return@callbackFlow
        }

        service.startListening(
            object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = field(hypothesis, "partial")
                    if (text.isNotEmpty() && text != heard) {
                        settle?.cancel() // Still talking.
                        heard = text
                        trySend(VoiceEvent.Partial(transcript()))
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = field(hypothesis, "text")
                    heard = ""
                    // Background noise is often decoded as a lone filler ("y", "eh"): skip it.
                    if (!isMeaningful(text)) return
                    segments += text
                    trySend(VoiceEvent.Partial(transcript()))
                    settle?.cancel()
                    settle = launch {
                        delay(SETTLE_SILENCE_MS)
                        finish(VoiceEvent.Final(transcript()))
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    field(hypothesis, "text").takeIf(::isMeaningful)?.let { segments += it }
                    complete()
                }

                override fun onTimeout() = complete()

                private fun complete() {
                    val text = transcript()
                    finish(if (isMeaningful(text)) VoiceEvent.Final(text) else VoiceEvent.Failed(VoiceFailure.NO_SPEECH))
                }

                override fun onError(exception: Exception?) {
                    finish(VoiceEvent.Failed(VoiceFailure.ENGINE))
                }

                private fun finish(event: VoiceEvent) {
                    trySend(event)
                    close()
                }
            },
            LISTEN_TIMEOUT_MS,
        )
        trySend(VoiceEvent.Ready)

        awaitClose {
            // cancel() stops and joins the audio thread; shutdown() only frees the recorder.
            // Closing the recognizer while that thread still feeds it crashes natively.
            service.cancel()
            service.shutdown()
            recognizer.close()
        }
    }

    private suspend fun loadModel(onProgress: (Float?) -> Unit): Model = loadMutex.withLock {
        model ?: run {
            if (!models.isInstalled) onProgress(0f)
            val directory = models.ensure(onProgress)
            onProgress(null) // Loading into memory takes a moment; no measurable progress.
            withContext(Dispatchers.IO) { Model(directory.absolutePath) }.also { model = it }
        }
    }

    private fun isMeaningful(text: String): Boolean =
        text.split(' ').any { word -> word.length >= MIN_WORD_LENGTH || word.any(Char::isDigit) }

    private fun field(json: String?, name: String): String = runCatching {
        Json.parseToJsonElement(json.orEmpty()).jsonObject[name]?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty().trim()

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val LISTEN_TIMEOUT_MS = 12_000
        const val MIN_WORD_LENGTH = 3

        /** Silence after a segment that means the request is complete. */
        const val SETTLE_SILENCE_MS = 1_300L
    }
}
