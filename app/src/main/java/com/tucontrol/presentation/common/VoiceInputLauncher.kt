package com.tucontrol.presentation.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import java.util.Locale

/**
 * Launches the platform speech recognizer in Spanish and returns the best transcript.
 * Using the system recognizer gives the best accuracy and needs no microphone permission.
 */
@Composable
fun rememberVoiceInputLauncher(
    prompt: String,
    onResult: (String) -> Unit,
    onCancelled: () -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { result.resultCode == Activity.RESULT_OK && it.isNotBlank() }
        if (transcript != null) onResult(transcript) else onCancelled()
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, spanishLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}

/** Keeps the user's regional Spanish (es-MX, es-CO, …) when the device already uses it. */
private fun spanishLanguageTag(): String {
    val locale = Locale.getDefault()
    return if (locale.language == "es") locale.toLanguageTag() else "es-419"
}
