package com.tucontrol.data.tv.androidtv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtoBuffersTest {

    @Test
    fun `varints use the protobuf encoding`() {
        val out = ByteArrayOutputStream()
        ProtoWriter.writeVarint(out, 300)
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), out.toByteArray())
        assertEquals(300L, ProtoMessage.readVarint(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `nested messages round-trip`() {
        val bytes = ProtoWriter()
            .int(1, 2)
            .int(2, 200)
            .message(10) {
                string(1, "tucontrol")
                string(2, "TuControl")
            }
            .toByteArray()

        val parsed = ProtoMessage.parse(bytes)

        assertEquals(2L, parsed.long(1))
        assertEquals(200L, parsed.long(2))
        assertEquals("tucontrol", parsed.message(10)?.bytes(1)?.decodeToString())
        assertNull(parsed.message(11))
    }

    @Test
    fun `frames are length prefixed`() {
        val payload = ProtoWriter().int(10, 24).toByteArray()
        val out = ByteArrayOutputStream()
        ProtoFraming.write(out, payload)

        val framed = out.toByteArray()
        assertEquals(payload.size, framed[0].toInt())
        assertEquals(24L, ProtoFraming.read(ByteArrayInputStream(framed)).long(10))
    }
}
