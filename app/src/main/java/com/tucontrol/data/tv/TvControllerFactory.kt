package com.tucontrol.data.tv

import com.tucontrol.data.network.LocalHttpClients
import com.tucontrol.data.tv.androidtv.AndroidTvCertificateStore
import com.tucontrol.data.tv.androidtv.AndroidTvController
import com.tucontrol.data.tv.lg.LgWebOsController
import com.tucontrol.data.tv.roku.RokuController
import com.tucontrol.data.tv.samsung.SamsungTizenController
import com.tucontrol.data.tv.sony.SonyBraviaController
import com.tucontrol.domain.model.TvBrand
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.domain.repository.DeviceRepository
import com.tucontrol.domain.repository.SettingsRepository
import com.tucontrol.domain.repository.TvController
import kotlinx.coroutines.flow.first

/** Picks the protocol implementation for a TV. */
class TvControllerFactory(
    private val http: LocalHttpClients,
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
) {
    private val androidTvCertificates by lazy { AndroidTvCertificateStore() }

    /** @return `null` when the TV's platform is not supported yet. */
    fun create(device: TvDevice): TvController? = when (device.brand) {
        TvBrand.ROKU -> RokuController(device, http.plain)
        TvBrand.SAMSUNG -> SamsungTizenController(device, http.localTls, devices)
        TvBrand.LG -> LgWebOsController(device, http.localTls, devices)
        TvBrand.SONY -> SonyBraviaController(device, http.plain) { settings.settings.first().sonyPreSharedKey }
        TvBrand.ANDROID_TV -> AndroidTvController(device, devices, androidTvCertificates)
        TvBrand.UNKNOWN -> null
    }
}
