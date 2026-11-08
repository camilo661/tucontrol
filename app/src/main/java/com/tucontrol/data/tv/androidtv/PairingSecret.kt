package com.tucontrol.data.tv.androidtv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * Computes the Android TV pairing secret from both RSA public keys and the 6-digit
 * hexadecimal code shown on the TV:
 * `SHA-256(clientModulus ‖ clientExponent ‖ serverModulus ‖ serverExponent ‖ code[1..2])`.
 * The first byte of the code is a checksum that must equal the first byte of the hash.
 */
object PairingSecret {

    const val CODE_LENGTH = 6

    fun normalize(code: String): String? =
        code.trim().uppercase().takeIf { it.length == CODE_LENGTH && it.all { c -> c in HEX_DIGITS } }

    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
        val normalized = normalize(code) ?: return null
        val codeBytes = normalized.chunked(2).map { it.toInt(16).toByte() }

        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(client.modulus.unsignedBytes())
            update(client.publicExponent.unsignedBytes())
            update(server.modulus.unsignedBytes())
            update(server.publicExponent.unsignedBytes())
            update(byteArrayOf(codeBytes[1], codeBytes[2]))
        }.digest()

        return digest.takeIf { it[0] == codeBytes[0] }
    }

    /** Big-endian magnitude without the sign byte Java adds for positive numbers. */
    internal fun BigInteger.unsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
