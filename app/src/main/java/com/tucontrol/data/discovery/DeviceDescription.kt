package com.tucontrol.data.discovery

/** The handful of fields TuControl needs from a UPnP device description XML. */
data class DeviceDescription(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val deviceType: String?,
    val udn: String?,
) {
    companion object {
        val EMPTY = DeviceDescription(null, null, null, null, null)

        /** Lightweight extraction: descriptions are tiny, so a full XML parser is unnecessary. */
        fun parse(xml: String): DeviceDescription = DeviceDescription(
            friendlyName = xml.tag("friendlyName"),
            manufacturer = xml.tag("manufacturer"),
            modelName = xml.tag("modelName"),
            deviceType = xml.tag("deviceType"),
            udn = xml.tag("UDN"),
        )

        private fun String.tag(name: String): String? =
            Regex("<(?:\\w+:)?$name>\\s*([^<]+?)\\s*</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE)
                .find(this)
                ?.groupValues
                ?.get(1)
                ?.unescapeXml()
                ?.takeIf { it.isNotBlank() }

        private fun String.unescapeXml(): String = this
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
