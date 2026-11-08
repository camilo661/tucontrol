package com.tucontrol.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFingerprintsTest {

    @Test
    fun `reads the Android TV name from the Cast endpoint`() {
        val body = """{"build_version":"446070","connected":true,"name":"Smart TV Pro","locale":"en-US"}"""
        assertEquals("Smart TV Pro", TvFingerprints.parseCastInfo(body)?.name)
    }

    @Test
    fun `reads Roku identity from device-info`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <device-info>
              <udn>29380000-0800-1025-80a4-d83134a2d7a1</udn>
              <serial-number>X0040001234</serial-number>
              <friendly-device-name>Roku Living</friendly-device-name>
              <friendly-model-name>Roku Ultra</friendly-model-name>
              <user-device-name>Sala</user-device-name>
            </device-info>
        """.trimIndent()
        val identity = TvFingerprints.parseRokuDeviceInfo(body)
        assertEquals("Sala", identity?.name)
        assertEquals("Roku Ultra", identity?.model)
        assertEquals("X0040001234", identity?.uniqueId)
    }

    @Test
    fun `reads Samsung identity and strips the TV prefix`() {
        val body = """{"device":{"id":"uuid:0d2f9a54","modelName":"QN55Q80T","name":"[TV] Samsung Q80 Series (55)"},"type":"Samsung SmartTV"}"""
        val identity = TvFingerprints.parseSamsungInfo(body)
        assertEquals("Samsung Q80 Series (55)", identity?.name)
        assertEquals("QN55Q80T", identity?.model)
        assertEquals("uuid:0d2f9a54", identity?.uniqueId)
    }

    @Test
    fun `accepts Sony TVs and rejects other Sony products`() {
        val tv = """{"result":[{"productCategory":"tv","productName":"BRAVIA","modelName":"KD-55X85J"}],"id":1}"""
        val speaker = """{"result":[{"productCategory":"homeTheaterSystem","productName":"HT"}],"id":1}"""
        assertEquals("KD-55X85J", TvFingerprints.parseSonyInterfaceInfo(tv)?.model)
        assertNull(TvFingerprints.parseSonyInterfaceInfo(speaker))
    }

    @Test
    fun `ignores unrelated responses`() {
        assertNull(TvFingerprints.parseRokuDeviceInfo("<html>router</html>"))
        assertNull(TvFingerprints.parseSamsungInfo("not json"))
        assertNull(TvFingerprints.parseSonyInterfaceInfo("""{"error":[404,"Not Found"]}"""))
    }
}
