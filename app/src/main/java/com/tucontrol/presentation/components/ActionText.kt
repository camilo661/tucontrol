package com.tucontrol.presentation.components

import android.content.res.Resources
import com.tucontrol.R
import com.tucontrol.domain.model.CommandPlan
import com.tucontrol.domain.model.ConnectionError
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.domain.model.TvAction

/** User-facing (Spanish) descriptions, always sourced from string resources. */
object ActionText {

    fun describe(resources: Resources, plan: CommandPlan): String =
        plan.actions.joinToString(" · ") { describe(resources, it) }

    private fun describe(resources: Resources, action: TvAction): String = when (action) {
        is TvAction.LaunchApp -> resources.getString(R.string.action_open_app, action.app.displayName)
        is TvAction.Press -> when (action.key) {
            RemoteKey.VOLUME_UP -> resources.getString(R.string.action_volume_up, action.times)
            RemoteKey.VOLUME_DOWN -> resources.getString(R.string.action_volume_down, action.times)
            RemoteKey.CHANNEL_UP -> resources.getString(R.string.action_channel_up, action.times)
            RemoteKey.CHANNEL_DOWN -> resources.getString(R.string.action_channel_down, action.times)
            RemoteKey.MUTE -> resources.getString(R.string.action_mute)
            RemoteKey.POWER -> resources.getString(R.string.action_power)
            RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT -> resources.getString(R.string.action_navigate)
            RemoteKey.OK -> resources.getString(R.string.action_select)
            RemoteKey.BACK -> resources.getString(R.string.action_back)
            RemoteKey.HOME -> resources.getString(R.string.action_home)
            RemoteKey.MENU -> resources.getString(R.string.action_menu)
            RemoteKey.INPUT -> resources.getString(R.string.action_input)
            RemoteKey.PLAY, RemoteKey.PLAY_PAUSE -> resources.getString(R.string.action_play)
            RemoteKey.PAUSE -> resources.getString(R.string.action_pause)
            RemoteKey.STOP -> resources.getString(R.string.action_stop)
            RemoteKey.REWIND -> resources.getString(R.string.action_rewind)
            RemoteKey.FAST_FORWARD -> resources.getString(R.string.action_fast_forward)
        }
    }

    fun status(state: ConnectionState): Int = when (state) {
        ConnectionState.Connected -> R.string.status_connected
        ConnectionState.Connecting -> R.string.status_connecting
        ConnectionState.AwaitingApproval -> R.string.status_awaiting_approval
        ConnectionState.AwaitingPin -> R.string.status_awaiting_pin
        ConnectionState.Disconnected -> R.string.status_disconnected
        is ConnectionState.Failed -> R.string.status_failed
    }

    fun error(error: ConnectionError): Int = when (error) {
        ConnectionError.NO_WIFI -> R.string.error_wifi
        ConnectionError.UNREACHABLE -> R.string.error_unreachable
        ConnectionError.REJECTED -> R.string.error_rejected
        ConnectionError.UNSUPPORTED -> R.string.error_unsupported
        ConnectionError.UNKNOWN -> R.string.error_generic
    }
}
