package com.tucontrol.data.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.tucontrol.domain.repository.VoiceEvent
import com.tucontrol.domain.repository.VoiceFailure
import com.tucontrol.domain.repository.VoiceRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully offline Spanish recognition with Vosk, for watches without a system recognizer
 * (and the emulator). The model is downloaded on first use and kept in memory afterwards.
 *
 * The microphone is read here rather than through Vosk's own service so the recording can
 * be kept: requests TuControl cannot resolve are handed, as spoken, to the TV's assistant.
 */
class VoskVoiceRecognizer(private val models: VoskModelStore) : VoiceRecognizer {

    private val loadMutex = Mutex()
    private var model: Model? = null

    @SuppressLint("MissingPermission") // The caller only listens once RECORD_AUDIO is granted.
    override fun listen(): Flow<VoiceEvent> = channelFlow {
        val loaded = try {
            loadModel { progress -> trySend(VoiceEvent.Preparing(progress)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            send(VoiceEvent.Failed(VoiceFailure.MODEL_UNAVAILABLE))
            return@channelFlow
        }

        val bufferBytes = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            READ_SAMPLES * 2,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            send(VoiceEvent.Failed(VoiceFailure.ENGINE))
            return@channelFlow
        }
        val recognizer = Recognizer(loaded, SAMPLE_RATE.toFloat())
        try {
            recorder.startRecording()
            send(VoiceEvent.Ready)
            send(transcribe(recorder, recognizer))
        } finally {
            recorder.stop()
            recorder.release()
            recognizer.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads the microphone until the request is complete. Vosk closes a segment at every short
     * pause and multi-step requests span several, so segments are joined until the user has
     * stayed silent for [SETTLE_SILENCE_MS].
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<VoiceEvent>.transcribe(
        recorder: AudioRecord,
        recognizer: Recognizer,
    ): VoiceEvent {
        val audio = ByteArrayOutputStream()
        val samples = ShortArray(READ_SAMPLES)
        val segments = mutableListOf<String>()
        var heard = ""
        var settleAt: Long? = null
        val deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS
        fun transcript() = (segments + heard).filter { it.isNotEmpty() }.joinToString(" ")

        while (isActive) {
            val read = recorder.read(samples, 0, samples.size)
            if (read > 0) {
                audio.write(toBytes(samples, read))
                if (recognizer.acceptWaveForm(samples, read)) {
                    val text = field(recognizer.result, "text")
                    heard = ""
                    // Background noise is often decoded as a lone filler ("y", "eh"): skip it.
                    if (isMeaningful(text)) {
                        segments += text
                        settleAt = System.currentTimeMillis() + SETTLE_SILENCE_MS
                        send(VoiceEvent.Partial(transcript()))
                    }
                } else {
                    val partial = field(recognizer.partialResult, "partial")
                    if (partial.isNotEmpty() && partial != heard) {
                        heard = partial
                        settleAt = null // Still talking.
                        send(VoiceEvent.Partial(transcript()))
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (settleAt?.let { now >= it } == true) break
            if (now >= deadline) {
                field(recognizer.finalResult, "text").takeIf(::isMeaningful)?.let { segments += it }
                heard = ""
                break
            }
        }
        val text = transcript()
        return if (isMeaningful(text)) VoiceEvent.Final(text, audio.toByteArray()) else VoiceEvent.Failed(VoiceFailure.NO_SPEECH)
    }

    private suspend fun loadModel(onProgress: (Float?) -> Unit): Model = loadMutex.withLock {
        model ?: run {
            if (!models.isInstalled) onProgress(0f)
            val directory = models.ensure(onProgress)
            onProgress(null) // Loading into memory takes a moment; no measurable progress.
            Model(directory.absolutePath).also { model = it }
        }
    }

    private fun toBytes(samples: ShortArray, count: Int): ByteArray =
        ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(samples, 0, count)
        }.array()

    private fun isMeaningful(text: String): Boolean =
        text.split(' ').any { word -> word.length >= MIN_WORD_LENGTH || word.any(Char::isDigit) }

    private fun field(json: String?, name: String): String = runCatching {
        Json.parseToJsonElement(json.orEmpty()).jsonObject[name]?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty().trim()

    private companion object {
        const val SAMPLE_RATE = AudioPcm.RECORDING_SAMPLE_RATE
        const val READ_SAMPLES = 1_600 // 100 ms
        const val LISTEN_TIMEOUT_MS = 12_000L
        const val MIN_WORD_LENGTH = 3

        /** Silence after a segment that means the request is complete. */
        const val SETTLE_SILENCE_MS = 1_300L
    }
}
