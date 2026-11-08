package com.tucontrol.data.ai

import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.SettingsSection
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

    @Test
    fun `a channel number tunes it directly`() {
        assertEquals(listOf(TvAction.TuneChannel(7)), actions("Pon el canal 7"))
        assertEquals(listOf(TvAction.TuneChannel(9)), actions("canal nueve"))
        assertEquals(listOf(TvAction.Press(RemoteKey.CHANNEL_UP, 2)), actions("sube dos canales"))
    }

    @Test
    fun `search is scoped to the named app`() {
        assertEquals(
            listOf(TvAction.Search("stranger things", StreamingApp.NETFLIX)),
            actions("Busca Stranger Things en Netflix"),
        )
        assertEquals(listOf(TvAction.Search("recetas de pasta", null)), actions("busca recetas de pasta"))
    }

    @Test
    fun `media and menu keys`() {
        assertEquals(listOf(TvAction.Press(RemoteKey.NEXT)), actions("siguiente capítulo"))
        assertEquals(listOf(TvAction.Press(RemoteKey.CAPTIONS)), actions("activa los subtítulos"))
        assertEquals(listOf(TvAction.LaunchApp(StreamingApp.CRUNCHYROLL)), actions("abre crunchyroll"))
    }

    @Test
    fun `number keys spell each digit`() {
        assertEquals(listOf(RemoteKey.NUM_1, RemoteKey.NUM_0, RemoteKey.NUM_5), RemoteKey.digitsOf(105))
    }

    @Test
    fun `multi-step request is planned in order with app context`() {
        assertEquals(
            listOf(
                TvAction.LaunchApp(StreamingApp.YOUTUBE),
                TvAction.Search("despacito", StreamingApp.YOUTUBE),
                TvAction.Press(RemoteKey.OK),
            ),
            actions("Entra a YouTube y busca la canción Despacito y ponla"),
        )
    }

    @Test
    fun `play requests search and play the first result`() {
        val play = listOf(TvAction.Search("despacito", StreamingApp.YOUTUBE), TvAction.Press(RemoteKey.OK))
        assertEquals(play, actions("Pon Despacito"))
        assertEquals(play, actions("reproduce la canción despacito en YouTube"))
        assertEquals(
            listOf(TvAction.Search("bad bunny", StreamingApp.SPOTIFY), TvAction.Press(RemoteKey.OK)),
            actions("pon música de Bad Bunny en Spotify"),
        )
    }

    @Test
    fun `titles containing y are not split into commands`() {
        assertEquals(
            listOf(TvAction.Search("tom y jerry", StreamingApp.YOUTUBE), TvAction.Press(RemoteKey.OK)),
            actions("pon tom y jerry"),
        )
        assertEquals(
            listOf(TvAction.Search("tom y jerry", StreamingApp.YOUTUBE), TvAction.Press(RemoteKey.VOLUME_UP, 3)),
            actions("entra a youtube, busca tom y jerry y sube el volumen").drop(1),
        )
    }

    @Test
    fun `control phrases with play verbs stay controls`() {
        assertEquals(listOf(TvAction.LaunchApp(StreamingApp.NETFLIX)), actions("pon Netflix"))
        assertEquals(listOf(TvAction.TuneChannel(7)), actions("pon el canal 7"))
        assertEquals(listOf(TvAction.Press(RemoteKey.PAUSE)), actions("pon pausa"))
    }

    @Test
    fun `any other app is opened by its spoken name`() {
        assertEquals(listOf(TvAction.OpenApp("xuper tv")), actions("Abre Xuper TV"))
        assertEquals(listOf(TvAction.OpenApp("play store")), actions("abre play store"))
        assertEquals(listOf(TvAction.Press(RemoteKey.SEARCH)), actions("abre la búsqueda"))
        assertEquals(listOf(TvAction.OpenApp("magis tv")), actions("entra a la aplicación de Magis TV"))
        assertEquals(listOf(TvAction.OpenSettings()), actions("abre configuración"))
        assertEquals(listOf(TvAction.LaunchApp(StreamingApp.YOUTUBE)), actions("abre YouTube"))
    }

    @Test
    fun `navigation continues inside an app opened by name`() {
        assertEquals(
            listOf(TvAction.OpenApp("xuper tv"), TvAction.Press(RemoteKey.DOWN, 2), TvAction.Press(RemoteKey.OK)),
            actions("abre xuper tv, baja dos y selecciona"),
        )
        // Its content cannot be searched through YouTube by mistake.
        assertTrue(interpreter.analyze("abre xuper tv y busca la casa de papel").plan.actions.none { it is TvAction.Search })
    }

    @Test
    fun `everyday settings requests`() {
        assertEquals(listOf(TvAction.OpenSettings(SettingsSection.BLUETOOTH)), actions("Abre ajustes y prende el bluetooth"))
        assertEquals(listOf(TvAction.OpenSettings()), actions("abre los ajustes"))
        assertEquals(listOf(TvAction.OpenSettings()), actions("entra a configuración"))
        assertEquals(
            listOf(TvAction.OpenSettings(SettingsSection.BLUETOOTH_PAIRING)),
            actions("conéctate por bluetooth a unos audífonos"),
        )
        assertEquals(
            listOf(
                TvAction.OpenSettings(SettingsSection.BLUETOOTH),
                TvAction.Select("kalley"),
                TvAction.Select("conectar", optional = true),
                TvAction.Select("aceptar", optional = true),
            ),
            actions("conéctate al Kalley por bluetooth"),
        )
        assertEquals(
            listOf(
                TvAction.OpenSettings(SettingsSection.BLUETOOTH),
                TvAction.Select("kalley"),
                TvAction.Select("conectar", optional = true),
                TvAction.Select("aceptar", optional = true),
            ),
            actions("conecta el parlante Kalley"),
        )
        assertEquals(listOf(TvAction.Select("segunda temporada")), actions("elige la segunda temporada"))
        assertEquals(listOf(TvAction.OpenSettings(SettingsSection.BLUETOOTH_PAIRING)), actions("busca dispositivos bluetooth"))
        assertEquals(listOf(TvAction.OpenSettings(SettingsSection.NETWORK)), actions("quiero ver el wifi"))
        assertEquals(
            listOf(TvAction.OpenSettings(SettingsSection.PICTURE), TvAction.Select("modo de imagen")),
            actions("cambia el modo de imagen"),
        )
        assertEquals(
            listOf(TvAction.OpenSettings(SettingsSection.PICTURE), TvAction.Select("modo de imagen"), TvAction.Select("cine")),
            actions("pon el modo cine"),
        )
        assertEquals(listOf(TvAction.Select("modo de imagen")), actions("selecciona modo de imagen"))
        assertEquals(listOf(TvAction.Press(RemoteKey.BRIGHTNESS_UP, 2)), actions("sube el brillo"))
        assertEquals(listOf(TvAction.Press(RemoteKey.BRIGHTNESS_DOWN, 4)), actions("baja el brillo 4"))
    }

    @Test
    fun `power only switches the TV itself`() {
        listOf("prende la tele", "apaga el televisor", "enciende la tv por favor", "apágala").forEach { phrase ->
            assertEquals(phrase, listOf(TvAction.Press(RemoteKey.POWER)), actions(phrase))
        }
        listOf("prende el bluetooth", "apaga la luz", "enciende el wifi").forEach { phrase ->
            assertTrue(phrase, actions(phrase).none { it == TvAction.Press(RemoteKey.POWER) })
        }
    }
}
