package com.tucontrol.data.network

/** Validation for addresses typed by the user. */
object LanAddress {

    /**
     * Normalizes [input] to a private IPv4 host address ("192.168.1.20"), or returns `null`.
     * Only private ranges are accepted: TuControl never talks to hosts on the internet.
     */
    fun parsePrivateIpv4(input: String): String? {
        // Watch keyboards often offer a comma before a dot.
        val octets = input.trim().replace(',', '.').split('.')
        if (octets.size != 4) return null
        val values = octets.map { octet -> octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
        val isPrivate = values[0] == 10 ||
            (values[0] == 172 && values[1] in 16..31) ||
            (values[0] == 192 && values[1] == 168)
        return values.joinToString(".").takeIf { isPrivate && values[3] in 1..254 }
    }
}
