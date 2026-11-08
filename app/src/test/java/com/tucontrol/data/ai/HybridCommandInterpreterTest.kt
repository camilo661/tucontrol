package com.tucontrol.data.ai

import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import com.tucontrol.domain.repository.AppSettings
import com.tucontrol.domain.repository.CommandInterpreter
import com.tucontrol.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridCommandInterpreterTest {

    private class FakeSettings(aiEnabled: Boolean) : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings(aiEnabled = aiEnabled))
        override suspend fun setAiEnabled(enabled: Boolean) = Unit
        override suspend fun setHapticsEnabled(enabled: Boolean) = Unit
        override suspend fun setSonyPreSharedKey(key: String) = Unit
    }

    private val aiPlan = CommandPlan(listOf(TvAction.LaunchApp(StreamingApp.SPOTIFY)))

    private class CountingInterpreter(private val result: () -> CommandPlan) : CommandInterpreter {
        var calls = 0
        override suspend fun interpret(phrase: String): CommandPlan {
            calls++
            return result()
        }
    }

    @Test
    fun `simple phrases never reach the AI`() = runTest {
        val ai = CountingInterpreter { aiPlan }
        val hybrid = HybridCommandInterpreter(LocalCommandInterpreter(), ai, FakeSettings(aiEnabled = true))

        val plan = hybrid.interpret("sube el volumen")

        assertEquals(listOf(TvAction.Press(RemoteKey.VOLUME_UP, 3)), plan.actions)
        assertEquals(0, ai.calls)
    }

    @Test
    fun `complex phrases are delegated to the AI`() = runTest {
        val ai = CountingInterpreter { aiPlan }
        val hybrid = HybridCommandInterpreter(LocalCommandInterpreter(), ai, FakeSettings(aiEnabled = true))

        assertEquals(aiPlan, hybrid.interpret("pon algo de música relajante"))
        assertEquals(1, ai.calls)
    }

    @Test
    fun `AI failures fall back to the offline plan`() = runTest {
        val ai = CountingInterpreter { error("network down") }
        val hybrid = HybridCommandInterpreter(LocalCommandInterpreter(), ai, FakeSettings(aiEnabled = true))

        val plan = hybrid.interpret("sube el volumen y cuéntame un chiste")

        assertEquals(listOf(TvAction.Press(RemoteKey.VOLUME_UP, 3)), plan.actions)
    }

    @Test
    fun `AI is skipped when disabled in settings`() = runTest {
        val ai = CountingInterpreter { aiPlan }
        val hybrid = HybridCommandInterpreter(LocalCommandInterpreter(), ai, FakeSettings(aiEnabled = false))

        assertEquals(CommandPlan.EMPTY, hybrid.interpret("pon algo de música relajante"))
        assertEquals(0, ai.calls)
    }
}
