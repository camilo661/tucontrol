package com.tucontrol.domain

import com.tucontrol.domain.model.AppNames
import com.tucontrol.domain.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNamesTest {

    private val apps = listOf(
        InstalledApp("com.google.android.youtube.tv", "YouTube"),
        InstalledApp("com.google.android.youtube.tvkids", "YouTube Kids"),
        InstalledApp("com.xupertv.app", "xupertv"),
        InstalledApp("tv.pluto.android", "Pluto TV"),
    )

    @Test
    fun `spoken names match regardless of spacing and accents`() {
        assertEquals("com.xupertv.app", AppNames.find("xuper tv", apps)?.id)
        assertEquals("com.xupertv.app", AppNames.find("Xúper TV", apps)?.id)
        assertEquals("tv.pluto.android", AppNames.find("pluto", apps)?.id)
    }

    @Test
    fun `the closest name wins`() {
        assertEquals("com.google.android.youtube.tv", AppNames.find("youtube", apps)?.id)
        assertEquals("com.google.android.youtube.tvkids", AppNames.find("youtube kids", apps)?.id)
    }

    @Test
    fun `unknown or too short names do not match`() {
        assertNull(AppNames.find("magis tv", apps))
        assertNull(AppNames.find("tv", apps))
    }
}
