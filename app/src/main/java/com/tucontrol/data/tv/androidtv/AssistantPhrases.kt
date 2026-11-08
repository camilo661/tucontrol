package com.tucontrol.data.tv.androidtv

import android.content.res.AssetManager

/**
 * Short spoken requests bundled with the app (16-bit mono PCM, 8 kHz) that TuControl says to
 * the TV's assistant itself, for things the remote protocol cannot do directly. They work
 * the same on every watch, without depending on the user's own recording.
 */
class AssistantPhrases(private val assets: AssetManager) {

    /** "Abre configuración", then "open settings" for TVs whose assistant uses English. */
    val openSettings: List<ByteArray> by lazy { listOf(load("open_settings_es.pcm"), load("open_settings_en.pcm")) }

    private fun load(name: String): ByteArray = assets.open("voice/$name").use { it.readBytes() }
}
