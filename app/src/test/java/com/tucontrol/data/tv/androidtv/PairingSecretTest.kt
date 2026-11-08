package com.tucontrol.data.tv.androidtv

import com.tucontrol.data.tv.androidtv.PairingSecret.unsignedBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

class PairingSecretTest {

    private fun rsaKey(): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey

    @Test
    fun `codes are normalized and validated`() {
        assertEquals("A1B2C3", PairingSecret.normalize(" a1b2c3 "))
        assertNull(PairingSecret.normalize("12345"))
        assertNull(PairingSecret.normalize("GHIJKL"))
    }

    @Test
    fun `exponent is encoded without sign byte`() {
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x01), BigInteger.valueOf(65537).unsignedBytes())
    }

    @Test
    fun `secret is accepted only when the checksum byte matches`() {
        val client = rsaKey()
        val server = rsaKey()
        val nonce = "BEEF"
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(client.modulus.unsignedBytes())
            update(client.publicExponent.unsignedBytes())
            update(server.modulus.unsignedBytes())
            update(server.publicExponent.unsignedBytes())
            update(byteArrayOf(0xBE.toByte(), 0xEF.toByte()))
        }.digest()
        val checksum = "%02X".format(digest[0])
        val wrongChecksum = "%02X".format((digest[0] + 1).toByte())

        assertNotNull(PairingSecret.compute(client, server, checksum + nonce))
        assertNull(PairingSecret.compute(client, server, wrongChecksum + nonce))
    }
}
