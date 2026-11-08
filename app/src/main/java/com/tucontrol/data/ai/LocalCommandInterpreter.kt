package com.tucontrol.data.ai

import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.SettingsSection
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import com.tucontrol.domain.repository.CommandInterpreter
import java.text.Normalizer

/**
 * TuControl's built-in assistant: a free, offline planner for Spanish TV requests.
 *
 * It splits a request into ordered steps and carries context between them, so
 * "entra a YouTube y busca Despacito y ponla" becomes open YouTube → search → play the
 * first result. It answers instantly with no network or API key; the optional AI
 * interpreter only complements it for free-form conversation.
 */
class LocalCommandInterpreter : CommandInterpreter {

    data class Result(val plan: CommandPlan, val unmatchedClauses: Int) {
        val isComplete: Boolean get() = !plan.isEmpty && unmatchedClauses == 0
    }

    override suspend fun interpret(phrase: String): CommandPlan = analyze(phrase).plan

    fun analyze(phrase: String): Result {
        val steps = mutableListOf<Step>()
        for ((separator, clause) in splitClauses(normalize(phrase))) {
            val previous = steps.lastOrNull()
            // A separator inside a title ("busca tom y jerry") splits it; glue the piece back.
            if (previous?.isContentRequest == true && !COMMAND_START.containsMatchIn(clause)) {
                steps.removeAt(steps.lastIndex)
                val merged = previous.clause + separator + clause
                val actions = parseClause(merged, previous.context, previous.inOtherApp, steps.lastOrNull()?.actions?.lastOrNull())
                steps += Step(merged, actions, previous.context, previous.inOtherApp)
                continue
            }
            val context = previous?.contextAfter
            val inOtherApp = previous?.inOtherAppAfter == true
            steps += Step(clause, parseClause(clause, context, inOtherApp, previous?.actions?.lastOrNull()), context, inOtherApp)
        }
        val unmatched = steps.count { it.actions.isEmpty() || it.isOpenEnded }
        return Result(CommandPlan(collapseSettings(steps.flatMap { it.actions })), unmatched)
    }

    /** "abre ajustes y prende el bluetooth": opening a section already opens the settings. */
    private fun collapseSettings(actions: List<TvAction>): List<TvAction> =
        actions.filterIndexed { index, action ->
            !(action == TvAction.OpenSettings() && actions.getOrNull(index + 1) is TvAction.OpenSettings)
        }

    /** One clause of the request and the actions planned for it. */
    private class Step(
        val clause: String,
        val actions: List<TvAction>,
        val context: StreamingApp?,
        val inOtherApp: Boolean = false,
    ) {
        val isContentRequest: Boolean get() = actions.any { it is TvAction.Search }

        /**
         * Content requested without naming an app or being in one ("pon algo relajante") is a
         * best guess offline: it runs as planned, but the optional AI gets the first try.
         */
        val isOpenEnded: Boolean
            get() = isContentRequest && context == null && APP_ALIASES.keys.none { alias -> alias in clause }

        /** Whether the TV is in an app opened by name (no built-in search) after this step. */
        val inOtherAppAfter: Boolean
            get() = when (actions.lastOrNull { it is TvAction.LaunchApp || it is TvAction.Search || it is TvAction.OpenApp }) {
                is TvAction.OpenApp -> true
                null -> inOtherApp
                else -> false
            }

        /** The app the TV is in after this step, used by later steps ("…y busca X"). */
        val contextAfter: StreamingApp?
            get() = actions.asReversed().firstNotNullOfOrNull {
                when (it) {
                    is TvAction.LaunchApp -> it.app
                    is TvAction.Search -> it.app
                    else -> null
                }
            } ?: context.takeUnless { inOtherAppAfter }
    }

