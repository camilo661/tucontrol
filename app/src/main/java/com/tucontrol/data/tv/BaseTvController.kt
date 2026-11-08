package com.tucontrol.data.tv

import com.tucontrol.domain.model.ConnectionError
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.repository.TvController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Shared state handling for protocol implementations. */
abstract class BaseTvController : TvController {

    protected val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    protected val isConnected: Boolean get() = mutableState.value == ConnectionState.Connected

    protected fun fail(error: ConnectionError) {
        mutableState.value = ConnectionState.Failed(error)
    }

    /** Maps a low-level failure to a user-meaningful [ConnectionError]. */
    protected fun Throwable.toConnectionError(): ConnectionError = when (this) {
        is TvRejectedException -> ConnectionError.REJECTED
        is IOException -> ConnectionError.UNREACHABLE
        else -> ConnectionError.UNKNOWN
    }

    protected fun ensureConnected() {
        if (!isConnected) throw IOException("Not connected to ${device.host}")
    }
}

/** Raised when the TV explicitly refuses this watch (pairing denied, wrong key, …). */
class TvRejectedException(message: String) : IOException(message)
