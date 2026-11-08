package com.tucontrol.data.ai

import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import com.tucontrol.domain.repository.CommandInterpreter
import java.text.Normalizer

/**
 * Offline, rule-based interpreter for common Spanish TV commands.
 *
 * It answers instantly and without network, so it handles everyday phrases
 * ("sube el volumen 5", "abre Netflix", "apaga la tele") while the AI interpreter
 * covers anything more conversational.
 */
class LocalCommandInterpreter : CommandInterpreter {

    data class Result(val plan: CommandPlan, val unmatchedClauses: Int) {
        val isComplete: Boolean get() = !plan.isEmpty && unmatchedClauses == 0
    }

    override suspend fun interpret(phrase: String): CommandPlan = analyze(phrase).plan

    fun analyze(phrase: String): Result {
        val clauses = normalize(phrase)
            .split(CLAUSE_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var unmatched = 0
        val actions = clauses.flatMap { clause ->
            parseClause(clause).also { if (it.isEmpty() || it.isOpenEndedSearch()) unmatched++ }
        }
        return Result(CommandPlan(actions), unmatched)
    }

    /**
     * A search with no app ("busca la película de anoche") is a guess offline: it runs when no
     * AI is available, but is reported as not fully understood so the AI gets the first try.
     */
    private fun List<TvAction>.isOpenEndedSearch(): Boolean =
        singleOrNull().let { it is TvAction.Search && it.app == null }

    private fun parseClause(clause: String): List<TvAction> {
        val words = clause.split(' ').filter { it.isNotEmpty() }.toSet()
        fun has(vararg terms: String) = terms.any { term ->
            if (' ' in term) term in clause else term in words
        }
        val amount = extractNumber(words)
        val app = APP_ALIASES.entries.firstOrNull { (alias, _) -> has(alias) }?.value

        // "busca stranger things en netflix" → search, scoped to the app when one is named.
        SEARCH_PREFIX.find(clause)?.let { match ->
            val query = clause.substring(match.range.last + 1)
                .replace(APP_SUFFIX, "")
                .trim()
            return if (query.isNotEmpty()) listOf(TvAction.Search(query, app)) else emptyList()
        }

        app?.let { return listOf(TvAction.LaunchApp(it)) }

        val isVolume = has("volumen", "sonido", "audio")
        val isChannel = has("canal", "canales")
        val increase = has("sube", "subir", "subele", "aumenta", "aumentar", "mas", "siguiente", "arriba")
        val decrease = has("baja", "bajar", "bajale", "disminuye", "reduce", "menos", "anterior", "abajo")

        // "pon el canal 7" tunes directly; "sube dos canales" is handled below as presses.
        if (has("canal") && amount != null && !increase && !decrease) {
            return listOf(TvAction.TuneChannel(amount.coerceIn(1, TvAction.MAX_CHANNEL)))
        }

        val key: RemoteKey? = when {
            has("siguiente capitulo", "siguiente episodio", "siguiente cancion", "proximo capitulo", "proxima cancion") -> RemoteKey.NEXT
            has("capitulo anterior", "episodio anterior", "cancion anterior") -> RemoteKey.PREVIOUS
            has("subtitulos", "subtitulo") -> RemoteKey.CAPTIONS
            has("informacion", "info") -> RemoteKey.INFO
            has("guia", "programacion") -> RemoteKey.GUIDE
            has("ajustes", "configuracion") -> RemoteKey.SETTINGS
            has("silencio", "silencia", "silenciar", "mute", "quita el sonido", "apaga el sonido", "activa el sonido") -> RemoteKey.MUTE
            isVolume && increase -> RemoteKey.VOLUME_UP
            isVolume && decrease -> RemoteKey.VOLUME_DOWN
            has("mas alto", "mas fuerte") -> RemoteKey.VOLUME_UP
            has("mas bajo", "mas despacio") -> RemoteKey.VOLUME_DOWN
            isChannel && decrease -> RemoteKey.CHANNEL_DOWN
            isChannel -> RemoteKey.CHANNEL_UP
            has("apaga", "apagar", "apagala", "enciende", "encender", "enciendela", "prende", "prender") -> RemoteKey.POWER
            has("pausa", "pausar", "pausalo", "pausala") -> RemoteKey.PAUSE
            has("reproduce", "reproducir", "play", "continua", "reanuda", "sigue") -> RemoteKey.PLAY
            has("deten", "detener", "stop") -> RemoteKey.STOP
            has("adelanta", "adelantar", "avanza") -> RemoteKey.FAST_FORWARD
            has("retrocede", "retroceder", "rebobina", "atrasa") -> RemoteKey.REWIND
            has("atras", "regresa", "regresar", "volver", "vuelve") -> RemoteKey.BACK
            has("inicio", "home", "pantalla principal") -> RemoteKey.HOME
            has("menu", "opciones") -> RemoteKey.MENU
            has("entrada", "fuente", "hdmi") -> RemoteKey.INPUT
            has("arriba") -> RemoteKey.UP
            has("abajo") -> RemoteKey.DOWN
            has("izquierda") -> RemoteKey.LEFT
            has("derecha") -> RemoteKey.RIGHT
            has("ok", "aceptar", "acepta", "selecciona", "seleccionar", "enter", "entrar") -> RemoteKey.OK
            else -> null
        }
        key ?: return emptyList()

        val times = when (key) {
            RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN -> amount ?: DEFAULT_VOLUME_STEP
            RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT,
            RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN -> amount ?: 1
            else -> 1
        }.coerceIn(1, TvAction.MAX_REPEAT)

        return listOf(TvAction.Press(key, times))
    }

    private fun extractNumber(words: Set<String>): Int? =
        words.firstNotNullOfOrNull { word -> word.toIntOrNull() ?: NUMBER_WORDS[word] }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private companion object {
        const val DEFAULT_VOLUME_STEP = 3

        val DIACRITICS = Regex("\\p{Mn}+")
        val PUNCTUATION = Regex("[^a-z0-9, ]")
        val WHITESPACE = Regex("\\s+")
        val CLAUSE_SEPARATORS = Regex(",|\\by luego\\b|\\by despues\\b|\\bluego\\b|\\bdespues\\b|\\by\\b")

        val APP_ALIASES = linkedMapOf(
            "netflix" to StreamingApp.NETFLIX,
            "youtube" to StreamingApp.YOUTUBE,
            "you tube" to StreamingApp.YOUTUBE,
            "prime video" to StreamingApp.PRIME_VIDEO,
            "amazon" to StreamingApp.PRIME_VIDEO,
            "prime" to StreamingApp.PRIME_VIDEO,
            "disney" to StreamingApp.DISNEY_PLUS,
            "spotify" to StreamingApp.SPOTIFY,
            "hbo" to StreamingApp.MAX,
            "hbo max" to StreamingApp.MAX,
            "apple tv" to StreamingApp.APPLE_TV,
            "paramount" to StreamingApp.PARAMOUNT_PLUS,
            "crunchyroll" to StreamingApp.CRUNCHYROLL,
            "pluto" to StreamingApp.PLUTO_TV,
            "plex" to StreamingApp.PLEX,
            "vix" to StreamingApp.VIX,
        )

        val SEARCH_PREFIX = Regex("^(?:(?:por favor|quiero|puedes)\\s+)?(?:busca|buscar|buscame|encuentra)\\b")
        val APP_SUFFIX = Regex("\\s+(?:en|por)\\s+(?:netflix|youtube|you tube|prime video|amazon|prime|disney|spotify|hbo max|hbo|max|apple tv|paramount|crunchyroll|pluto|plex|vix)\\b.*$")

        val NUMBER_WORDS = mapOf(
            "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
            "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
            "diez" to 10, "quince" to 15, "veinte" to 20, "treinta" to 30,
        )
    }
}
