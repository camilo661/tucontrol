package com.tucontrol.domain.model

/** Lifecycle of the link between the watch and the selected TV. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** The TV shows an "allow this device?" prompt that must be accepted on screen. */
    data object AwaitingApproval : ConnectionState

    /** The TV shows a PIN that must be entered on the watch. */
    data object AwaitingPin : ConnectionState

    data object Connected : ConnectionState
    data class Failed(val error: ConnectionError) : ConnectionState
}

enum class ConnectionError {
    NO_WIFI,
    UNREACHABLE,
    REJECTED,
    UNSUPPORTED,
    UNKNOWN,
}
