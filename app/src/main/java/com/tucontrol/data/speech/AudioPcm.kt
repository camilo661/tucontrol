package com.tucontrol.data.speech

import com.tucontrol.domain.repository.VOICE_SAMPLE_RATE

/** Helpers for the 16-bit little-endian mono PCM recorded by the watch. */
object AudioPcm {

    const val RECORDING_SAMPLE_RATE = 16_000

    /**
     * 16 kHz → 8 kHz for the TV's assistant: averaging each pair of samples acts as a simple
     * low-pass filter, which keeps speech intelligible without aliasing artifacts.
     */
    fun downsampleForTv(pcm16k: ByteArray): ByteArray {
        check(RECORDING_SAMPLE_RATE == VOICE_SAMPLE_RATE * 2)
        val out = ByteArray(pcm16k.size / 4 * 2)
        for (i in out.indices step 2) {
            val a = sample(pcm16k, i * 2)
            val b = sample(pcm16k, i * 2 + 2)
            val mean = (a + b) / 2
            out[i] = (mean and 0xFF).toByte()
            out[i + 1] = (mean shr 8 and 0xFF).toByte()
        }
        return out
    }

    private fun sample(pcm: ByteArray, offset: Int): Int =
        (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
}
