package com.tucontrol.domain.model

/** Areas of the TV's own settings a request can point to ("prende el bluetooth"). */
enum class SettingsSection {
    GENERAL,
    PICTURE,
    SOUND,
    NETWORK,
    BLUETOOTH,

    /** Bluetooth with pairing started ("conecta mis audífonos por bluetooth"). */
    BLUETOOTH_PAIRING,
}
