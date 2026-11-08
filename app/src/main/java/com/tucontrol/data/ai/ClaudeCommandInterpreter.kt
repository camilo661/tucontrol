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
            when (action.type) {
                "press" -> runCatching { RemoteKey.valueOf(action.key) }.getOrNull()
                    ?.let { TvAction.Press(it, action.times.coerceIn(1, TvAction.MAX_REPEAT)) }
                "launch" -> runCatching { StreamingApp.valueOf(action.app) }.getOrNull()
                    ?.let { TvAction.LaunchApp(it) }
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
    )

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val MAX_TOKENS = 2048L
        const val REQUEST_TIMEOUT_SECONDS = 12L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val NONE = "NONE"

        val SYSTEM_PROMPT = """
            You turn spoken TV remote commands into actions. The user speaks Spanish (any
            regional variant) to a smartwatch that controls their television.

            Return the ordered list of actions that fulfils the request:
            - "press": press a remote key `times` times (1-${TvAction.MAX_REPEAT}). Set app to "NONE".
            - "launch": open a streaming app. Set key to "NONE" and times to 1.

            Guidelines:
            - Volume changes without an explicit amount mean 3 presses; "un poco" means 2; "mucho" means 6.
            - "apaga" / "enciende" / "prende" the TV all map to POWER.
            - Navigation words (arriba, abajo, izquierda, derecha, aceptar, atrás) map to the D-pad keys.
            - If the phrase is not a TV command, return an empty actions list.
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
                                "required" to listOf("type", "key", "times", "app"),
                                "properties" to mapOf(
                                    "type" to mapOf("type" to "string", "enum" to listOf("press", "launch")),
                                    "key" to mapOf(
                                        "type" to "string",
                                        "enum" to RemoteKey.entries.map { it.name } + NONE,
                                    ),
                                    "times" to mapOf("type" to "integer"),
                                    "app" to mapOf(
                                        "type" to "string",
                                        "enum" to StreamingApp.entries.map { it.name } + NONE,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            .build()
    }
}
