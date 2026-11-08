package com.tucontrol.data.discovery

import com.tucontrol.domain.model.TvBrand
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.TvDiscovery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeTvDiscoveryTest {

    private fun tv(brand: TvBrand, name: String = brand.displayName) =
        TvDevice(id = "$brand:$name", name = name, brand = brand, host = "192.168.1.7")

    private fun discovery(vararg devices: TvDevice) = object : TvDiscovery {
        override fun discover() = flowOf(*devices)
    }

    @Test
    fun `a better protocol replaces a generic match and keeps the real name`() = runBlocking {
        val composite = CompositeTvDiscovery(
            listOf(discovery(tv(TvBrand.UNKNOWN, "Smart TV Pro"), tv(TvBrand.ANDROID_TV))),
        )
        val last = composite.discover().toList().last()
        assertEquals(TvBrand.ANDROID_TV, last.brand)
        assertEquals("Smart TV Pro", last.name)
    }

    @Test
    fun `the same protocol is re-emitted only to add a real name`() = runBlocking {
        val composite = CompositeTvDiscovery(
            listOf(
                discovery(
                    tv(TvBrand.ANDROID_TV),
                    tv(TvBrand.ANDROID_TV, "Smart TV Pro"),
                    tv(TvBrand.ANDROID_TV, "Other name"),
                ),
            ),
        )
        val emitted = composite.discover().toList()
        assertEquals(listOf("Android TV", "Smart TV Pro"), emitted.map { it.name })
    }
}
