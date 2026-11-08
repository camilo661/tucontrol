package com.tucontrol.data.tv.androidtv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protocol-buffers wire-format codec. Android TV Remote messages are small and use
 * only varint and length-delimited fields, so a generated runtime is not needed.
 */
class ProtoWriter {
    private val buffer = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter = apply {
        tag(field, WIRE_VARINT)
        writeVarint(buffer, value)
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, WIRE_LENGTH_DELIMITED)
        writeVarint(buffer, value.size.toLong())
        buffer.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray())

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = buffer.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(buffer, ((field shl 3) or wireType).toLong())

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5

        fun writeVarint(out: OutputStream, value: Long) {
            var remaining = value
            while (remaining and 0x7FL.inv() != 0L) {
                out.write(((remaining and 0x7F) or 0x80).toInt())
                remaining = remaining ushr 7
            }
            out.write(remaining.toInt())
        }
    }
}

/** Decoded view of a protobuf message: field number → values (varints as Long, others as bytes). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray

    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)

    fun string(field: Int): String? = bytes(field)?.decodeToString()

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = mutableMapOf<Int, MutableList<Any>>()
            val input = data.inputStream()
            while (input.available() > 0) {
                val key = readVarint(input)
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 0x7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> readVarint(input)
                    ProtoWriter.WIRE_LENGTH_DELIMITED -> input.readExactly(readVarint(input).toInt())
                    ProtoWriter.WIRE_FIXED64 -> input.readExactly(8)
                    ProtoWriter.WIRE_FIXED32 -> input.readExactly(4)
                    else -> error("Unsupported wire type in field $field")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }

        fun readVarint(input: InputStream): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Stream ended inside a varint")
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            error("Malformed varint")
        }

        private fun InputStream.readExactly(size: Int): ByteArray {
            val out = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = read(out, read, size - read)
                if (count < 0) throw EOFException("Stream ended inside a field")
                read += count
            }
            return out
        }
    }
}

/** Android TV frames every protobuf message with a varint length prefix. */
object ProtoFraming {
    fun write(out: OutputStream, payload: ByteArray) {
        val frame = ByteArrayOutputStream()
        ProtoWriter.writeVarint(frame, payload.size.toLong())
        frame.write(payload)
        out.write(frame.toByteArray())
        out.flush()
    }

    fun read(input: InputStream): ProtoMessage {
        val size = ProtoMessage.readVarint(input).toInt()
        val payload = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(payload, read, size - read)
            if (count < 0) throw EOFException("Stream ended inside a frame")
            read += count
        }
        return ProtoMessage.parse(payload)
    }
}
