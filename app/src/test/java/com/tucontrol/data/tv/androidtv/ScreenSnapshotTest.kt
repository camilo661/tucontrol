package com.tucontrol.data.tv.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Based on the Bluetooth screen of a TCL Google TV. */
class ScreenSnapshotTest {

    private val xml = """
        <hierarchy rotation="0">
        <node index="0" text="" class="android.widget.HorizontalScrollView" focusable="true" focused="false" bounds="[0,0][1920,1080]">
        <node index="1" text="Control remoto y accesorios" class="android.widget.TextView" focusable="false" focused="false" bounds="[260,120][860,292]" />
        <node index="2" text="" class="androidx.recyclerview.widget.RecyclerView" focusable="true" focused="false" bounds="[220,334][900,1080]">
        <node index="3" text="" class="android.widget.LinearLayout" focusable="true" focused="true" bounds="[220,334][900,463]">
        <node index="4" text="Bluetooth" class="android.widget.TextView" focusable="false" focused="false" bounds="[364,354][488,397]" />
        </node>
        <node index="5" text="" class="android.widget.LinearLayout" focusable="true" focused="false" bounds="[220,469][900,589]">
        <node index="6" text="Accesorio de emparejamiento" class="android.widget.TextView" focusable="false" focused="false" bounds="[364,507][745,550]" />
        </node>
        <node index="7" text="" class="android.widget.LinearLayout" focusable="true" focused="false" bounds="[220,673][900,800]">
        <node index="8" text="Kalley K-BSK40" class="android.widget.TextView" focusable="false" focused="false" bounds="[364,693][561,736]" />
        </node>
        </node>
        </node>
        </hierarchy>
    """.trimIndent()

    private val screen = ScreenSnapshot.parse(xml)

    @Test
    fun `finds rows and the highlighted one`() {
        assertEquals(3, screen.items.size)
        assertEquals(334, screen.focusedItem?.bounds?.top)
    }

    @Test
    fun `finds a device by part of its name`() {
        val kalley = screen.find("kalley")
        assertEquals("Kalley K-BSK40", kalley?.text)
        assertEquals(673, kalley?.let(screen::itemOf)?.bounds?.top)
        assertEquals("Accesorio de emparejamiento", screen.find("accesorio de emparejamiento")?.text)
        assertNull(screen.find("samsung"))
        // The page title is not selectable.
        assertNull(screen.find("control remoto y accesorios"))
    }

    @Test
    fun `a row holding its own control is still selectable`() {
        val picture = ScreenSnapshot.parse(
            """
            <node text="" focusable="true" focused="false" bounds="[48,185][628,1000]">
            <node text="" focusable="true" focused="true" bounds="[48,185][628,281]">
            <node text="Modo de imagen" focusable="false" focused="false" bounds="[70,212][340,254]" />
            <node text="" focusable="true" focused="false" bounds="[382,201][628,265]">
            <node text="Inteligente" focusable="false" focused="false" bounds="[432,212][581,254]" />
            </node>
            </node>
            <node text="" focusable="true" focused="false" bounds="[48,313][628,409]">
            <node text="Brillo" focusable="false" focused="false" bounds="[80,341][532,381]" />
            </node>
            <node text="" focusable="true" focused="false" bounds="[48,774][628,904]">
            <node text="Aplicar todos los modos de imagen" focusable="false" focused="false" bounds="[70,798][540,840]" />
            </node>
            </node>
            """.trimIndent(),
        )
        assertEquals("Modo de imagen", picture.find("modo de imagen")?.text)
        assertEquals(185, picture.find("modo de imagen")?.let(picture::itemOf)?.bounds?.top)
        assertEquals(185, picture.focusedItem?.bounds?.top)
        // The row and its control count as one row: Brillo is one step below.
        assertEquals(1, picture.rowsBetween(234..361))
    }
}
