package com.tucontrol.data.ai

import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.StreamingApp
import com.tucontrol.domain.model.TvAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandInterpreterTest {

    private val interpreter = LocalCommandInterpreter()

    private fun actions(phrase: String) = interpreter.analyze(phrase).plan.actions

    @Test
    fun `volume up uses the spoken amount`() {
        assertEquals(listOf(TvAction.Press(RemoteKey.VOLUME_UP, 5)), actions("Sube el volumen 5"))
        assertEquals(listOf(TvAction.Press(RemoteKey.VOLUME_UP, 4)), actions("súbele cuatro al volumen"))
    }

    @Test
    fun `volume without amount uses the default step`() {
        assertEquals(listOf(TvAction.Press(RemoteKey.VOLUME_DOWN, 3)), actions("Baja el volumen"))
    }

    @Test
    fun `power commands map to the power key`() {
        listOf("Apaga la tele", "enciende el televisor", "prende la TV").forEach { phrase ->
            assertEquals(phrase, listOf(TvAction.Press(RemoteKey.POWER)), actions(phrase))
        }
    }

    @Test
    fun `mute phrases do not turn the TV off`() {
        assertEquals(listOf(TvAction.Press(RemoteKey.MUTE)), actions("apaga el sonido"))
        assertEquals(listOf(TvAction.Press(RemoteKey.MUTE)), actions("silencio"))
    }

    @Test
    fun `apps are launched by name`() {
        assertEquals(listOf(TvAction.LaunchApp(StreamingApp.NETFLIX)), actions("Abre Netflix"))
        assertEquals(listOf(TvAction.LaunchApp(StreamingApp.DISNEY_PLUS)), actions("pon disney plus"))
    }

    @Test
    fun `channel direction is detected`() {
        assertEquals(listOf(TvAction.Press(RemoteKey.CHANNEL_UP)), actions("siguiente canal"))
        assertEquals(listOf(TvAction.Press(RemoteKey.CHANNEL_DOWN, 2)), actions("baja dos canales"))
    }

    @Test
    fun `compound phrases produce ordered actions`() {
        assertEquals(
            listOf(
                TvAction.LaunchApp(StreamingApp.YOUTUBE),
                TvAction.Press(RemoteKey.VOLUME_UP, 2),
            ),
            actions("abre YouTube y luego sube el volumen dos"),
        )
    }

    @Test
    fun `unknown phrases are reported as incomplete`() {
        val result = interpreter.analyze("qué hora es")
        assertTrue(result.plan.isEmpty)
        assertFalse(result.isComplete)
    }

    @Test
    fun `partially understood phrases are flagged for the AI`() {
        val result = interpreter.analyze("sube el volumen y busca la película de anoche")
        assertFalse(result.plan.isEmpty)
        assertFalse(result.isComplete)
    }

    @Test
    fun `repeat count is clamped`() {
        assertEquals(listOf(TvAction.Press(RemoteKey.VOLUME_UP, TvAction.MAX_REPEAT)), actions("sube el volumen 99"))
    }
}
