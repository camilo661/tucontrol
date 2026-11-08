package com.tucontrol.presentation.common

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.wear.input.RemoteInputIntentHelper

private const val RESULT_KEY = "tucontrol_text"

/**
 * Opens the system Wear OS text input (keyboard, handwriting or dictation) and returns
 * the entered text. Typing on a watch is best delegated to the platform.
 */
@Composable
fun rememberTextInputLauncher(
    label: String,
    onCancelled: () -> Unit = {},
    onUnavailable: () -> Unit = {},
    onResult: (String) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.takeIf { result.resultCode == Activity.RESULT_OK }
            ?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(RESULT_KEY)?.toString() }
            ?.takeIf { it.isNotBlank() }
        if (text != null) onResult(text) else onCancelled()
    }
    return {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(RESULT_KEY).setLabel(label).build()),
        )
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
