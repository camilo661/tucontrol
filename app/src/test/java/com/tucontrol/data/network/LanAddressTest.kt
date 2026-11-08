package com.tucontrol.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressTest {

    @Test
    fun `accepts private addresses and normalizes input`() {
        assertEquals("192.168.18.7", LanAddress.parsePrivateIpv4(" 192.168.18.7 "))
        assertEquals("10.0.0.5", LanAddress.parsePrivateIpv4("10,0,0,5"))
        assertEquals("172.16.4.20", LanAddress.parsePrivateIpv4("172.16.004.20"))
    }

    @Test
    fun `rejects public, malformed and broadcast addresses`() {
        assertNull(LanAddress.parsePrivateIpv4("8.8.8.8"))
        assertNull(LanAddress.parsePrivateIpv4("172.32.0.1"))
        assertNull(LanAddress.parsePrivateIpv4("192.168.1"))
        assertNull(LanAddress.parsePrivateIpv4("192.168.1.256"))
        assertNull(LanAddress.parsePrivateIpv4("192.168.1.255"))
        assertNull(LanAddress.parsePrivateIpv4("tv.local"))
    }
}
