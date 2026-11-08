package com.tucontrol.data.ai

import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.repository.CommandInterpreter
import com.tucontrol.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Fast path first, AI second:
 * 1. Simple phrases fully understood offline are executed instantly.
 * 2. Anything else goes to Claude (when enabled and configured).
 * 3. If Claude is unavailable, whatever the offline parser understood is used.
 */
class HybridCommandInterpreter(
    private val local: LocalCommandInterpreter,
    private val remote: CommandInterpreter?,
    private val settings: SettingsRepository,
) : CommandInterpreter {

    val isAiConfigured: Boolean get() = remote != null

    override suspend fun interpret(phrase: String): CommandPlan {
        val offline = local.analyze(phrase)
        if (offline.isComplete) return offline.plan

        val ai = remote?.takeIf { settings.settings.first().aiEnabled } ?: return offline.plan
        return try {
            ai.interpret(phrase).takeUnless { it.isEmpty } ?: offline.plan
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            offline.plan
        }
    }
}
