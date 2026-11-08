package com.tucontrol.data.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsers for the identity endpoints each TV platform exposes over HTTP. Kept free of
 * networking so they can be unit tested against captured responses.
 */
internal object TvFingerprints {

    data class Identity(val name: String?, val model: String? = null, val uniqueId: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** Google Cast `GET :8008/setup/eureka_info?params=name` (Android TV / Google TV). */
    fun parseCastInfo(body: String): Identity? = parseObject(body)?.let { root ->
        Identity(name = root.string("name"))
    }

    /** Roku ECP `GET :8060/query/device-info`. */
    fun parseRokuDeviceInfo(body: String): Identity? {
        if (!body.contains("<device-info")) return null
        return Identity(
            name = xmlTag(body, "user-device-name") ?: xmlTag(body, "friendly-device-name"),
            model = xmlTag(body, "friendly-model-name") ?: xmlTag(body, "model-name"),
            uniqueId = xmlTag(body, "serial-number"),
        )
    }

    /** Samsung Tizen `GET :8001/api/v2/`. */
    fun parseSamsungInfo(body: String): Identity? {
        val device = parseObject(body)?.get("device") as? JsonObject ?: return null
        return Identity(
            name = device.string("name")?.removePrefix("[TV]")?.trim(),
            model = device.string("modelName"),
            uniqueId = device.string("id"),
        )
    }

    /** Sony Bravia `POST /sony/system` → `getInterfaceInformation`. Returns `null` for non-TVs. */
    fun parseSonyInterfaceInfo(body: String): Identity? {
        val info = runCatching {
            parseObject(body)?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return null
        if (info.string("productCategory") != "tv") return null
        return Identity(name = info.string("productName"), model = info.string("modelName"))
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