    /** Splits on "y", "luego", "después", commas…, keeping each clause's separator. */
    private fun splitClauses(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var start = 0
        var separator = ""
        CLAUSE_SEPARATORS.findAll(text).forEach { match ->
            text.substring(start, match.range.first).trim().takeIf { it.isNotEmpty() }?.let { result += separator to it }
            separator = " ${match.value.trim()} ".replace(" , ", ", ")
            start = match.range.last + 1
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { result += separator to it }
        return result
    }

    private fun parseClause(clause: String, context: StreamingApp?, inOtherApp: Boolean, previous: TvAction?): List<TvAction> {
        val words = clause.split(' ').filter { it.isNotEmpty() }.toSet()
        fun has(vararg terms: String) = terms.any { term ->
            if (' ' in term) term in clause else term in words
        }
        val amount = extractNumber(words)
        val app = APP_ALIASES.entries.firstOrNull { (alias, _) -> has(alias) }?.value

        // "busca stranger things en netflix" → search, in the named app or the one already open.
        // "busca dispositivos bluetooth" is about pairing, not content.
        SEARCH_PREFIX.find(clause)?.takeUnless { has(*BLUETOOTH_TERMS) }?.let { match ->
            val query = contentAfter(clause, match.range.last + 1)
            // Apps opened by name have no search TuControl can drive; don't fall back to YouTube.
            if (query.isEmpty() || (app == null && inOtherApp)) return emptyList()
            return listOf(TvAction.Search(query, app ?: context))
        }

        // "pon despacito en youtube" → search, then play the first result.
        PLAY_PREFIX.find(clause)?.let { match ->
            val content = contentAfter(clause, match.range.last + 1)
            val isContent = content.isNotEmpty() && !APP_NAME.matches(content) && !CONTROL_WORDS.containsMatchIn(content)
            if (isContent && (app != null || !inOtherApp)) {
                return listOf(TvAction.Search(content, app ?: context ?: StreamingApp.YOUTUBE), TvAction.Press(RemoteKey.OK))
            }
        }

        // "…y ponla" right after a search plays the highlighted result.
        if (previous is TvAction.Search && has(*PLAY_RESULT_WORDS)) return listOf(TvAction.Press(RemoteKey.OK))

        app?.let { return listOf(TvAction.LaunchApp(it)) }

        // "abre xuper tv" / "abre play store": any other app, matched by name on the TV.
        // TV functions ("abre ajustes", "abre la búsqueda") fall through to the keys below.
        OPEN_PREFIX.find(clause)?.let { match ->
            val name = contentAfter(clause, match.range.last + 1).replace(LEADING_ARTICLE, "").trim()
            if (name.isNotEmpty() && !TV_FUNCTION.containsMatchIn(name)) return listOf(TvAction.OpenApp(name))
        }

        val isVolume = has("volumen", "sonido", "audio")
        val isChannel = has("canal", "canales")
        val increase = has("sube", "subir", "subele", "aumenta", "aumentar", "mas", "siguiente", "arriba")
        val decrease = has("baja", "bajar", "bajale", "disminuye", "reduce", "menos", "anterior", "abajo")

        // "pon el canal 7" tunes directly; "sube dos canales" is handled below as presses.
        if (has("canal") && amount != null && !increase && !decrease) {
            return listOf(TvAction.TuneChannel(amount.coerceIn(1, TvAction.MAX_CHANNEL)))
        }

        // TV settings in everyday words: "sube el brillo", "prende el bluetooth", "modo cine".
        if (has("brillo")) {
            return listOf(
                when {
                    increase -> TvAction.Press(RemoteKey.BRIGHTNESS_UP, (amount ?: DEFAULT_STEP).coerceIn(1, TvAction.MAX_REPEAT))
                    decrease -> TvAction.Press(RemoteKey.BRIGHTNESS_DOWN, (amount ?: DEFAULT_STEP).coerceIn(1, TvAction.MAX_REPEAT))
                    else -> TvAction.OpenSettings(SettingsSection.PICTURE)
                },
            )
        }
        SETTINGS_SECTIONS.firstOrNull { (terms, _) -> has(*terms) }?.let { (_, section) ->
            // "conecta mis audífonos por bluetooth": open Bluetooth and start pairing.
            val pairing = section == SettingsSection.BLUETOOTH && has(*PAIRING_VERBS)
            return listOf(TvAction.OpenSettings(if (pairing) SettingsSection.BLUETOOTH_PAIRING else section))
        }
        if (has("ajustes", "ajuste", "configuracion", "configuraciones", "configurar", "opciones del tv", "opciones de la tele")) {
            return listOf(TvAction.OpenSettings())
        }

        val key: RemoteKey? = when {
            has("siguiente capitulo", "siguiente episodio", "siguiente cancion", "proximo capitulo", "proxima cancion") -> RemoteKey.NEXT
            has("capitulo anterior", "episodio anterior", "cancion anterior") -> RemoteKey.PREVIOUS
            has("subtitulos", "subtitulo") -> RemoteKey.CAPTIONS
            has("informacion", "info") -> RemoteKey.INFO
            has("guia", "programacion") -> RemoteKey.GUIDE
            has("busqueda", "buscador", "lupa") -> RemoteKey.SEARCH
            has("silencio", "silencia", "silenciar", "mute", "quita el sonido", "apaga el sonido", "activa el sonido") -> RemoteKey.MUTE
            isVolume && increase -> RemoteKey.VOLUME_UP
            isVolume && decrease -> RemoteKey.VOLUME_DOWN
            has("mas alto", "mas fuerte") -> RemoteKey.VOLUME_UP
            has("mas bajo", "mas despacio") -> RemoteKey.VOLUME_DOWN
            isChannel && decrease -> RemoteKey.CHANNEL_DOWN
            isChannel -> RemoteKey.CHANNEL_UP
            // Only the TV itself: "apaga la luz" or "prende el aire" must never switch the TV off.
            has(*POWER_VERBS) && words.all { it in POWER_VERBS || it in POWER_TARGET_WORDS } -> RemoteKey.POWER
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
            // Bare "baja/sube dos" moves through menus; "bájale/súbele" stays volume (above).
            has("baja", "bajar") -> RemoteKey.DOWN
            has("sube", "subir") -> RemoteKey.UP
            has("bajale", "subele") -> if (has("bajale")) RemoteKey.VOLUME_DOWN else RemoteKey.VOLUME_UP
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

    /** The requested content: the text after the verb, without fillers or a trailing "en <app>". */
    private fun contentAfter(clause: String, index: Int): String =
        clause.substring(index).replace(APP_SUFFIX, "").trim().replace(CONTENT_FILLER, "").trim()

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
        const val DEFAULT_STEP = 2

        val POWER_VERBS = arrayOf(
            "apaga", "apagar", "apagala", "apagalo", "enciende", "encender", "enciendela", "enciendelo",
            "prende", "prender", "prendela", "prendelo",
        )
        val POWER_TARGET_WORDS = setOf(
            "la", "el", "mi", "tele", "televisor", "television", "tv", "pantalla", "por", "favor", "porfa", "ya", "ahora",
        )

        val BLUETOOTH_TERMS = arrayOf(
            "bluetooth", "blutu", "blutooth", "bluetuth", "audifonos", "auriculares", "parlante", "bocina",
        )
        val PAIRING_VERBS = arrayOf(
            "conecta", "conectate", "conectar", "conectalo", "empareja", "emparejar", "vincula", "vincular", "enlaza",
            "agrega", "anade", "busca",
        )

        /** Spoken terms for each settings area. */
        val SETTINGS_SECTIONS = listOf(
            BLUETOOTH_TERMS to SettingsSection.BLUETOOTH,
            arrayOf("wifi", "wi fi", "wifai", "internet", "red", "redes", "conexion a internet") to SettingsSection.NETWORK,
            arrayOf(
                "modo de imagen", "imagen", "modo cine", "modo pelicula", "modo deportes", "modo deporte", "modo juego",
                "modo vivido", "modo estandar", "contraste", "nitidez", "colores", "color de la pantalla",
            ) to SettingsSection.PICTURE,
            arrayOf("modo de sonido", "ecualizador", "sonido envolvente", "graves", "agudos", "salida de audio") to
                SettingsSection.SOUND,
        )

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
        val OPEN_PREFIX = Regex(
            "^(?:(?:por favor|quiero|puedes)\\s+)?(?:abre|abrir|abreme|entra|entrar|ve|vete|inicia|iniciar|lanza|" +
                "ejecuta)(?:\\s+(?:a|al|en))?(?:\\s+la\\s+(?:app|aplicacion)(?:\\s+de)?)?\\b",
        )
        val LEADING_ARTICLE = Regex("^(?:el|la|los|las)\\s+")

        /** Things "abre …" can mean on the TV itself rather than an app. */
        val TV_FUNCTION = Regex(
            "^(?:ajustes|ajuste|configuracion|configuraciones|busqueda|buscador|lupa|menu|opciones|inicio|pantalla principal|guia|" +
                "bluetooth|wifi|internet|red|redes|brillo|imagen|modo|sonido|ecualizador|" +
                "programacion|entrada|fuente|hdmi|subtitulos|informacion|info|canal)\\b",
        )

        val PLAY_PREFIX = Regex(
            "^(?:(?:por favor|quiero|puedes)\\s+)?(?:ponme|pon|poner|reproduce|reproducir|reproduceme|toca|tocame|" +
                "escucha|escuchar|oir|ver|mira|mirar|muestrame)\\b",
        )
        /** "la canción despacito" → "despacito", "música de bad bunny" → "bad bunny". */
        val CONTENT_FILLER = Regex(
            "^(?:(?:la|una|el|un|unas|unos)\\s+)?(?:cancion|canciones|tema|video|videos|pelicula|serie|capitulo)\\s+" +
                "(?:de\\s+)?|^musica de\\s+",
        )

        /** Just an app's name ("disney plus", "la app de youtube"), not content to play. */
        val APP_NAME = Regex(
            "^(?:la\\s+)?(?:app|aplicacion)?\\s*(?:de\\s+)?(?:netflix|youtube|you tube|prime video|amazon|prime|" +
                "disney|spotify|hbo max|hbo|max|apple tv|paramount|crunchyroll|pluto|plex|vix)" +
                "(?:\\s+(?:plus|tv|video|max|prime))?$",
        )

        /** Words that mean a control request, not content ("pon pausa", "pon el canal 7"). */
        val CONTROL_WORDS = Regex(
            "\\b(?:canal|volumen|pausa|silencio|subtitulos|play|inicio|menu|entrada|hdmi|ajustes|configuracion|" +
                "modo|imagen|brillo|bluetooth|wifi|internet|ecualizador|" +
                "atras|arriba|abajo|izquierda|derecha|siguiente|anterior|informacion|guia)\\b",
        )
        val PLAY_RESULT_WORDS = arrayOf(
            "ponla", "ponlo", "reproducela", "reproducelo", "dale play", "abrela", "abrelo", "el primero",
            "la primera", "dale", "ponle play",
        )

        /** A clause that begins a new command rather than continuing a title. */
        val COMMAND_START = Regex(
            "^(?:sube|subir|subele|baja|bajar|bajale|abre|abrir|entra|entrar|ve|vete|busca|buscar|buscame|pon|ponme|" +
                "ponla|ponlo|ponle|reproduce|reproducela|reproducelo|apaga|enciende|prende|pausa|silencia|cambia|" +
                "regresa|vuelve|selecciona|dale|abrela|abrelo|el primero|la primera|toca|escucha|mira|quita|activa|" +
                "adelanta|retrocede|deten|aumenta|disminuye|canal|volumen)\\b",
        )

        val APP_SUFFIX = Regex("\\s+(?:en|por)\\s+(?:netflix|youtube|you tube|prime video|amazon|prime|disney|spotify|hbo max|hbo|max|apple tv|paramount|crunchyroll|pluto|plex|vix)\\b.*$")

        val NUMBER_WORDS = mapOf(
            "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
            "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
            "diez" to 10, "quince" to 15, "veinte" to 20, "treinta" to 30,
        )
    }
}
