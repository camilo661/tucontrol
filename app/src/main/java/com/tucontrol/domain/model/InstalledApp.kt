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

/**
 * Matches a spoken app name ("xuper") against the apps a TV has ("Xuper Crystal Clear").
 *
 * The app with the highest share of matching words wins, as long as at least half of the
 * spoken words match; a word matches when it is the same, a prefix ("xuper" ~ "xupertv"),
 * or close enough to absorb a recognition slip ("cristal" ~ "crystal").
 */
object AppNames {

    /** "Xúper TV+" → "xupertv": lowercase letters and digits only. */
    fun compact(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }

    /** Share of the spoken words found in [text] (0.0–1.0), with the same loose word matching. */
    fun coverage(spoken: String, text: String): Double {
        val wanted = words(spoken)
        if (wanted.isEmpty()) return 0.0
        val candidates = words(text)
        return wanted.count { word -> candidates.any { matches(word, it) } }.toDouble() / wanted.size
    }

    /** Whether [spoken] names [text] closely enough ([MIN_SIMILARITY] of its words). */
    fun names(spoken: String, text: String): Boolean = coverage(spoken, text) >= MIN_SIMILARITY

    /** Best match for [spoken] among [apps], or `null` if none reaches [MIN_SIMILARITY]. */
    fun find(spoken: String, apps: List<InstalledApp>): InstalledApp? {
        val wanted = words(spoken)
        if (wanted.isEmpty()) return null
        return apps
            .map { app -> app to similarity(wanted, app) }
            .filter { (_, score) -> score.coverage >= MIN_SIMILARITY }
            .maxWithOrNull(compareBy({ it.second.coverage }, { it.second.precision }))
            ?.first
    }

    /** How much of the spoken name the app covers, and how much of the app's name was said. */
    private data class Score(val coverage: Double, val precision: Double)

    private fun similarity(wanted: List<String>, app: InstalledApp): Score {
        val nameWords = words(app.name)
        val candidates = (nameWords + words(app.id.replace('.', ' '))).distinct()
        val matched = wanted.count { word -> candidates.any { matches(word, it) } }
        val named = nameWords.count { word -> wanted.any { matches(it, word) } }
        return Score(
            coverage = matched.toDouble() / wanted.size,
            precision = if (nameWords.isEmpty()) 0.0 else named.toDouble() / nameWords.size,
        )
    }

    private fun matches(a: String, b: String): Boolean = when {
        a == b -> true
        minOf(a.length, b.length) >= MIN_WORD && (a.startsWith(b) || b.startsWith(a)) -> true
        else -> 1.0 - editDistance(a, b).toDouble() / maxOf(a.length, b.length) >= CLOSE_WORD
    }

    /** Meaningful words: platform filler ("tv", "app", "com") does not identify an app. */
    private fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "") // "xúper" → "xuper", not "xu per"
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.length >= 2 && it !in FILLER }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private const val MIN_SIMILARITY = 0.5
    private const val CLOSE_WORD = 0.75
    private const val MIN_WORD = 3
    private val FILLER = setOf(
        "tv", "app", "apps", "la", "el", "de", "the", "com", "org", "net", "android", "google", "aplicacion", "leanback",
    )
}
