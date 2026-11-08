package com.tucontrol.data.discovery

/** A parsed SSDP M-SEARCH response (HTTP-over-UDP headers). */
data class SsdpMessage(
    val location: String,
    val server: String,
    val searchTarget: String,
    val usn: String,
) {
    companion object {
        /** Parses a raw datagram; returns `null` when it is not a valid search response. */
        fun parse(raw: String): SsdpMessage? {
            val lines = raw.split("\r\n", "\n")
            if (lines.firstOrNull()?.startsWith("HTTP/1.1 200", ignoreCase = true) != true) return null

            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim().uppercase() to line.substring(separator + 1).trim()
                }
                .toMap()

            val location = headers["LOCATION"]?.takeIf { it.isNotBlank() } ?: return null
            return SsdpMessage(
                location = location,
                server = headers["SERVER"].orEmpty(),
                searchTarget = headers["ST"].orEmpty(),
                usn = headers["USN"].orEmpty(),
            )
        }

        fun searchRequest(searchTarget: String, maxWaitSeconds: Int = 2): String =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $maxWaitSeconds\r\n" +
                "ST: $searchTarget\r\n" +
                "USER-AGENT: Android/TuControl UPnP/1.1\r\n\r\n"
    }
}
