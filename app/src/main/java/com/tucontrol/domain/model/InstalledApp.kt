package com.tucontrol.domain.model

import kotlinx.serialization.Serializable
import java.text.Normalizer

/**
 * An app on the TV that can be opened by name, beyond the built-in [StreamingApp]s
 * (e.g. an IPTV or regional app). [id] is the platform identifier: an Android package,
 * a Roku channel id or a webOS app id.
 */
@Serializable
data class InstalledApp(val id: String, val name: String)

/** Matches a spoken app name ("xuper tv") against the apps a TV has. */
object AppNames {

    /** "Xúper TV+" → "xupertv": lowercase letters and digits only. */
    fun compact(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * Best match for [spoken] by name, falling back to the identifier (Android packages often
     * contain the app's name, e.g. "com.xupertv.app"). Returns `null` if nothing is close.
     */
    fun find(spoken: String, apps: List<InstalledApp>): InstalledApp? {
        val wanted = compact(spoken).takeIf { it.length >= MIN_LENGTH } ?: return null
        return apps
            .mapNotNull { app -> score(wanted, app)?.let { app to it } }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(wanted: String, app: InstalledApp): Int? {
        val name = compact(app.name)
        val id = compact(app.id)
        return when {
            name == wanted -> 4
            name.startsWith(wanted) || wanted.startsWith(name) && name.length >= MIN_LENGTH -> 3
            wanted in name -> 2
            wanted in id -> 1
            else -> null
        }
    }

    private const val MIN_LENGTH = 3
}
