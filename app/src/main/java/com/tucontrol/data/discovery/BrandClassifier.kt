package com.tucontrol.data.discovery

import com.tucontrol.domain.model.TvBrand

/** Infers the TV platform from SSDP headers and the UPnP description. */
object BrandClassifier {

    fun classify(message: SsdpMessage, description: DeviceDescription): TvBrand {
        val haystack = listOf(
            message.searchTarget,
            message.server,
            message.usn,
            description.manufacturer,
            description.modelName,
            description.deviceType,
        ).joinToString(" ") { it.orEmpty() }.lowercase()

        return when {
            "roku" in haystack -> TvBrand.ROKU
            "lge-com" in haystack || "webos" in haystack || "lg electronics" in haystack -> TvBrand.LG
            "samsung" in haystack || "tizen" in haystack -> TvBrand.SAMSUNG
            "sony" in haystack || "bravia" in haystack -> TvBrand.SONY
            else -> TvBrand.UNKNOWN
        }
    }

    /** Whether a device with an unknown brand still looks like a television. */
    fun looksLikeTv(description: DeviceDescription): Boolean {
        val text = "${description.deviceType} ${description.modelName} ${description.friendlyName}".lowercase()
        return listOf("tv", "television", "display").any { it in text }
    }
}
