package com.tucontrol.data.discovery

import com.tucontrol.domain.model.TvBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpParsingTest {

    @Test
    fun `parses a Roku search response`() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "Cache-Control: max-age=3600\r\n" +
            "ST: roku:ecp\r\n" +
            "Location: http://192.168.1.20:8060/\r\n" +
            "USN: uuid:roku:ecp:X00400123456\r\n\r\n"

        val message = SsdpMessage.parse(raw)!!

        assertEquals("http://192.168.1.20:8060/", message.location)
        assertEquals("roku:ecp", message.searchTarget)
        assertEquals(TvBrand.ROKU, BrandClassifier.classify(message, DeviceDescription.EMPTY))
    }

    @Test
    fun `ignores notifications and responses without location`() {
        assertNull(SsdpMessage.parse("NOTIFY * HTTP/1.1\r\nLOCATION: http://x\r\n\r\n"))
        assertNull(SsdpMessage.parse("HTTP/1.1 200 OK\r\nST: ssdp:all\r\n\r\n"))
    }

    @Test
    fun `reads the device description and classifies by manufacturer`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>[TV] Sala &amp; Comedor</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <modelName>QN55Q60B</modelName>
                <UDN>uuid:1234-abcd</UDN>
              </device>
            </root>
        """.trimIndent()

        val description = DeviceDescription.parse(xml)
        val message = SsdpMessage("http://10.0.0.5:9197/dmr", "", "urn:dial-multiscreen-org:service:dial:1", "")

        assertEquals("[TV] Sala & Comedor", description.friendlyName)
        assertEquals("uuid:1234-abcd", description.udn)
        assertEquals(TvBrand.SAMSUNG, BrandClassifier.classify(message, description))
    }

    @Test
    fun `detects LG webOS from the search target`() {
        val message = SsdpMessage("http://10.0.0.7:1828/", "WebOS/4.1 UPnP/1.0", "urn:lge-com:service:webos-second-screen:1", "")
        assertEquals(TvBrand.LG, BrandClassifier.classify(message, DeviceDescription.EMPTY))
    }

    @Test
    fun `search request is well formed`() {
        val request = SsdpMessage.searchRequest("roku:ecp")
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("ST: roku:ecp\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }
}
