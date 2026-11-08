package com.tucontrol.domain.model

import kotlinx.serialization.Serializable

/**
 * A television discovered on (or remembered from) the local network.
 *
 * @property id stable identifier (UPnP UDN, mDNS name or host fallback).
 * @property host IPv4 address of the TV on the LAN.
 */
@Serializable
data class TvDevice(
    val id: String,
    val name: String,
    val brand: TvBrand,
    val host: String,
    val model: String? = null,
)
