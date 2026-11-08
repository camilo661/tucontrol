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
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val container = (context.applicationContext as TuControlApp).container
        val pending = goAsync()
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
