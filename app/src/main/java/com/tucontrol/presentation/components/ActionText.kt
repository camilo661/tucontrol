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

    /** A plan's outcome: the action itself, or for multi-step plans the step it ended on. */
    fun describe(resources: Resources, plan: CommandPlan): String {
        val last = describeStep(resources, plan, plan.actions.lastIndex)
        return if (plan.actions.size > 1) resources.getString(R.string.action_plan_done, plan.actions.size, last) else last
    }

    /** One step of [plan], worded for its context (OK after a search plays the result). */
    fun describeStep(resources: Resources, plan: CommandPlan, index: Int): String {
        val action = plan.actions[index]
        val followsSearch = plan.actions.getOrNull(index - 1) is TvAction.Search
        return if (action == TvAction.Press(RemoteKey.OK) && followsSearch) {
            resources.getString(R.string.action_play_first_result)
        } else {
            describe(resources, action)
        }
    }

    private fun describe(resources: Resources, action: TvAction): String = when (action) {
        is TvAction.LaunchApp -> resources.getString(R.string.action_open_app, action.app.displayName)
        is TvAction.OpenApp -> resources.getString(R.string.action_open_app, appName(action.name))
        is TvAction.TuneChannel -> resources.getString(R.string.action_tune_channel, action.number)
        is TvAction.Search -> action.app
            ?.let { resources.getString(R.string.action_search_in, action.query, it.displayName) }
            ?: resources.getString(R.string.action_search, action.query)
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
            RemoteKey.NEXT -> resources.getString(R.string.action_next)
            RemoteKey.PREVIOUS -> resources.getString(R.string.action_previous)
            RemoteKey.INFO -> resources.getString(R.string.action_info)
            RemoteKey.GUIDE -> resources.getString(R.string.action_guide)
            RemoteKey.CAPTIONS -> resources.getString(R.string.action_captions)
            RemoteKey.SETTINGS -> resources.getString(R.string.action_settings)
            RemoteKey.SEARCH -> resources.getString(R.string.action_open_search)
            RemoteKey.NUM_0, RemoteKey.NUM_1, RemoteKey.NUM_2, RemoteKey.NUM_3, RemoteKey.NUM_4,
            RemoteKey.NUM_5, RemoteKey.NUM_6, RemoteKey.NUM_7, RemoteKey.NUM_8, RemoteKey.NUM_9,
            -> resources.getString(R.string.action_number, action.key.name.removePrefix("NUM_"))
        }
    }

    /** "xuper tv" → "Xuper Tv": how a spoken app name is shown. */
    fun appName(spoken: String): String =
        spoken.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }

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
