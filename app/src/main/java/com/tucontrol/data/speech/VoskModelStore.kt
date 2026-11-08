package com.tucontrol.data.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads the compact offline Spanish model (~40 MB) once and keeps it in app storage.
 * It is fetched on first use instead of bundled so the APK stays small for the majority
 * of watches that already have a system recognizer.
 */
class VoskModelStore(context: Context, http: OkHttpClient) {

    private val root = File(context.applicationContext.filesDir, "speech")
    private val modelDir = File(root, MODEL_NAME)
    private val mutex = Mutex()

    private val http = http.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isInstalled: Boolean get() = File(modelDir, READY_MARKER).exists()

    /** Returns the model directory, downloading it first if needed. */
    suspend fun ensure(onProgress: (Float?) -> Unit): File = mutex.withLock {
        if (isInstalled) return modelDir
        withContext(Dispatchers.IO) {
            val staging = File(root, "$MODEL_NAME.partial").apply { deleteRecursively(); mkdirs() }
            try {
                download(staging, onProgress)
                // The archive contains a single top-level folder with the model.
                val extracted = staging.listFiles()?.singleOrNull { it.isDirectory } ?: throw IOException("Unexpected archive layout")
                modelDir.deleteRecursively()
                if (!extracted.renameTo(modelDir)) throw IOException("Could not install the model")
                File(modelDir, READY_MARKER).createNewFile()
            } finally {
                staging.deleteRecursively()
            }
        }
        modelDir
    }

    private suspend fun download(target: File, onProgress: (Float?) -> Unit) {
        http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Model download failed: ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
            val counting = CountingInputStream(body.byteStream()) { read ->
                onProgress(total?.let { read.toFloat() / it })
            }
            ZipInputStream(counting).use { zip ->
                val base = target.canonicalPath + File.separator
                generateSequence { zip.nextEntry }.forEach { entry ->
                    coroutineContext.ensureActive()
                    val file = File(target, entry.name)
                    // Reject entries that would escape the target directory ("zip slip").
                    if (!file.canonicalPath.startsWith(base)) throw IOException("Unsafe archive entry")
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
    }

    private class CountingInputStream(
        private val source: java.io.InputStream,
        private val onRead: (Long) -> Unit,
    ) : java.io.FilterInputStream(source) {
        private var count = 0L
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(bytes: Long) {
            count += bytes
            if (count - reported >= REPORT_STEP_BYTES) {
                reported = count
                onRead(count)
            }
        }
    }

    private companion object {
        const val MODEL_NAME = "vosk-model-small-es-0.42"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        const val READY_MARKER = ".ready"
        const val REPORT_STEP_BYTES = 512 * 1024L
    }
}
