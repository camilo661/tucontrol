package com.tucontrol.data.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import com.tucontrol.domain.repository.CommandInterpreter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * Uses Claude to understand free-form Spanish phrases ("ponle más volumen que no escucho
 * y luego abre Netflix") and returns a validated [CommandPlan].
 *
 * The response is constrained with a JSON schema (structured outputs), so it can be
 * decoded directly and every key/app is guaranteed to be one TuControl understands.
 */
class ClaudeCommandInterpreter(apiKey: String) : CommandInterpreter {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
        .maxRetries(1)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun interpret(phrase: String): CommandPlan = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            // Server-side fallback: if a safety classifier declines, the API retries on a
            // fallback model within the same call.
            .addBeta(FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .system(SYSTEM_PROMPT)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(BetaJsonOutputFormat.builder().schema(RESPONSE_SCHEMA).build())
                    .build(),
            )
            .addUserMessage(phrase)
            .build()

        val response = client.beta().messages().create(params)
        val stopReason = response.stopReason().orElse(null)
        if (stopReason == BetaStopReason.REFUSAL || stopReason == BetaStopReason.MAX_TOKENS) {
            return@withContext CommandPlan.EMPTY
        }

        val text = response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
        toPlan(json.decodeFromString<ClaudePlan>(text))
    }

    private fun toPlan(result: ClaudePlan): CommandPlan = CommandPlan(
        result.actions.mapNotNull { action ->
            val app = action.app.takeIf { it != NONE }?.let { runCatching { StreamingApp.valueOf(it) }.getOrNull() }
            when (action.type) {
                "press" -> runCatching { RemoteKey.valueOf(action.key) }.getOrNull()
                    ?.let { TvAction.Press(it, action.times.coerceIn(1, TvAction.MAX_REPEAT)) }
                "launch" -> app?.let { TvAction.LaunchApp(it) }
                "channel" -> action.number.takeIf { it in 1..TvAction.MAX_CHANNEL }?.let { TvAction.TuneChannel(it) }
                "search" -> action.query.trim().takeIf { it.isNotEmpty() }?.let { TvAction.Search(it, app) }
                else -> null
            }
        },
    )

    @Serializable
    private data class ClaudePlan(val actions: List<ClaudeAction> = emptyList())

    @Serializable
    private data class ClaudeAction(
        val type: String,
        val key: String = NONE,
        val times: Int = 1,
        val app: String = NONE,
        val number: Int = 0,
        val query: String = "",
    )

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val MAX_TOKENS = 2048L
        const val REQUEST_TIMEOUT_SECONDS = 12L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val NONE = "NONE"

        val SYSTEM_PROMPT = """
            You are the voice assistant of a smartwatch TV remote. The user speaks Spanish (any
            regional variant, often casual or with speech-recognition mistakes) and expects the
            TV to react immediately, exactly as if they had used the remote themselves.

            Return the ordered list of actions that fulfils the request. Every action has all
            fields; fill the ones it does not use with "NONE", 1, 0 or "" respectively.
            - "press": press `key` `times` times (1-${TvAction.MAX_REPEAT}).
            - "launch": open the streaming `app`.
            - "channel": tune channel `number` (e.g. "pon el canal 7", "ve al 105").
            - "search": search for `query` (titles, artists, topics), inside `app` when the user
              names one or the content clearly belongs to one; otherwise app "NONE".

            Guidelines:
            - Infer intent: "no oigo nada" → volume up; "está muy alto" → volume down; "quiero ver
              una peli de Marvel en Disney" → search "Marvel" in DISNEY_PLUS; "pon música de Bad
              Bunny" → search "Bad Bunny" in SPOTIFY if music, otherwise YOUTUBE.
            - Plan multi-step requests in execution order; the watch runs them on its own.
              To play specific content ("pon Despacito", "entra a YouTube y pon X"), search it
              and then press OK to play the first result. Default to YOUTUBE for songs and
              videos when no app is named.
            - Volume changes without an explicit amount mean 3 presses; "un poco" 2; "mucho" 6.
            - "apaga" / "enciende" / "prende" the TV all map to POWER.
            - "siguiente capítulo/canción" → NEXT; "anterior" media → PREVIOUS; "subtítulos" →
              CAPTIONS; "información" → INFO; "guía" → GUIDE; "ajustes/configuración" → SETTINGS.
            - Navigation words (arriba, abajo, izquierda, derecha, aceptar, atrás) map to the
              D-pad keys; "baja tres" while navigating means DOWN 3 times.
            - Fix obvious recognition errors ("net flix" → Netflix, "yutub" → YouTube).
            - If the phrase is not a TV request, return an empty actions list.
        """.trimIndent()

        val RESPONSE_SCHEMA: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("actions")))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "actions" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "additionalProperties" to false,
                                "required" to listOf("type", "key", "times", "app", "number", "query"),
                                "properties" to mapOf(
                                    "type" to mapOf(
                                        "type" to "string",
                                        "enum" to listOf("press", "launch", "channel", "search"),
                                    ),
                                    "key" to mapOf(
                                        "type" to "string",
                                        "enum" to RemoteKey.entries.map { it.name } + NONE,
                                    ),
                                    "times" to mapOf("type" to "integer"),
                                    "app" to mapOf(
                                        "type" to "string",
                                        "enum" to StreamingApp.entries.map { it.name } + NONE,
                                    ),
                                    "number" to mapOf("type" to "integer"),
                                    "query" to mapOf("type" to "string"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            .build()
    }
}
