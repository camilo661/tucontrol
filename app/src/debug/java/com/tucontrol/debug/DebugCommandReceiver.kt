package com.tucontrol.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tucontrol.TuControlApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only test hook: runs a phrase exactly as if it had been spoken, so every TV function
 * can be exercised against a real TV without the microphone:
 *
 * `adb shell am broadcast -a com.tucontrol.debug.COMMAND --es text "sube el volumen"`
 *
 * or hands a recorded request to the TV's assistant (`--es voice <file in app storage>`).
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TuControlApp).container
        val pending = goAsync()
        // Show the first-run welcome guide again (`--ez reset_welcome true`).
        if (intent.getBooleanExtra(EXTRA_RESET_WELCOME, false)) {
            scope.launch {
                try {
                    container.settingsRepository.setWelcomeSeen(false)
                    Log.d(TAG, "welcome guide reset")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        // A raw 8 kHz PCM file in app storage: hand it to the TV's assistant as voice.
        intent.getStringExtra(EXTRA_VOICE_FILE)?.let { name ->
            scope.launch {
                try {
                    val pcm = java.io.File(context.filesDir, name).readBytes()
                    Log.d(TAG, "voice $name (${pcm.size} bytes) → ${container.session.sendVoiceToTv(pcm)}")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return pending.finish()
        scope.launch {
            try {
                val plan = container.interpreter.interpret(text)
                Log.d(TAG, "\"$text\" → ${plan.actions}")
                val unknown = container.session.unknownApps(plan)
                val result = when {
                    plan.isEmpty -> "NOT UNDERSTOOD"
                    unknown.isNotEmpty() -> "UNKNOWN APP $unknown"
                    container.session.execute(plan) { step -> Log.d(TAG, "step ${step + 1}/${plan.actions.size}") } -> "OK"
                    else -> "FAILED"
                }
                Log.d(TAG, "\"$text\" → $result")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TuControlTest"
        const val EXTRA_TEXT = "text"
        const val EXTRA_VOICE_FILE = "voice"
        const val EXTRA_RESET_WELCOME = "reset_welcome"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
